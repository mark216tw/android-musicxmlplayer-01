#pragma once
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <vector>

namespace musicxml {
static_assert(std::atomic<int64_t>::is_always_lock_free, "The realtime clock must not use hidden locks");
constexpr int CHANNELS = 16;
struct LiveNote { double start, end; int channel, pitch, velocity, id, program = 0, bank = 0; bool percussion = false; };
struct LiveControl { double start, end; int channel, controller, from, to; };
struct Mix {
    std::array<int, CHANNELS> programs{};
    std::array<float, CHANNELS> levels{};
    std::array<bool, CHANNELS> overrides{};
};
class Synth {
public:
    virtual ~Synth() = default;
    virtual void sampleRate(int rate) = 0;
    virtual void preset(int channel, int program, int bank, bool percussion = false) = 0;
    virtual void volume(int channel, float level) = 0;
    virtual void noteOn(const LiveNote& note) = 0;
    virtual void noteOff(int noteId) = 0;
    virtual void silence() = 0;
    virtual void render(float* stereo, int frames) = 0;
};
std::unique_ptr<Synth> loadSoundFont(const char* path);
void writeWave(const char* bank, const char* output, std::vector<LiveNote> notes, std::vector<LiveControl> controls, double duration,
                 const Mix& mix,
                 std::array<int, CHANNELS> basePrograms = {}, std::array<int, CHANNELS> baseBanks = {});
enum class Operation { Play, Pause, Seek, Speed, Mix };
struct Command {
    Operation operation = Operation::Pause;
    double first = 0;
    uint32_t seekId = 0;
    Mix mix;
    Command(Operation op = Operation::Pause, double value = 0) : operation(op), first(value) {}
};
// Producers are serialized outside the callback. The consumer never takes a mutex.
class Commands {
    static constexpr uint32_t CAPACITY = 512;
    std::array<Command, CAPACITY> data_{};
    std::atomic<uint32_t> read_{0}, write_{0};
public:
    bool push(const Command& command) {
        const auto write = write_.load(std::memory_order_relaxed);
        if (write - read_.load(std::memory_order_acquire) == CAPACITY) return false;
        data_[write % CAPACITY] = command;
        write_.store(write + 1, std::memory_order_release);
        return true;
    }
    bool pop(Command& command) {
        const auto read = read_.load(std::memory_order_relaxed);
        if (read == write_.load(std::memory_order_acquire)) return false;
        command = data_[read % CAPACITY];
        read_.store(read + 1, std::memory_order_release);
        return true;
    }
};
class ClockObserver {
public:
    virtual ~ClockObserver() = default;
    virtual void record(int frameOffset, double scoreMillis, double speed) = 0;
};
// Maps hardware-presented frames back to score time, including seeks and rate changes.
class AudioClock {
    static constexpr uint32_t CAPACITY = 2048;
    struct Point {
        std::atomic<uint32_t> stamp{0};
        std::atomic<int64_t> frame{0}, micros{0};
        std::atomic<int32_t> speedQ16{0};
    };
    std::array<Point, CAPACITY> points_{};
    std::atomic<uint32_t> count_{0};
public:
    void clear() { count_.store(0, std::memory_order_release); }
    void record(int64_t frame, double millis, double speed) {
        const auto count = count_.load(std::memory_order_relaxed);
        auto& p = points_[count % CAPACITY];
        p.stamp.store(0, std::memory_order_release);
        p.frame.store(frame, std::memory_order_relaxed);
        p.micros.store(static_cast<int64_t>(std::llround(millis * 1000)), std::memory_order_relaxed);
        p.speedQ16.store(static_cast<int32_t>(std::llround(speed * 65536)), std::memory_order_relaxed);
        p.stamp.store(count + 1, std::memory_order_release);
        count_.store(count + 1, std::memory_order_release);
    }
    double position(int64_t presentedFrame, int sampleRate, double fallback) const {
        const auto count = count_.load(std::memory_order_acquire);
        const auto available = std::min(count, CAPACITY);
        for (uint32_t offset = 0; offset < available; ++offset) {
            const auto index = count - offset - 1;
            const auto& p = points_[index % CAPACITY];
            if (p.stamp.load(std::memory_order_acquire) != index + 1) continue;
            const auto frame = p.frame.load(std::memory_order_relaxed);
            const auto micros = p.micros.load(std::memory_order_relaxed);
            const auto speed = p.speedQ16.load(std::memory_order_relaxed);
            if (p.stamp.load(std::memory_order_acquire) != index + 1) continue;
            if (frame <= presentedFrame) return micros / 1000.0 +
                (presentedFrame - frame) * 1000.0 / sampleRate * speed / 65536.0;
        }
        return fallback;
    }
};
class RealtimeCore {
    struct Event { double time; int note; bool on; };
    std::unique_ptr<Synth> synth_;
    std::vector<LiveNote> notes_;
    std::vector<LiveControl> controls_;
    std::vector<Event> events_;
    std::vector<double> intervalEnds_;
    std::array<int, CHANNELS> basePrograms_{}, baseBanks_{}, programs_{};
    std::array<bool, CHANNELS> overrides_{};
    std::array<float, CHANNELS> levels_{}, targets_{};
    std::array<int, CHANNELS> expressions_{}, softPedals_{}, activeExpressions_{}, activeSoftPedals_{};
    std::array<int, CHANNELS> rampFrames_{};
    Commands commands_;
    size_t nextEvent_ = 0, nextControl_ = 0;
    int rate_ = 48000, tailFrames_ = 0;
    double position_ = 0, duration_, speed_ = 1;
    bool playing_ = false, finished_ = false;
    uint32_t seekId_ = 0;
    std::atomic<int64_t> publishedMicros_{0};
    std::atomic<bool> publishedFinished_{false};
    std::atomic<uint32_t> latestPlayingVersion_{0}, latestSpeedVersion_{0};
    std::atomic<int32_t> latestPlaying_{0};
    std::atomic<int32_t> latestSpeedQ16_{65536};
    std::array<Mix, 3> latestMixes_{};
    std::atomic<uint32_t> pendingMix_{1};
    uint32_t backMix_ = 2, frontMix_ = 0;
    uint32_t appliedPlayingVersion_ = 0, appliedSpeedVersion_ = 0;
    double buildIntervals(size_t tree, size_t begin, size_t end);
    void restoreNotes(size_t tree, size_t begin, size_t end, double position, int& remaining, int& budget);
    void restoreControls(double position);
    void applyControls();
    float expression(int channel, double position) const;
    float softPedal(int channel, double position) const;
    void selectPreset(const LiveNote& note);
    void seek(double position);
    void consumeLatest(bool& seeking, double& targetPosition, bool& mixed, Mix& lastMix);
    void consume();
    void applyEvents();
    void smoothVolumes(int frames);
public:
    RealtimeCore(std::unique_ptr<Synth> synth, std::vector<LiveNote> notes, double duration,
                  std::vector<LiveControl> controls = {},
                  std::array<int, CHANNELS> basePrograms = {}, std::array<int, CHANNELS> baseBanks = {});
    bool command(const Command& command) { return commands_.push(command); }
    void latestPlaying(bool playing);
    void latestSpeed(double speed);
    void latestMix(const Mix& mix);
    void sampleRate(int rate) { rate_ = rate; synth_->sampleRate(rate); }
    void render(float* stereo, int frames, ClockObserver* clock = nullptr);
    double position() const { return publishedMicros_.load(std::memory_order_acquire) / 1000.0; }
    double duration() const { return duration_; }
    bool finished() const { return publishedFinished_.load(std::memory_order_acquire); }
    uint32_t appliedSeek() const { return seekId_; } // audio-thread only
};
}
