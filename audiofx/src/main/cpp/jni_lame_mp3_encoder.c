#include <jni.h>
#include <stdint.h>

#include "lame.h"

#define QS_HANDLE_INVALID (-9001)

static lame_global_flags *qs_flags(jlong handle)
{
    return (lame_global_flags *) (intptr_t) handle;
}

JNIEXPORT jlong JNICALL Java_cn_qishui_tool_media_export_LameNativeBridge_nativeInit(
        JNIEnv *env, jobject bridge, jint sample_rate, jint channels, jint vbr_quality)
{
    lame_global_flags *flags = lame_init();
    if (flags == NULL) {
        return QS_HANDLE_INVALID;
    }
    if (lame_set_in_samplerate(flags, (int) sample_rate) < 0
        || lame_set_num_channels(flags, (int) channels) < 0
        || lame_set_mode(flags, channels == 1 ? MONO : JOINT_STEREO) < 0
        || lame_set_VBR(flags, vbr_mt) < 0
        || lame_set_VBR_q(flags, (int) vbr_quality) < 0
        || lame_set_bWriteVbrTag(flags, 0) < 0
        || lame_set_quality(flags, 2) < 0
        || lame_init_params(flags) < 0) {
        lame_close(flags);
        return -1;
    }
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
