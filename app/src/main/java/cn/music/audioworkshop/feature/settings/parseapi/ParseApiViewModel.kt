package cn.music.audioworkshop.feature.settings.parseapi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.music.audioworkshop.domain.model.FieldMapping
import cn.music.audioworkshop.domain.model.ParseApiSource
import cn.music.audioworkshop.domain.parse.ParseApiSourceRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 编辑中的源。null 表示列表页，null 字段由 UI 传空串覆盖。 */
data class ParseApiEditorState(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val url: String = "",
    val typeParam: String = "json",
    val apiKey: String = "",
    val note: String = "",
    val fields: FieldMapping = FieldMapping(),
)

data class ParseApiUiState(
    val editor: ParseApiEditorState? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
)

/**
 * 解析源管理。
 *
 * 不预置任何源。用户添加的源保存在 [ParseApiSourceRepository]，
 * 解析页下拉读取的是启用中的那些。
 */
class ParseApiViewModel(
    private val repository: ParseApiSourceRepository,
) : ViewModel() {

    val sources: StateFlow<List<ParseApiSource>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val mutableState = MutableStateFlow(ParseApiUiState())
    val uiState: StateFlow<ParseApiUiState> = mutableState.asStateFlow()

    fun startCreate() {
        mutableState.update { it.copy(editor = ParseApiEditorState(), message = null) }
    }

    fun startEdit(source: ParseApiSource) {
        mutableState.update {
            it.copy(
                editor = ParseApiEditorState(
                    id = source.id,
                    name = source.name,
                    url = source.url,
                    typeParam = source.typeParam,
                    apiKey = source.apiKey,
                    note = source.note,
                    fields = source.fields,
                ),
                message = null,
            )
        }
    }

    fun cancelEdit() = mutableState.update { it.copy(editor = null) }

    fun updateEditor(transform: (ParseApiEditorState) -> ParseApiEditorState) {
        mutableState.update { state ->
            val editor = state.editor ?: return@update state
            state.copy(editor = transform(editor))
        }
    }

    fun save() {
        val editor = mutableState.value.editor ?: return
        val name = editor.name.trim()
        val url = editor.url.trim()
        when {
            name.isBlank() -> {
                notify("请填写名称")
                return
            }
            !url.startsWith("http://") && !url.startsWith("https://") -> {
                notify("地址必须以 http:// 或 https:// 开头")
                return
            }
        }
        viewModelScope.launch {
            repository.upsert(
                ParseApiSource(
                    id = editor.id,
                    name = name,
                    url = url,
                    typeParam = editor.typeParam.trim(),
                    apiKey = editor.apiKey.trim(),
                    enabled = true,
                    note = editor.note.trim(),
                    fields = editor.fields,
                ),
            )
            mutableState.update { it.copy(editor = null, message = "已保存「$name」") }
        }
    }

    fun setEnabled(source: ParseApiSource, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(source.id, enabled) }
    }

    fun delete(source: ParseApiSource) {
        viewModelScope.launch {
            repository.delete(source.id)
            mutableState.update { it.copy(message = "已删除「${source.name}」") }
        }
    }

    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    private fun notify(message: String) = mutableState.update { it.copy(message = message) }

    companion object {
        fun factory(repository: ParseApiSourceRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ParseApiViewModel::class.java)) {
                    "Unsupported ViewModel class: ${modelClass.name}"
                }
                return ParseApiViewModel(repository) as T
            }
        }
    }
}
