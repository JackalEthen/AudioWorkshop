// Signalsmith Stretch（MIT，源自 BBC R&D 在 ADC22 发表的音调移位算法）
// 与 RNNoise 不同，这里必须用 C++ 编译，文件后缀特意用 .cpp。
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include "signalsmith-stretch.h"
#include "ebur128.h"

namespace {

// Stretch 的输入输出容器：交织 float + 偏移，接口对齐 signalsmith 的期望。
struct Pcm {
    std::vector<float> samples;
    size_t channels = 1;
    size_t offset = 0;

    size_t length() const {
        size_t perChannel = samples.size() / channels;
        return (perChannel >= offset) ? perChannel - offset : 0;
    }

    void resize(size_t value) { samples.resize((offset + value) * channels, 0.0f); }

    class ChannelRef {
        float *data;
        size_t stride;

    public:
        ChannelRef(float *data, size_t stride) : data(data), stride(stride) {}
        float &operator[](size_t index) { return data[index * stride]; }
        const float &operator[](size_t index) const { return data[index * stride]; }
    };

    ChannelRef operator[](size_t c) {
        return ChannelRef(samples.data() + offset * channels + c, channels);
    }
};

void interleave(const jshort *input, float *output, jint frames, jint channels) {
    for (jint index = 0; index < frames * channels; ++index) {
        output[index] = static_cast<float>(input[index]) / 32768.0f;
    }
}

void deinterleave(const float *input, jshort *output, jint frames, jint channels) {
    for (jint index = 0; index < frames * channels; ++index) {
        float value = input[index] * 32768.0f;
        output[index] = static_cast<jshort>(std::max(-32768.0f, std::min(32767.0f, value)));
    }
}

/** libebur128 只吃 double/int，这里统一转 double。 */
void toDouble(const jshort *input, jint count, double *output) {
    for (jint index = 0; index < count; ++index) {
        output[index] = static_cast<double>(input[index]);
    }
}

}  // namespace

