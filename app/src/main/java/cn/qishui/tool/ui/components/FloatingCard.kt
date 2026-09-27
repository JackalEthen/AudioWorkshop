package cn.qishui.tool.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.qishui.tool.ui.theme.LocalCardStyle

val QishuiCardShape = RoundedCornerShape(20.dp)
val QishuiFieldShape = RoundedCornerShape(16.dp)

@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.background,
    alpha: Float = LocalCardStyle.current.alpha,
    blurDp: Int = LocalCardStyle.current.blurDp,
    content: @Composable () -> Unit,
) {
    FrostedBox(
        modifier = modifier,
        shape = QishuiCardShape,
        tint = containerColor.copy(alpha = alpha.coerceIn(0.08f, 1f)),
        blurDp = blurDp,
        content = content,
    )
}

/** 二级页面专用：不透明卡片，不做壁纸模糊。 */
@Composable
fun PlainCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    FrostedBox(
        modifier = modifier,
        shape = QishuiCardShape,
        tint = containerColor,
        blurDp = 0,
        content = content,
    )
}

@Composable
internal fun FrostedBox(
    modifier: Modifier,
    shape: Shape,
    tint: Color,
    blurDp: Int,
    borderWidth: Dp = 1.dp,
    content: @Composable () -> Unit,
) {
    // ponytail: 不用 Modifier.shadow —— 它在 clip 之前绘制，会在卡片内部留下一块方形亮斑
    val border = if (borderWidth > 0.dp) {
        Modifier.border(
            borderWidth,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            shape,
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(tint)
            .then(border),
    ) {
        content()
    }
}
