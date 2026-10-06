#!/bin/sh
set -eu
g++ -std=c++17 -O2 -pthread -DMUSICXML_TEST_ALLOCATIONS \
  -Iapp/build/generated/audio -Iapp/src/main/cpp \
  tests/native/realtime_test.cpp app/src/main/cpp/RealtimeCore.cpp \
  app/src/main/cpp/SoundFont.cpp app/src/main/cpp/OfflineWave.cpp \
  -o /tmp/realtime-tests
/tmp/realtime-tests app/build/generated/audio/GeneralUser-GS.sf2
