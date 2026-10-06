#include "RealtimeCore.h"
#include "RealtimeController.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <new>
#include <thread>
#include <fstream>

thread_local bool callback = false;
void* musicxml_test_malloc(size_t size) { assert(!callback && "TSF allocated in the audio callback"); return std::malloc(size); }
void* musicxml_test_realloc(void* pointer, size_t size) { assert(!callback && "TSF reallocated in the audio callback"); return std::realloc(pointer, size); }
void* operator new(size_t size) { assert(!callback && "C++ allocated in the audio callback"); if (auto p = std::malloc(size)) return p; throw std::bad_alloc(); }
void operator delete(void* pointer) noexcept { std::free(pointer); }
void operator delete(void* pointer, size_t) noexcept { std::free(pointer); }

using namespace musicxml;
struct Captured { int id; bool on; int frame; int preset = 0, bank = 0; bool percussion = false; };
class ProbeSynth : public Synth {
public:
    int frames = 0, clears = 0;
    std::vector<Captured> events;
    std::array<int, CHANNELS> presets{};
    std::array<int, CHANNELS> banks{};
    std::array<bool, CHANNELS> percussion{};
    std::array<float, CHANNELS> levels{};
    ProbeSynth() { events.reserve(4096); }
    void sampleRate(int) override {}
    void preset(int channel, int program, int bank, bool drum = false) override { presets[channel] = program; banks[channel] = bank; percussion[channel] = drum; }
    void volume(int channel, float level) override { levels[channel] = level; }
    void noteOn(const LiveNote& note) override { events.push_back({note.id, true, frames, presets[note.channel], banks[note.channel], percussion[note.channel]}); }
    void noteOff(int id) override { events.push_back({id, false, frames}); }
    void silence() override { ++clears; }
    void render(float* output, int count) override { std::fill(output, output + count * 2, 0); frames += count; }
};
void render(RealtimeCore& core, int frames) {
    std::array<float, 1024> output{};
    while (frames > 0) { const auto count = std::min(frames, 512); callback = true; core.render(output.data(), count); callback = false; frames -= count; }
}
void scheduleTests() {
    auto synth = std::make_unique<ProbeSynth>(); auto probe = synth.get();
    RealtimeCore core(std::move(synth), {{0,100,0,60,100,0}, {50,80,0,60,100,1}}, 100);
    core.sampleRate(48000); core.command({Operation::Play}); render(core, 4801);
    assert(probe->events.size() == 4);
    assert(probe->events[0].on && probe->events[0].frame == 0);
    assert(probe->events[1].on && probe->events[1].frame == 2400);
    assert(!probe->events[2].on && probe->events[2].id == 1 && probe->events[2].frame == 3840);
    assert(!probe->events[3].on && probe->events[3].id == 0 && probe->events[3].frame == 4800);
    std::puts("PASS: sample-boundary on/off events and overlapping note identities");

    auto tempoSynth = std::make_unique<ProbeSynth>(); auto tempoProbe = tempoSynth.get();
    RealtimeCore tempo(std::move(tempoSynth), {{100,200,0,60,100,0}}, 200);
    tempo.sampleRate(48000); tempo.command({Operation::Play}); render(tempo, 960);
    tempo.command({Operation::Speed, 1.5}); render(tempo, 2561);
    assert(tempoProbe->events.front().frame == 3520);
    const auto before = tempo.position(); tempo.command({Operation::Pause}); render(tempo, 4800);
    assert(std::abs(tempo.position() - before) < 0.001);
    tempo.command({Operation::Play}); render(tempo, 480); assert(tempo.position() > before);
    std::puts("PASS: live tempo and pause/resume without retriggering notes");

    auto seekSynth = std::make_unique<ProbeSynth>(); auto seekProbe = seekSynth.get();
    RealtimeCore seek(std::move(seekSynth), {{0,100,0,60,100,0}, {50,80,0,64,100,1}}, 100);
    seek.sampleRate(48000); seek.command({Operation::Seek, 60}); seek.command({Operation::Play}); render(seek, 961);
    assert(seekProbe->events.size() == 3);
    assert(seekProbe->events[0].id == 1 && seekProbe->events[1].id == 0);
    assert(!seekProbe->events[2].on && seekProbe->events[2].id == 1 && seekProbe->events[2].frame == 960);
    std::puts("PASS: seek restores crossing long notes with their original off times");

    std::vector<LiveNote> denseNotes;
    for (int i = 0; i < 300; ++i) denseNotes.push_back({double(i),1000,0,60,100,i});
    auto denseSynth = std::make_unique<ProbeSynth>(); auto denseProbe = denseSynth.get();
    RealtimeCore dense(std::move(denseSynth), std::move(denseNotes), 1000);
    dense.sampleRate(48000); dense.command({Operation::Seek,500}); render(dense, 1);
    assert(denseProbe->events.size() == 256);
    assert(denseProbe->events.front().id == 299 && denseProbe->events.back().id == 44);
    std::puts("PASS: seek restores at most 256 crossing notes with latest starts first");

    RealtimeCore completed(std::make_unique<ProbeSynth>(), {{0,100,0,60,100,0}}, 100);
    completed.sampleRate(48000); completed.command({Operation::Play}); render(completed, 76801);
    assert(completed.finished());
    completed.command({Operation::Seek,50}); completed.command({Operation::Play}); render(completed,48);
    assert(std::abs(completed.position() - 51) < 0.01 && !completed.finished());
    std::puts("PASS: seeking a completed song takes precedence over automatic restart");

    auto latestSynth = std::make_unique<ProbeSynth>(); auto latestProbe = latestSynth.get();
    RealtimeCore latest(std::move(latestSynth), {{0,100,0,60,100,0}}, 100);
    latest.sampleRate(48000); Mix latestMix;
    for (int i = 0; i < 1000; ++i) {
        latestMix.programs[0] = i % 128; latestMix.levels[0] = i / 999.0f; latestMix.overrides[0] = true;
        latest.latestMix(latestMix); latest.latestSpeed(0.5 + (i % 21) * 0.05);
    }
    latest.command({Operation::Play}); render(latest, 480);
    assert(latestProbe->events.front().preset == 999 % 128);
    assert(std::abs(latest.position() - 11) < 0.01);
    assert(latestProbe->levels[0] > 0.99f);
    std::puts("PASS: 1000 mix/rate updates coalesce to the latest mailbox values");

    RealtimeCore transport(std::make_unique<ProbeSynth>(), {{0,100,0,60,100,0}}, 100);
    transport.sampleRate(48000);
    for (int i = 0; i < 1000; ++i) { transport.latestPlaying(false); transport.latestPlaying(true); }
    render(transport, 480);
    assert(transport.position() > 9.9 && !transport.finished());
    transport.latestPlaying(false); render(transport, 480);
    assert(std::abs(transport.position() - 10) < 0.01);
    std::puts("PASS: rapid play/pause requests coalesce without filling the transport queue");

    auto programSynth = std::make_unique<ProbeSynth>(); auto programProbe = programSynth.get();
    std::array<int, CHANNELS> bases{}, banks{}; bases[0] = 19; banks[0] = 3;
    RealtimeCore program(std::move(programSynth),
        {{0,40,0,60,100,0,40,5}, {50,90,0,62,100,1,41,6}, {100,150,0,64,100,2,42,7}},
        150, {}, bases, banks);
    Command patch; patch.operation = Operation::Mix; patch.mix.programs[0] = 19; patch.mix.levels[0] = 1;
    program.command(patch); program.command({Operation::Play}); render(program, 1);
    patch.mix.programs[0] = 73; patch.mix.overrides[0] = true; program.command(patch); render(program, 2400);
    patch.mix.programs[0] = 19; patch.mix.overrides[0] = false; program.command(patch); render(program, 2400);
    assert(programProbe->events[0].on && programProbe->events[0].preset == 40 && programProbe->events[0].bank == 5);
    assert(programProbe->events[2].on && programProbe->events[2].preset == 73 && programProbe->events[2].bank == 0);
    assert(programProbe->events[4].on && programProbe->events[4].preset == 42 && programProbe->events[4].bank == 7);
    assert(programProbe->events[0].id == 0 && programProbe->events[1].id == 0);
    std::puts("PASS: score note program/bank, GM override, and score-instrument restoration affect only new voices");

    auto mixedSynth = std::make_unique<ProbeSynth>(); auto mixedProbe = mixedSynth.get();
    RealtimeCore mixed(std::move(mixedSynth), {{0,10,0,60,100,0,40,0,false}, {20,30,0,76,100,1,0,0,true}}, 30);
    mixed.sampleRate(48000); mixed.command({Operation::Play}); render(mixed, 961);
    assert(!mixedProbe->events[0].percussion && mixedProbe->events[2].percussion);
    std::puts("PASS: percussion is selected per note on a mixed logical part");

    auto controlSynth = std::make_unique<ProbeSynth>(); auto controlProbe = controlSynth.get();
    std::vector<LiveControl> controls{{0,100,0,11,0,127}, {0,100,0,67,0,127}};
    RealtimeCore controlled(std::move(controlSynth), {{0,40,0,60,100,0}, {20,50,0,60,100,1}}, 100, controls);
    controlled.sampleRate(48000);
    Command fullMix; fullMix.operation = Operation::Mix; fullMix.mix.levels[0] = 1;
    controlled.command(fullMix); controlled.command({Operation::Play}); render(controlled, 480);
    render(controlled, 480); // Complete the 10 ms UI gain ramp.
    controlled.command({Operation::Seek,50}); render(controlled, 1);
    assert(std::abs(controlProbe->levels[0] - 0.4375f) < 0.002f);
    controlled.command({Operation::Seek,25}); render(controlled, 1);
    assert(std::abs(controlled.position() - 25.0208) < 0.01);
    assert(std::abs(controlProbe->levels[0] - 0.234375f) < 0.002f);

    auto sostenutoSynth = std::make_unique<ProbeSynth>(); auto sostenutoProbe = sostenutoSynth.get();
    RealtimeCore sostenuto(std::move(sostenutoSynth), {{0,40,0,60,100,0}, {30,50,0,60,100,1}}, 100,
        {{20,20,0,66,127,127}, {80,80,0,66,0,0}});
    sostenuto.sampleRate(48000); sostenuto.command({Operation::Play}); render(sostenuto, 3841);
    const auto heldOff = std::find_if(sostenutoProbe->events.begin(), sostenutoProbe->events.end(), [](const Captured& e) { return !e.on && e.id == 0; });
    const auto laterOff = std::find_if(sostenutoProbe->events.begin(), sostenutoProbe->events.end(), [](const Captured& e) { return !e.on && e.id == 1; });
    assert(heldOff->frame == 3840 && laterOff->frame == 2400);

    auto halfSynth = std::make_unique<ProbeSynth>(); auto halfProbe = halfSynth.get();
    RealtimeCore half(std::move(halfSynth), {{0,20,0,60,100,0}}, 1000,
        {{10,10,0,64,63,63}, {900,900,0,64,0,0}});
    half.sampleRate(48000); half.command({Operation::Play}); render(half, 24961);
    assert(!halfProbe->events.back().on && halfProbe->events.back().frame == 24960); // 20 ms + 500 ms cap.

    auto fullSynth = std::make_unique<ProbeSynth>(); auto fullProbe = fullSynth.get();
    RealtimeCore full(std::move(fullSynth), {{0,20,0,60,100,0}}, 100,
        {{10,10,0,64,127,127}, {80,80,0,64,0,0}});
    full.sampleRate(48000); full.command({Operation::Play}); render(full, 3841);
    assert(!fullProbe->events.back().on && fullProbe->events.back().frame == 3840);

    auto repedalSynth = std::make_unique<ProbeSynth>(); auto repedalProbe = repedalSynth.get();
    RealtimeCore repedal(std::move(repedalSynth), {{0,20,0,60,100,0}}, 800,
        {{10,10,0,64,63,63}, {400,400,0,64,127,127}, {700,700,0,64,0,0}});
    repedal.sampleRate(48000); repedal.command({Operation::Play}); render(repedal, 33601);
    assert(!repedalProbe->events.back().on && repedalProbe->events.back().frame == 33600);

    auto changeSynth = std::make_unique<ProbeSynth>(); auto changeProbe = changeSynth.get();
    RealtimeCore change(std::move(changeSynth), {{0,20,0,60,100,0}}, 200,
        {{10,10,0,64,127,127}, {80,80,0,64,0,0}, {80,80,0,64,127,127}, {120,120,0,64,0,0}});
    change.sampleRate(48000); change.command({Operation::Play}); render(change, 5761);
    assert(!changeProbe->events.back().on && changeProbe->events.back().frame == 5760);

    auto sustainSynth = std::make_unique<ProbeSynth>(); auto sustainProbe = sustainSynth.get();
    RealtimeCore sustain(std::move(sustainSynth), {{0,40,0,60,100,0}, {20,50,0,60,100,1}}, 100,
        {{10,10,0,64,127,127}, {90,90,0,64,0,0}});
    sustain.sampleRate(48000); sustain.command({Operation::Play}); render(sustain, 4321);
    assert(sustainProbe->events.size() == 4);
    assert(!sustainProbe->events[2].on && !sustainProbe->events[3].on);
    assert(sustainProbe->events[2].id != sustainProbe->events[3].id);
    assert(sustainProbe->events[2].frame == 4320 && sustainProbe->events[3].frame == 4320);
    std::puts("PASS: CC11/CC67 seek gain, CC66 capture, deterministic half/full/repedal damper, and overlapping identities");
}
double energy(const std::vector<float>& samples, size_t begin = 0) {
    double sum = 0; for (size_t i = begin; i < samples.size(); ++i) sum += samples[i] * samples[i];
    return std::sqrt(sum / (samples.size() - begin));
}
void soundFontTests(const char* bank) {
    RealtimeCore core(loadSoundFont(bank), {{0,2000,0,60,100,0}}, 2000);
    core.sampleRate(48000);
    Command mix; mix.operation = Operation::Mix; mix.mix.levels[0] = 100 / 127.0f; mix.mix.programs[0] = 19; mix.mix.overrides[0] = true;
    core.command(mix); core.command({Operation::Play}); render(core, 24000);
    std::vector<float> audio(1920);
    callback = true; core.render(audio.data(), 960); callback = false; const auto sounding = energy(audio, 960);
    mix.mix.levels[0] = 0; core.command(mix);
    callback = true; core.render(audio.data(), 960); callback = false; const auto muted = energy(audio, 960);
    mix.mix.levels[0] = 100 / 127.0f; core.command(mix);
    callback = true; core.render(audio.data(), 960); callback = false; const auto restored = energy(audio, 960);
    assert(sounding > 0.001 && muted < sounding * 0.0001 && restored > muted * 1000);
    assert(std::abs(core.position() - 560) < 0.02);
    std::puts("PASS: real GM samples, smooth mute/unmute and uninterrupted score time; no callback allocations");

    RealtimeCore fast(loadSoundFont(bank), {{0,2000,0,72,100,0}}, 2000);
    RealtimeCore slow(loadSoundFont(bank), {{0,2000,0,72,100,0}}, 2000);
    Command flute; flute.operation = Operation::Mix; flute.mix.programs[0] = 73; flute.mix.levels[0] = 1; flute.mix.overrides[0] = true;
    fast.command(flute); slow.command(flute); fast.command({Operation::Speed,1.5}); slow.command({Operation::Speed,0.5});
    fast.command({Operation::Play}); slow.command({Operation::Play});
    std::vector<float> fastAudio(48000), slowAudio(48000);
    callback = true; fast.render(fastAudio.data(), 24000); slow.render(slowAudio.data(), 24000); callback = false;
    assert(fastAudio == slowAudio); assert(fast.position() == 750 && slow.position() == 250);
    std::puts("PASS: tempo changes leave the sampled pitch/waveform unchanged");

    auto synth = loadSoundFont(bank); synth->preset(0, 19, 0); synth->volume(0, 1);
    synth->noteOn({0,1000,0,60,100,0}); synth->render(audio.data(), 960);
    synth->noteOn({20,40,0,60,100,1}); synth->render(audio.data(), 960);
    synth->noteOff(1); synth->noteOff(1);
    for (int i = 0; i < 20; ++i) synth->render(audio.data(), 960);
    assert(energy(audio) > 0.001); // Repeated short-note off must not release the sustained note.
    synth->silence(); synth->render(audio.data(), 960); assert(energy(audio) == 0);
    std::puts("PASS: real same-pitch polyphony and complete stop/seek silence");
    auto fallback = loadSoundFont(bank); auto expected = loadSoundFont(bank);
    fallback->volume(0, 1); expected->volume(0, 1);
    fallback->preset(0, 19, 0); fallback->preset(0, 73, 9999); expected->preset(0, 73, 0);
    fallback->noteOn({0,100,0,60,100,0}); expected->noteOn({0,100,0,60,100,0});
    std::vector<float> fallbackAudio(1920), expectedAudio(1920);
    fallback->render(fallbackAudio.data(), 960); expected->render(expectedAudio.data(), 960);
    assert(fallbackAudio == expectedAudio);
    auto finalFallback = loadSoundFont(bank); auto gmZero = loadSoundFont(bank);
    finalFallback->volume(0, 1); gmZero->volume(0, 1);
    finalFallback->preset(0, 19, 0); finalFallback->preset(0, 999, 9999); gmZero->preset(0, 0, 0);
    finalFallback->noteOn({0,100,0,60,100,0}); gmZero->noteOn({0,100,0,60,100,0});
    fallbackAudio.assign(1920, 0); expectedAudio.assign(1920, 0);
    finalFallback->render(fallbackAudio.data(), 960); gmZero->render(expectedAudio.data(), 960);
    assert(fallbackAudio == expectedAudio);
    std::puts("PASS: missing bank falls back deterministically instead of retaining the previous preset");

    Mix exportMix; exportMix.levels[0] = 1; exportMix.programs[0] = 19; exportMix.overrides[0] = true;
    std::thread exporter([&] { writeWave(bank, "/tmp/musicxml-native-export.wav", {{0,200,0,60,100,0}}, {}, 200, exportMix); });
    render(core, 12000); exporter.join();
    std::ifstream wave("/tmp/musicxml-native-export.wav", std::ios::binary);
    std::array<char, 44> header{}; wave.read(header.data(), header.size());
    assert(std::string(header.data(), 4) == "RIFF" && std::string(header.data() + 8, 4) == "WAVE");
    assert(static_cast<unsigned char>(header[24]) == (44100 & 255));
    assert(static_cast<unsigned char>(header[22]) == 2 && static_cast<unsigned char>(header[34]) == 16);
    assert(wave.seekg(0, std::ios::end).tellg() == 44 + 1700 * 44100 / 1000 * 4);
    std::remove("/tmp/musicxml-native-export.wav");
    writeWave(bank, "/tmp/musicxml-native-pedal.wav", {{0,20,0,60,100,0}},
              {{10,10,0,64,63,63}, {900,900,0,64,0,0}}, 100, exportMix);
    std::ifstream pedalWave("/tmp/musicxml-native-pedal.wav", std::ios::binary);
    assert(pedalWave.seekg(0, std::ios::end).tellg() == 44 + 2020 * 44100 / 1000 * 4);
    pedalWave.close(); std::remove("/tmp/musicxml-native-pedal.wav");
    assert(std::abs(core.position() - 810) < 0.02);
    std::puts("PASS: WAV length includes resolved pedal duration and concurrent export does not interrupt live synth");
}
void clockAndQueueTests() {
    AudioClock clock; clock.record(0,0,1); clock.record(480,10,1.5); clock.record(960,500,1.5); clock.record(1440,20,1);
    assert(std::abs(clock.position(240,48000,0) - 5) < 0.001);
    assert(std::abs(clock.position(720,48000,0) - 17.5) < 0.001);
    assert(std::abs(clock.position(1000,48000,0) - 501.25) < 0.001);
    assert(std::abs(clock.position(1500,48000,0) - 21.25) < 0.001);
    Commands commands;
    std::thread producer([&] { for (int i = 0; i < 100000; ++i) { Command command{Operation::Seek, double(i)}; while (!commands.push(command)) std::this_thread::yield(); } });
    for (int i = 0; i < 100000; ++i) { Command command; while (!commands.pop(command)) std::this_thread::yield(); assert(command.first == i); }
    producer.join();
    ReconnectPolicy retry;
    assert(retry.nextDelayMillis(1000) == 100 && retry.nextDelayMillis(1100) == 200);
    assert(!retry.exhausted(30999) && retry.exhausted(31000));
    assert(adaptiveBufferFrames(384, 192, 4096, 48000) == 576);
    assert(adaptiveBufferFrames(1400, 192, 4096, 48000) == 1536);
    assert(adaptiveBufferFrames(1800, 192, 4096, 48000) == 1800);
    std::puts("PASS: hardware-frame clock across rate/seek boundaries and 100000 concurrent controls");
}
int main(int argc, char** argv) {
    assert(argc == 2); scheduleTests(); clockAndQueueTests(); soundFontTests(argv[1]);
    std::puts("ALL REALTIME NATIVE TESTS PASSED");
}
