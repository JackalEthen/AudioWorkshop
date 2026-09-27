package cn.qishui.tool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs

/**
 * 参数卡的通用控件：标题 + 重置、数字值、滑杆、步进按钮。
 * 所有效果页共用，靠 [cn.qishui.tool.media.effect.EffectParam] 声明驱动。
 */
@Composable
fun ParamCard(
    title: String,
    onReset: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FloatingCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (onReset != null) {
                    SecondaryButton(
                        text = "重置",
                        onClick = onReset,
                        modifier = Modifier.height(40.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun ParamRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    stepButtons: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
        )
        if (stepButtons) {
            StepperRow(step = niceStep(range), onChange = onChange)
        }
    }
}

@Composable
private fun StepperRow(step: Float, onChange: (Float) -> Unit) {
    val big = step * 2f
    val minusStep = "-${formatNumber(step)}"
    val plusStep = "+${formatNumber(step)}"
    val minusBig = "-${formatNumber(big)}"
    val plusBig = "+${formatNumber(big)}"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StepButton(minusBig, Modifier.weight(1f)) { onChange(-big) }
        StepButton(minusStep, Modifier.weight(1f)) { onChange(-step) }
        StepButton(plusStep, Modifier.weight(1f)) { onChange(step) }
        StepButton(plusBig, Modifier.weight(1f)) { onChange(big) }
    }
}

@Composable
private fun StepButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SecondaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        textStyle = MaterialTheme.typography.labelMedium,
        contentPadding = PaddingValues(horizontal = 2.dp),
    )
}

/**
 * 步进按钮用一个「好看」的步长：让大步长约等于量程的 5%，
 * 避免出现 -0.05 这种按钮放不下的数字。
 */
private fun niceStep(range: ClosedFloatingPointRange<Float>): Float {
    val target = (range.endInclusive - range.start) / 20f
    val candidates = floatArrayOf(10f, 5f, 2f, 1f, 0.5f, 0.2f, 0.1f, 0.05f, 0.02f, 0.01f)
    return candidates.firstOrNull { it <= target } ?: 0.01f
}

fun formatNumber(value: Float): String =
    if (abs(value - value.toInt().toFloat()) < 0.001f) {
        value.toInt().toString()
    } else {
        String.format(Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd('.')
    }
