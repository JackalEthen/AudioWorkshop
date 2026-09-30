# lx 自定义源接入记录

这份文档记录「音源怎么跑起来的」和「踩了哪些坑」，供后续接搜索/播放/歌词时对照。
重点是那些**从代码里看不出来、只有真跑才暴露**的约束。

参考实现：<https://github.com/lyswhut/lx-music-mobile>（Apache-2.0）

---

## 1. 协议全貌

### 1.1 脚本能做的事只有三件

`src/types/user_api.d.ts` 里写死了：

```ts
type UserApiSourceInfoActions = 'musicUrl' | 'lyric' | 'pic'
```

**没有 `search`。** lx-music-mobile 的搜索是 App 内置的
（`src/utils/musicSdk/*/musicSearch.js`），从不暴露给自定义源。
脚本就算声明 `search` 也会被 preload 的白名单过滤掉。

### 1.2 各平台能做的事不一样

来自 preload 的 `supportActions`：

| 平台 | 可用 action |
|---|---|
| `kw` / `kg` / `tx` / `wy` / `mg` | **只有 `musicUrl`** |
| `local` | `musicUrl` + `lyric` + `pic` |

所以「从音源拿歌词」**只对 `local` 平台生效**。远端平台的歌，
音源只给播放地址，歌词得另外找。

音质档位（`supportQualitys`）远端平台是
`['128k', '320k', 'flac', 'flac24bit']`，`local` 是 `[]`。

### 1.3 双向回调协议

**宿主 → 脚本**（函数调用）：

```js
__lx_native__(key, action, dataJson)   // key 必须在第一个参数
```

action：`request`（问脚本要东西）、`response`（HTTP 结果）、
`__set_timeout__`（定时器）、`__run_error__`

**脚本 → 宿主**（函数调用）：

```js
__lx_native_call__(key, action, dataJson)
```

action：`init`（声明能力）、`request`（要发 HTTP）、
`response`（回 HTTP 结果）、`cancelRequest`、`log`、`showUpdateAlert`

`key` 是每个引擎实例的 UUID，防止别的脚本借这条通道。

### 1.4 脚本拿到的全局对象

`lx_setup(key, id, name, description, version, author, homepage, rawScript)` 由宿主调用，
它会往全局装上 `globalThis.lx`：

```js
lx.EVENT_NAMES          // { request, inited, updateAlert }
lx.request(url, opts, cb)  // 发 HTTP，cb(err, {statusCode, headers, body})
lx.on('request', handler)   // 宿主反查 musicUrl/lyric/pic
lx.send('inited', info)     // 声明能力
lx.send('updateAlert', {...})
lx.utils.crypto           // md5 / aesEncrypt / rsaEncrypt / randomBytes
lx.utils.buffer           // from / bufToString
lx.currentScriptInfo
lx.version = '2.0.0'
lx.env = 'mobile'
```

`setTimeout` / `clearTimeout` 被替换成宿主驱动的版本
（脚本调 → `__lx_native_call__set_timeout` → 宿主计时 → 回调 `__set_timeout__`）。

---

## 2. 引擎：为什么是 QuickJS

### 2.1 先试 Rhino，被实测否掉

原计划用 Rhino 1.8.0。探针结果（`Context.VERSION_ES6`）：

| 语法 | Rhino 1.8.0 |
|---|---|
| const/let、箭头函数、模板字符串、解构、默认/剩余参数 | ✅ |
| Proxy、Symbol、Map、Set、Promise | ✅ |
| `Object.freeze`/`entries`/`getOwnPropertyDescriptors`、`Array.from`、`padStart` | ✅ |
| 可选链 `?.`、空值合并 `??` | ✅ |
| **`for...of`** | ❌ 语法错误 |
| **展开 `f(...arr)`** | ❌ 语法错误 |
| **`class`** | ❌ 语法错误 |
| **`async` / `await`** | ❌ 语法错误 |

现代 lx 音源普遍用 `class` + `async/await` + `for...of`，等于一个都加载不了。

### 2.2 lx-music-mobile 用的是什么

```gradle
// lx-music-mobile android/app/build.gradle
implementation 'wang.harlon.quickjs:wrapper-android:2.4.0'
```

