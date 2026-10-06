#include <jni.h>
#include <oboe/Oboe.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <ctime>
#include <mutex>
#include <stdexcept>
#include <thread>
#include "RealtimeController.h"
#include "RealtimeCore.h"

namespace musicxml {
class RealtimeOutput final : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback,
                              public ClockObserver, public std::enable_shared_from_this<RealtimeOutput> {
    RealtimeCore core_;
    AudioClock clock_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamMutex_, producerMutex_, workerMutex_;
    std::condition_variable workerWake_;
    std::thread worker_;
    std::atomic<oboe::AudioStream*> activeStream_{nullptr};
    std::atomic<bool> closing_{false}, desiredPlaying_{false}, streamLost_{false};
    bool wake_ = true, forceRetry_ = false;
    std::atomic<OutputPhase> phase_{OutputPhase::Reconnecting};
    std::atomic<int32_t> error_{0}, sampleRate_{48000}, xruns_{0}, bufferFrames_{0}, burstFrames_{0}, capacityFrames_{0};
    std::atomic<int32_t> reconnectAttempts_{0}, deviceId_{0}, audioApi_{0};
    std::atomic<int64_t> callbacksOverBudget_{0}, maxCallbackNanos_{0};
    std::atomic<uint32_t> epoch_{0}, seekRequested_{0}, seekApplied_{0};
    std::atomic<int64_t> seekFrame_{0}, seekTargetMicros_{0}, pausedMicros_{0}, lastMicros_{0}, recoveryMicros_{0};
    std::atomic<int64_t> finishFrame_{-1};
    std::atomic<uint32_t> recoverySeek_{0};
    int64_t writtenFrames_ = 0;
    double duration_;

    static int64_t nowMillis() {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    void signalWorker(bool retry = false) {
        { std::lock_guard<std::mutex> lock(workerMutex_); wake_ = true; forceRetry_ = forceRetry_ || retry; }
        workerWake_.notify_one();
    }
    void setFailure(oboe::Result result) {
        if (result != oboe::Result::OK) error_.store(static_cast<int32_t>(result));
    }
    bool submit(Command command) {
        std::lock_guard<std::mutex> lock(producerMutex_);
        if (closing_) return false;
        if (command.operation == Operation::Seek) command.seekId = seekRequested_.load(std::memory_order_relaxed) + 1;
        if (!core_.command(command)) { error_.store(static_cast<int32_t>(oboe::Result::ErrorInternal)); phase_.store(OutputPhase::Error); return false; }
        if (command.operation == Operation::Seek) {
            seekTargetMicros_.store(static_cast<int64_t>(command.first * 1000));
            seekRequested_.store(command.seekId, std::memory_order_release);
        }
        return true;
    }
    bool submitCheckedSeek(uint32_t expectedSeek, int64_t targetMicros) {
        std::lock_guard<std::mutex> lock(producerMutex_);
        if (closing_ || seekRequested_.load(std::memory_order_relaxed) != expectedSeek) return false;
        Command command{Operation::Seek, targetMicros / 1000.0};
        command.seekId = expectedSeek + 1;
        if (!core_.command(command)) {
            error_.store(static_cast<int32_t>(oboe::Result::ErrorInternal)); phase_.store(OutputPhase::Error); return false;
        }
        seekTargetMicros_.store(targetMicros);
        seekRequested_.store(command.seekId, std::memory_order_release);
        return true;
    }
    int64_t presentedFrame(oboe::AudioStream* stream, int rate) const {
        int64_t frame = 0, nanos = 0;
        if (stream->getTimestamp(CLOCK_MONOTONIC, &frame, &nanos) != oboe::Result::OK) {
            frame = stream->getFramesRead();
            if (frame < 0) frame = std::max(int64_t(0), stream->getFramesWritten() - stream->getBufferSizeInFrames());
        } else if (nanos > 0) {
            timespec now{};
            if (clock_gettime(CLOCK_MONOTONIC, &now) == 0) {
                const auto elapsed = now.tv_sec * int64_t(1000000000) + now.tv_nsec - nanos;
                frame = std::clamp(frame + static_cast<int64_t>(elapsed / 1000000000.0 * rate), int64_t(0),
                                   std::max(int64_t(0), stream->getFramesWritten()));
            }
        }
        return frame;
    }
    double positionFor(oboe::AudioStream* stream, int rate) const {
        if (seekRequested_.load(std::memory_order_acquire) != seekApplied_.load(std::memory_order_acquire))
            return seekTargetMicros_.load() / 1000.0;
        const auto frame = presentedFrame(stream, rate);
        if (frame < seekFrame_.load()) return seekTargetMicros_.load() / 1000.0;
        return std::clamp(clock_.position(frame, rate, lastMicros_.load() / 1000.0), 0.0, duration_);
    }
    oboe::Result openStream(std::shared_ptr<oboe::AudioStream>& opened) {
        auto self = shared_from_this();
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)->setFormat(oboe::AudioFormat::Float)->setChannelCount(2)
            ->setChannelConversionAllowed(true)->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)->setUsage(oboe::Usage::Media)->setContentType(oboe::ContentType::Music)
            ->setDataCallback(std::static_pointer_cast<oboe::AudioStreamDataCallback>(self))
            ->setErrorCallback(std::static_pointer_cast<oboe::AudioStreamErrorCallback>(self));
        auto result = builder.openStream(opened);
        if (result != oboe::Result::OK) {
            opened.reset(); builder.setSharingMode(oboe::SharingMode::Shared); result = builder.openStream(opened);
        }
        if (result != oboe::Result::OK) {
            opened.reset(); builder.setPerformanceMode(oboe::PerformanceMode::None); result = builder.openStream(opened);
        }
        return result;
    }
    bool waitOutTransientState(const std::shared_ptr<oboe::AudioStream>& stream) {
        auto state = stream->getState();
        if (state != oboe::StreamState::Pausing && state != oboe::StreamState::Stopping && state != oboe::StreamState::Flushing)
            return true;
        oboe::StreamState next{};
        const auto result = stream->waitForStateChange(state, &next, 200000000);
        if (result != oboe::Result::OK) setFailure(result);
        return result == oboe::Result::OK;
    }
    void retire(std::shared_ptr<oboe::AudioStream> stream, bool alreadyClosed = false) {
        if (!stream) return;
        auto expected = stream.get();
        if (activeStream_.compare_exchange_strong(expected, nullptr)) deviceId_.store(0);
        if (!alreadyClosed) {
            const auto stop = stream->requestStop();
            if (stop != oboe::Result::OK && stop != oboe::Result::ErrorInvalidState && stop != oboe::Result::ErrorDisconnected) setFailure(stop);
            const auto close = stream->close();
            if (close != oboe::Result::OK && close != oboe::Result::ErrorClosed) setFailure(close);
        }
    }
    void pollDiagnostics(const std::shared_ptr<oboe::AudioStream>& stream, int& priorXruns) {
        deviceId_.store(stream->getDeviceId());
        auto count = stream->getXRunCount();
        if (count && count.value() >= 0) {
            xruns_.store(count.value());
            if (count.value() > priorXruns) {
                const int target = adaptiveBufferFrames(stream->getBufferSizeInFrames(), stream->getFramesPerBurst(),
                                                        stream->getBufferCapacityInFrames(), stream->getSampleRate());
                auto changed = stream->setBufferSizeInFrames(target);
                if (changed) bufferFrames_.store(changed.value());
                else if (changed.error() != oboe::Result::ErrorUnimplemented) setFailure(changed.error());
                priorXruns = count.value();
            }
        } else if (!count && count.error() != oboe::Result::ErrorUnimplemented) setFailure(count.error());
        bufferFrames_.store(stream->getBufferSizeInFrames());
    }
    void workerLoop() {
        ReconnectPolicy retry;
        std::shared_ptr<oboe::AudioStream> stream;
        uint32_t handledSeek = 0;
        int priorXruns = 0;
        int64_t retryAt = 0;
        while (!closing_) {
            {
                std::unique_lock<std::mutex> lock(workerMutex_);
                workerWake_.wait_for(lock, std::chrono::milliseconds(100), [&] { return wake_ || closing_.load() || streamLost_.load(); });
                wake_ = false;
                if (forceRetry_) { retry.reset(); retryAt = 0; forceRetry_ = false; }
            }
            if (closing_) break;
            if (streamLost_.exchange(false)) {
                deviceId_.store(0); activeStream_.store(nullptr);
                deviceId_.store(0);
                { std::lock_guard<std::mutex> lock(streamMutex_); stream_.reset(); }
                stream.reset();
                phase_.store(OutputPhase::Reconnecting);
                retryAt = 0;
            }
            if (stream && phase_.load() == OutputPhase::Playing) {
                retry.reset(); reconnectAttempts_.store(0);
            }
            if (!stream) {
                const auto now = nowMillis();
                if (retry.exhausted(now)) { phase_.store(OutputPhase::RouteUnavailable); continue; }
                if (now < retryAt) continue;
                phase_.store(OutputPhase::Reconnecting);
                std::shared_ptr<oboe::AudioStream> opened;
                const auto result = openStream(opened);
                if (result != oboe::Result::OK || !opened) {
                    setFailure(result); retryAt = now + retry.nextDelayMillis(now); reconnectAttempts_.store(retry.attempts()); continue;
                }
                stream = std::move(opened);
                const int rate = stream->getSampleRate();
                sampleRate_.store(rate); core_.sampleRate(rate);
                burstFrames_.store(stream->getFramesPerBurst()); capacityFrames_.store(stream->getBufferCapacityInFrames());
                deviceId_.store(stream->getDeviceId()); audioApi_.store(static_cast<int32_t>(stream->getAudioApi()));
                const int capacity = stream->getBufferCapacityInFrames(), burst = stream->getFramesPerBurst();
                const int initial = capacity > 0 && burst > 0 && rate > 0 ? std::min({capacity, burst * 2, rate / 5}) : 0;
                if (initial > 0) {
                    auto size = stream->setBufferSizeInFrames(initial);
                    if (size) bufferFrames_.store(size.value());
                    else {
                        bufferFrames_.store(stream->getBufferSizeInFrames());
                        if (size.error() != oboe::Result::ErrorUnimplemented) setFailure(size.error());
                    }
                } else {
                    bufferFrames_.store(stream->getBufferSizeInFrames());
                }
                writtenFrames_ = 0; clock_.clear(); seekFrame_.store(0); finishFrame_.store(-1); epoch_.fetch_add(1);
                activeStream_.store(stream.get());
                { std::lock_guard<std::mutex> lock(streamMutex_); stream_ = stream; }
                error_.store(0); priorXruns = 0;
                const auto recoveredSeek = recoverySeek_.load();
                if (recoveredSeek == seekRequested_.load()) submitCheckedSeek(recoveredSeek, recoveryMicros_.load());
                handledSeek = seekRequested_.load();
                phase_.store(desiredPlaying_ ? OutputPhase::Starting : OutputPhase::Paused);
                if (!desiredPlaying_) { retry.reset(); reconnectAttempts_.store(0); }
            }
            const auto requestedSeek = seekRequested_.load(std::memory_order_acquire);
            if (requestedSeek != handledSeek) {
                bool safe = true;
                auto result = stream->requestPause();
                if (result != oboe::Result::OK && result != oboe::Result::ErrorInvalidState) { setFailure(result); safe = false; }
                if (!waitOutTransientState(stream)) safe = false;
                const auto state = stream->getState();
                if (state == oboe::StreamState::Paused) {
                    result = stream->requestFlush();
                    if (result != oboe::Result::OK || !waitOutTransientState(stream)) safe = false;
                } else if (state != oboe::StreamState::Open) safe = false;
                if (!safe) {
                    deviceId_.store(0); activeStream_.store(nullptr);
                    { std::lock_guard<std::mutex> lock(streamMutex_); stream_.reset(); }
                    retire(std::move(stream)); phase_.store(OutputPhase::Reconnecting); retryAt = 0; continue;
                }
                // If the callback consumed the seek before the flush, replay it so the discarded note attacks are rendered again.
                const auto latestSeek = seekRequested_.load(std::memory_order_acquire);
                if (seekApplied_.load(std::memory_order_acquire) == latestSeek)
                    submitCheckedSeek(latestSeek, seekTargetMicros_.load());
                handledSeek = seekRequested_.load(std::memory_order_acquire);
            }
            if (desiredPlaying_) {
                const auto state = stream->getState();
                if (state != oboe::StreamState::Started && state != oboe::StreamState::Starting) {
                    phase_.store(OutputPhase::Starting);
                    const auto result = stream->requestStart();
                    if (result != oboe::Result::OK) {
                        setFailure(result); deviceId_.store(0); activeStream_.store(nullptr);
                        { std::lock_guard<std::mutex> lock(streamMutex_); stream_.reset(); }
                        retire(std::move(stream)); retryAt = nowMillis() + retry.nextDelayMillis(nowMillis());
                        reconnectAttempts_.store(retry.attempts()); phase_.store(OutputPhase::Reconnecting); continue;
                    }
                }
                pollDiagnostics(stream, priorXruns);
            } else if (stream->getState() == oboe::StreamState::Started || stream->getState() == oboe::StreamState::Starting) {
                const auto result = stream->requestPause();
                if (result == oboe::Result::OK && waitOutTransientState(stream)) phase_.store(OutputPhase::Paused);
                else if (result != oboe::Result::ErrorInvalidState) setFailure(result);
            } else if (phase_.load() != OutputPhase::Finished) phase_.store(OutputPhase::Paused);
        }
        deviceId_.store(0); activeStream_.store(nullptr);
        deviceId_.store(0);
        { std::lock_guard<std::mutex> lock(streamMutex_); stream_.reset(); }
        retire(std::move(stream));
        phase_.store(OutputPhase::Closed);
    }
public:
    RealtimeOutput(std::unique_ptr<Synth> synth, std::vector<LiveNote> notes, std::vector<LiveControl> controls, double duration,
                   std::array<int, CHANNELS> basePrograms, std::array<int, CHANNELS> baseBanks)
        : core_(std::move(synth), std::move(notes), duration, std::move(controls), basePrograms, baseBanks), duration_(duration) {}
    void open() { worker_ = std::thread([self = shared_from_this()] { self->workerLoop(); }); }
    void record(int frameOffset, double millis, double speed) override { clock_.record(writtenFrames_ + frameOffset, millis, speed); }
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* source, void* data, int32_t frames) override {
        const auto began = std::chrono::steady_clock::now();
        if (source != activeStream_.load(std::memory_order_acquire) || closing_) {
            std::fill_n(static_cast<float*>(data), frames * 2, 0); return oboe::DataCallbackResult::Stop;
        }
        const auto oldSeek = core_.appliedSeek();
        core_.render(static_cast<float*>(data), frames, this);
        if (oldSeek != core_.appliedSeek()) { seekFrame_.store(writtenFrames_); seekApplied_.store(core_.appliedSeek(), std::memory_order_release); }
        writtenFrames_ += frames;
        if (core_.finished()) { if (finishFrame_.load() < 0) finishFrame_.store(writtenFrames_); }
        else finishFrame_.store(-1);
        if (desiredPlaying_ && !core_.finished()) phase_.store(OutputPhase::Playing);
        const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - began).count();
        auto maximum = maxCallbackNanos_.load(); while (elapsed > maximum && !maxCallbackNanos_.compare_exchange_weak(maximum, elapsed)) {}
        if (elapsed * source->getSampleRate() > int64_t(frames) * 1000000000) callbacksOverBudget_.fetch_add(1);
        return oboe::DataCallbackResult::Continue;
    }
    void onErrorBeforeClose(oboe::AudioStream* source, oboe::Result) override {
        recoverySeek_.store(seekRequested_.load());
        recoveryMicros_.store(static_cast<int64_t>((desiredPlaying_ ? positionFor(source, source->getSampleRate()) : pausedMicros_.load() / 1000.0) * 1000));
    }
    void onErrorAfterClose(oboe::AudioStream* source, oboe::Result result) override {
        if (source != activeStream_.load() || closing_) return;
        error_.store(static_cast<int32_t>(result)); streamLost_.store(true); phase_.store(OutputPhase::Reconnecting); workerWake_.notify_one();
    }
    double position() {
        if (!desiredPlaying_) return pausedMicros_.load() / 1000.0;
        std::shared_ptr<oboe::AudioStream> stream; uint32_t epoch; int rate;
        { std::lock_guard<std::mutex> lock(streamMutex_); stream = stream_; epoch = epoch_.load(); rate = sampleRate_.load(); }
        if (!stream) return seekRequested_.load() != seekApplied_.load() ? seekTargetMicros_.load() / 1000.0 : lastMicros_.load() / 1000.0;
        const auto value = positionFor(stream.get(), rate);
        if (epoch == epoch_.load()) lastMicros_.store(static_cast<int64_t>(value * 1000));
        return value;
    }
    bool finished() {
        if (phase_.load() == OutputPhase::Starting || !core_.finished() || seekRequested_.load() != seekApplied_.load()) return false;
        const auto finalFrame = finishFrame_.load();
        std::shared_ptr<oboe::AudioStream> stream; int rate;
        { std::lock_guard<std::mutex> lock(streamMutex_); stream = stream_; rate = sampleRate_.load(); }
        if (!stream || finalFrame < 0 || presentedFrame(stream.get(), rate) < finalFrame) return false;
        {
            std::lock_guard<std::mutex> lock(producerMutex_);
            if (phase_.load() == OutputPhase::Starting || !core_.finished() || seekRequested_.load() != seekApplied_.load()) return false;
            pausedMicros_.store(static_cast<int64_t>(duration_ * 1000)); desiredPlaying_.store(false); phase_.store(OutputPhase::Finished);
        }
        signalWorker(); return true;
    }
    bool playing() { return phase_.load() == OutputPhase::Playing && !finished(); }
    double state(int property) {
        switch (property) {
            case 0: return position(); case 1: return playing(); case 2: return finished(); case 3: return error_.load();
            case 4: return static_cast<int>(phase_.load()); case 5: return xruns_.load(); case 6: return bufferFrames_.load();
            case 7: return burstFrames_.load(); case 8: return capacityFrames_.load(); case 9: return callbacksOverBudget_.load();
            case 10: return maxCallbackNanos_.load() / 1000.0; case 11: return reconnectAttempts_.load();
            case 12: return deviceId_.load(); case 13: return audioApi_.load(); default: return 0;
        }
    }
    void play() {
        const bool unavailable = phase_.load() == OutputPhase::RouteUnavailable;
        {
            std::lock_guard<std::mutex> lock(producerMutex_);
            if (closing_) return;
            core_.latestPlaying(true); desiredPlaying_.store(true); phase_.store(OutputPhase::Starting);
        }
        signalWorker(unavailable);
    }
    void pause() {
        const auto millis = position();
        { std::lock_guard<std::mutex> lock(producerMutex_); if (!closing_) core_.latestPlaying(false); }
        desiredPlaying_.store(false); pausedMicros_.store(static_cast<int64_t>(millis * 1000)); signalWorker();
    }
    void seek(double millis) {
        millis = std::clamp(millis, 0.0, duration_); pausedMicros_.store(static_cast<int64_t>(millis * 1000)); submit({Operation::Seek, millis}); signalWorker();
    }
    void speed(double speed) { std::lock_guard<std::mutex> lock(producerMutex_); if (!closing_) core_.latestSpeed(speed); }
    void mix(const Mix& mix) { std::lock_guard<std::mutex> lock(producerMutex_); if (!closing_) core_.latestMix(mix); }
    void retryRoute() { signalWorker(true); }
    void shutdown() {
        if (closing_.exchange(true)) return;
        desiredPlaying_.store(false); workerWake_.notify_one();
        if (worker_.joinable()) worker_.join();
    }
};
}

