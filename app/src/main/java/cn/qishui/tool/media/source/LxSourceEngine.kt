package cn.qishui.tool.media.source

import android.util.Base64
import cn.qishui.tool.domain.source.LxSourceInfo
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * lx 自定义源的宿主侧，跑在 QuickJS 上。
 *
 * 引擎选型是被实测逼出来的：先试 Rhino 1.8.0，探针结果
 * `class` / `async-await` / `for-of` / 展开调用 全是语法错误，
 * 而现代 lx 音源普遍在用这些，等于一个都加载不了。
 * lx-music-mobile 自己用的是 QuickJS（wang.harlon.quickjs），所以跟它一致。
 *
 * 实现 lx 的双向回调协议（参考 lx-music-mobile 的
 * `android/app/src/main/assets/script/user-api-preload.js`，Apache-2.0）：
 * - 宿主往全局注入 `__lx_native_call__` 和几个 `utils_*`（脚本 → 宿主）
 * - evaluate preload，它装上 `globalThis.lx` 和 `__lx_native__`（宿主 → 脚本）
 * - evaluate 用户脚本，脚本调 `lx.send('inited', ...)` 上报能力
 * - 之后宿主用 `__lx_native__` 反过来向脚本要 musicUrl / lyric / pic
 *
 * QuickJS 上下文是**线程绑定**的，所以整个引擎由 [scriptThread] 这一个线程独占，
 * 所有 JS 求值和回调都投递过去；只有 HTTP 在别的线程。
 */

/**
 * 音源明确回了失败。消息是音源脚本给的原文，不要改写 ——
 * 里面通常就是真正的原因（网络不通、接口 500、歌 id 不支持…）。
 */
class LxSourceRequestException(message: String) : Exception(message)