**和 QuickJS 一致。** 3.2.0 也没有新增 microtask 排空 API。

它**没有**用 wrapper 库的 job 能力，而是自己封了一层
（`android/app/src/main/java/cn/toside/music/mobile/userApi/QuickJS.java`），
核心 220 行，注入的原生函数和我们做的几乎一样。

---

## 3. 真踩过的九个坑

### 坑 1：网络请求不能在主线程

```
android.os.NetworkOnMainThreadException
  at MusicSourceRepository.importFromUrl
```

`viewModelScope.launch` 默认跑在 `Dispatchers.Main.immediate`，
在里面调 `okHttpClient.newCall().execute()` 会被系统直接拦掉。

**这个异常的 `message` 是 `null`**，所以第一版显示的是「未知错误」，
完全没有诊断价值。现在有 `describe()` 按异常类型翻译成中文。

**修**：`launchBusy` 里 `withContext(Dispatchers.IO) { block() }`。
引擎相关操作（起 QuickJS 上下文）也必须切 IO。

### 坑 2：换音源不能关线程池

```
java.util.concurrent.RejectedExecutionException:
  Task ... rejected from ThreadPoolExecutor[Terminated, pool size =0]
```

`scriptThread` 是构造时一次性创建的。我最初让「换音源」也调 `release()`，
而 `release()` 里有 `shutdownNow()` —— 第一次之后池子就死了，
后续所有请求全被拒。

**修**：拆成两个语义

| 方法 | 时机 | 行为 |
|---|---|---|
| `reset()` | 换音源 | 只销毁 QuickJS 上下文，**保留线程池** |
| `release()` | 引擎对象被弃用 | 才关池子 |

### 坑 3：握手是异步的，不能同步等

这是最容易误判的一个。真实音源（尤其混淆过的）声明能力前**先发网络请求探测**：

```js
// 野花🌷 音源的实际结构（混淆后）
Promise.all([L(source, 'latest'), L(source, 'flac24bit')])
  .then(c => { j = c })
  .then(() => { lx.send(EVENT_NAMES.inited, { sources: c }) })
```

`cx.evaluate(script)` 在第一个 await 处就返回了，此时 `initInfo` 还是 `null`。

**我第一版写的是**：
```kotlin
cx.evaluate(script)
val info = initInfo ?: throw LxSourceInitException("没有声明能力")  // ← 必然 null
```

lx 的做法（`QuickJS.loadScript`）是 evaluate 完**直接返回成功**，
初始化结果走异步回调，不在这里检查。

**修**：`start()` 挂一个 `CompletableFuture`，谁先到谁完成它 ——
`init` 消息、脚本异常、或 60 秒超时。`handleInit` 里 `future.complete(info)`。

> 超时给 60 秒不是 20 秒：社区音源的探测接口经常很慢，
> 而且它们会并发打 npmjs / npmmirror 两个镜像取最快那个。

### 坑 4：`__lx_native__` 的 key 必须传（最隐蔽）

```
pushToScript(response) 返回 Invalid key
```

preload 里：

```js
globalThis.__lx_native__ = function (_key, action, data) {
  if (key !== _key) return 'Invalid key'
  ...
}
```

我调的是：

```kotlin
bridge.call(action, data)        // ❌ 只有 2 个参数
```

**所有响应都被 preload 静默拒掉**，脚本毫无反应，日志里只能看到
`Invalid key` 这个返回值 —— 不埋点根本发现不了。

lx 的 `callJS(action, args)` 会自动把 key 拼在参数最前面：

```java
Object[] params = new Object[args.length + 2];
params[0] = this.key;
params[1] = action;
System.arraycopy(args, 0, params, 2, args.length);
```

**修**：`bridge.call(key, action, data)`。

### 坑 5：元信息赋值时序

`scriptMeta` 必须在 `evaluate` **之前**赋值。因为脚本可能在求值期间
就调 `send('inited')`，那时 `handleInit` 要读 `scriptMeta` 填
`LxSourceInfo` 的名字字段。第一版写在 `evaluate` 后面，名字全是空。

### 坑 6：回包 body 必须是解析后的对象（不是字符串）

**这条最容易漏，漏了会让音源在初始化阶段就失败，看起来像「源坏了」。**

