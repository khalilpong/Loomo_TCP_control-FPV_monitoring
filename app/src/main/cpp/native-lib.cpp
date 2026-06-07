#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_sample_loomodemo_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "行进中";
    return env->NewStringUTF(hello.c_str());
}