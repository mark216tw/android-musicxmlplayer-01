#pragma once
#include <algorithm>
#include <cstdint>

namespace musicxml {
enum class OutputPhase : int {
    Paused, Starting, Playing, Reconnecting, RouteUnavailable, Finished, Error, Closed
};

class ReconnectPolicy {
    int attempts_ = 0;
    int64_t startedMillis_ = -1;
public:
    void reset() { attempts_ = 0; startedMillis_ = -1; }
    int attempts() const { return attempts_; }
    bool exhausted(int64_t nowMillis) const { return startedMillis_ >= 0 && nowMillis - startedMillis_ >= 30000; }
    int nextDelayMillis(int64_t nowMillis) {
        if (startedMillis_ < 0) startedMillis_ = nowMillis;
        ++attempts_;
        return std::min(2000, 100 << std::min(attempts_ - 1, 4));
    }
};

inline int adaptiveBufferFrames(int current, int burst, int capacity, int sampleRate) {
    if (burst <= 0 || capacity <= 0 || sampleRate <= 0) return current;
    const int cap = std::min({capacity, burst * 8, sampleRate / 5});
    if (current >= cap) return current;
    return std::min(cap, std::max(current, burst * 2) + burst);
}
}