对照 lx 的 `src/core/init/userApi/request.js:105`：

```js
try { resp.body = JSON.parse(resp.body) } catch {}
```

脚本是按这个契约写的，普遍直接访问字段：

```js
h(A + '/init.conf').then(r => {
  if (r.body.code !== 200) x('脚本初始化失败')   // ← r.body 必须是对象
})
```

我最初把 body 当字符串发过去，字符串的 `.code` 是 `undefined`，
`undefined !== 200` 恒成立 → 音源直接抛「脚本初始化失败」。
`juhe` 这个源就是被这条卡住的。

lx 交给脚本的完整形状：

```js
{ headers, body, statusCode, statusMessage, url, ok }
```

`url` 和 `ok` 也要有，`headers` 同名头是**数组**（`resp.headers.map` 的形状），
脚本可能写 `r.headers['x'][0]`。

请求侧还要补默认头（lx 的 `defaultHeaders`）：
`Accept: application/json` + 桌面 Chrome UA。这些源接口对 UA 敏感。

### 坑 7：`JSONArray(String)` 是「解析」不是「构造」

```kotlin
put("bytes", JSONArray(encodeBase64(bytes)))   // ❌
```

org.json 的 `JSONArray(String)` 语义是**把字符串当 JSON 文本解析**，
传 base64 会抛 `cannot be converted to JSONArray`，于是**每一次脚本发起的
HTTP 请求都构造失败**。

正确写法是新建数组再逐个 put：

```kotlin
val byteArray = JSONArray()
bytes.forEach { byteArray.put(it.toInt() and 0xFF) }
put("bytes", byteArray)
```

这个 bug 的隐蔽之处在于：当时 `handleScriptHttp` 的成功和失败分支
打的是同一句日志「HTTP 完成」，看不出来。等我把日志改成分别打
`-> 200` 和 `失败：原因` 之后它才暴露。

### 坑 8：明文 HTTP 被系统拦

音源会用 `http://` 的裸 IP 端点（比如 grass 源的 `http://97.64.37.235/...`），
Android 9+ 默认拦：

```
CLEARTEXT communication to 97.64.37.235 not permitted by network security policy
```

端点由脚本决定、无法枚举，所以只能全局放开。照抄 lx 的做法：

```xml
<!-- res/xml/network_security_config.xml -->
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

清单里加 `android:networkSecurityConfig="@xml/network_security_config"`。

### 坑 9：音质必须降级，不能硬发用户选的档位

grass 源只声明 `qualitys: ["128k"]`，我们硬发 `320k`，服务端直接 404，
报错完全看不出是音质选错了。

照 lx 的 `getPlayQuality`（`src/core/music/utils.ts`）做降级：
**从用户选的档位开始往下（降级），取第一个该音源支持的**。
顺序是「从高往低」找，所以先拿到能用的高音质。默认 128k。

---

## 5. 播放失败排查：日志必须落盘

音源问题一次复现很慢（要搜索、点播放、等报错）。用 logcat 存不住关键那几行 ——
实测 16MB 缓冲也会被厂商组件（`CardWidget` / `Seedling` 那套通知卡片）刷掉。

所以音源链路有独立的文件日志：

```
filesDir/lxsource.log     见 util/SourceLog.kt
```

覆盖三段完整链路，一次复现就能定位死在哪一层：

```
[SearchPlay]    搜索播放 <歌名> platform=kw id=574372359
[LxSourceResolver] 源=<音源名>(kw) quality=320k info={...}
[LxSourceResolver] 拿到直链 url=...
[PlaybackConn]  playNow <歌名> -> <真正送进播放器的 URI>
[Playback]      播放失败 code=2004 | ExoPlaybackException: Source error
[Playback]        cause[0] HttpDataSource$InvalidResponseCodeException: Response code: 301
```

拉出来看：

```bash
adb shell "run-as cn.qishui.tool cat files/lxsource.log"
```

**`cause` 链才是真相**，Media3 自己只打「Source error」这个包装。

---

## 6. 「链接浏览器能下、App 播不了」——跨协议重定向

**这是整条链路最容易误判的一个症状**，我在这上面绕了很久。

现象：音源正常返回直链，浏览器下载正常，App 报 `Source error`。

真因（长青源）：

```
我们发出:  http://yinyue.haitangw.net/kw/kw.php?type=mp3&id=574372359&level=exhigh
服务器回:  HTTP 301 MovedPermanently
           Location: https://yinyue.haitangw.net/kw/kw.php?type=mp3&id=574372359&level=exhigh
