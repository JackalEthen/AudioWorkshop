#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "rnnoise.h"

// RNNoise 固定 48kHz 单声道 float，帧长 480（10ms）。模型已编进本库，
// 所以 rnnoise_create 传 NULL 即可，不需要运行时加载权重文件。

typedef struct {
    DenoiseState *state;
    float scratch[480];
} QishuiRnnoise;

JNIEXPORT jlong JNICALL
Java_cn_qishui_tool_media_effect_RnnoiseBridge_nativeCreate(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    DenoiseState *state = rnnoise_create(NULL);
    if (state == NULL) {
        return 0;
    }
    QishuiRnnoise *handle = (QishuiRnnoise *) calloc(1, sizeof(QishuiRnnoise));
    if (handle == NULL) {
        rnnoise_destroy(state);
        return 0;
    }
    handle->state = state;
    return (jlong) (intptr_t) handle;
}

JNIEXPORT void JNICALL
Java_cn_qishui_tool_media_effect_RnnoiseBridge_nativeDestroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    QishuiRnnoise *owner = (QishuiRnnoise *) (intptr_t) handle;
    if (owner == NULL) {
        return;
    }
    if (owner->state != NULL) {
        rnnoise_destroy(owner->state);
    }
    free(owner);
}

/**
 * 就地处理一段 float，返回末帧语音概率（0..1）。长度会向下取整到整帧。
 */
JNIEXPORT jfloat JNICALL
Java_cn_qishui_tool_media_effect_RnnoiseBridge_nativeProcess(
        JNIEnv *env,
        jclass clazz,
        jlong handle,
        jfloatArray samples,
        jint length) {
    (void) env;
    (void) clazz;
    QishuiRnnoise *owner = (QishuiRnnoise *) (intptr_t) handle;
    if (owner == NULL) {
        return (jfloat) -1;
    }
    const jsize size = (*env)->GetArrayLength(env, samples);
    const jint count = length < size ? length : size;
    const int frame = rnnoise_get_frame_size();
    if (count < frame) {
        return (jfloat) -1;
    }
    jfloat *buffer = (jfloat *) malloc(sizeof(jfloat) * (size_t) count);
    if (buffer == NULL) {
        return (jfloat) -1;
    }
    (*env)->GetFloatArrayRegion(env, samples, 0, count, buffer);
    float vad = 0.0f;
    const jint usable = count - (count % frame);
    for (jint offset = 0; offset < usable; offset += frame) {
        vad = rnnoise_process_frame(owner->state, buffer + offset, buffer + offset);
    }
    (*env)->SetFloatArrayRegion(env, samples, 0, count, buffer);
    free(buffer);
    return vad;
}

JNIEXPORT jint JNICALL
Java_cn_qishui_tool_media_effect_RnnoiseBridge_nativeFrameSize(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) rnnoise_get_frame_size();
}