extern "C" {

/**
 * 变速 + 变调。speed>1 变快变短，semitones>0 升调。
 * [output] 已由调用方按最大长度分配，返回写入的帧数，失败 -1。
 */
JNIEXPORT jint JNICALL
Java_cn_qishui_tool_media_effect_StretchBridge_nativeProcess(
        JNIEnv *env,
        jclass clazz,
        jshortArray pcm,
        jint frames,
        jint channels,
        jint sampleRate,
        jfloat speed,
        jfloat semitones,
        jshortArray output) {
    (void) clazz;
    if (pcm == nullptr || output == nullptr || frames <= 0 || channels <= 0 || channels > 2 || sampleRate <= 0) {
        return -1;
    }
    const jsize size = env->GetArrayLength(pcm);
    if (size < frames * channels) {
        return -1;
    }
    const float factor = std::max(0.25f, std::min(4.0f, speed));
    const jint targetFrames = std::max(1, static_cast<jint>(static_cast<float>(frames) / factor));
    if (env->GetArrayLength(output) < targetFrames * channels) {
        return -1;
    }

    jshort *input = env->GetShortArrayElements(pcm, nullptr);
    if (input == nullptr) {
        return -1;
    }
    Pcm in;
    in.channels = static_cast<size_t>(channels);
    in.samples.resize(static_cast<size_t>(frames) * channels);
    interleave(input, in.samples.data(), frames, channels);
    env->ReleaseShortArrayElements(pcm, input, JNI_ABORT);

    Pcm out;
    out.channels = in.channels;
    try {
        signalsmith::stretch::SignalsmithStretch<float> stretch;
        stretch.presetDefault(channels, static_cast<float>(sampleRate));
        if (std::fabs(semitones) > 0.001f) {
            stretch.setTransposeSemitones(semitones, 0.5f);
        }
        // 输入延迟：先补一段前导静音，保证输出起点对齐
        const jint latency = stretch.inputLatency();
        Pcm padded;
        padded.channels = in.channels;
        padded.resize(static_cast<size_t>(latency) + static_cast<size_t>(frames) + channels);
        for (size_t i = 0; i < in.samples.size(); ++i) {
            padded.samples[i + static_cast<size_t>(latency) * in.channels] = in.samples[i];
        }
        out.resize(static_cast<size_t>(targetFrames));
        stretch.process(padded, frames + latency, out, targetFrames);
    } catch (...) {
        return -1;
    }

    jshort *buffer = env->GetShortArrayElements(output, nullptr);
    if (buffer == nullptr) {
        return -1;
    }
    deinterleave(out.samples.data(), buffer, targetFrames, channels);
    env->ReleaseShortArrayElements(output, buffer, 0);
    return targetFrames;
}

/** EBU R128 整体响度（LUFS），失败返回 -1000。 */
JNIEXPORT jfloat JNICALL
Java_cn_qishui_tool_media_effect_LoudnessBridge_nativeMeasureLufs(
        JNIEnv *env,
        jclass clazz,
        jshortArray pcm,
        jint frames,
        jint channels,
        jint sampleRate) {
    (void) clazz;
    if (pcm == nullptr || frames <= 0 || channels <= 0) {
        return -1000.0f;
    }
    jshort *input = env->GetShortArrayElements(pcm, nullptr);
    if (input == nullptr) {
        return -1000.0f;
    }
    ebur128_state *state = ebur128_init(static_cast<unsigned>(channels),
                                           static_cast<unsigned long>(sampleRate), EBUR128_MODE_I);
    float result = -1000.0f;
    if (state != nullptr) {
        std::vector<double> samples(static_cast<size_t>(frames) * channels);
        toDouble(input, frames * channels, samples.data());
        ebur128_add_frames_double(state, samples.data(), frames);
        double loudness = 0.0;
        if (ebur128_loudness_global(state, &loudness) == EBUR128_SUCCESS) {
            result = static_cast<float>(loudness);
        }
        ebur128_destroy(&state);
    }
    env->ReleaseShortArrayElements(pcm, input, JNI_ABORT);
    return result;
}

/** 按目标 LUFS 就地增益，返回实际施加的增益 dB。 */
JNIEXPORT jfloat JNICALL
Java_cn_qishui_tool_media_effect_LoudnessBridge_nativeNormalize(
        JNIEnv *env,
        jclass clazz,
        jshortArray pcm,
        jint frames,
        jint channels,
        jint sampleRate,
        jfloat targetLufs) {
    (void) clazz;
    if (pcm == nullptr || frames <= 0 || channels <= 0) {
        return 0.0f;
    }
    jshort *input = env->GetShortArrayElements(pcm, nullptr);
    if (input == nullptr) {
        return 0.0f;
    }
    const jint total = frames * channels;
    ebur128_state *state = ebur128_init(static_cast<unsigned>(channels),
                                           static_cast<unsigned long>(sampleRate), EBUR128_MODE_I);
    float gainDb = 0.0f;
    if (state != nullptr) {
        std::vector<double> samples(static_cast<size_t>(total));
        toDouble(input, total, samples.data());
        ebur128_add_frames_double(state, samples.data(), frames);
        double loudness = 0.0;
        if (ebur128_loudness_global(state, &loudness) == EBUR128_SUCCESS && loudness > -70.0) {
            gainDb = static_cast<float>(targetLufs) - static_cast<float>(loudness);
            // 超过 +12dB 说明原音频几乎是静音，猛拉只会把底噪放大
            gainDb = std::max(-24.0f, std::min(12.0f, gainDb));
            const float gain = std::pow(10.0f, gainDb / 20.0f);
            for (jint index = 0; index < total; ++index) {
                input[index] = static_cast<jshort>(std::max(
                    -32768.0f, std::min(32767.0f, static_cast<float>(input[index]) * gain)));
            }
        }
        ebur128_destroy(&state);
    }
    env->ReleaseShortArrayElements(pcm, input, 0);
    return gainDb;
}

}  // extern "C"
