# Vendored whisper.cpp

This directory contains the CPU-only source needed from
[whisper.cpp v1.7.6](https://github.com/ggerganov/whisper.cpp/releases/tag/v1.7.6), under the
included MIT license. GPU and desktop-only ggml backends were omitted because the Android build
uses ggml's CPU backend only. Update the Whisper and ggml sources together.
