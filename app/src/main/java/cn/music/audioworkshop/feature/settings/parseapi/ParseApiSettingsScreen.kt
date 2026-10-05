package cn.music.audioworkshop.feature.settings.parseapi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.music.audioworkshop.domain.model.FieldSlot
import cn.music.audioworkshop.domain.model.ParseApiSource
import cn.music.audioworkshop.domain.model.slotValue
import cn.music.audioworkshop.domain.model.withSlot
import cn.music.audioworkshop.feature.settings.SettingsSection
import cn.music.audioworkshop.ui.components.ConfirmSheet
import cn.music.audioworkshop.ui.components.FloatingCard
import cn.music.audioworkshop.ui.components.PrimaryButton
import androidx.compose.material3.OutlinedTextField
import cn.music.audioworkshop.ui.components.QishuiFieldShape
import cn.music.audioworkshop.ui.components.QishuiTopBar
import cn.music.audioworkshop.ui.components.SecondaryButton
import cn.music.audioworkshop.ui.components.TopSnackbarHost

private const val HINT = "解析源为外部接口，由使用者自行添加。" +
    "字段名留空时按内置候选顺序自动匹配；仅当返回结构特殊时才需手动指定，逗号分隔可填多个候选。" +
    "未配置任何可用源时，解析页无法执行解析。"

private const val EDITOR_HINT = "名称与地址为必填项。类型参数用于要求 ?type=json 的接口；" +
    "鉴权密钥会同时以 key 与 apikey 两个参数名附加。" +
    "字段名留空表示使用内置候选，例如播放地址会依次尝试 url、play_url、playUrl、music_url 等。"

/** 解析源列表 + 编辑。 */
@Composable
fun ParseApiSettingsScreen(
    viewModel: ParseApiViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleting by remember { mutableStateOf<ParseApiSource?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val editor = state.editor
    if (editor != null) {
        ParseApiEditor(
            initial = editor,
            onChange = viewModel::updateEditor,
            onSave = viewModel::save,
            onCancel = viewModel::cancelEdit,
            onBack = viewModel::cancelEdit,
        )
        return
    }

    SettingsSection(
        modifier = modifier,
        title = "解析源",
        onBack = onBack,
    ) {
        Text(
            text = HINT,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PrimaryButton(
            text = "添加解析源",
            onClick = { viewModel.startCreate() },
            modifier = Modifier.fillMaxWidth(),
        )

        if (sources.isEmpty()) {
            Text(
                text = "尚未添加解析源",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        sources.forEach { source ->
            FloatingCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .clickable { viewModel.startEdit(source) }
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(source.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = source.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (source.note.isNotBlank()) {
                                Text(
                                    text = source.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = source.enabled,
                            onCheckedChange = { viewModel.setEnabled(source, it) },
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SecondaryButton(
                            text = "编辑",
                            onClick = { viewModel.startEdit(source) },
                        )
                        SecondaryButton(
                            text = "删除",
                            onClick = { deleting = source },
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    deleting?.let { target ->
        ConfirmSheet(
            title = "删除「${target.name}」？",
            confirmLabel = "删除",
            onConfirm = {
                viewModel.delete(target)
                deleting = null
            },
            onDismiss = { deleting = null },
        ) {
            Text(
                text = "删除后该源不再出现在解析页的下拉列表中。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 源编辑表单。字段名映射默认折叠，避免一屏塞十几个输入框。 */
@Composable
private fun ParseApiEditor(
    initial: ParseApiEditorState,
    onChange: ((ParseApiEditorState) -> ParseApiEditorState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
) {
    var showFields by remember { mutableStateOf(false) }

    // 编辑表单比设置页其他二级页内容多，没有 Scaffold + 顶栏的话会被
    // 通知栏压住顶部、被导航栏压住底部按钮。
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            QishuiTopBar(title = if (initial.name.isBlank()) "添加解析源" else "编辑解析源", onBack = onBack)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            Text(
                text = EDITOR_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledField("名称", initial.name) { value ->
                onChange { it.copy(name = value) }
            }
            LabeledField("接口地址", initial.url) { value ->
                onChange { it.copy(url = value) }
            }
            LabeledField("类型参数", initial.typeParam, hint = "json") { value ->
                onChange { it.copy(typeParam = value) }
            }
            LabeledField("API 密钥", initial.apiKey, hint = "无需鉴权则留空") { value ->
                onChange { it.copy(apiKey = value) }
            }
            LabeledField("备注", initial.note, hint = "选填") { value ->
                onChange { it.copy(note = value) }
            }

            SecondaryButton(
                text = if (showFields) "收起字段名映射" else "展开字段名映射（高级）",
                onClick = { showFields = !showFields },
                modifier = Modifier.fillMaxWidth(),
            )

            if (showFields) {
                Text(
                    text = "留空即使用内置候选。多个候选用英文逗号分隔，按顺序尝试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FieldSlot.entries.forEach { slot ->
                    val current = initial.fields.slotValue(slot)
                    LabeledField(
                        label = slot.mappingKey,
                        value = current,
                        hint = slot.candidates.take(4).joinToString(", "),
                    ) { value ->
                        onChange { it.copy(fields = it.fields.withSlot(slot, value)) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            }
            // 按钮固定在底部，不跟着内容滚走 —— 表单有十几个输入框，
            // 滚到底才能按保存的话很容易漏掉。
            // navigationBarsPadding 防止被系统导航栏盖住。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryButton(
                    text = "保存",
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                )
                SecondaryButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    hint: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        // 有 hint 时作为占位，不与 label 重叠
        placeholder = hint?.let { { Text(it) } },
        singleLine = true,
        shape = QishuiFieldShape,
    )
}