namespace {
using Holder = std::shared_ptr<musicxml::RealtimeOutput>;
std::vector<int> ints(JNIEnv* env, jintArray value) {
    if (!value) throw std::runtime_error("Missing realtime score array");
    std::vector<int> result(env->GetArrayLength(value));
    env->GetIntArrayRegion(value, 0, static_cast<jsize>(result.size()), result.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read realtime score array");
    return result;
}
void failure(JNIEnv* env, const char* message) { if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), message); }
Holder& output(jlong handle) { if (!handle) throw std::runtime_error("Realtime player is closed"); return *reinterpret_cast<Holder*>(handle); }
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_musicxml_player_NativeRealtimePlayer_create(JNIEnv* env, jobject, jstring bank, jintArray starts, jintArray ends,
    jintArray channels, jintArray pitches, jintArray velocities, jintArray notePrograms, jintArray noteBanks,
    jintArray percussion, jintArray partPrograms, jintArray partBanks, jintArray controlStarts,
    jintArray controlEnds, jintArray controlParts, jintArray controllers, jintArray controlFrom, jintArray controlTo, jint duration) {
    try {
        auto start = ints(env, starts), end = ints(env, ends), channel = ints(env, channels), pitch = ints(env, pitches), velocity = ints(env, velocities);
        auto noteProgram = ints(env, notePrograms), noteBank = ints(env, noteBanks), drums = ints(env, percussion);
        auto bases = ints(env, partPrograms), banks = ints(env, partBanks);
        if (start.size() != end.size() || start.size() != channel.size() || start.size() != pitch.size() || start.size() != velocity.size() ||
            start.size() != noteProgram.size() || start.size() != noteBank.size() || start.size() != drums.size() ||
            bases.size() != banks.size() || bases.size() > musicxml::CHANNELS) throw std::runtime_error("Invalid realtime score arrays");
        auto controlStart = ints(env, controlStarts), controlEnd = ints(env, controlEnds), controlPart = ints(env, controlParts);
        auto controller = ints(env, controllers), from = ints(env, controlFrom), to = ints(env, controlTo);
        if (controlStart.size() != controlEnd.size() || controlStart.size() != controlPart.size() || controlStart.size() != controller.size() ||
            controlStart.size() != from.size() || controlStart.size() != to.size()) throw std::runtime_error("Invalid realtime control arrays");
        std::vector<musicxml::LiveNote> notes; notes.reserve(start.size());
        for (size_t i = 0; i < start.size(); ++i)
            notes.push_back({double(start[i]), double(end[i]), channel[i], pitch[i], velocity[i], static_cast<int>(i), noteProgram[i], noteBank[i], drums[i] != 0});
        std::vector<musicxml::LiveControl> controls; controls.reserve(controlStart.size());
        for (size_t i = 0; i < controlStart.size(); ++i)
            controls.push_back({double(controlStart[i]), double(controlEnd[i]), controlPart[i], controller[i], from[i], to[i]});
        std::array<int, musicxml::CHANNELS> basePreset{}, baseBank{};
        for (size_t i = 0; i < bases.size(); ++i) { basePreset[i] = bases[i]; baseBank[i] = banks[i]; }
        const auto path = env->GetStringUTFChars(bank, nullptr);
        if (!path) throw std::runtime_error("Unable to read SoundFont path");
        std::unique_ptr<musicxml::Synth> synth;
        try { synth = musicxml::loadSoundFont(path); } catch (...) { env->ReleaseStringUTFChars(bank, path); throw; }
        env->ReleaseStringUTFChars(bank, path);
        auto result = std::make_shared<musicxml::RealtimeOutput>(std::move(synth), std::move(notes), std::move(controls), duration, basePreset, baseBank);
        result->open();
        return reinterpret_cast<jlong>(new Holder(std::move(result)));
    } catch (const std::exception& e) { failure(env, e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_musicxml_player_NativeRealtimePlayer_control(JNIEnv* env, jobject, jlong handle, jint operation, jdouble first, jdouble) {
    try {
        auto& engine = output(handle);
        switch (operation) { case 0: engine->play(); break; case 1: engine->pause(); break; case 2: engine->seek(first); break;
            case 3: engine->speed(first); break; case 4: engine->retryRoute(); break; default: throw std::runtime_error("Invalid realtime operation"); }
    } catch (const std::exception& e) { failure(env, e.what()); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_musicxml_player_NativeRealtimePlayer_configure(JNIEnv* env, jobject, jlong handle, jintArray programs, jintArray levels,
                                                         jbooleanArray enabled, jbooleanArray overrides) {
    try {
        const auto presets = ints(env, programs), volumes = ints(env, levels);
        if (presets.size() != volumes.size() || presets.size() != static_cast<size_t>(env->GetArrayLength(enabled)) ||
            presets.size() != static_cast<size_t>(env->GetArrayLength(overrides)) || presets.size() > musicxml::CHANNELS)
            throw std::runtime_error("Invalid realtime mix");
        std::array<jboolean, musicxml::CHANNELS> flags{}, overrideFlags{};
        env->GetBooleanArrayRegion(enabled, 0, static_cast<jsize>(presets.size()), flags.data());
        env->GetBooleanArrayRegion(overrides, 0, static_cast<jsize>(presets.size()), overrideFlags.data());
        musicxml::Mix mix;
        for (size_t i = 0; i < presets.size(); ++i) {
            mix.programs[i] = std::clamp(presets[i], 0, 127); mix.levels[i] = flags[i] ? std::clamp(volumes[i], 0, 127) / 127.0f : 0;
            mix.overrides[i] = overrideFlags[i];
        }
        output(handle)->mix(mix);
    } catch (const std::exception& e) { failure(env, e.what()); }
}
extern "C" JNIEXPORT jdouble JNICALL
Java_com_musicxml_player_NativeRealtimePlayer_state(JNIEnv* env, jobject, jlong handle, jint property) {
    try { return output(handle)->state(property); } catch (const std::exception& e) { failure(env, e.what()); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_musicxml_player_NativeRealtimePlayer_destroy(JNIEnv*, jobject, jlong handle) {
    if (!handle) return; auto holder = reinterpret_cast<Holder*>(handle); (*holder)->shutdown(); delete holder;
}
