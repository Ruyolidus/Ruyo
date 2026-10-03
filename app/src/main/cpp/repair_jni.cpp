#include "patch_repair.h"
#include <jni.h>
#include <exception>
extern "C" JNIEXPORT jintArray JNICALL Java_com_ruyo_reader_NativeArtworkRepair_repairPixels(
    JNIEnv* env, jobject, jintArray pixels, jint width, jint height, jbooleanArray mask, jbooleanArray excluded) {
    if(!pixels || !mask || !excluded || width<1 || height<1 || int64_t(width)*height>2000000 ||
       env->GetArrayLength(pixels)!=int64_t(width)*height || env->GetArrayLength(mask)!=env->GetArrayLength(pixels) ||
       env->GetArrayLength(excluded)!=env->GetArrayLength(pixels)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid repair dimensions"); return nullptr;
    }
    try {
        int n=env->GetArrayLength(pixels);
        std::vector<int32_t> input(n); std::vector<uint8_t> holes(n),blocked(n);
        env->GetIntArrayRegion(pixels,0,n,input.data()); env->GetBooleanArrayRegion(mask,0,n,holes.data());
        env->GetBooleanArrayRegion(excluded,0,n,blocked.data());
        if(env->ExceptionCheck()) return nullptr;
        auto output=ruyo::repair(input,width,height,holes,blocked);
        auto result=env->NewIntArray(n);
        if(result) env->SetIntArrayRegion(result,0,n,output.data());
        return result;
    } catch(const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),error.what()); return nullptr;
    }
}