Media3:    InvalidResponseCodeException: Response code: 301
```

`DefaultHttpDataSource` 底层是 `HttpURLConnection`，
**默认不跟随跨协议重定向**（`http` → `https`），301 被原样交给 Media3，
判定非 2xx 就报错。浏览器自动跟随，所以浏览器能下。

修法（`PlaybackService.kt`）：

```kotlin
.setMediaSourceFactory(
    DefaultMediaSourceFactory(
        DefaultDataSource.Factory(
            this,
            DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(mapOf("User-Agent" to DESKTOP_UA))
        )
    )
)
```

**这段不能删** —— 看着像「多余」的跨协议开关，删了长青、幻音这类
返回跳转地址的源就全播不了。

### 为什么只有部分源能用

| 源 | 返回的直链形态 | 能否播 |
|---|---|---|
| qdy | `kw-er.kuwo.cn/992ae40929de...` CDN 直链 | ✅ 不重定向 |
| changqing 长青 | `http://yinyue.haitangw.net/kw/kw.php?...` | ✅（开了跨协议重定向后） |
| huanyin 幻音 | `https://music-dl.sayqz.com/api/?...` | ❌ 域名已注销 |
| flower / lx | `https://88.lxmusic.xn--fiqs8s/...` | ❌ 主机 `No route to host` |
| grass | `http://97.64.37.235/...` | ❌ 443 端口 `No route to host` |

判断某个源能不能用，**不用装 App**，用 Node 桩跑一遍脚本最省事 ——
见下面的「音源离线体检」。

---

## 7. 音源离线体检（不用装机）

音源脚本高度混淆，静态读不出它要连哪。写个 Node 桩模拟 lx 宿主，
把脚本真跑一遍就能看到它真实请求的地址和握手声明的能力。

```
tools/source-probe/probe.js
```

用法：

```bash
node tools/source-probe/probe.js <脚本路径> [音质] [songmid]
```

输出 `*.probe.json`，含：

- `requests` — 初始化时真实请求的 URL（混淆也藏不住）
- `inited` — 握手声明的平台和音质
- `urlResult` / `urlError` — 拿一首歌试求直链的结果

对 `pdone/lx-music-source` 全量跑一遍的结果：

```
源          名称                 声明平台                  求直链    后端状态
--------------------------------------------------------------------------
qdy         全豆要(聚合)          wy,tx,kw,kg,mg,qsvip       OK        连通
changqing   长青SVIP             kg,tx,wy,kw,mg            OK        连通
juhe        聚合API接口(CF)       kg,kw,mg,tx,wy            部分歌曲   连通，覆盖度有限
huanyin     幻音                  tx,kw,wy,mg               OK        music-dl.sayqz.com 域名已注销
huibq       Huibq                kw,kg,tx,wy,mg            失败      onrender 503
sixyin      六音                  —                         —         脚本要求去官网下新版
flower/lx   野花 / lx            —                         —         88.lxmusic.xn--fiqs8s 不可达
grass       野草                  —                         —         97.64.37.235 不可达
```

**结论：源能不能用是后端说了算，跟我们的实现无关。**
判主机活不活用系统工具即可，别绕到 App 上：

```bash
adb shell "echo -n | nc -w 8 <host> 443"    # 无输出=通，"No route to host"=不通
```

---

## 8. 现在的实现结构

```
assets/lx/user-api-preload.js          脚本侧环境（实现 lx 那套契约）
media/source/LxSourceEngine.kt         QuickJS 宿主，原生函数 + 双向回调
media/source/HttpBodyParse.kt          回包 body 解析（坑 6 的回归测试在这）
media/source/LxSourceResolver.kt       平台 → 音质降级 → 求直链
data/source/LxSourceRuntime.kt         引擎生命周期（reset / activate）
data/source/MusicSourceRepository.kt   存储 + 勾选互斥 + 导入
domain/source/LxSourceInfo.kt          平台 / action / 音质 能力模型
data/local/MusicSourceEntity.kt        Room 实体
data/local/MusicSourceDao.kt           DAO，selectOnly 保证只能选一个
feature/source/MusicSourceViewModel.kt UI 状态
feature/settings/MusicSourceSection.kt 导入 UI（链接 + 文件 + 复选框）
data/search/*SearchClient.kt           五个平台的搜索（协议里没有 search）
util/SourceLog.kt                      音源链路文件日志
```

