# Whisper acceleration on Android

The app currently uses the CPU backend from whisper.cpp v1.7.6. GPU use is explicitly disabled in
`whisper_jni.cpp`, and the deliberately small vendored source set does not include the Vulkan or
OpenCL backend implementations.

## Pixel 9 Pro findings

The Pixel 9 Pro uses Tensor G4 and a Mali-G715 GPU. The practical acceleration options are:

- **Vulkan:** whisper.cpp has a real Vulkan backend, but Android support is not part of its standard
  Android example and requires a larger coherent upstream source import, shader generation, and a
  CPU fallback. Reports in [whisper.cpp issue #2370](https://github.com/ggml-org/whisper.cpp/issues/2370)
  say Vulkan on Pixel 9 is comparable to or slower than CPU; Pixel 8 showed only a small improvement.
  Android driver failures are also still reported upstream. It is not suitable as the default without
  device benchmarks showing an end-to-end win.
- **OpenCL:** Android does not expose OpenCL as a stable NDK API, and ggml's optimized mobile kernels
  target Adreno rather than the Pixel's Mali GPU. Community end-to-end Whisper results are mixed or
  slower even where matrix benchmarks improve.
- **NNAPI:** ggml has no NNAPI backend, standard GGML Q5 model files are not compatible with NNAPI,
  and Android 15 deprecated NNAPI. Adding it would mean replacing the inference engine and model
  format rather than enabling a build flag.
- **Tensor TPU/NPU:** Google's public Tensor SDK currently lists Tensor G5, not the Pixel 9's Tensor
  G4, and is a beta LiteRT/AOT workflow. AICore only runs Google-provided Gemini Nano capabilities;
  it cannot run this app's Whisper model.
- **LiteRT GPU:** this is the most credible Android GPU runtime for a future alternative engine, but
  it needs separately converted TFLite encoder/decoder models, tokenizer/generation integration, and
  a new model catalog. It cannot consume the existing GGML downloads.

For now, CPU remains the reliable production backend. The next acceleration experiment should first
update whisper.cpp and benchmark an optimized ARM64 CPU build (including FP16/KleidiAI where runtime
CPU checks make it safe), thread counts, and the existing Tiny through Large Turbo models on the
Pixel 9 Pro. Vulkan is worth a time-boxed opt-in prototype only after that baseline exists.

Useful upstream references:

- [whisper.cpp Android example](https://github.com/ggml-org/whisper.cpp/tree/v1.9.1/examples/whisper.android)
- [whisper.cpp Vulkan backend](https://github.com/ggml-org/whisper.cpp/tree/v1.9.1/ggml/src/ggml-vulkan)
- [Android NNAPI deprecation and migration guidance](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)
- [LiteRT GPU acceleration](https://developers.google.com/edge/litert/next/gpu)
- [Google Tensor SDK](https://developers.google.com/edge/tensor-sdk)
