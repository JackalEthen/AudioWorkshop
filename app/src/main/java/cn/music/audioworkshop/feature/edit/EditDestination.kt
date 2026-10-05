package cn.music.audioworkshop.feature.edit

import android.net.Uri
import cn.music.audioworkshop.domain.model.EditOperation

object EditDestination {
    const val HomeRoute = "edit"
    const val RecordsRoute = "edit-records"
    const val OperationArg = "operation"
    const val TrackIdArg = "trackId"
    const val JoinedIdsArg = "joinedIds"
    const val WorkspaceRoute = "editor/{$OperationArg}?$TrackIdArg={$TrackIdArg}&$JoinedIdsArg={$JoinedIdsArg}"

    fun workspace(operation: EditOperation, trackId: String, joinedIds: List<String> = emptyList()): String {
        val joined = joinedIds.joinToString(",") { Uri.encode(it) }
        return "editor/${operation.name}?$TrackIdArg=$trackId&$JoinedIdsArg=$joined"
    }

    fun joinedIdsOf(raw: String?): List<String> = raw
        ?.split(',')
        ?.mapNotNull { it.takeIf(String::isNotBlank)?.let(Uri::decode) }
        .orEmpty()
}

data class EditFeatureCard(
    val operation: EditOperation,
    val label: String,
    val recordOperations: Set<EditOperation>,
)

val EditFeatureCards: List<EditFeatureCard> = listOf(
    EditFeatureCard(
        operation = EditOperation.TRIM,
        label = "裁剪",
        recordOperations = setOf(EditOperation.TRIM),
    ),
    EditFeatureCard(
        operation = EditOperation.JOIN,
        label = "合成",
        recordOperations = setOf(EditOperation.JOIN),
    ),
    EditFeatureCard(
        operation = EditOperation.FADE_IN,
        label = "淡入淡出",
        recordOperations = setOf(EditOperation.FADE_IN, EditOperation.FADE_OUT),
    ),
    EditFeatureCard(
        operation = EditOperation.GAIN,
        label = "音量增益",
        recordOperations = setOf(EditOperation.GAIN),
    ),
    EditFeatureCard(
        operation = EditOperation.LYRIC_OFFSET,
        label = "歌词编辑",
        recordOperations = setOf(EditOperation.LYRIC_OFFSET),
    ),
)

// 历史记录里可能存着已经下线的功能类型，配不到卡片时退回第一张，
// 不能让 first{} 直接抛 —— 那会让整个编辑记录页打不开。
fun EditOperation.card(): EditFeatureCard =
    EditFeatureCards.firstOrNull { card -> card.operation == this } ?: EditFeatureCards.first()
