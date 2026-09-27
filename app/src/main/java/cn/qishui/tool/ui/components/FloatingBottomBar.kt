package cn.qishui.tool.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.qishui.tool.R
import cn.qishui.tool.ui.MainDestination
import cn.qishui.tool.ui.NavGlyph
import cn.qishui.tool.ui.theme.LocalCardStyle

@Composable
fun FloatingBottomBar(
    destinations: List<MainDestination>,
    currentRoute: String?,
    onSelect: (MainDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    FrostedBox(
        modifier = modifier
            .widthIn(max = 300.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 26.dp)
            .drawBehind {
                // ponytail: 1dp 步进的二次衰减多圈叠加 = 柔和过渡；Modifier.shadow 会留方形亮斑
                val spread = 8.dp.toPx()
                val steps = 8
                for (i in steps downTo 1) {
                    val grow = spread * i / steps
                    val falloff = 1f - i / steps.toFloat()
                    drawRoundRect(
                        color = Color.Black.copy(alpha = 0.055f * falloff * falloff),
                        topLeft = Offset(-grow, -grow),
                        size = Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = CornerRadius(size.height / 2f + grow),
                    )
                }
            },
        shape = BottomBarShape,
        tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        blurDp = LocalCardStyle.current.blurDp,
        borderWidth = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            destinations.forEach { destination ->
                BottomBarItem(
                    label = destination.label,
                    glyph = destination.glyph,
                    selected = currentRoute == destination.route,
                    onClick = { onSelect(destination) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.BottomBarItem(
    label: String,
    glyph: NavGlyph,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            // ponytail: indication = null —— 默认点击高亮是个矩形色块，和胶囊风格冲突
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            NavGlyphIcon(glyph = glyph, tint = contentColor)
        }
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NavGlyphIcon(glyph: NavGlyph, tint: Color) = LucideIcon(
    icon = when (glyph) {
        NavGlyph.Resolve -> R.drawable.ic_arrow_down_to_line
        NavGlyph.Edit -> R.drawable.ic_scissors
        NavGlyph.Settings -> R.drawable.ic_sliders_horizontal
    },
    tint = tint,
    size = 20.dp,
)

private val BottomBarShape = RoundedCornerShape(percent = 50)
private val BarHeight = 72.dp
