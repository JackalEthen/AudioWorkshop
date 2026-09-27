package cn.qishui.tool.domain.waveform

import cn.qishui.tool.domain.model.WaveformPeaks
import java.io.File

interface WaveformSource {
    suspend fun peaks(sourceTrackId: String, file: File): WaveformPeaks
}
