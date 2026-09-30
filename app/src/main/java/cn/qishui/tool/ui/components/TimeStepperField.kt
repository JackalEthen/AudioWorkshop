package cn.qishui.tool.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 「开始时间 / 结束时间」那种分秒毫秒三段式步进器：
 * 每段上面一排 +、下面一排 -，中间显示数值。
 *
 * 回调给的是**合成后的完整毫秒值**，不是被改动那一段的新值 ——
 * 否则调「毫秒」会把分和秒清零。
 */
/**
 * 秒级步进器，双 + 双 -：细调 0.1 秒、粗调 1 秒。
 * 淡入淡出用这个就够了，不需要分/毫秒段。
 */
@Composable
fun SecondsStepperField(
    label: String,
    seconds: Float,
    onSecondsChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safe = seconds.coerceAtLeast(0f)
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepUnitButton("+0.1", Modifier.weight(1f)) {
                onSecondsChange(roundTenths(safe + 0.1f))
            }
            StepUnitButton("+1", Modifier.weight(1f)) {
                onSecondsChange(roundTenths(safe + 1f))
            }
        }
        Text(
            text = String.format(Locale.getDefault(), "%.1f 秒", safe),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepUnitButton("-0.1", Modifier.weight(1f)) {
                onSecondsChange(roundTenths(safe - 0.1f))
            }
            StepUnitButton("-1", Modifier.weight(1f)) {
                onSecondsChange(roundTenths(safe - 1f))
            }
        }
    }
}

/**
 * 竖排步进块：标签 / [-] 数值+单位 [+]，双列并排就是参考示例那种卡片。
 * 用于「半圆环绕时间」「环绕幅度」这种整数档位。
 */
@Composable
fun StepperTile(
    label: String,
    value: Int,
    unit: String,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StepUnitButton("-", Modifier.size(40.dp)) { onChange(max(value - 1, range.first)) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StepUnitButton("+", Modifier.size(40.dp)) { onChange(min(value + 1, range.last)) }
        }
    }
}

/**
 * 「去除头部 00:03.000」那种横排时间块：彩色块内左边标签、右边白底数值框，
 * 点数值框才展开分/秒/毫秒步进器，避免把步进按钮摊在主界面上。
 */
@Composable
fun TimeValueTile(
    label: String,
    valueMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 1,
            )
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Text(
                    text = formatClockMs(valueMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** 毫秒显示成 00:03.000 这种时分秒格式。 */
fun formatClockMs(valueMs: Long): String {
    val safe = valueMs.coerceAtLeast(0L)
    return String.format(
        Locale.getDefault(),
        "%02d:%02d.%03d",
        safe / 60_000L,
        (safe / 1000L) % 60L,
        safe % 1000L,
    )
}

/**
 * 解析用户直接敲进去的时间。只接受两种写法：纯毫秒数字，或 00:03.000 / 3.5。
 * 解析不了就返回 null，调用方保持原值，不把非法输入写进状态。
 */
fun parseClockMs(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.all { it.isDigit() }) return trimmed.toLongOrNull()
    val clock = Regex("^(\\d+):(\\d{1,2})(?:\\.(\\d{1,3}))?$").find(trimmed) ?: return null
    val minutes = clock.groupValues[1].toLongOrNull() ?: return null
    val seconds = clock.groupValues[2].toLongOrNull() ?: return null
    if (seconds > 59) return null
    val millis = (clock.groupValues[3].padEnd(3, '0')).toLongOrNull() ?: 0L
    return minutes * 60_000L + seconds * 1000L + millis
}

/**
 * 「淡入淡出时间 [2000]」那种横排胶囊，左边标题、右边白底输入框，
 * 数值可以直接敲进去改。混音的淡入淡出时间、去除头尾的头尾时长都用它。
 *
 * 和 [TimeValueTile] 的区别：那个点一下才弹步进器，这个直接在原地输入。
 */
@Composable
fun LabeledTimeField(
    label: String,
    valueMs: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    /** 填 true 时数值按 00:03.000 显示，填 false 则直接显示毫秒数字。 */
    clockFormat: Boolean = true,
) {
    var text by remember(valueMs) { mutableStateOf(if (clockFormat) formatClockMs(valueMs) else valueMs.toString()) }
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = containerColor) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            BasicTextField(
                value = text,
                onValueChange = { updated ->
                    text = updated
                    parseClockMs(updated)?.let(onValueChange)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .widthIn(min = 84.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

private fun roundTenths(value: Float): Float = (max(value, 0f) * 10f).toInt() / 10f

@Composable
fun TimeStepperField(
    label: String,
    valueMs: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safe = valueMs.coerceAtLeast(0L)
    val minutes = (safe / 60_000L).toInt()
    val seconds = ((safe / 1000L) % 60L).toInt()
    val millis = (safe % 1000L).toInt()

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepUnit("分", minutes, 0..999, onChange = { onValueChange(composeMs(it, seconds, millis)) }, modifier = Modifier.weight(1f))
            StepUnit("秒", seconds, 0..59, onChange = { onValueChange(composeMs(minutes, it, millis)) }, modifier = Modifier.weight(1f))
            StepUnit("毫秒", millis, 0..999, onChange = { onValueChange(composeMs(minutes, seconds, it)) }, modifier = Modifier.weight(1.4f))
        }
    }
}

private fun composeMs(minutes: Int, seconds: Int, millis: Int): Long =
    minutes * 60_000L + seconds * 1000L + millis

@Composable
private fun StepUnit(
    unit: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        StepUnitButton("+", Modifier.weight(1f)) { onChange(min(value + 1, range.last)) }
        Text(
            text = String.format(Locale.getDefault(), "%02d", value),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StepUnitButton("-", Modifier.weight(1f)) { onChange(max(value - 1, range.first)) }
    }
}

@Composable
private fun StepUnitButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    SecondaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp),
        contentPadding = PaddingValues(0.dp),
        textStyle = MaterialTheme.typography.titleMedium,
    )
}
