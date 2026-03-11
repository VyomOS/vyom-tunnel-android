#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <stdio.h>

extern "C" {
int XrayMain(const char *configJson, const char *assetPath);
void XrayStop();
char* XrayTestConfig(const char *configJson, const char *assetPath);
void XrayFreeString(char* str);

void hev_socks5_tunnel_main(const char *config_path, int fd);
void hev_socks5_tunnel_stop();
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_vyomtunnel_core_NativeEngine_startXray(JNIEnv *env, jobject thiz, jstring config, jstring asset_path) {
    const char *nativeConfig = env->GetStringUTFChars(config, 0);
    const char *nativePath = env->GetStringUTFChars(asset_path, 0);
    int result = XrayMain(nativeConfig, nativePath);
    env->ReleaseStringUTFChars(config, nativeConfig);
    env->ReleaseStringUTFChars(asset_path, nativePath);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_vyomtunnel_core_NativeEngine_stopXray(JNIEnv *env, jobject thiz) {
    XrayStop();
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_vyomtunnel_core_NativeEngine_validateConfig(JNIEnv *env, jobject thiz, jstring config, jstring asset_path) {
    const char *nativeConfig = env->GetStringUTFChars(config, 0);
    const char *nativePath = env->GetStringUTFChars(asset_path, 0); // Get the path string

    char *error = XrayTestConfig(nativeConfig, nativePath);

    env->ReleaseStringUTFChars(config, nativeConfig);
    env->ReleaseStringUTFChars(asset_path, nativePath); // Release path string

    if (error == nullptr) return nullptr;
    jstring result = env->NewStringUTF(error);

    XrayFreeString(error);
    return result;
}

extern "C"
bool protect_socket(JNIEnv *env, int fd) {
    jclass engineClass = env->FindClass("io/github/vyomtunnel/core/NativeEngine");
    jmethodID method = env->GetStaticMethodID(engineClass, "protectSocket", "(I)Z");

    jboolean result = env->CallStaticBooleanMethod(engineClass, method, fd);
    return result == JNI_TRUE;
}

