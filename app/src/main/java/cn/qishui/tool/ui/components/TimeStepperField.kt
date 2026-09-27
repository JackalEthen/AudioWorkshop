package cn.qishui.tool.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 「开始时间 / 结束时间」那种分秒毫秒三段式步进器：
 * 每段上面一排 +、下面一排 -，中间显示数值。
 */
@Composable
fun TimeStepperField(
    label: String,
    minutes: Int,
    seconds: Int,
    millis: Int,
    onMinutes: (Int) -> Unit,
    onSeconds: (Int) -> Unit,
    onMillis: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            StepUnit("分", minutes, 0..999, onMinutes, Modifier.weight(1f))
            StepUnit("秒", seconds, 0..59, onSeconds, Modifier.weight(1f))
            StepUnit("毫秒", millis, 0..999, onMillis, Modifier.weight(1.4f))
        }
    }
}

@Composable
private fun StepUnit(
    unit: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        StepUnitButton("+", Modifier) { onChange(min(value + 1, range.last)) }
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
        StepUnitButton("-", Modifier) { onChange(max(value - 1, range.first)) }
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
