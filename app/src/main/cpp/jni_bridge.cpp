#include <jni.h>
#include "llama.h"

// Milestone 1: prove that llama.cpp is compiled in, linked, and callable.
// Chat, model loading and generation come in milestone 2.
extern "C" JNIEXPORT jstring JNICALL
Java_com_offlineai_assistant_NativeBridge_systemInfo(JNIEnv *env, jobject /* this */) {
    const char *info = llama_print_system_info();
    return env->NewStringUTF(info != nullptr ? info : "llama.cpp returned no info");
}
