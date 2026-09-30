package cn.qishui.tool.data.search

import cn.qishui.tool.domain.search.MusicSearchClient
import cn.qishui.tool.domain.search.MusicSearchException
import cn.qishui.tool.domain.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient

/**
 * 搜索仓库：按平台分发，并支持「全部平台」并发聚合。
 *
 * 聚合对应 lx-music-mobile 的 `src/core/search/music.ts` —— `sourceId == 'all'`
 * 时把所有平台并发打一遍，任一失败不影响其它（失败的那个只贡献 0 条）。
 */
class MusicSearchRepository(client: OkHttpClient) {

    /**
     * 五个平台。
     *
     * **汽水（qsvip）没做**：搜索接口是通的（`api.qishui.com/luna/search/track`），
     * 但唯一声明支持它的音源（qdy）求直链走后端 `api.vsaa.cn`，那个已经 404 ——
     * 搜得到播不了，给用户看一堆点不动的结果没有意义。
     *
     * 平台列表见 `SEARCHABLE_PLATFORMS`（feature/player），那边还要
     * 和音源声明的平台取交集。
     */
    private val clients: List<MusicSearchClient> = listOf(
        KwSearchClient(client),
        KgSearchClient(client),
        TxSearchClient(client),
        WySearchClient(client),
        MgSearchClient(client),
    )

    /** 可选平台，按注册顺序。 */
    val platforms: List<String> = clients.map { it.platform }

    /** `ALL` 会并发搜所有平台。 */
    fun clientFor(platform: String): MusicSearchClient? =
        clients.firstOrNull { it.platform == platform }

    suspend fun search(platform: String, keyword: String, page: Int, limit: Int): SearchResult {
        if (platform != ALL) {
            val target = clientFor(platform)
                ?: throw MusicSearchException("不支持的平台：$platform")
            return target.search(keyword, page, limit)
        }
        return searchAll(keyword, page, limit)
    }

    private suspend fun searchAll(keyword: String, page: Int, limit: Int): SearchResult =
        coroutineScope {
            val deferred = clients.map { client ->
                async(Dispatchers.IO) {
                    // 一个平台挂了不能拖垮整页。
                    runCatching { client.search(keyword, page, limit) }
                        .onFailure { error ->
                            android.util.Log.w(TAG, "${client.platform} 搜索失败", error)
                        }
                        .getOrNull()
                }
            }
            val results = deferred.map { it.await() }.filterNotNull()

            if (results.isEmpty()) {
                throw MusicSearchException("所有平台都没搜到结果，检查一下网络")
            }
            SearchResult(
                platform = ALL,
                hits = results.flatMap { it.hits },
                total = results.sumOf { it.total },
                allPage = results.maxOf { it.allPage },
            )
        }

    companion object {
        const val ALL = "all"
        private const val TAG = "MusicSearchRepo"
    }
}
