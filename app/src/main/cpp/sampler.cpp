#include <jni.h>
#include <stdexcept>
#include <string>
#include "RealtimeCore.h"

static std::vector<int> array(JNIEnv* env, jintArray input) {
    std::vector<int> result(env->GetArrayLength(input));
    env->GetIntArrayRegion(input, 0, static_cast<jsize>(result.size()), result.data()); return result;
}
static std::string string(JNIEnv* env, jstring input) {
    const auto raw = env->GetStringUTFChars(input, nullptr);
    std::string result(raw); env->ReleaseStringUTFChars(input, raw); return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_musicxml_player_SampleRenderer_render(JNIEnv* env, jobject,
    jstring bank, jstring output, jintArray starts, jintArray ends, jintArray channels,
    jintArray pitches, jintArray velocities, jintArray notePrograms, jintArray noteBanks,
    jintArray presets, jintArray partPrograms, jintArray partBanks, jbooleanArray overrides, jintArray drums, jintArray volumes,
    jintArray controlStarts, jintArray controlEnds, jintArray controlParts,
    jintArray controllers, jintArray controlFrom, jintArray controlTo, jint durationMillis) {
    try {
        const auto start = array(env, starts), end = array(env, ends), part = array(env, channels);
        const auto pitch = array(env, pitches), velocity = array(env, velocities);
        const auto noteProgram = array(env, notePrograms), noteBank = array(env, noteBanks);
        const auto programs = array(env, presets), bases = array(env, partPrograms), banks = array(env, partBanks);
        const auto percussion = array(env, drums), levels = array(env, volumes);
        if (start.size() != end.size() || start.size() != part.size() || start.size() != pitch.size() ||
            start.size() != velocity.size() || start.size() != noteProgram.size() || start.size() != noteBank.size() || start.size() != percussion.size() ||
            programs.size() != bases.size() || programs.size() != banks.size() || programs.size() != levels.size() ||
            programs.size() != static_cast<size_t>(env->GetArrayLength(overrides)) || programs.size() > musicxml::CHANNELS)
            throw std::runtime_error("Invalid export score");
        const auto controlStart = array(env, controlStarts), controlEnd = array(env, controlEnds), controlPart = array(env, controlParts);
        const auto controller = array(env, controllers), from = array(env, controlFrom), to = array(env, controlTo);
        if (controlStart.size() != controlEnd.size() || controlStart.size() != controlPart.size() ||
            controlStart.size() != controller.size() || controlStart.size() != from.size() || controlStart.size() != to.size())
            throw std::runtime_error("Invalid export controls");
        std::vector<musicxml::LiveNote> notes; notes.reserve(start.size());
        for (size_t i = 0; i < start.size(); ++i)
            notes.push_back({double(start[i]), double(end[i]), part[i], pitch[i], velocity[i], static_cast<int>(i), noteProgram[i], noteBank[i], percussion[i] != 0});
        std::vector<musicxml::LiveControl> controls; controls.reserve(controlStart.size());
        for (size_t i = 0; i < controlStart.size(); ++i)
            controls.push_back({double(controlStart[i]), double(controlEnd[i]), controlPart[i], controller[i], from[i], to[i]});
        std::array<jboolean, musicxml::CHANNELS> overrideFlags{};
        env->GetBooleanArrayRegion(overrides, 0, static_cast<jsize>(programs.size()), overrideFlags.data());
        std::array<int, musicxml::CHANNELS> basePrograms{}, baseBanks{}; musicxml::Mix mix;
        for (size_t i = 0; i < programs.size(); ++i) {
            basePrograms[i] = bases[i]; baseBanks[i] = banks[i];
            mix.programs[i] = programs[i]; mix.levels[i] = levels[i] / 127.0f; mix.overrides[i] = overrideFlags[i];
        }
        musicxml::writeWave(string(env, bank).c_str(), string(env, output).c_str(), std::move(notes), std::move(controls),
                            durationMillis, mix, basePrograms, baseBanks);
    } catch (const std::exception& e) { env->ThrowNew(env->FindClass("java/io/IOException"), e.what()); }
}
