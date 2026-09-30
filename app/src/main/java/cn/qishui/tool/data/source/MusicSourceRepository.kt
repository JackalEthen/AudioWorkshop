package cn.qishui.tool.data.source

import cn.qishui.tool.data.local.MusicSourceDao
import cn.qishui.tool.data.local.MusicSourceEntity
import cn.qishui.tool.domain.source.LxSourceState
import cn.qishui.tool.domain.source.MusicSource
import cn.qishui.tool.domain.source.MusicSourceMeta
import cn.qishui.tool.media.source.LxSourceMeta
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 音源的存储 + 生命周期。
 *
 * 「同一时刻只有一个生效」是硬约束：勾选走 [MusicSourceDao.selectOnly]，
 * 它在事务里先取消其余再选中，所以 UI 只管传 id，不用自己先取消。
 *
 * 选中态变化时重建引擎；引擎起不来就把错误写回 [MusicSource.lastError]，
 * 列表上直接能看到，不用去翻日志。
 */
class MusicSourceRepository(
    private val dao: MusicSourceDao,
    private val runtime: LxSourceRuntime,
    private val client: OkHttpClient,
) {
    val sources: Flow<List<MusicSource>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * 导入一份脚本。
     *
     * 同一个脚本重复导入会覆盖原来的（主键是脚本内容的稳定哈希），
     * 但**不会自动选中** —— 选谁是用户的决定。
     */
    suspend fun importScript(script: String, origin: String, fallbackName: String): Result<MusicSource> =
        runCatching {
            android.util.Log.i(TAG, "importScript: 长度=${script.length} origin=$origin")
            require(script.isNotBlank()) { "脚本内容为空" }
            val meta = MusicSourceMeta.parse(script, fallbackName)
            android.util.Log.i(TAG, "importScript: 解析 name=${meta.name} v=${meta.version}")
            val id = MusicSourceMeta.stableId(meta, script)
            val entity = MusicSourceEntity(
                id = id,
                name = meta.name,
                description = meta.description,
                author = meta.author,
                version = meta.version,
                homepage = meta.homepage,
                script = script,
                origin = origin,
                selected = false,
                created_at = System.currentTimeMillis(),
                last_error = null,
            )
            dao.upsert(entity)
            android.util.Log.i(TAG, "importScript: 已入库 id=$id")
            entity.toDomain()
        }.onFailure {
            android.util.Log.e(TAG, "importScript 失败: ${it.javaClass.name}: ${it.message}", it)
        }

    /** 从链接导入。 */
    suspend fun importFromUrl(url: String): Result<MusicSource> = runCatching {
        android.util.Log.i(TAG, "importFromUrl: 开始 url=$url")
        val request = Request.Builder().url(url.trim()).build()
        client.newCall(request).execute().use { response ->
            android.util.Log.i(TAG, "importFromUrl: HTTP ${response.code} type=${response.header("Content-Type")}")
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            android.util.Log.i(TAG, "importFromUrl: 正文 ${body.length} 字节")
            importScript(
                script = body,
                origin = MusicSource.ORIGIN_URL,
                fallbackName = url.trim().substringAfterLast('/').ifBlank { "未命名音源" },
            ).getOrThrow()
        }
    }.onFailure {
        android.util.Log.e(TAG, "importFromUrl 失败: ${it.javaClass.name}: ${it.message}", it)
    }

    /** 从文件导入。 */
    suspend fun importFromFile(content: String, fileName: String): Result<MusicSource> =
        importScript(content, MusicSource.ORIGIN_FILE, fileName.substringBeforeLast('.').ifBlank { "未命名音源" })

    /**
     * 勾选/取消勾选。
     *
     * 勾上会真的起引擎；起不来就把错误记到这一行上，但**仍然保持勾选**，
     * 因为那是用户的意图，引擎错误不该偷偷改回去。
     */
    suspend fun setSelected(source: MusicSource, selected: Boolean) {
        if (selected) dao.selectOnly(source.id) else dao.deselectAll()
        if (selected) activate(source) else runtime.activate(null)
    }

    /** 用当前选中的音源重建引擎。 */
    suspend fun activateCurrent() {
        val selected = dao.getSelected()
        runtime.activate(
            script = selected?.script,
            id = selected?.id.orEmpty(),
            name = selected?.name.orEmpty(),
            description = selected?.description.orEmpty(),
            version = selected?.version.orEmpty(),
            author = selected?.author.orEmpty(),
            homepage = selected?.homepage.orEmpty(),
        )
        dao.setLastError(selected?.id.orEmpty(), (runtime.state.value as? LxSourceState.Failed)?.message)
    }

    private suspend fun activate(source: MusicSource) {
        runtime.activate(
            script = source.script,
            id = source.id,
            name = source.name,
            description = source.description,
            version = source.version,
            author = source.author,
            homepage = source.homepage,
        )
        val error = (runtime.state.value as? LxSourceState.Failed)?.message
        dao.setLastError(source.id, error)
    }

    suspend fun delete(id: String) {
        val wasSelected = dao.getById(id)?.selected == true
        dao.delete(id)
        if (wasSelected) runtime.activate(null)
    }

    /** 重新跑一遍选中的音源（用户想确认是否修好了）。 */
    suspend fun reloadSelected() {
        val selected = dao.getSelected()?.toDomain() ?: return
        activate(selected)
    }

    private companion object {
        const val TAG = "MusicSourceRepo"
    }
}

internal fun MusicSourceEntity.toDomain() = MusicSource(
    id = id,
    name = name,
    description = description,
    author = author,
    version = version,
    homepage = homepage,
    script = script,
    origin = origin,
    selected = selected,
    lastError = last_error,
)
