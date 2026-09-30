package cn.qishui.tool.feature.edit.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import cn.qishui.tool.domain.model.LyricLine
import cn.qishui.tool.domain.model.LyricsTrack

@Composable
fun LyricsPanel(
    lyrics: LyricsTrack,
    positionUs: Long,
    modifier: Modifier = Modifier,
    activeIndex: Int = -1,
    onLineClick: ((Int) -> Unit)? = null,
    /** 播放页整屏居中，编辑页沿用默认左对齐（要跟表单里的其它文本对齐）。 */
    textAlign: TextAlign = TextAlign.Start,
) {
    val lines = lyrics.lines
    val cursor by remember(lyrics, positionUs) {
        derivedStateOf { LyricLocator.locate(lines, positionUs) }
    }

    if (lines.isEmpty()) {
        Text(
            text = "此歌曲没有可用歌词",
            modifier = modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val selected = remember(activeIndex, lines) { activeIndex.takeIf { it in lines.indices } }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            LyricRow(
                line = line,
                isCurrent = index == cursor.lineIndex,
                isSelected = index == selected,
                currentWordIndex = if (index == cursor.lineIndex) cursor.wordIndex else -1,
                textAlign = textAlign,
                modifier = if (onLineClick != null) {
                    Modifier.clickable { onLineClick(index) }
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun LyricRow(
    line: LyricLine,
    isCurrent: Boolean,
    isSelected: Boolean,
    currentWordIndex: Int,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    val text = if (isCurrent && currentWordIndex >= 0) {
        buildAnnotatedString {
            line.words.forEachIndexed { index, word ->
                if (index == currentWordIndex) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)) {
                        append(word.text)
                    }
                } else {
                    append(word.text)
                }
            }
        }
    } else {
        buildAnnotatedString { append(line.text) }
    }
    Text(
        text = text,
        style = if (isCurrent || isSelected) {
            MaterialTheme.typography.headlineSmall
        } else {
            MaterialTheme.typography.bodyMedium
        },
        textAlign = textAlign,
        fontWeight = when {
            isSelected -> FontWeight.Bold
            isCurrent -> FontWeight.Bold
            else -> FontWeight.Normal
        },
        color = when {
            isSelected -> MaterialTheme.colorScheme.primary
            isCurrent -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
