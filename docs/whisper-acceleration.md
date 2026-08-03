# Whisper acceleration on Android

The app uses the CPU backend from whisper.cpp v1.9.1. GPU use is explicitly disabled in
`whisper_jni.cpp`.

## Optimized ARM64 CPU backend

ARM64 builds package seven separately compiled ggml CPU modules:

- Armv8.0 baseline;
- Armv8.2 with dot-product instructions;
- Armv8.2 with dot product and FP16 vector arithmetic;
- Armv8.6 with dot product, FP16 vector arithmetic, and i8mm;
- Armv9.0 with dot product, i8mm, and SVE2;
- Armv9.2 with dot product, i8mm, FP16 vector arithmetic, SVE, and SME;
- Armv9.2 with the additional SVE2 instructions.

Before loading a model, ggml reads Android's `AT_HWCAP` and `AT_HWCAP2` feature flags and loads the
fastest compatible module. On a Pixel 9 Pro this now selects the highest Armv9 or Armv8 module
reported by the device, while retaining the Armv8.0 fallback for older phones. The modules are extracted at installation so ggml can enumerate
and load them from the app's native-library directory. Other Android ABIs retain the baseline static
backend.

The Whisper settings dialog also permits Automatic or 1–8 worker threads. Automatic uses up to four threads, matching the four performance-oriented cores on Tensor G4 while
leaving its efficiency cores available to Android. The fastest value
can vary by model, recording length, device temperature, and Android scheduling. The native system
information, including the runtime HWCAP values for DOTPROD, FP16, i8mm, SVE, SVE2, and SME plus
the exact selected CPU backend variant, is written to Logcat under `LocalWhisper` when the first model
context is created. The native loader also emits separate `Android HWCAP features: ...` and
`Selected CPU backend variant: ...` lines.

KleidiAI remains disabled: the vendored release would fetch a non-vendored dependency, and its
principal optimized path does not match the app's Q5 model catalog. OpenMP, fast-math, hard CPU
affinity, and device-specific global `-march` flags also remain disabled to avoid nested thread pools,
quality changes, thermal regressions, and crashes on older ARM64 devices.

## Pixel 9 Pro GPU/NPU findings

The Pixel 9 Pro uses Tensor G4 and a Mali-G715 GPU. The practical non-CPU options are:

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

Vulkan remains worth a time-boxed opt-in prototype only if the optimized CPU baseline still cannot
meet the required latency.

Useful upstream references:

- [whisper.cpp Android example](https://github.com/ggml-org/whisper.cpp/tree/v1.9.1/examples/whisper.android)
- [whisper.cpp Vulkan backend](https://github.com/ggml-org/whisper.cpp/tree/v1.9.1/ggml/src/ggml-vulkan)
- [Android runtime CPU feature detection](https://developer.android.com/ndk/guides/cpu-features)
- [Android NNAPI deprecation and migration guidance](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)
- [LiteRT GPU acceleration](https://developers.google.com/edge/litert/next/gpu)
- [Google Tensor SDK](https://developers.google.com/edge/tensor-sdk)
