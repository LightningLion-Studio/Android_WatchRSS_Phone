#pragma once
// ImageProxy tests use readYBuffer. Fail if AndroidBitmap is accidentally used on the host.
#include <jni.h>
#include <cstdint>
struct AndroidBitmapInfo { uint32_t width, height, stride; int32_t format; uint32_t flags; };
enum { ANDROID_BITMAP_RESULT_SUCCESS=0, ANDROID_BITMAP_FORMAT_A_8=8, ANDROID_BITMAP_FORMAT_RGBA_8888=1 };
inline int AndroidBitmap_getInfo(JNIEnv*, jobject, AndroidBitmapInfo* info) { *info={}; return -1; }
inline int AndroidBitmap_lockPixels(JNIEnv*, jobject, void**) { return -1; }
inline int AndroidBitmap_unlockPixels(JNIEnv*, jobject) { return -1; }
