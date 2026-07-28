#include <jni.h>
#include <string>
#include "whisper.h"

namespace {
void throw_runtime(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_qelg_harnessandroid_voice_WhisperNative_createContext(
        JNIEnv * env, jobject, jstring model_path) {
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context * context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    if (context == nullptr) {
        throw_runtime(env, "Could not load the local Whisper model");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_qelg_harnessandroid_voice_WhisperNative_freeContext(
        JNIEnv *, jobject, jlong pointer) {
    if (pointer != 0) whisper_free(reinterpret_cast<whisper_context *>(pointer));
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_qelg_harnessandroid_voice_WhisperNative_transcribe(
        JNIEnv * env, jobject, jlong pointer, jfloatArray samples, jint thread_count) {
    auto * context = reinterpret_cast<whisper_context *>(pointer);
    if (context == nullptr) {
        throw_runtime(env, "Whisper is not initialized");
        return nullptr;
    }

    jfloat * data = env->GetFloatArrayElements(samples, nullptr);
    const jsize count = env->GetArrayLength(samples);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = thread_count;
    params.language = "auto";
    params.translate = false;
    params.no_context = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;

    const int result = whisper_full(context, params, data, count);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (result != 0) {
        throw_runtime(env, "Local Whisper transcription failed");
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(context, i);
    return env->NewStringUTF(text.c_str());
}
