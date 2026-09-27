package cn.qishui.tool.feature.edit.lyrics

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
import androidx.compose.ui.text.SpanStyle
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
                currentWordIndex = if (index == cursor.lineIndex) cursor.wordIndex else -1,
            )
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, isCurrent: Boolean, currentWordIndex: Int) {
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
        style = if (isCurrent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
