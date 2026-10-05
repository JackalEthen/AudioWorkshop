package cn.music.audioworkshop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.music.audioworkshop.R
import java.util.Locale
import kotlin.math.abs

/**
 * 参数卡的通用控件：标题 + 重置、数字值、滑杆、步进按钮。
 * 所有效果页共用，靠 [cn.music.audioworkshop.media.effect.EffectParam] 声明驱动。
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

/** 均衡器那种「频段 | 滑杆 | 增益」三列行。 */
@Composable
fun BandRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
            maxLines = 1,
        )
        QishuiSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            label = label,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (kotlin.math.abs(value) < 0.01f) {
                "不变"
            } else {
                String.format(Locale.getDefault(), "%+.1f dB", value)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
            maxLines = 1,
        )
    }
}

/** 「标签 …… 值 >」这种点开选一个选项的行，参考示例里到处在用。 */
@Composable
fun OptionRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlainCard(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            LucideIcon(
                icon = R.drawable.ic_chevron_right,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 18.dp,
            )
        }
    }
}

/** 离散选项用分段选择器：三选一的东西不该给滑杆。 */
@Composable
fun ChoiceRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SegmentedControl(
            options = options,
            selectedIndex = selectedIndex.coerceIn(0, options.lastIndex),
            onSelect = onSelect,
        )
    }
}

/** 参考示例里那种居中的大数值盒，配一组步进按钮用。 */
@Composable
fun ValueDisplayBox(
    valueText: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(QishuiFieldShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = valueText,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = unit,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        QishuiSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            label = label,
        )
        if (stepButtons) {
            StepperRow(step = niceStep(range), onChange = onChange)
        }
    }
}

@Composable
private fun StepperRow(step: Float, onChange: (Float) -> Unit) {
    StepperRow(
        labels = arrayOf(
            "-${formatNumber(step * 2f)}",
            "-${formatNumber(step)}",
            "+${formatNumber(step)}",
            "+${formatNumber(step * 2f)}",
        ),
        deltas = floatArrayOf(-step * 2f, -step, step, step * 2f),
        onChange = onChange,
    )
}

/**
 * 四个自定义步进按钮。「减半音 / 加半音」这种非等差步长没法用 niceStep 推出来，
 * 由调用方直接给标签和增量。
 */
@Composable
fun StepperRow(
    labels: Array<String>,
    deltas: FloatArray,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, text ->
            StepButton(text, Modifier.weight(1f)) { onChange(deltas[index]) }
        }
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
