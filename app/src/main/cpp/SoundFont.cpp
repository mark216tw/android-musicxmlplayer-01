#include "RealtimeCore.h"
#include <stdexcept>
#include <cstdlib>
#ifdef MUSICXML_TEST_ALLOCATIONS
extern void* musicxml_test_malloc(size_t);
extern void* musicxml_test_realloc(void*, size_t);
#define TSF_MALLOC musicxml_test_malloc
#define TSF_REALLOC musicxml_test_realloc
#define TSF_FREE free
#endif
#define TSF_IMPLEMENTATION
#include "tsf.h"

namespace musicxml {
class SoundFontSynth final : public Synth {
    std::unique_ptr<tsf, decltype(&tsf_close)> synth_{nullptr, tsf_close};
    std::array<int, 256> noteIds_{};
    std::array<unsigned int, 256> playIds_{};
public:
    explicit SoundFontSynth(const char* path) {
        synth_.reset(tsf_load_filename(path));
        if (!synth_ || !tsf_set_max_voices(synth_.get(), 256)) throw std::runtime_error("無法載入採樣音源");
        noteIds_.fill(-1);
        sampleRate(48000);
        // Allocate all channel state before any audio callback; preset changes don't allocate.
        for (int channel = 0; channel < CHANNELS; ++channel) preset(channel, 0, 0);
    }
    void sampleRate(int rate) override { tsf_set_output(synth_.get(), TSF_STEREO_INTERLEAVED, rate, -6); }
    void preset(int channel, int program, int bank, bool percussion = false) override {
        const auto gmBank = percussion ? 128 : 0;
        const auto requestedBank = percussion && bank == 0 ? 128 : bank;
        if (tsf_channel_set_bank_preset(synth_.get(), channel, requestedBank, program)) return;
        if (tsf_channel_set_bank_preset(synth_.get(), channel, gmBank, program)) return;
        if (tsf_channel_set_bank_preset(synth_.get(), channel, gmBank, 0)) return;
        // Even a malformed SoundFont must not leave the previous channel preset selected.
        tsf_channel_set_presetindex(synth_.get(), channel, 0);
        tsf_channel_set_bank(synth_.get(), channel, gmBank);
    }
    void volume(int channel, float level) override { tsf_channel_set_volume(synth_.get(), channel, level); }
    void noteOn(const LiveNote& note) override {
        const auto playId = synth_->voicePlayIndex;
        tsf_channel_note_on(synth_.get(), note.channel, note.pitch, note.velocity / 127.0f);
        for (int i = 0; i < synth_->voiceNum; ++i) if (synth_->voices[i].playingPreset != -1 && synth_->voices[i].playIndex == playId) {
            noteIds_[i] = note.id; playIds_[i] = playId;
        }
    }
    void noteOff(int noteId) override {
        // Release by note identity, not pitch: overlapping voices can share a channel/key.
        for (int i = 0; i < synth_->voiceNum; ++i) {
            auto& voice = synth_->voices[i];
            if (noteIds_[i] == noteId && voice.playIndex == playIds_[i] && voice.playingPreset != -1 && voice.ampenv.segment < TSF_SEGMENT_RELEASE)
                tsf_voice_end(synth_.get(), &voice);
        }
    }
    void silence() override {
        for (int i = 0; i < synth_->voiceNum; ++i) synth_->voices[i].playingPreset = -1;
        noteIds_.fill(-1);
    }
    void render(float* stereo, int frames) override { tsf_render_float(synth_.get(), stereo, frames, 0); }
};
std::unique_ptr<Synth> loadSoundFont(const char* path) {
    return std::make_unique<SoundFontSynth>(path);
}
}
