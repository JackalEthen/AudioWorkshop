'use strict'

/**
 * lx 自定义源脚本的运行环境。
 *
 * 这个文件实现的是 lx-music-mobile 里
 * `android/app/src/main/assets/script/user-api-preload.js` 的同一套契约，
 * 参考自 https://github.com/lyswhut/lx-music-mobile （Apache-2.0）。
 *
 * 宿主（Kotlin 侧 LxSourceEngine）会先往全局注入：
 *   __lx_native_call__(key, action, dataJson)     脚本 → 宿主
 *   __lx_native_call__set_timeout(id, ms)
 *   __lx_native_call__utils_str2b64 / b642buf / str2md5 / aes_encrypt / rsa_encrypt
 * 然后 evaluate 本文件，再调用 lx_setup(...)，
 * 本文件会反过来往全局装上 __lx_native__(key, action, dataJson) 给宿主调用。
 *
 * 与原版的区别：原版带一大段 freezeObjectProperty 的全局冻结加固。
 * 那部分在 Rhino 上遍历并重定义全部全局属性，既慢又容易和引擎打架，
 * 而它防的是脚本改宿主 API —— 这里改成直接把 lx 对象冻结，够用。
 */
globalThis.lx_setup = function (key, id, name, description, version, author, homepage, rawScript) {
  delete globalThis.lx_setup

  const nativeCallRaw = globalThis.__lx_native_call__
  const nativeFuncs = {}
  const nativeFuncNames = [
    'set_timeout',
    'utils_str2b64',
    'utils_b642buf',
    'utils_str2md5',
    'utils_aes_encrypt',
    'utils_rsa_encrypt',
  ]
  for (const n of nativeFuncNames) {
    const fn = globalThis['__lx_native_call__' + n]
    delete globalThis['__lx_native_call__' + n]
    nativeFuncs[n] = fn
  }

  const nativeCall = function (action, data) {
    nativeCallRaw(key, action, data === undefined ? null : JSON.stringify(data))
  }

  // ---- 定时器：宿主计时，宿主回调 __set_timeout__ ----
  let timeoutId = 0
  const callbacks = new Map()
  globalThis.setTimeout = function (callback, timeout) {
    if (typeof callback !== 'function') throw new Error('callback required a function')
    timeout = Number(timeout) || 0
    if (timeout < 0) throw new Error('timeout required a number')
    const id = timeoutId++
    callbacks.set(id, callback)
    nativeFuncs.set_timeout(id, parseInt(timeout, 10))
    return id
  }
  globalThis.clearTimeout = function (id) {
    callbacks.delete(id)
  }
  const handleSetTimeout = function (id) {
    const cb = callbacks.get(id)
    if (!cb) return
    callbacks.delete(id)
    try {
      cb()
    } catch (err) {
      console.error(err)
    }
  }

  // ---- UTF-8 <-> 字节数组，供 utils.buffer 用 ----
  function stringToBytes(input) {
    const bytes = []
    for (let i = 0; i < input.length; i++) {
      const c = input.charCodeAt(i)
      if (c < 128) {
        bytes.push(c)
      } else if (c < 2048) {
        bytes.push((c >> 6) | 192, (c & 63) | 128)
      } else {
        bytes.push((c >> 12) | 224, ((c >> 6) & 63) | 128, (c & 63) | 128)
      }
    }
    return bytes
  }
  function bytesToString(bytes) {
    let result = ''
    let i = 0
    while (i < bytes.length) {
      const b = bytes[i]
      if (b < 128) {
        result += String.fromCharCode(b)
        i++
      } else if (b >= 192 && b < 224) {
        result += String.fromCharCode(((b & 31) << 6) | (bytes[i + 1] & 63))
        i += 2
      } else {
        result += String.fromCharCode(
          ((b & 15) << 12) | ((bytes[i + 1] & 63) << 6) | (bytes[i + 2] & 63),
        )
        i += 3
      }
    }
    return result
  }

  const utils = {
    crypto: {
      randomBytes: function (size) {
        const out = new Uint8Array(size)
        for (let i = 0; i < size; i++) out[i] = Math.floor(Math.random() * 256)
        return out
      },
      md5: function (str) {
        if (typeof str !== 'string') throw new Error('param required a string')
        return nativeFuncs.utils_str2md5(encodeURIComponent(str))
      },
      aesEncrypt: function (buffer, mode, key, iv) {
        const data = typeof buffer === 'string' ? nativeFuncs.utils_str2b64(buffer) : null
        if (data === null) throw new Error('aesEncrypt only supports string input')
        const k = nativeFuncs.utils_str2b64(key)
        const v = mode === 'aes-128-cbc' ? nativeFuncs.utils_str2b64(iv) : ''
        const padding = mode === 'aes-128-cbc' ? 'AES/CBC/PKCS7Padding' : 'AES/ECB/NoPadding'
        return nativeFuncs.utils_aes_encrypt(data, k, v, padding)
      },
      rsaEncrypt: function (buffer, key) {
        if (typeof key !== 'string') throw new Error('Invalid RSA key')
        key = key
          .replace('-----BEGIN PUBLIC KEY-----', '')
          .replace('-----END PUBLIC KEY-----', '')
        return nativeFuncs.utils_rsa_encrypt(nativeFuncs.utils_str2b64(buffer), key, 'RSA/ECB/NoPadding')
      },
    },
    buffer: {
      from: function (input, encoding) {
        if (typeof input === 'string') {
          switch (encoding) {
            case 'base64':
              return new Uint8Array(JSON.parse(nativeFuncs.utils_b642buf(input)))
            case 'hex':
              return new Uint8Array(input.match(/.{1,2}/g).map(function (b) { return parseInt(b, 16) }))
            default:
              return new Uint8Array(stringToBytes(input))
          }
        }
        if (Array.isArray(input)) return new Uint8Array(input)
        throw new Error('Unsupported input type: ' + typeof input)
      },
      bufToString: function (buf, format) {
        if (!Array.isArray(buf) && !ArrayBuffer.isView(buf)) throw new Error('not a buffer')
        switch (format) {
          case 'binary':
            return buf
          case 'hex':
            return new Uint8Array(buf).reduce(function (s, b) { return s + b.toString(16).padStart(2, '0') }, '')
          case 'base64':
            return nativeFuncs.utils_str2b64(bytesToString(Array.from(buf)))
          default:
            return bytesToString(Array.from(buf))
        }
      },
    },
  }

  // ---- 请求：脚本发起，宿主去发 HTTP，再把结果推回来 ----
  const requestQueue = new Map()
  const handleNativeResponse = function (data) {
    const target = requestQueue.get(data.requestKey)
    if (!target) {
      console.error('[lx] 响应找不到对应请求: ' + data.requestKey)
      return
    }
    requestQueue.delete(data.requestKey)
    try {
      if (data.error == null) target.callback(null, data.response)
      else target.callback(new Error(data.error), null)
    } catch (error) {
      console.error('[lx] 响应回调抛错: ' + (error && error.message))
    }
  }

  // ---- 宿主反过来问脚本要东西（musicUrl / lyric / pic） ----
  const events = { request: null }
  const verifyLyricInfo = function (info) {
    if (typeof info !== 'object' || typeof info.lyric !== 'string') throw new Error('failed')
    return {
      lyric: info.lyric,
      tlyric: typeof info.tlyric === 'string' ? info.tlyric : null,
      rlyric: typeof info.rlyric === 'string' ? info.rlyric : null,
      lxlyric: typeof info.lxlyric === 'string' ? info.lxlyric : null,
    }
  }
  const handleRequest = function (data) {
    if (!events.request) {
      nativeCall('response', {
        requestKey: data.requestKey,
        status: false,
        errorMessage: 'Request event is not defined',
      })
      return
    }
    const info = data.data
    let promise
    try {
      promise = events.request.call(globalThis.lx, {
        source: info.source,
        action: info.action,
        info: info.info,
      })
    } catch (err) {
      nativeCall('response', { requestKey: data.requestKey, status: false, errorMessage: String(err && err.message) })
      return
    }
    Promise.resolve(promise).then(function (response) {
      let result
      if (info.action === 'musicUrl') {
        if (typeof response !== 'string' || !/^https?:/.test(response)) throw new Error('failed')
        result = { source: info.source, action: info.action, data: { type: info.info.type, url: response } }
      } else if (info.action === 'lyric') {
        result = { source: info.source, action: info.action, data: verifyLyricInfo(response) }
      } else if (info.action === 'pic') {
        if (typeof response !== 'string' || !/^https?:/.test(response)) throw new Error('failed')
        result = { source: info.source, action: info.action, data: response }
      } else {
        throw new Error('unsupported action: ' + info.action)
      }
      nativeCall('response', { requestKey: data.requestKey, status: true, result: result })
    }).catch(function (err) {
      nativeCall('response', {
        requestKey: data.requestKey,
        status: false,
        errorMessage: String(err && err.message),
      })
    })
  }

  const jsCall = function (action, data) {
    switch (action) {
      case '__run_error__':
        return
      case '__set_timeout__':
        handleSetTimeout(data)
        return
      case 'request':
        handleRequest(data)
        return
      case 'response':
        handleNativeResponse(data)
        return
    }
    return 'Unknown action: ' + action
  }

  // 宿主通过 getJSFunction('__lx_native__') 拿到这个函数再反向调用
  globalThis.__lx_native__ = function (_key, action, data) {
    if (key !== _key) return 'Invalid key'
    return data == null ? jsCall(action) : jsCall(action, JSON.parse(data))
  }

  const EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' }
  let isInited = false
  let updateAlertShown = false

  const allSources = ['kw', 'kg', 'tx', 'wy', 'mg', 'local']
  const supportQualitys = {
    kw: ['128k', '320k', 'flac', 'flac24bit'],
    kg: ['128k', '320k', 'flac', 'flac24bit'],
    tx: ['128k', '320k', 'flac', 'flac24bit'],
    wy: ['128k', '320k', 'flac', 'flac24bit'],
    mg: ['128k', '320k', 'flac', 'flac24bit'],
    local: [],
  }
  // 和 lx 原版一致：远端平台只给播放地址，歌词封面只有 local 给
  const supportActions = {
    kw: ['musicUrl'],
    kg: ['musicUrl'],
    tx: ['musicUrl'],
    wy: ['musicUrl'],
    mg: ['musicUrl'],
    xm: ['musicUrl'],
    local: ['musicUrl', 'lyric', 'pic'],
  }

  const handleInit = function (info) {
    if (!info) {
      nativeCall('init', { info: null, status: false, errorMessage: 'Missing required parameter init info' })
      return
    }
    const sourceInfo = { sources: {} }
    try {
      for (const source of allSources) {
        const userSource = info.sources[source]
        if (!userSource || userSource.type !== 'music') continue
        sourceInfo.sources[source] = {
          type: 'music',
          actions: supportActions[source].filter(function (a) { return userSource.actions.indexOf(a) > -1 }),
          qualitys: supportQualitys[source].filter(function (q) { return userSource.qualitys.indexOf(q) > -1 }),
        }
      }
    } catch (err) {
      nativeCall('init', { info: null, status: false, errorMessage: String(err && err.message) })
      return
    }
    nativeCall('init', { info: sourceInfo, status: true })
  }

  globalThis.lx = {
    EVENT_NAMES: EVENT_NAMES,
    version: '2.0.0',
    env: 'mobile',
    currentScriptInfo: {
      name: name,
      description: description,
      version: version,
      author: author,
      homepage: homepage,
      rawScript: rawScript,
    },
    utils: utils,
    request: function (url, options, callback) {
      if (typeof options === 'function') {
        callback = options
        options = {}
      }
      options = options || {}
      callback = callback || function () {}
      const requestKey = 'request__' + Math.random().toString().substring(2)
      const opts = {
        method: options.method || 'get',
        headers: options.headers,
        body: options.body,
        form: options.form,
        formData: options.formData,
        binary: options.binary === true,
      }
      if (typeof options.timeout === 'number' && options.timeout > 0) {
        opts.timeout = Math.min(options.timeout, 60000)
      }
      requestQueue.set(requestKey, { callback: callback })
      nativeCall('request', { requestKey: requestKey, url: url, options: opts })
      return function () {
        if (!requestQueue.has(requestKey)) return
        requestQueue.delete(requestKey)
        nativeCall('cancelRequest', requestKey)
      }
    },
    on: function (eventName, handler) {
      if (eventName === EVENT_NAMES.request) {
        events.request = handler
        return Promise.resolve()
      }
      return Promise.reject(new Error('The event is not supported: ' + eventName))
    },
    send: function (eventName, data) {
      if (eventName === EVENT_NAMES.inited) {
        if (isInited) return Promise.reject(new Error('Script is inited'))
        isInited = true
        handleInit(data)
        return Promise.resolve()
      }
      if (eventName === EVENT_NAMES.updateAlert) {
        if (updateAlertShown) return Promise.reject(new Error('The update alert can only be called once.'))
        updateAlertShown = true
        nativeCall('showUpdateAlert', { log: data && data.log, updateUrl: data && data.updateUrl, name: name })
        return Promise.resolve()
      }
      return Promise.reject(new Error('The event is not supported: ' + eventName))
    },
  }

  // 冻结 lx，脚本改不了宿主的 API
  Object.freeze(utils)
  Object.freeze(utils.crypto)
  Object.freeze(utils.buffer)
  Object.freeze(globalThis.lx)
  Object.freeze(globalThis.lx.utils)
}
