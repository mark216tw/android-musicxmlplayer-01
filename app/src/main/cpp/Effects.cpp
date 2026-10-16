#include "RealtimeCore.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace musicxml {
namespace {

// All buffers are reserved for this rate so sampleRate() never reallocates during playback.
constexpr int kReserveRate = 96000;
constexpr float kPi = 3.14159265358979323846f;

// Fixed voicing for the shared playback/export effect chain.
constexpr float kDryGain = 1.0f;
constexpr float kReverbWet = 0.6f;
constexpr float kChorusWet = 0.10f;
constexpr float kMasterGain = 1.25f;
constexpr float kLimiterThreshold = 0.95f;
constexpr float kReverbInputGain = 0.015f;
constexpr float kReverbRoomSize = 0.84f;
constexpr float kReverbDamping = 0.20f;
constexpr float kChorusDepthMs = 3.0f;
constexpr float kChorusRateHz = 0.5f;
constexpr float kChorusBaseMs = 10.0f;

struct DelayBuffer {
    std::vector<float> samples;
    int size = 0;
    int index = 0;
    int capacity() const { return static_cast<int>(samples.size()); }
    void reserve(int count) { if (capacity() < count) samples.assign(count, 0.0f); }
    void configure(int count) {
        size = std::clamp(count, 1, capacity());
        index = 0;
        std::fill(samples.begin(), samples.end(), 0.0f);
    }
    void clear() { std::fill(samples.begin(), samples.end(), 0.0f); index = 0; }
};

struct Comb {
    DelayBuffer line;
    float store = 0.0f, feedback = kReverbRoomSize, damp1 = kReverbDamping, damp2 = 1.0f - kReverbDamping;
    float process(float input) {
        const float output = line.samples[line.index];
        store = output * damp2 + store * damp1;
        line.samples[line.index] = input + store * feedback;
        if (++line.index >= line.size) line.index = 0;
        return output;
    }
};

struct Allpass {
    DelayBuffer line;
    float feedback = 0.5f;
    float process(float input) {
        const float buffered = line.samples[line.index];
        const float output = -input + buffered;
        line.samples[line.index] = input + buffered * feedback;
        if (++line.index >= line.size) line.index = 0;
        return output;
    }
};

// Schroeder/Freeverb-style stereo reverb. The SoundFont's own reverb sends are ignored by
// TinySoundFont, so this restores the space the sampled presets were voiced for.
class Reverb {
    static constexpr int kCombs = 8, kAllpasses = 4, kSpread = 23;
    static constexpr int kCombTuning[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    static constexpr int kAllpassTuning[kAllpasses] = {556, 441, 341, 225};
    Comb combsLeft_[kCombs], combsRight_[kCombs];
    Allpass allpassesLeft_[kAllpasses], allpassesRight_[kAllpasses];
    float wet1_ = 1.0f, wet2_ = 0.0f;
    static int capacity(int tuning) { return tuning * kReserveRate / 44100 + 2; }
public:
    Reverb() {
        for (int i = 0; i < kCombs; ++i) {
            combsLeft_[i].line.reserve(capacity(kCombTuning[i]));
            combsRight_[i].line.reserve(capacity(kCombTuning[i] + kSpread));
        }
        for (int i = 0; i < kAllpasses; ++i) {
            allpassesLeft_[i].line.reserve(capacity(kAllpassTuning[i]));
            allpassesRight_[i].line.reserve(capacity(kAllpassTuning[i] + kSpread));
        }
    }
    void setSampleRate(int rate) {
        const float scale = static_cast<float>(rate) / 44100.0f;
        for (int i = 0; i < kCombs; ++i) {
            combsLeft_[i].line.configure(static_cast<int>(kCombTuning[i] * scale));
            combsRight_[i].line.configure(static_cast<int>((kCombTuning[i] + kSpread) * scale));
        }
        for (int i = 0; i < kAllpasses; ++i) {
            allpassesLeft_[i].line.configure(static_cast<int>(kAllpassTuning[i] * scale));
            allpassesRight_[i].line.configure(static_cast<int>((kAllpassTuning[i] + kSpread) * scale));
        }
    }
    void clear() {
        for (int i = 0; i < kCombs; ++i) {
            combsLeft_[i].line.clear(); combsLeft_[i].store = 0.0f;
            combsRight_[i].line.clear(); combsRight_[i].store = 0.0f;
        }
        for (int i = 0; i < kAllpasses; ++i) { allpassesLeft_[i].line.clear(); allpassesRight_[i].line.clear(); }
    }
    void process(float inputLeft, float inputRight, float& outputLeft, float& outputRight) {
        const float input = (inputLeft + inputRight) * kReverbInputGain;
        float left = 0.0f, right = 0.0f;
        for (int i = 0; i < kCombs; ++i) left += combsLeft_[i].process(input);
        for (int i = 0; i < kCombs; ++i) right += combsRight_[i].process(input);
        for (int i = 0; i < kAllpasses; ++i) left = allpassesLeft_[i].process(left);
        for (int i = 0; i < kAllpasses; ++i) right = allpassesRight_[i].process(right);
        outputLeft = left * wet1_ + right * wet2_;
        outputRight = right * wet1_ + left * wet2_;
    }
};

// Light stereo chorus that returns its wet (modulated-delay) signal only.
class Chorus {
    std::vector<float> left_, right_;
    int size_ = 0, index_ = 0, rate_ = 48000;
    float phase_ = 0.0f;
    float read(const std::vector<float>& buffer, float delay) const {
        float position = static_cast<float>(index_) - delay;
        while (position < 0.0f) position += static_cast<float>(size_);
        const int lower = static_cast<int>(position);
        const int upper = lower + 1 >= size_ ? 0 : lower + 1;
        const float fraction = position - static_cast<float>(lower);
        return buffer[lower] + (buffer[upper] - buffer[lower]) * fraction;
    }
public:
    Chorus() {
        const int count = static_cast<int>(0.05f * kReserveRate) + 4;
        left_.assign(count, 0.0f);
        right_.assign(count, 0.0f);
    }
    void setSampleRate(int rate) {
        rate_ = std::max(1, rate);
        size_ = std::min(static_cast<int>(0.05f * rate_) + 4, static_cast<int>(left_.size()));
        clear();
        phase_ = 0.0f;
    }
    void clear() {
        std::fill(left_.begin(), left_.end(), 0.0f);
        std::fill(right_.begin(), right_.end(), 0.0f);
        index_ = 0;
    }
    void process(float inputLeft, float inputRight, float& outputLeft, float& outputRight) {
        const float base = kChorusBaseMs * 0.001f * rate_;
        const float depth = kChorusDepthMs * 0.001f * rate_;
        outputLeft = read(left_, base + std::sin(2.0f * kPi * phase_) * depth);
        outputRight = read(right_, base + std::sin(2.0f * kPi * (phase_ + 0.5f)) * depth);
        left_[index_] = inputLeft;
        right_[index_] = inputRight;
        if (++index_ >= size_) index_ = 0;
        phase_ += kChorusRateHz / rate_;
        if (phase_ >= 1.0f) phase_ -= 1.0f;
    }
};

// Feed-forward limiter with a hard safety clamp so the device never clips the float stream.
class Limiter {
    float envelopeLeft_ = 0.0f, envelopeRight_ = 0.0f;
    float attack_ = 0.0f, release_ = 0.0f;
public:
    void setSampleRate(int rate) {
        attack_ = std::exp(-1.0f / (rate * 0.002f));
        release_ = std::exp(-1.0f / (rate * 0.150f));
    }
    void clear() { envelopeLeft_ = envelopeRight_ = 0.0f; }
    float process(float sample, bool right) {
        float& envelope = right ? envelopeRight_ : envelopeLeft_;
        const float peak = std::fabs(sample);
        envelope = peak > envelope
            ? attack_ * envelope + (1.0f - attack_) * peak
            : release_ * envelope + (1.0f - release_) * peak;
        const float gain = envelope > kLimiterThreshold ? kLimiterThreshold / envelope : 1.0f;
        return std::clamp(sample * gain, -1.0f, 1.0f);
    }
};

class EffectsSynth final : public Synth {
    std::unique_ptr<Synth> inner_;
    Reverb reverb_;
    Chorus chorus_;
    Limiter limiter_;
public:
    explicit EffectsSynth(std::unique_ptr<Synth> inner, int rate) : inner_(std::move(inner)) {
        reverb_.setSampleRate(rate);
        chorus_.setSampleRate(rate);
        limiter_.setSampleRate(rate);
    }
    void sampleRate(int rate) override {
        inner_->sampleRate(rate);
        reverb_.setSampleRate(rate);
        chorus_.setSampleRate(rate);
        limiter_.setSampleRate(rate);
    }
    void preset(int channel, int program, int bank, bool percussion = false) override { inner_->preset(channel, program, bank, percussion); }
    void volume(int channel, float level) override { inner_->volume(channel, level); }
    void noteOn(const LiveNote& note) override { inner_->noteOn(note); }
    void noteOff(int noteId) override { inner_->noteOff(noteId); }
    void silence() override {
        inner_->silence();
        reverb_.clear();
        chorus_.clear();
        limiter_.clear();
    }
    void render(float* stereo, int frames) override {
        inner_->render(stereo, frames);
        for (int frame = 0; frame < frames; ++frame) {
            const float dryLeft = stereo[frame * 2], dryRight = stereo[frame * 2 + 1];
            float reverbLeft, reverbRight, chorusLeft, chorusRight;
            reverb_.process(dryLeft, dryRight, reverbLeft, reverbRight);
            chorus_.process(dryLeft, dryRight, chorusLeft, chorusRight);
            stereo[frame * 2] = limiter_.process(
                (dryLeft * kDryGain + reverbLeft * kReverbWet + chorusLeft * kChorusWet) * kMasterGain, false);
            stereo[frame * 2 + 1] = limiter_.process(
                (dryRight * kDryGain + reverbRight * kReverbWet + chorusRight * kChorusWet) * kMasterGain, true);
        }
    }
};
}

std::unique_ptr<Synth> loadSoundFontWithEffects(const char* path) {
    return std::make_unique<EffectsSynth>(loadSoundFont(path), 48000);
}
}