class LxSourceEngine(private val client: OkHttpClient) {
    private val scriptThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lx-source-script").apply { isDaemon = true }
    }
    private val timers: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "lx-source-timer").apply { isDaemon = true }
    }

    private val requestSeq = AtomicInteger()

    /** requestKey -> 宿主等脚本回包的门闩。 */
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val timeouts = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val timersById = ConcurrentHashMap<Int, ScheduledFuture<*>>()

    /** 本实例令牌，脚本只能用自己的 key 走这条通道。 */
    private val key: String = UUID.randomUUID().toString().replace("-", "")

    @Volatile
    private var context: QuickJSContext? = null

    @Volatile
    private var nativeBridge: JSFunction? = null

    @Volatile
    private var initInfo: LxSourceInfo? = null

    @Volatile
    private var scriptMeta: LxSourceMeta? = null

    /**
     * 等待脚本声明能力的门闩。
     *
     * 真实音源会在声明前先发网络请求，所以握手是**异步**的：
     * 脚本 evaluate 完就返回了，这里一直挂着，直到 [handleInit] 到达、
     * 出错、或超时。
     */
    @Volatile
    private var initFuture: CompletableFuture<LxSourceInfo>? = null

    /**
     * 起一个引擎，等脚本声明能力后返回。
     *
     * **必须异步等，不能 evaluate 完就检查。**
     *
     * 真实音源（尤其是混淆过的）在声明能力前会先发网络请求：
     * `Promise.all([...]).then(() => lx.send('inited', ...))`。
     * `evaluate` 在第一个 await 处就返回了，此时 `initInfo` 还是 null。
     *
     * 微任务不是靠显式排空 API 推进的 —— 而是在**下一次进入 JS** 时顺带跑完。
     * 脚本发请求 → 宿主做完 HTTP → 调 `pushToScript("response", ...)` 重新进 JS
     * → 微任务链推进 → 脚本调 `send('inited')`。
     * 所以这里挂一个 future，等 [handleInit] 来完成它。
     * 这也是 lx-music-mobile 的做法（`QuickJS.loadScript` evaluate 完直接返回成功，
     * 初始化结果走异步回调）。
     */
    fun start(preload: String, script: String, meta: LxSourceMeta): LxSourceInfo {
        val future = CompletableFuture<LxSourceInfo>()
        initFuture = future
        // 元信息必须先就位：脚本声明能力时 handleInit 要读它填名字字段
        scriptMeta = meta
        initInfo = null
        scriptThread.execute {
            try {
                destroyContextOnScriptThread()
                ensureNativeLoaded()
                val cx = QuickJSContext.create()
                val global = cx.getGlobalObject()
                initConsoleLog(cx)
                installNatives(cx, global)
                context = cx

                cx.evaluate(preload, "user-api-preload.js")
                val setup = global.getJSFunction("lx_setup")
                    ?: throw LxSourceInitException("脚本环境异常：缺少 lx_setup")
                setup.call(
                    key, meta.id, meta.name, meta.description,
                    meta.version, meta.author, meta.homepage, script,
                )
                nativeBridge = global.getJSFunction("__lx_native__")
                    ?: throw LxSourceInitException("脚本环境异常：缺少 __lx_native__")
                // 脚本跑完就返回。声明能力可能还在飞行中（等网络），
                // 由 handleInit 异步完成 future，这里不检查。
                android.util.Log.i(TAG, "用户脚本 evaluate 返回")
                cx.evaluate(script, "user-source.js")
                android.util.Log.i(TAG, "用户脚本 evaluate 完毕")

                // 兜底：脚本卡住不发 init 时别无限等
                timers.schedule({
                    initFuture?.takeIf { !it.isDone }?.let {
                        initFuture = null
                        it.completeExceptionally(
                            LxSourceInitException(
                                "音源初始化超时（${INIT_TIMEOUT_SECONDS} 秒内没有声明能力）。" +
                                    "该音源可能需要联网才能初始化，或者接口已失效。",
                            ),
                        )
                    }
                }, INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (error: Throwable) {
                android.util.Log.e(TAG, "start 失败", error)
                initFuture = null
                future.completeExceptionally(
                    LxSourceInitException(error.message ?: error.javaClass.simpleName),
                )
            }
        }
        // 等脚本异步声明能力，不是等 evaluate 返回
        return future.get(INIT_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS)
    }

    /**
     * 向脚本要一个 action 的结果。
     *
     * 返回 null = 引擎没就绪或脚本没回包（超时）。这种情况没有更多信息可给。
     *
     * 脚本**明确回了失败**时抛 [LxSourceRequestException]，消息里带它给的原因。
     * 这两种必须分开：之前一律吞成 null，结果网络不通、音源 500、
     * 歌 id 不对全都变成同一句「音源没能解析出播放地址」，没法排查。
     */
    fun requestAction(
        platform: String,
        action: String,
        musicInfo: Map<String, Any?>,
        quality: String?,
    ): JSONObject? {
        val requestKey = "host__${requestSeq.incrementAndGet()}"
        val result = CompletableFuture<JSONObject>()
        pending.put(requestKey, result)
        timeouts[requestKey] = timers.schedule({ pending.remove(requestKey) }, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val payload = JSONObject().apply {
            put("requestKey", requestKey)
            put("data", JSONObject().apply {
                put("source", platform)
                put("action", action)
                put("info", JSONObject().apply {
                    put("type", quality ?: "")
                    put("musicInfo", JSONObject(musicInfo))
                })
            })
        }
        return try {
            pushToScript("request", payload.toString())
            result.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            // CompletableFuture.get() 会把 completeExceptionally 里的异常
            // 包一层 ExecutionException，所以得顺着 cause 链找到底。
            val cause = generateSequence(error as Throwable?) { it.cause }.last()
            if (cause is LxSourceRequestException) {
                // 音源自己说了原因，往上带。
                throw cause
            }
            android.util.Log.w(TAG, "requestAction($action) 无回包：${error.message}")
            null
        } finally {
            timeouts.remove(requestKey)?.cancel(false)
            pending.remove(requestKey)
        }
    }

    /**
     * 销毁当前 QuickJS 上下文，**保留线程池**，引擎还能再次 [start]。
     *
     * 换音源走这里。绝对不能关 [scriptThread] ——
     * 关了之后任何后续请求都会被
     * `RejectedExecutionException: ThreadPoolExecutor[Terminated]` 拒掉，
     * 而线程池是构造时一次性创建的，关了就再也起不来。
     */
    fun reset() {
        initFuture?.takeIf { !it.isDone }?.let {
            it.completeExceptionally(IllegalStateException("引擎已重置"))
        }
        initFuture = null
        runCatching { scriptThread.execute { destroyContextOnScriptThread() } }
            .onFailure { android.util.Log.w(TAG, "reset 被拒，引擎可能已彻底释放") }
        pending.values.forEach { it.completeExceptionally(IllegalStateException("引擎已重置")) }
        pending.clear()
        timeouts.values.forEach { it.cancel(false) }
        timeouts.clear()
        timersById.values.forEach { it.cancel(false) }
        timersById.clear()
    }

    /**
     * 彻底销毁引擎，之后不能再用。只在 [LxSourceRuntime] 自身被弃用时调。
     */
    fun release() {
        reset()
        scriptThread.shutdownNow()
        timers.shutdownNow()
    }

    /** 销毁 QuickJS 上下文。必须在脚本线程上调用。 */
    private fun destroyContextOnScriptThread() {
        runCatching { context?.destroy() }
            .onFailure { android.util.Log.w(TAG, "销毁上下文出错", it) }
        context = null
        nativeBridge = null
        initInfo = null
    }

    // ---- 宿主注入的原生函数 ----

    /** QuickJS 的 so 必须先加载一次，JVM 上没有这一步所以仪器测试也过不了这关。 */
    private fun ensureNativeLoaded() {
        if (nativeLoaded) return
        synchronized(this) {
            if (nativeLoaded) return
            com.whl.quickjs.android.QuickJSLoader.init()
            nativeLoaded = true
        }
    }

    /** 把脚本的 console.log 转到 logcat，否则调试音源什么都看不到。 */
    private fun initConsoleLog(cx: QuickJSContext) {
        runCatching { com.whl.quickjs.android.QuickJSLoader.initConsoleLog(cx) }
    }

    private fun installNatives(cx: QuickJSContext, global: com.whl.quickjs.wrapper.JSObject) {
        // 脚本 → 宿主的主通道
        global.setProperty("__lx_native_call__", JSCallFunction { args ->
            if (args.isEmpty() || args[0] != key) return@JSCallFunction null
            handleScriptAction(args.getOrNull(1) as? String ?: "", args.getOrNull(2) as? String)
            null
        })
        global.setProperty("__lx_native_call__set_timeout", JSCallFunction { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@JSCallFunction null
            val ms = ((args.getOrNull(1) as? Number)?.toLong() ?: 0L).coerceIn(0L, 60_000L)
            timersById[id] = timers.schedule({ fireTimeout(id) }, ms, TimeUnit.MILLISECONDS)
            null
        })
        global.setProperty("__lx_native_call__utils_str2b64", JSCallFunction { args ->
            encodeBase64((args.getOrNull(0) as? String ?: "").toByteArray(Charsets.UTF_8))
        })
        global.setProperty("__lx_native_call__utils_b642buf", JSCallFunction { args ->
            JSONArray(decodeBase64(args.getOrNull(0) as? String ?: "")).toString()
        })
        global.setProperty("__lx_native_call__utils_str2md5", JSCallFunction { args ->
            md5Hex((args.getOrNull(0) as? String ?: "").toByteArray(Charsets.UTF_8))
        })
        global.setProperty("__lx_native_call__utils_aes_encrypt", JSCallFunction { args ->
            runCatching {
                encodeBase64(
                    aesEncrypt(
                        data = decodeBase64(args.getOrNull(0) as? String ?: ""),
                        secret = decodeBase64(args.getOrNull(1) as? String ?: ""),
                        iv = decodeBase64(args.getOrNull(2) as? String ?: ""),
                        padding = args.getOrNull(3) as? String ?: "",
                    ),
                )
            }.getOrDefault("")
        })
        global.setProperty("__lx_native_call__utils_rsa_encrypt", JSCallFunction { args ->
            runCatching {
                encodeBase64(rsaEncrypt(decodeBase64(args.getOrNull(0) as? String ?: ""), args.getOrNull(1) as? String ?: ""))
            }.getOrDefault("")
        })
    }

    private fun fireTimeout(id: Int) {
        timersById.remove(id)
        pushToScript("__set_timeout__", id.toString())
    }

    // ---- 脚本 → 宿主 ----

    private fun handleScriptAction(action: String, dataJson: String?) {
        android.util.Log.i(TAG, "收到 action=$action thread=${Thread.currentThread().name} data=${dataJson?.take(120)}")
        when (action) {
            "init" -> handleInit(dataJson)
            "response" -> handleResponse(dataJson)
            "request" -> handleScriptHttp(dataJson)
            "cancelRequest", "log", "showUpdateAlert" -> Unit
        }
    }

    private fun handleInit(dataJson: String?) {
        val future = initFuture
        runCatching {
            val data = JSONObject(dataJson ?: "{}")
            if (!data.optBoolean("status")) {
                throw LxSourceInitException(data.optString("errorMessage", "音源声明失败"))
            }
            val sources = data.optJSONObject("info")?.optJSONObject("sources")
                ?: throw LxSourceInitException("音源没有返回 sources")
            val qualitys = mutableMapOf<String, List<String>>()
            val actions = mutableMapOf<String, List<String>>()
            sources.keys().forEach { platform ->
                val node = sources.getJSONObject(platform)
                qualitys[platform] = node.optJSONArray("qualitys").toStringList()
                actions[platform] = node.optJSONArray("actions").toStringList()
            }
            LxSourceInfo(
                name = scriptMeta?.name.orEmpty(),
                description = scriptMeta?.description.orEmpty(),
                author = scriptMeta?.author.orEmpty(),
                version = scriptMeta?.version.orEmpty(),
                homepage = scriptMeta?.homepage.orEmpty(),
                qualitys = qualitys,
                actions = actions,
            )
        }.fold(
            onSuccess = { info ->
                initInfo = info
                android.util.Log.i(TAG, "握手完成，可用平台 ${info.platforms}")
                initFuture = null
                future?.complete(info)
            },
            onFailure = { error ->
                android.util.Log.e(TAG, "握手失败", error)
                initFuture = null
                future?.completeExceptionally(
                    LxSourceInitException(error.message ?: "音源声明失败"),
                )
            },
        )
    }

    private fun handleResponse(dataJson: String?) {
        runCatching {
            val data = JSONObject(dataJson ?: "{}")
            val requestKey = data.optString("requestKey")
            if (requestKey.isEmpty()) return
            if (data.optBoolean("status")) {
                pending.remove(requestKey)?.complete(data.optJSONObject("result") ?: JSONObject())
            } else {
                pending.remove(requestKey)?.completeExceptionally(
                    LxSourceRequestException(
                        "音源返回失败：${data.optString("errorMessage").ifBlank { "没给原因" }}"
                    )
                )
            }
        }
    }

    private fun handleScriptHttp(dataJson: String?) {
        val data = runCatching { JSONObject(dataJson ?: "{}") }.getOrNull() ?: return
        val requestKey = data.optString("requestKey")
        val url = data.optString("url")
        if (requestKey.isEmpty() || url.isEmpty()) return
        val options = data.optJSONObject("options") ?: JSONObject()
        val httpMethod = options.optString("method", "get").uppercase()

        Thread({
            val payload = JSONObject().apply { put("requestKey", requestKey) }
            runCatching { performHttp(url, options) }
                .onSuccess {
                    payload.put("error", JSONObject.NULL)
                    payload.put("response", it)
                    android.util.Log.i(TAG, "HTTP $httpMethod $url -> ${it.optInt("statusCode")}")
                }
                .onFailure {
                    payload.put("error", it.message ?: "request failed")
                    payload.put("response", JSONObject.NULL)
                    // 失败必须打出来。脚本拿到 error 后只会原样抛回来，
                    // 外面看到的是「音源解析失败」，不看日志会以为是协议问题。
                    android.util.Log.e(TAG, "HTTP $httpMethod $url 失败：${it.message}")
                }
            pushToScript("response", payload.toString())
        }, "lx-source-http").apply { isDaemon = true }.start()
    }

    /**
     * 代脚本发一次 HTTP。
     *
     * 逐项对齐 lx-music-mobile 的 `src/core/init/userApi/request.js` ——
     * 脚本是按那份契约写的，字段少一个或类型不对，它就取不到值。
     *
     * 回包给脚本的形状（`fetchData` 的返回值）：
     * ```
     * { headers, body, statusCode, statusMessage, url, ok }
     * ```
     * 其中 **`body` 是 JSON.parse 之后的对象**，解析失败才退回字符串 ——
     * 这是最容易漏的一条，脚本普遍直接写 `r.body.code`，给字符串就全是 undefined。
     */
    private fun performHttp(url: String, options: JSONObject): JSONObject {
        val method = options.optString("method", "get")
        val binary = options.optBoolean("binary")
        val timeoutMs = options.optLong("timeout", 13_000L).coerceIn(1_000L, 60_000L)

        // Android 的 org.json 没有 JSONObject(JSONObject) 拷贝构造，手动拷一份。
        val headers = JSONObject()
        options.optJSONObject("headers")?.let { source ->
            source.keys().forEach { name -> headers.put(name, source.opt(name)) }
        }

        // lx 的默认头：Accept + 桌面 Chrome UA。这些源接口对 UA 敏感，
        // 缺了会被判成爬虫。
        if (!hasHeaderIgnoreCase(headers, "Accept")) headers.put("Accept", "application/json")
        if (!hasHeaderIgnoreCase(headers, "User-Agent")) headers.put("User-Agent", DESKTOP_UA)

        val bodyText = when {
            options.has("body") -> options.optString("body")
            options.has("form") -> encodeForm(options.getJSONObject("form"))
            else -> null
        }

        // lx：POST 未指定 Content-Type 时按 form / json 分别处理。
        val contentType = when {
            hasHeaderIgnoreCase(headers, "Content-Type") -> headerIgnoreCase(headers, "Content-Type")
            bodyText != null && !options.has("body") -> "application/x-www-form-urlencoded"
            bodyText != null -> "application/json"
            else -> null
        }

        val builder = Request.Builder().url(url)
        headers.keys().forEach { name -> builder.header(name, headers.optString(name)) }

        when {
            method.equals("get", true) || method.equals("head", true) ->
                builder.method(method.uppercase(), null)
            bodyText != null ->
                builder.method(method.uppercase(), bodyText.toRequestBody(contentType?.toMediaTypeOrNull()))
            else -> builder.method(method.uppercase(), "".toRequestBody(null))
        }

        return client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())
            .execute()
            .use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)

                // lx 用 resp.headers.map，同名头是**数组**，脚本可能写 r.headers['x'][0]
                val headerMap = JSONObject()
                response.headers.names().forEach { name ->
                    val values = JSONArray()
                    response.headers.values(name).forEach { values.put(it) }
                    headerMap.put(name, values)
                }

                val text = String(bytes, Charsets.UTF_8)
                JSONObject().apply {
                    put("statusCode", response.code)
                    put("statusMessage", response.message)
                    put("headers", headerMap)
                    put("body", if (binary) JSONObject.NULL else HttpBodyParse.parse(text))
                    put("url", response.request.url.toString())
                    put("ok", response.isSuccessful)
                    // preload 的 utils.buf2str 要字节数组。必须「新建数组再逐个 put」，
                    // 写 JSONArray(字符串) 会被当成解析 JSON 数组而抛异常。
                    if (!binary) {
                        val byteArray = JSONArray()
                        bytes.forEach { byteArray.put(it.toInt() and 0xFF) }
                        put("bytes", byteArray)
                    }
                }
            }
    }

    /**
     * 和 lx 的 `try { JSON.parse(text) } catch {}` 行为一致：
     * 能解析成对象/数组就解析，不能就原样给字符串。
     *
     * 脚本普遍直接写 `r.body.code`，给字符串就全是 undefined ——
     * 这条漏了会让音源在初始化阶段就报错，看起来像源坏了。
     */
    private fun hasHeaderIgnoreCase(headers: JSONObject, name: String): Boolean =
        headerName(headers, name) != null

    /** 忽略大小写地读一个请求头的值。 */
    private fun headerIgnoreCase(headers: JSONObject, name: String): String? =
        headerName(headers, name)?.let { headers.optString(it) }

    private fun headerName(headers: JSONObject, name: String): String? {
        // JSONObject.keys() 是 Iterator，不是 List，不能用 firstOrNull
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.equals(name, ignoreCase = true)) return key
        }
        return null
    }

    // ---- 宿主 → 脚本 ----

    private fun pushToScript(action: String, data: String) {
        val bridge = nativeBridge
        val ctx = context
        android.util.Log.i(TAG, "pushToScript $action bridge=${bridge != null} ctx=${ctx != null}")
        if (bridge == null || ctx == null) {
            android.util.Log.w(TAG, "pushToScript $action 放弃：上下文已销毁")
            return
        }
        // 引擎已释放时池子是关的，execute 会抛 RejectedExecutionException。
        // 那属于正常收尾，不是错误。
        runCatching {
            scriptThread.execute {
                // __lx_native__ 校验 key，签名是 (key, action, data)。
                // 漏掉 key 会被 preload 直接拒掉并返回 'Invalid key'，
                // 表现就是「响应推回去了但脚本毫无反应」。
                runCatching { bridge.call(key, action, data) }
                    .onSuccess { android.util.Log.i(TAG, "pushToScript($action) 返回 $it") }
                    .onFailure { android.util.Log.e(TAG, "pushToScript($action) 抛错", it) }
            }
        }.onFailure {
            android.util.Log.w(TAG, "pushToScript($action) 被拒：引擎可能已释放")
        }
    }

    private fun encodeForm(form: JSONObject): String =
        form.keys().asSequence().joinToString("&") { key ->
            "${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(form.optString(key), "UTF-8")}"
        }

    private companion object {
        const val TAG = "LxSourceEngine"

        /**
         * 代脚本发 HTTP 时的默认 UA。值取自 lx 的
         * `src/core/init/userApi/request.js` 的 `defaultHeaders`，
         * 这些源接口对 UA 敏感，换成别的会被判成爬虫。
         */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36"

        /**
         * 握手超时。真实音源要先联网探测才声明能力，给足时间。
         * 社区音源的探测接口经常很慢，20 秒会误杀。
         */
        const val INIT_TIMEOUT_SECONDS = 60L
        const val REQUEST_TIMEOUT_SECONDS = 20L

        @Volatile
        private var nativeLoaded = false

        fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun decodeBase64(text: String): ByteArray =
            runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrDefault(ByteArray(0))

        fun md5Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        fun aesEncrypt(data: ByteArray, secret: ByteArray, iv: ByteArray, padding: String): ByteArray {
            val cbc = padding.contains("CBC")
            val cipher = Cipher.getInstance(if (cbc) "AES/CBC/PKCS5Padding" else "AES/ECB/NoPadding")
            val key = SecretKeySpec(secret, "AES")
            if (cbc) cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            else cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.doFinal(data)
        }

        fun rsaEncrypt(data: ByteArray, base64Key: String): ByteArray {
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(decodeBase64(base64Key)))
            return Cipher.getInstance("RSA/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }.doFinal(data)
        }

        fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).map { optString(it) }
        }
    }
}

/** 音源初始化失败。UI 上直接显示 message。 */
class LxSourceInitException(message: String) : Exception(message)

/** 从脚本头部注释解析出来的元信息。 */
data class LxSourceMeta(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val homepage: String,
)