### 复选框互斥在哪保证

不在 UI，在数据层事务里：

```kotlin
@Transaction
suspend fun selectOnly(id: String) {
    deselectAll()
    markSelected(id)
}
```

### 搜索不在音源协议里

lx 自定义源**只有 `musicUrl` / `lyric` / `pic` 三个 action，没有 `search`**。
搜索是 App 自己实现的（`data/search/`），每个平台一份，
端点对着 lx 的 `src/utils/musicSdk/` 下各平台的 `musicSearch.js` 抄。

---

## 9. 怎么验证

**QuickJS 不能跑 JVM 单测** —— 会抛
`The so library must be initialized before createContext`。
协议层只能装机验，但**取值形状可以单测**：

```
data/source/LxSourceResponseTest.kt     回包形状（坑 6）
media/source/HttpBodyParseTest.kt       body 解析（坑 6）
media/source/LxSourceResolverQualityTest.kt  音质降级（坑 9）
```

音源链路验证清单：

1. 导入链接 → `已入库 id=xxx`
2. 勾选 → 日志出现 `握手完成，可用平台 [...]`
3. 搜索页的平台标签只剩该音源支持的
4. 点结果 → 能出声
5. 拉 `files/lxsource.log` 确认 `cause` 链无异常

---

## 10. 搜索与歌词：协议外的东西

### 搜索不在音源协议里

lx 自定义源只有 `musicUrl` / `lyric` / `pic` 三个 action，**没有 `search`**。
搜索是 App 自己实现的（`data/search/`），每个平台一份，
端点对着 lx 的 `src/utils/musicSdk/` 下各平台的 `musicSearch.js` 抄。

五个平台，各实测确认仍可用：

```
kw  search.kuwo.cn/r.s                              无签名
kg  songsearch.kugou.com/song_search_v2              无签名
tx  c.y.qq.com/soso/fcgi-bin/search_for_qq_cp        无签名（老接口，无需登录）
wy  music.163.com/api/linux/forward                  AES-128-ECB/PKCS5
mg  jadeite.migu.cn/music_search/v3                  MD5 固定盐
```

两条踩过的坑：

- **QQ 别用 lx 的新接口**。lx 打 `DoSearchForQQMusicDesktop`，zzcSign 签名
  算得出来、服务器也认（`code=2000`，错签名才是 500001），但**未登录时
  返回 0 条**。老接口 `search_for_qq_cp` 无签名且照常有数据。
- **网易别用 lx 的 EAPI 那条路**。lx 打 `eapi/batch`，而且它的 Java
  `AES.encrypt` 用 `AES/ECB/NoPadding` —— base64 之后长度不是 16 的倍数时
  `doFinal` 抛异常被 `catch` 吞掉，返回空 params，服务端回空响应。
  走 `linux/forward` + linuxapi（PKCS5）就正常。密钥两种写法同一个：
  `rFgB&h#%2?^eDg:Q`。

### 平台标签要取交集

UI 上的平台标签 = **「我们实现了搜索」∩「当前音源声明支持 musicUrl」**。

只看一边都会给出「点得动但必然失败」的选项。音源能力从握手时的 `init`
拿到（`LxSourceInfo.platforms`），音源换了标签跟着变，换源不用重启页面。

音源没就绪时给**全量 + 明确提示**，不是空列表 —— 用户不知道该去勾音源。

### 汽水音乐：能做但没做

qdy 额外声明了 `qsvip`（汽水）平台。搜索端点是通的：

```
https://api.qishui.com/luna/search/track
（设备指纹是必需项，参数照 musicdl 的 soda.py → _constructsearchurls）
```

**但没做**，因为唯一支持它的音源求直链走后端 `api.vsaa.cn`，那个已经 404。
搜得到播不了，给用户看一堆点不动的结果没意义。

