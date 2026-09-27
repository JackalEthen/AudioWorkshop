package cn.qishui.tool.domain.waveform

import cn.qishui.tool.domain.model.WaveformPeaks
import java.io.File

interface WaveformSource {

    /**
     * 计算波形峰值。[onPartial] 会在解码过程中被反复调用（已解出的部分），
     * 让界面可以边解码边画，不用等整首解码完。
     */
    suspend fun peaks(
        sourceTrackId: String,
        file: File,
        onPartial: (WaveformPeaks) -> Unit = {},
    ): WaveformPeaks
}
