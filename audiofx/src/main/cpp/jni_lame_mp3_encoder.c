#include <jni.h>
#include <android/log.h>
#include <stdint.h>

#include "lame.h"

/* nativeInit 成功返回 lame_global_flags* 指针，失败返回 -(1000 + step)。
 *
 * Kotlin 侧靠 |code| <= 2000 来识别错误码 —— 不能改成判正负：
 * Android 的 MTE 堆指针带标签（形如 0xb40000748beeaf00），符号位为 1，
 * 作为有符号 jlong 看是负数，判 <= 0 会把每次成功的初始化都当成失败。 */
#define QS_STEP_ERROR_BASE (-1000)

/* 其它 JNI 函数的错误码。必须落在 |code| <= 2000 区间内，
 * 别再单独造 -9001 这种超界的值。 */
#define QS_HANDLE_INVALID (-900)
#define QS_TAG "qishui/lame"

static lame_global_flags *qs_flags(jlong handle)
{
    return (lame_global_flags *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL Java_cn_qishui_tool_media_export_LameNativeBridge_nativeInit(
        JNIEnv *env, jobject bridge, jint sample_rate, jint channels, jint bitrate_kbps)
{
    lame_global_flags *flags = lame_init();
    if (flags == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, QS_TAG, "lame_init() returned NULL");
        return QS_HANDLE_INVALID;
    }

    /* 每一步单独判，别把 5 个 set 串成一个 || —— 失败时根本看不出是哪一步挂的。
     * 每个 -1 都带 step 编号，Kotlin 侧直接透出给日志。 */
#define QS_SET_STEP(call, step) \
    do { \
        int rc_ = (call); \
        if (rc_ < 0) { \
            __android_log_print(ANDROID_LOG_ERROR, QS_TAG, \
                "step %d failed: %s rc=%d (rate=%d ch=%d brate=%d)", \
                (step), #call, rc_, (int) sample_rate, (int) channels, (int) bitrate_kbps); \
            lame_close(flags); \
            return QS_STEP_ERROR_BASE - (step); \
        } \
    } while (0)

    QS_SET_STEP(lame_set_in_samplerate(flags, (int) sample_rate), 1);
    QS_SET_STEP(lame_set_num_channels(flags, (int) channels), 2);
    QS_SET_STEP(lame_set_mode(flags, channels == 1 ? MONO : JOINT_STEREO), 3);
    QS_SET_STEP(lame_set_bWriteVbrTag(flags, 0), 4);
    QS_SET_STEP(lame_set_quality(flags, 2), 5);

    // bitrate_kbps > 0 走固定码率，否则退回 VBR（质量 2）
    if (bitrate_kbps > 0) {
        QS_SET_STEP(lame_set_VBR(flags, vbr_off), 6);
        QS_SET_STEP(lame_set_brate(flags, (int) bitrate_kbps), 7);
    } else {
        /* vbr_mt 是多线程 VBR，在这份 LAME 配置下 lame_init_params 会拒绝。
         * vbr_default / vbr_rh 才是单线程稳妥选择，音质差别对导出无感。 */
        QS_SET_STEP(lame_set_VBR(flags, vbr_default), 8);
        QS_SET_STEP(lame_set_VBR_q(flags, 2), 9);
    }

#undef QS_SET_STEP

    if (lame_init_params(flags) < 0) {
        __android_log_print(ANDROID_LOG_ERROR, QS_TAG,
            "lame_init_params failed (rate=%d ch=%d brate=%d)",
            (int) sample_rate, (int) channels, (int) bitrate_kbps);
        lame_close(flags);
        return QS_STEP_ERROR_BASE - 10;
    }
__android_log_print(ANDROID_LOG_INFO, QS_TAG,
        "init ok (rate=%d ch=%d brate=%d)",
        (int) sample_rate, (int) channels, (int) bitrate_kbps);
    return (jlong) (intptr_t) flags;
}

JNIEXPORT jint JNICALL Java_cn_qishui_tool_media_export_LameNativeBridge_nativeEncode(
        JNIEnv *env, jobject bridge, jlong handle, jshortArray pcm, jint samples_per_channel, jbyteArray out)
{
    lame_global_flags *flags = qs_flags(handle);
    if (flags == NULL) {
        return QS_HANDLE_INVALID;
    }
    int channels = lame_get_num_channels(flags);
    if (channels <= 0 || samples_per_channel <= 0) {
        return QS_HANDLE_INVALID;
    }
    jsize required = (jsize) samples_per_channel * channels;
    if ((*env)->GetArrayLength(env, pcm) < required) {
        return QS_HANDLE_INVALID;
    }
    jsize out_length = (*env)->GetArrayLength(env, out);
    if (out_length <= 0) {
        return QS_HANDLE_INVALID;
    }
    jshort *pcm_ptr = (*env)->GetShortArrayElements(env, pcm, NULL);
    if (pcm_ptr == NULL) {
        return QS_HANDLE_INVALID;
    }
    jbyte *out_ptr = (*env)->GetByteArrayElements(env, out, NULL);
    if (out_ptr == NULL) {
        (*env)->ReleaseShortArrayElements(env, pcm, pcm_ptr, JNI_ABORT);
        return QS_HANDLE_INVALID;
    }
    int written = lame_encode_buffer_interleaved(
            flags, pcm_ptr, (int) samples_per_channel, (unsigned char *) out_ptr, (int) out_length);
    (*env)->ReleaseByteArrayElements(env, out, out_ptr, 0);
    (*env)->ReleaseShortArrayElements(env, pcm, pcm_ptr, JNI_ABORT);
    return (jint) written;
}

JNIEXPORT jint JNICALL Java_cn_qishui_tool_media_export_LameNativeBridge_nativeFlush(
        JNIEnv *env, jobject bridge, jlong handle, jbyteArray out)
{
    lame_global_flags *flags = qs_flags(handle);
    if (flags == NULL) {
        return QS_HANDLE_INVALID;
    }
    jsize out_length = (*env)->GetArrayLength(env, out);
    if (out_length <= 0) {
        return QS_HANDLE_INVALID;
    }
    jbyte *out_ptr = (*env)->GetByteArrayElements(env, out, NULL);
    if (out_ptr == NULL) {
        return QS_HANDLE_INVALID;
    }
    int written = lame_encode_flush(flags, (unsigned char *) out_ptr, (int) out_length);
    (*env)->ReleaseByteArrayElements(env, out, out_ptr, 0);
    return (jint) written;
}

JNIEXPORT void JNICALL Java_cn_qishui_tool_media_export_LameNativeBridge_nativeClose(
        JNIEnv *env, jobject bridge, jlong handle)
{
    lame_global_flags *flags = qs_flags(handle);
    if (flags != NULL) {
        lame_close(flags);
    }
}
