#include "RealtimeCore.h"
#include <cstdio>
#include <stdexcept>

namespace musicxml {
static void word(FILE* file, uint32_t value, int bytes) {
    for (int i = 0; i < bytes; ++i) std::fputc((value >> (8 * i)) & 255, file);
}
void writeWave(const char* bank, const char* path, std::vector<LiveNote> notes, std::vector<LiveControl> controls, double duration,
                const Mix& mix,
               std::array<int, CHANNELS> basePrograms, std::array<int, CHANNELS> baseBanks) {
    RealtimeCore core(loadSoundFont(bank), std::move(notes), duration, std::move(controls), basePrograms, baseBanks);
    core.sampleRate(44100);
    Command levels; levels.operation = Operation::Mix; levels.mix = mix;
    core.command(levels); core.command({Operation::Play});
    std::unique_ptr<FILE, decltype(&std::fclose)> file(std::fopen(path, "wb"), std::fclose);
    if (!file) throw std::runtime_error("無法建立匯出音訊");
    const int64_t frames = static_cast<int64_t>((core.duration() + 1500) * 44100 / 1000);
    const uint32_t size = static_cast<uint32_t>(frames * 4);
    auto f = file.get();
    std::fwrite("RIFF", 1, 4, f); word(f, size + 36, 4); std::fwrite("WAVEfmt ", 1, 8, f);
    word(f, 16, 4); word(f, 1, 2); word(f, 2, 2); word(f, 44100, 4);
    word(f, 44100 * 4, 4); word(f, 4, 2); word(f, 16, 2);
    std::fwrite("data", 1, 4, f); word(f, size, 4);
    std::array<float, 1024> audio{};
    for (int64_t frame = 0; frame < frames;) {
        const auto count = static_cast<int>(std::min(int64_t(512), frames - frame));
        core.render(audio.data(), count);
        for (int i = 0; i < count * 2; ++i) {
            const auto sample = static_cast<int16_t>(std::clamp(audio[i] * 32767, -32768.0f, 32767.0f));
            word(f, static_cast<uint16_t>(sample), 2);
        }
        if (std::ferror(f)) throw std::runtime_error("儲存空間不足，無法匯出音訊");
        frame += count;
    }
    auto raw = file.release();
    if (std::fclose(raw) != 0) throw std::runtime_error("無法儲存匯出音訊");
}
}