如果哪天有音源的汽水后端活了，`api.qishui.com/luna/search/track` 直接可用，
字段是 `result_groups[0].data[].entity.track.{id,name,duration,artists,album}`
（`duration` 是毫秒）。

### 歌词：音源帮不上，走独立一层

**lx 协议的 `lyric` action 只对 `local` 平台开放**，
远端平台的歌音源脚本给不了歌词。所以歌词是独立实现（`data/lyrics/`）。

**歌词和歌曲是两件事。** 歌从哪个平台来，不影响歌词能不能拿到。
只有网易和 QQ 有能用的公开歌词接口，但这两家的曲库几乎覆盖所有中文歌，
所以拿别的平台的歌时，按歌名反查到这两个平台就行。

| 平台 | 来源 | 说明 |
|---|---|---|
| 网易云 | 官方 `song/lyric`，经 `linux/forward` 代理 | 返回 `lrc`（原文）/ `tlyric`（译文）/ `klyric`（逐字）/ `romalrc`。只取 `lrc` |
| 网易云 | `amll-dev/amll-ttml-db` 兜底 | **CC0-1.0** 公共领域，官方没收录的冷门歌这里可能有。`ncm-lyrics/{id}.lrc` |
| QQ | `c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg` | 官方接口，匿名可用（`nobase64=1`）。**必须带 `Referer`** |
| 酷我 / 酷狗 / 咪咕 | 反查到上面两个 | 这三个平台没有可用的公开歌词接口 |

网易官方接口本身在 `weapi` 下，要 RSA + 双层 AES；但 `linux/forward` 这层
只要 linuxapi 的 AES-128-ECB（密钥固定），**能直接代理到 `song/lyric`**，
不用碰 RSA。`jitwxs/163MusicLyrics`（Apache-2.0）的 `NetEaseMusicNativeApi.GetLyric`
用哪些 `lv/tv/kv` 参数拿全歌词，可以照它。

**没用第三方歌词库。** 试过 `lrclib.net`，实测中文歌**零覆盖**
（张月《信》搜索 0 命中）—— 它是英文社区库。宁可没歌词也不能猜，
而且它自己也是按歌名/歌手/时长猜匹配，配错了比空白更糟。

跨平台反查的匹配规则（`LyricsMatch`，`LyricsMatchTest` 锁住）三道闸：

- **歌名**：归一化后相等，或一方包含另一方（吃下 `信 (Live)` 这类后缀差异）。
  中文歌名大量是单字（「信」「海」），所以这里**不能**套最小长度门槛
- **歌手**：只做「结果 → 原曲」这一个方向，且要求至少两个字。
  反方向会把歌名「信」配到「信乐团」
- **时长**：差不超过 8 秒。Live / DJ 版时长差得多，这道闸能挡掉大部分

顺序是「本平台有官方接口就直接用 → QQ 反查 → 网易反查 → 没有就是没有」。

#### 坑 10：歌词接口返回了内容，但被解析器整段丢弃

**这是最容易误判成「覆盖率不够」的一个坑。** 现象是接口明明返回了
938 字符，日志里也看得见，但播放页一个字都没有。

根因：`QsmusicLyricParser` 是 **QRC 解析器**，只认
`[起毫秒,时长毫秒]` + `<相对毫秒,时长,0>字` 这种格式：

```kotlin
val header = line.substring(1, headerEnd).split(',')
if (header.size != 2) return null      // "[00:03.11]" 没有逗号 → 整行丢弃
```

而**所有歌词源返回的都是标准 LRC** —— QQ 官方、网易官方、amll 库、
本地文件内嵌标签，全是 `[mm:ss.xx]歌词`。每一行都被丢掉，
`parse()` 返回 0 行，被 `takeIf { lines.isNotEmpty() }` 静默筛掉。

标准 LRC 的解析器 `LrcCodec.parse` **早就写好了**，只是从来没接上。
`LyricsCodec` 现在按结果判：先试 LRC，解析不出再走 QRC。
两种格式的行数不可能同时非零，按结果判比按来源猜可靠 ——
本地文件两种格式都见过。

