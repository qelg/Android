#include <jni.h>
#include <cstdint>
#include <string>
#include "whisper.h"

namespace {
void throw_runtime(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(type, message);
}

jstring utf8_to_java(JNIEnv * env, const std::string & text) {
    std::u16string utf16;
    for (size_t i = 0; i < text.size();) {
        const uint8_t first = static_cast<uint8_t>(text[i]);
        uint32_t code_point;
        size_t continuation_count;
        if (first < 0x80) {
            code_point = first;
            continuation_count = 0;
        } else if ((first & 0xe0) == 0xc0) {
            code_point = first & 0x1f;
            continuation_count = 1;
        } else if ((first & 0xf0) == 0xe0) {
            code_point = first & 0x0f;
            continuation_count = 2;
        } else if ((first & 0xf8) == 0xf0) {
            code_point = first & 0x07;
            continuation_count = 3;
        } else {
            throw_runtime(env, "Whisper returned invalid UTF-8");
            return nullptr;
        }
        if (i + continuation_count >= text.size()) {
            throw_runtime(env, "Whisper returned invalid UTF-8");
            return nullptr;
        }
        for (size_t j = 1; j <= continuation_count; ++j) {
            const uint8_t next = static_cast<uint8_t>(text[i + j]);
            if ((next & 0xc0) != 0x80) {
                throw_runtime(env, "Whisper returned invalid UTF-8");
                return nullptr;
            }
            code_point = (code_point << 6) | (next & 0x3f);
        }
        const bool overlong =
            (continuation_count == 1 && code_point < 0x80) ||
            (continuation_count == 2 && code_point < 0x800) ||
            (continuation_count == 3 && code_point < 0x10000);
        if (overlong || code_point > 0x10ffff ||
            (code_point >= 0xd800 && code_point <= 0xdfff)) {
            throw_runtime(env, "Whisper returned invalid UTF-8");
            return nullptr;
        }
        if (code_point <= 0xffff) {
            utf16.push_back(static_cast<char16_t>(code_point));
        } else {
            code_point -= 0x10000;
            utf16.push_back(static_cast<char16_t>(0xd800 + (code_point >> 10)));
            utf16.push_back(static_cast<char16_t>(0xdc00 + (code_point & 0x3ff)));
        }
        i += continuation_count + 1;
    }
    return env->NewString(
        reinterpret_cast<const jchar *>(utf16.data()),
        static_cast<jsize>(utf16.size()));
}

struct transcription_callbacks {
    JNIEnv * env;
    jobject listener;
    jmethodID on_progress;
    jmethodID on_partial;
    jmethodID should_abort;
    bool callback_failed = false;
};

void report_progress(whisper_context *, whisper_state *, int progress, void * user_data) {
    auto * callbacks = static_cast<transcription_callbacks *>(user_data);
    if (callbacks->callback_failed) return;
    callbacks->env->CallVoidMethod(callbacks->listener, callbacks->on_progress, progress);
    callbacks->callback_failed = callbacks->env->ExceptionCheck();
}

void report_segments(whisper_context *, whisper_state * state, int, void * user_data) {
    auto * callbacks = static_cast<transcription_callbacks *>(user_data);
    if (callbacks->callback_failed) return;
    std::string text;
    const int segments = whisper_full_n_segments_from_state(state);
    for (int i = 0; i < segments; ++i) {
        text += whisper_full_get_segment_text_from_state(state, i);
    }
    jstring partial = utf8_to_java(callbacks->env, text);
    if (partial == nullptr) {
        callbacks->callback_failed = true;
        return;
    }
    callbacks->env->CallVoidMethod(callbacks->listener, callbacks->on_partial, partial);
    callbacks->env->DeleteLocalRef(partial);
    callbacks->callback_failed = callbacks->env->ExceptionCheck();
}

bool abort_transcription(void * user_data) {
    auto * callbacks = static_cast<transcription_callbacks *>(user_data);
    if (callbacks->callback_failed || callbacks->env->ExceptionCheck()) return true;
    const bool abort =
        callbacks->env->CallBooleanMethod(callbacks->listener, callbacks->should_abort);
    if (callbacks->env->ExceptionCheck()) {
        callbacks->callback_failed = true;
        return true;
    }
    return abort;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_qelg_harnessandroid_voice_WhisperNative_createContext(
        JNIEnv * env, jobject, jstring model_path) {
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;
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
        JNIEnv * env,
        jobject,
        jlong pointer,
        jfloatArray samples,
        jint thread_count,
        jstring initial_prompt,
        jobject listener) {
    auto * context = reinterpret_cast<whisper_context *>(pointer);
    if (context == nullptr) {
        throw_runtime(env, "Whisper is not initialized");
        return nullptr;
    }

    jclass listener_class = env->GetObjectClass(listener);
    transcription_callbacks callbacks = {
        env,
        listener,
        env->GetMethodID(listener_class, "onProgress", "(I)V"),
        env->GetMethodID(listener_class, "onPartial", "(Ljava/lang/String;)V"),
        env->GetMethodID(listener_class, "shouldAbort", "()Z"),
    };
    env->DeleteLocalRef(listener_class);
    if (callbacks.on_progress == nullptr || callbacks.on_partial == nullptr ||
        callbacks.should_abort == nullptr) {
        return nullptr;
    }

    jfloat * data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(samples);
    const char * prompt =
        initial_prompt == nullptr ? nullptr : env->GetStringUTFChars(initial_prompt, nullptr);
    if (initial_prompt != nullptr && prompt == nullptr) {
        env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
        return nullptr;
    }
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = thread_count;
    params.language = "auto";
    params.translate = false;
    params.no_context = true;
    params.initial_prompt = prompt;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.progress_callback = report_progress;
    params.progress_callback_user_data = &callbacks;
    params.new_segment_callback = report_segments;
    params.new_segment_callback_user_data = &callbacks;
    params.abort_callback = abort_transcription;
    params.abort_callback_user_data = &callbacks;

    const int result = whisper_full(context, params, data, count);
    if (prompt != nullptr) env->ReleaseStringUTFChars(initial_prompt, prompt);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (env->ExceptionCheck()) return nullptr;
    if (result != 0) {
        throw_runtime(env, "Local Whisper transcription failed");
        return nullptr;
    }

    std::string text;
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(context, i);
    return utf8_to_java(env, text);
}
