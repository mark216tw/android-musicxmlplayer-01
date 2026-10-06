#include "RealtimeCore.h"
#include <stdexcept>

namespace musicxml {
RealtimeCore::RealtimeCore(std::unique_ptr<Synth> synth, std::vector<LiveNote> notes, double duration,
                           std::vector<LiveControl> controls, std::array<int, CHANNELS> basePrograms,
                           std::array<int, CHANNELS> baseBanks)
    : synth_(std::move(synth)), notes_(std::move(notes)), controls_(std::move(controls)),
      basePrograms_(basePrograms), baseBanks_(baseBanks), programs_(basePrograms), duration_(duration) {
    std::stable_sort(controls_.begin(), controls_.end(), [](const LiveControl& a, const LiveControl& b) { return a.start < b.start; });
    for (const auto& control : controls_) {
        if (control.channel < 0 || control.channel >= CHANNELS ||
            (control.controller != 11 && control.controller != 64 && control.controller != 66 && control.controller != 67) ||
            control.start < 0 || control.end < control.start || control.from < 0 || control.from > 127 || control.to < 0 || control.to > 127)
            throw std::runtime_error("Invalid realtime control");
    }
    for (int channel = 0; channel < CHANNELS; ++channel) {
        if (basePrograms_[channel] < 0 || basePrograms_[channel] > 127 || baseBanks_[channel] < 0 || baseBanks_[channel] > 16383)
            throw std::runtime_error("Invalid realtime part preset");
        synth_->preset(channel, basePrograms_[channel], baseBanks_[channel]);
    }
    // Resolve damper and sostenuto into identity-based release times before entering the audio callback.
    for (auto& note : notes_) {
        if (note.channel < 0 || note.channel >= CHANNELS || note.pitch < 0 || note.pitch > 127 ||
            note.velocity < 1 || note.velocity > 127 || note.program < 0 || note.program > 127 ||
            note.bank < 0 || note.bank > 16383 || note.start < 0 || note.end <= note.start)
            throw std::runtime_error("Invalid realtime note");
        const auto physicalEnd = note.end;
        int damper = 0;
        for (const auto& control : controls_) {
            if (control.start > physicalEnd) break;
            if (control.channel == note.channel && control.controller == 64) damper = control.to;
        }
        if (damper > 0) {
            double release = damper == 127 ? duration_ : physicalEnd + 1000.0 * damper / 126.0;
            for (size_t i = 0; i < controls_.size();) {
                const auto time = controls_[i].start;
                size_t next = i + 1;
                while (next < controls_.size() && controls_[next].start == time) ++next;
                bool changed = false;
                if (time > physicalEnd && time <= release) {
                    for (size_t j = i; j < next; ++j) {
                        const auto& control = controls_[j];
                        if (control.channel == note.channel && control.controller == 64) { damper = control.to; changed = true; }
                    }
                }
                if (!changed) { i = next; continue; }
                if (damper == 0) { release = time; break; }
                release = damper == 127 ? duration_ : time + 1000.0 * damper / 126.0;
                i = next;
            }
            note.end = std::max(note.end, release);
        }
        int sostenuto = 0;
        for (size_t i = 0; i < controls_.size(); ++i) {
            const auto& control = controls_[i];
            if (control.channel != note.channel || control.controller != 66) continue;
            if (sostenuto < 64 && control.to >= 64 && note.start <= control.start && physicalEnd > control.start) {
                double release = duration_;
                for (size_t j = i + 1; j < controls_.size(); ++j) {
                    const auto& later = controls_[j];
                    if (later.channel == note.channel && later.controller == 66 && later.to < 64) { release = later.start; break; }
                }
                note.end = std::max(note.end, release);
            }
            sostenuto = control.to;
        }
    }
    std::stable_sort(notes_.begin(), notes_.end(), [](const LiveNote& a, const LiveNote& b) { return a.start < b.start; });
    events_.reserve(notes_.size() * 2);
    for (size_t i = 0; i < notes_.size(); ++i) {
        const auto& note = notes_[i];
        duration_ = std::max(duration_, note.end);
        events_.push_back({note.start, static_cast<int>(i), true});
        events_.push_back({note.end, static_cast<int>(i), false});
    }
    std::sort(events_.begin(), events_.end(), [](const Event& a, const Event& b) {
        return a.time < b.time || (a.time == b.time && a.on < b.on);
    });
    intervalEnds_.resize(std::max(size_t(1), notes_.size() * 4));
    if (!notes_.empty()) buildIntervals(0, 0, notes_.size());
    expressions_.fill(127); softPedals_.fill(0); activeExpressions_.fill(-1); activeSoftPedals_.fill(-1);
    for (int channel = 0; channel < CHANNELS; ++channel) synth_->volume(channel, 0);
}
double RealtimeCore::buildIntervals(size_t tree, size_t begin, size_t end) {
    if (end - begin == 1) return intervalEnds_[tree] = notes_[begin].end;
    const auto middle = (begin + end) / 2;
    return intervalEnds_[tree] = std::max(buildIntervals(tree * 2 + 1, begin, middle), buildIntervals(tree * 2 + 2, middle, end));
}
void RealtimeCore::restoreNotes(size_t tree, size_t begin, size_t end, double position, int& remaining, int& budget) {
    if (remaining == 0 || budget == 0) return;
    --budget;
    if (begin == end || intervalEnds_[tree] <= position || notes_[begin].start > position) return;
    if (end - begin == 1) { selectPreset(notes_[begin]); synth_->noteOn(notes_[begin]); --remaining; return; }
    const auto middle = (begin + end) / 2;
    restoreNotes(tree * 2 + 2, middle, end, position, remaining, budget);
    restoreNotes(tree * 2 + 1, begin, middle, position, remaining, budget);
}
float RealtimeCore::expression(int channel, double position) const {
    const auto index = activeExpressions_[channel];
    if (index < 0) return expressions_[channel] / 127.0f;
    const auto& control = controls_[index];
    if (control.end <= control.start || position >= control.end) return control.to / 127.0f;
    const auto fraction = std::clamp((position - control.start) / (control.end - control.start), 0.0, 1.0);
    return static_cast<float>((control.from + (control.to - control.from) * fraction) / 127.0);
}
float RealtimeCore::softPedal(int channel, double position) const {
    const auto index = activeSoftPedals_[channel];
    double value = softPedals_[channel];
    if (index >= 0) {
        const auto& control = controls_[index];
        if (control.end <= control.start || position >= control.end) value = control.to;
        else value = control.from + (control.to - control.from) *
            std::clamp((position - control.start) / (control.end - control.start), 0.0, 1.0);
    }
    return static_cast<float>(1.0 - 0.25 * value / 127.0);
}
void RealtimeCore::selectPreset(const LiveNote& note) {
    synth_->preset(note.channel, overrides_[note.channel] ? programs_[note.channel] : note.program,
                    overrides_[note.channel] ? 0 : note.bank, note.percussion);
}
void RealtimeCore::restoreControls(double position) {
    expressions_.fill(127); softPedals_.fill(0); activeExpressions_.fill(-1); activeSoftPedals_.fill(-1);
    nextControl_ = 0;
    while (nextControl_ < controls_.size() && controls_[nextControl_].start <= position + 0.000001) {
        const auto& control = controls_[nextControl_];
        if (control.controller == 11) {
            expressions_[control.channel] = control.to;
            activeExpressions_[control.channel] = static_cast<int>(nextControl_);
        } else if (control.controller == 67) {
            softPedals_[control.channel] = control.to;
            activeSoftPedals_[control.channel] = static_cast<int>(nextControl_);
        }
        ++nextControl_;
    }
    for (int channel = 0; channel < CHANNELS; ++channel)
        synth_->volume(channel, levels_[channel] * expression(channel, position) * softPedal(channel, position));
}
void RealtimeCore::applyControls() {
    while (nextControl_ < controls_.size() && controls_[nextControl_].start <= position_ + 0.000001) {
        const auto& control = controls_[nextControl_];
        if (control.controller == 11) {
            expressions_[control.channel] = control.to;
            activeExpressions_[control.channel] = static_cast<int>(nextControl_);
        } else if (control.controller == 67) {
            softPedals_[control.channel] = control.to;
            activeSoftPedals_[control.channel] = static_cast<int>(nextControl_);
        }
        ++nextControl_;
    }
}
void RealtimeCore::seek(double position) {
    position_ = std::clamp(position, 0.0, duration_); tailFrames_ = 0; finished_ = false;
    synth_->silence();
    nextEvent_ = static_cast<size_t>(std::upper_bound(events_.begin(), events_.end(), position_,
        [](double time, const Event& event) { return time < event.time; }) - events_.begin());
    restoreControls(position_);
    int remaining = 256, budget = 4096;
    if (!notes_.empty() && position_ < duration_) restoreNotes(0, 0, notes_.size(), position_, remaining, budget);
}
void RealtimeCore::latestPlaying(bool playing) {
    latestPlaying_.store(playing ? 1 : 0, std::memory_order_release);
    latestPlayingVersion_.fetch_add(1, std::memory_order_release);
}
void RealtimeCore::latestSpeed(double speed) {
    latestSpeedQ16_.store(static_cast<int32_t>(std::llround(std::clamp(speed, 0.5, 1.5) * 65536)), std::memory_order_release);
    latestSpeedVersion_.fetch_add(1, std::memory_order_release);
}
void RealtimeCore::latestMix(const Mix& mix) {
    constexpr uint32_t DIRTY = 4;
    latestMixes_[backMix_] = mix;
    backMix_ = pendingMix_.exchange(backMix_ | DIRTY, std::memory_order_acq_rel) & 3;
}
void RealtimeCore::consumeLatest(bool& seeking, double& targetPosition, bool& mixed, Mix& lastMix) {
    const auto playingVersion = latestPlayingVersion_.load(std::memory_order_acquire);
    if (playingVersion != appliedPlayingVersion_) {
        const bool requested = latestPlaying_.load(std::memory_order_acquire) != 0;
        if (requested && finished_ && !seeking) { seeking = true; targetPosition = 0; }
        playing_ = requested; appliedPlayingVersion_ = playingVersion;
    }
    const auto speedVersion = latestSpeedVersion_.load(std::memory_order_acquire);
    if (speedVersion != appliedSpeedVersion_) {
        speed_ = std::clamp(latestSpeedQ16_.load(std::memory_order_acquire) / 65536.0, 0.5, 1.5);
        appliedSpeedVersion_ = speedVersion;
    }
    constexpr uint32_t DIRTY = 4;
    if ((pendingMix_.load(std::memory_order_acquire) & DIRTY) == 0) return;
    const auto pending = pendingMix_.exchange(frontMix_, std::memory_order_acq_rel);
    frontMix_ = pending & 3;
    lastMix = latestMixes_[frontMix_]; mixed = true;
}
void RealtimeCore::consume() {
    Command command;
    bool seeking = false, mixed = false;
    double targetPosition = position_;
    Mix lastMix;
    for (int processed = 0; processed < 512 && commands_.pop(command); ++processed) {
        switch (command.operation) {
            case Operation::Play:
                if (finished_ && !seeking) { seeking = true; targetPosition = 0; }
                playing_ = true; break;
            case Operation::Pause: playing_ = false; break;
            case Operation::Seek: seeking = true; targetPosition = command.first; seekId_ = command.seekId; break;
            case Operation::Speed: speed_ = std::clamp(command.first, 0.5, 1.5); break;
            case Operation::Mix: lastMix = command.mix; mixed = true; break;
        }
    }
    consumeLatest(seeking, targetPosition, mixed, lastMix);
    if (mixed) for (int channel = 0; channel < CHANNELS; ++channel) {
        if (programs_[channel] != lastMix.programs[channel] || overrides_[channel] != lastMix.overrides[channel]) {
            programs_[channel] = lastMix.programs[channel];
            overrides_[channel] = lastMix.overrides[channel];
            synth_->preset(channel, overrides_[channel] ? programs_[channel] : basePrograms_[channel],
                           overrides_[channel] ? 0 : baseBanks_[channel]);
        }
        const auto target = std::clamp(lastMix.levels[channel], 0.0f, 1.0f);
        if (targets_[channel] != target) {
            targets_[channel] = target; rampFrames_[channel] = std::max(1, rate_ / 100); // 10 ms gain ramp
        }
    }
    if (seeking) seek(targetPosition);
}
void RealtimeCore::applyEvents() {
    while (nextEvent_ < events_.size() && events_[nextEvent_].time <= position_ + 0.000001) {
        const auto event = events_[nextEvent_++]; const auto& note = notes_[event.note];
        if (event.on) { selectPreset(note); synth_->noteOn(note); } else synth_->noteOff(note.id);
    }
}
void RealtimeCore::smoothVolumes(int frames) {
    for (int channel = 0; channel < CHANNELS; ++channel) {
        if (rampFrames_[channel] != 0) {
            const auto count = std::min(frames, rampFrames_[channel]);
            levels_[channel] += (targets_[channel] - levels_[channel]) * count / rampFrames_[channel];
            rampFrames_[channel] -= count;
        }
        synth_->volume(channel, levels_[channel] * expression(channel, position_) * softPedal(channel, position_));
    }
}
void RealtimeCore::render(float* stereo, int frames, ClockObserver* clock) {
    consume();
    int offset = 0;
    while (offset < frames) {
        if (!playing_) {
            if (clock) clock->record(offset, position_, 0);
            std::fill(stereo + offset * 2, stereo + frames * 2, 0); break;
        }
        applyControls();
        applyEvents();
        if (position_ >= duration_ - 0.000001) position_ = duration_;
        int count = std::min(64, frames - offset);
        const bool tail = position_ >= duration_;
        if (tail) {
            count = std::min(count, std::max(0, rate_ * 3 / 2 - tailFrames_));
            if (count == 0) { finished_ = true; playing_ = false; synth_->silence(); continue; }
        } else {
            double boundary = duration_;
            if (nextEvent_ < events_.size()) boundary = std::min(boundary, events_[nextEvent_].time);
            if (nextControl_ < controls_.size()) boundary = std::min(boundary, controls_[nextControl_].start);
            count = std::min(count, std::max(1, static_cast<int>(std::ceil((boundary - position_) * rate_ / (1000 * speed_) - 0.000001))));
        }
        if (clock) clock->record(offset, position_, tail ? 0 : speed_);
        smoothVolumes(count); synth_->render(stereo + offset * 2, count);
        if (tail) {
            for (int i = 0; i < count; ++i) {
                const auto gain = std::min(1.0f, (rate_ * 3 / 2 - tailFrames_ - i) / (rate_ * 0.01f));
                stereo[(offset + i) * 2] *= gain; stereo[(offset + i) * 2 + 1] *= gain;
            }
            tailFrames_ += count;
            if (tailFrames_ >= rate_ * 3 / 2) { finished_ = true; playing_ = false; synth_->silence(); }
        } else position_ = std::min(duration_, position_ + count * 1000.0 * speed_ / rate_);
        offset += count;
    }
    publishedMicros_.store(static_cast<int64_t>(std::llround(position_ * 1000)), std::memory_order_release);
    publishedFinished_.store(finished_, std::memory_order_release);
}
}