同一根因还影响另外三处，它们当时也单独 `new QsmusicLyricParser()`，
症状是**导出 MP3 时把 LRC 写成空歌词**（静默数据丢失）：
`EncoderService` / `ExportEngine` / `ResolveScreen`。现在统一走 `LyricsCodec`。

#### 坑 11：QQ 歌词接口不给 `Referer` 会返回「成功」的空结果

```
不带 Referer → {"retcode":-1310,"code":-1310,"subcode":-1310}    46 字节
带 Referer   → {"retcode":0,...,"lyric":"[00:03.11]祈祷星星..."} 1756 字节
```

**不是 403，不是空歌词，是 HTTP 200 + 错误码。** 所以在链路上完全
表现为「这首歌没有歌词」，会被误判成覆盖问题，然后跑去换数据源 ——
方向从第一步就错了。

`extractQqLyric` 因此把 `retcode != 0` 一律判为拿不到，不再当空歌词往下走。
`QqLyricsFetcherTest` 用线上原样 payload 锁住这个响应。

另外路径里的 `fcgi-bin` 不能写成 `fcg-bin`，写错是 404。

#### 歌词必须异步补，不能卡在起播前面

抓歌词要发一次搜索 + 一次歌词请求，实测会卡 **12 秒** ——
用户点了一首歌，十几秒后才出声。所以 `playNow` 先执行，
歌词抓完再 `attachLyrics` 推进 `StateFlow`。

歌词**不能靠队列回读带回来**：播放页的队列是从服务的 `MediaItem` 重建的，
歌词没地方放（塞 `extras` 太大，还要跨进程序序化）。所以歌词按
`mediaId` 存在 `PlaybackConnection` 里，用 `StateFlow` 让播放页被动刷新。

#### 播放进度要自己轮询

进度条、时钟、歌词高亮**全都只认 `PlaybackUiState.positionMs`**，
但 Media3 的 `onEvents` **只在离散事件触发**（播放/暂停、切歌、状态变化），
**播放头前进不通知**。

不自己轮询的话，进度会一直停在起播那一刻，只有暂停时才跳到真实位置 ——
表现就是「进度条和歌词卡在 15 秒，暂停一下才同步，一播放又卡」。
`PlaybackConnection.tickPosition()` 按 200ms 取一次，
位置没变就不发新状态（否则暂停时每 200ms 白白重组一次）。

### 遗留

- [ ] 歌词逐字（`yrc`/`qrc`/`krc`/`ttml`）—— `LyricsCodec` 只取行级，
      逐字要各自解密（QQ 的 QRC 是 buggy DES、酷狗 KRC 是 XOR+zlib）。
      网易官方其实已经返回 `klyric` 了，只差一个 YRC 解析器
- [ ] 译文合并 —— 网易 `tlyric`、QQ 也返回翻译，现在是丢掉只显示原文
- [ ] 汽水音乐 —— 等有音源的后端活了再说
- [ ] 清理过量埋点（`SourceLog` 保留，它是唯一的可靠诊断手段）
- [ ] 音源后端在成片关停（实测 10 个源里 3 个后端已死），
      可能需要一个多音源 fallback：首选源失败自动换下一个

### 排查方法论：静默失败比崩溃难查

这轮歌词排查绕了三轮弯路，三个坑都表现为「什么都没有」：

1. `LyricsRepository` 的 `?: runCatching{}.getOrNull()` 吞掉一切异常
2. `takeIf { lines.isNotEmpty() }` 把「解析失败」和「真的没歌词」压成同一个结果
3. 链路上没有任何一行日志能区分「没取到」和「取到了但被丢掉」

**教训：多组件链路（搜索 → 匹配 → 取原文 → 解析 → 渲染）每一段边界
都要能看见输入和输出。** 之前只在候选侧打了日志、把查询侧漏掉，
结果对着一条完美命中的候选去猜接口问题。

后来在四处边界都加了记录（查询三元组、匹配结果、每平台取词结果、
原始响应前 90 字节），一次就定位到是解析器。
**接口的错误码（`-1310`）就藏在那 90 字节里** —— 成功状态码不代表成功。

这些诊断日志在定位后已经清掉了，代码里只留下必要的两处：
`official()` 的失败上报（`runCatching` 会把异常吞成 null，
删掉就等于静默失败）和播放路径上既有的 `SourceLog`。



