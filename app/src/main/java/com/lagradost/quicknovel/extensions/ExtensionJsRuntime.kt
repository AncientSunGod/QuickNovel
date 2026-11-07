package com.lagradost.quicknovel.extensions

import android.content.Context as AndroidContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Comment
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Partial CommonJS-style JavaScript runtime for LNReader extension bundles.
 *
 * This is an in-process compatibility layer, not a security sandbox. It exposes a narrow,
 * bounded host bridge for networking and HTML selection; unknown imports fail closed rather
 * than being silently replaced with fake implementations. Arbitrary third-party bundles
 * should not be treated as safe until execution is moved to a genuinely isolated runtime.
 */
object ExtensionJsRuntime {
    private val sessionStorage = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    internal fun clearSessionStorage(extensionId: String) {
        sessionStorage.remove(extensionId)
    }

    internal fun clearExtensionData(context: AndroidContext, extensionId: String) {
        clearSessionStorage(extensionId)
        context.getSharedPreferences(
            "quicknovel_extension_storage_$extensionId",
            AndroidContext.MODE_PRIVATE,
        ).edit().clear().apply()
    }

    private fun jsByteArray(value: Any?): ByteArray {
        val array = value as? Scriptable
            ?: throw IllegalArgumentException("AES input must be a byte array.")
        val length = (ScriptableObject.getProperty(array, "length") as? Number)?.toInt()
            ?: throw IllegalArgumentException("AES input byte array has no length.")
        require(length in 0..(2 * 1024 * 1024)) { "AES input exceeds the 2 MB limit." }
        return ByteArray(length) { index ->
            (Context.toNumber(ScriptableObject.getProperty(array, index)).toInt() and 0xff).toByte()
        }
    }

    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_ARGUMENT_BYTES = 256 * 1024
    private const val MAX_RESULT_BYTES = 8 * 1024 * 1024
    // Timers in published LNReader bundles are used for retry backoff and cooperative yields.
    // The bridge executes synchronously, so callbacks run inline after a bounded delay.
    private const val MAX_TIMER_DELAY_MS = 10_000L
    private const val MAX_TOTAL_TIMER_DELAY_MS = 30_000L
    // Parsing novel metadata commonly walks a large chapter catalogue. A single
    // global 2M budget was too small for otherwise valid LNReader plugins and caused
    // parseNovel to fail even when the network response and selectors were valid.
    // Keep operation-specific ceilings so catalogue parsing has room without removing
    // the runaway-script guard from cheaper operations.
    private const val MAX_DEFAULT_INSTRUCTIONS = 4_000_000
    private const val MAX_PARSE_NOVEL_INSTRUCTIONS = 24_000_000
    private const val MAX_PARSE_CHAPTER_INSTRUCTIONS = 12_000_000
    private const val MAX_LIST_INSTRUCTIONS = 8_000_000
    private const val OBSERVER_THRESHOLD = 10_000
    private val allowedOperations = setOf(
        "searchNovels",
        "parseNovel",
        "parseChapter",
        "popularNovels",
        "parsePage",
        "resolveUrl",
        "getPluginSettings",
        "setPluginSetting",
        "getPluginFilters",
        "transformChapterHtml",
        "getImageRequestInit",
    )

    data class ExecutionResult(val json: String)

    internal fun execute(
        extension: InstalledExtension,
        operation: String,
        argumentsJson: String,
        testFetchOverride: ((String) -> ExtensionHttpClient.TextResponse)? = null,
        storageContext: AndroidContext? = null,
        testRequestOverride: ((String, String, Map<String, String>, String?) -> ExtensionHttpClient.TextResponse)? = null,
        customScript: String? = null,
    ): Result<ExecutionResult> = runCatching {
        require(operation in allowedOperations) { "Unsupported extension operation." }
        if (operation == "transformChapterHtml") {
            require(customScript != null) { "Custom JavaScript content is missing." }
            require(customScript.toByteArray(Charsets.UTF_8).size <= 512 * 1024) {
                "Custom JavaScript exceeds the 512 KB limit."
            }
        }
        require(argumentsJson.toByteArray(Charsets.UTF_8).size <= MAX_ARGUMENT_BYTES) {
            "Extension arguments exceed the size limit."
        }
        val arguments = JSONArray(argumentsJson)
        val sourceFile = File(extension.bundlePath)
        require(sourceFile.isFile) { "Extension bundle is missing." }
        require(sourceFile.length() in 1..MAX_SOURCE_BYTES.toLong()) {
            "Extension bundle is empty or exceeds the 8 MB limit."
        }
        val source = sourceFile.readText(Charsets.UTF_8)
        require(source.isNotBlank()) { "Extension bundle is empty." }

        val instructionLimit = when (operation) {
            "parseNovel" -> MAX_PARSE_NOVEL_INSTRUCTIONS
            "parseChapter" -> MAX_PARSE_CHAPTER_INSTRUCTIONS
            "searchNovels", "popularNovels", "parsePage" -> MAX_LIST_INSTRUCTIONS
            else -> MAX_DEFAULT_INSTRUCTIONS
        }
        val factory = BudgetedContextFactory(instructionLimit, operation)
        val output = factory.call { cx ->
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = OBSERVER_THRESHOLD
            cx.setClassShutter { false }

            val scope = cx.initSafeStandardObjects()
            ScriptableObject.putProperty(scope, "module", cx.newObject(scope).apply {
                put("exports", this, cx.newObject(scope))
            })
            ScriptableObject.putProperty(scope, "exports", ScriptableObject.getProperty(
                ScriptableObject.getProperty(scope, "module") as Scriptable,
                "exports"
            ))
            val approvedHosts = approvedHostsForBundle(extension.site, source)
            var fetchCount = 0
            val fetchFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    fetchCount += 1
                    val requestLimit = if (operation == "parseNovel") 80 else 20
                    require(fetchCount <= requestLimit) {
                        "Extension exceeded the $requestLimit-request operation limit during $operation."
                    }
                    val requestedUrl = args.firstOrNull()?.toString().orEmpty()
                    require(requestedUrl.isNotBlank()) { "Extension fetch URL is missing." }
                    val init = runCatching {
                        JSONObject(args.getOrNull(1)?.toString().orEmpty())
                    }.getOrDefault(JSONObject())
                    val method = init.optString("method", "GET").uppercase(java.util.Locale.ROOT)
                    require(method == "GET" || method == "POST") {
                        "Extension requests currently support GET and POST only."
                    }
                    val headerJson = init.optJSONObject("headers") ?: JSONObject()
                    val requestHeaders = buildMap {
                        val keys = headerJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, headerJson.optString(key, ""))
                        }
                    }
                    val bodyValue = init.opt("body")
                    val requestBody = when {
                        bodyValue == null || bodyValue === JSONObject.NULL -> null
                        bodyValue is String -> bodyValue
                        else -> throw IllegalArgumentException(
                            "Extension request bodies must be strings; multipart and binary bodies are not supported."
                        )
                    }
                    val responseEncoding = args.getOrNull(2)?.toString()
                        ?.takeIf { it.isNotBlank() && it != "undefined" && it != "null" }
                    val binaryBase64 = args.getOrNull(3)?.toString() == "base64"
                    require(!binaryBase64 || method == "GET") {
                        "Binary fetchFile currently supports GET requests only."
                    }
                    require(method != "GET" || requestBody == null) {
                        "GET requests may not include a request body."
                    }
                    require(requestBody == null || requestBody.toByteArray(Charsets.UTF_8).size <= 256 * 1024) {
                        "Extension request body exceeds the 256 KB limit."
                    }
                    val response = testRequestOverride?.invoke(
                        requestedUrl, method, requestHeaders, requestBody
                    ) ?: testFetchOverride?.invoke(requestedUrl) ?: ExtensionHttpClient.requestText(
                        rawUrl = requestedUrl,
                        method = method,
                        body = requestBody,
                        headers = requestHeaders,
                        allowedHosts = approvedHosts,
                        responseEncoding = responseEncoding,
                    ).getOrThrow()
                    val responseJson = JSONObject()
                        .put("url", response.url)
                        .put("statusCode", response.statusCode)
                        .put("statusText", response.statusText.ifBlank { response.statusCode.toString() })
                        .put("contentType", response.contentType)
                        .put("headers", JSONObject(response.headers))
                    if (binaryBase64) {
                        require(response.bodyBytes.size <= 1024 * 1024) {
                            "Extension fetchFile response exceeds the 1 MB binary limit."
                        }
                        responseJson.put("bodyBase64", encodeBase64(response.bodyBytes))
                    } else {
                        responseJson.put("body", response.body)
                    }
                    return responseJson.toString()
                }
            }
            fetchFunction.parentScope = scope
            fetchFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnFetch", fetchFunction)
            var accumulatedTimerDelayMs = 0L
            val timerDelayFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val requestedDelayMs = when (val value = args.firstOrNull()) {
                        is Number -> value.toLong()
                        null -> 0L
                        else -> value.toString().toLongOrNull() ?: 0L
                    }
                    require(requestedDelayMs in 0..MAX_TIMER_DELAY_MS) {
                        "Extension timer delay exceeds the 10 second limit."
                    }
                    accumulatedTimerDelayMs += requestedDelayMs
                    require(accumulatedTimerDelayMs <= MAX_TOTAL_TIMER_DELAY_MS) {
                        "Extension exceeded the 30 second timer budget for one operation."
                    }
                    if (requestedDelayMs > 0L) {
                        try {
                            Thread.sleep(requestedDelayMs)
                        } catch (error: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IllegalStateException("Extension timer was interrupted.", error)
                        }
                    }
                    return 0
                }
            }
            timerDelayFunction.parentScope = scope
            timerDelayFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnDelay", timerDelayFunction)
            val htmlBridge = HtmlBridge(extension.site)
            val htmlFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val command = args.getOrNull(0)?.toString().orEmpty()
                    val selectionIdArg = args.getOrNull(1)
                    val selectionId = (selectionIdArg as? Number)?.toInt()
                        ?: selectionIdArg?.toString()?.toIntOrNull()
                        ?: 0
                    val value = args.getOrNull(2)?.toString()
                    val indexArg = args.getOrNull(3)
                    val index = (indexArg as? Number)?.toInt()
                        ?: indexArg?.toString()?.toIntOrNull()
                    return htmlBridge.invoke(command, selectionId, value, index)
                }
            }
            htmlFunction.parentScope = scope
            htmlFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnHtml", htmlFunction)
            val htmlEventsFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    return htmlBridge.parseEvents(args.firstOrNull()?.toString().orEmpty())
                }
            }
            htmlEventsFunction.parentScope = scope
            htmlEventsFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnHtmlEvents", htmlEventsFunction)
            val storageFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val command = args.getOrNull(0)?.toString().orEmpty()
                    val key = args.getOrNull(1)?.toString().orEmpty()
                    val rawValue = args.getOrNull(2)?.toString().orEmpty()
                    val expires = args.getOrNull(3)?.toString()?.toLongOrNull()
                    val raw = args.getOrNull(4)?.toString().equals("true", ignoreCase = true)
                    val area = args.getOrNull(5)?.toString().orEmpty()
                    require(area.isEmpty() || area == "local" || area == "session") {
                        "Unsupported extension storage area."
                    }
                    // localStorage and the @libs/storage helper persist across app restarts.
                    // sessionStorage is isolated per extension and kept only in this process.
                    // Unit tests and non-Android callers may omit a Context. Keep their
                    // storage in the same per-extension in-memory area rather than failing
                    // during module initialization; production providers pass app context.
                    val sessionValues = if (area == "session" || storageContext == null) {
                        sessionStorage.computeIfAbsent(extension.id) { ConcurrentHashMap() }
                    } else null
                    val prefs = if (sessionValues == null) {
                        storageContext?.getSharedPreferences(
                            "quicknovel_extension_storage_${extension.id}",
                            AndroidContext.MODE_PRIVATE,
                        ) ?: throw IllegalStateException("Extension storage requires an app context.")
                    } else null
                    val prefix = if (area == "local") "local_" else "entry_"
                    val output = when (command) {
                        "set" -> {
                            require(key.length <= 500) { "Extension storage key is too long." }
                            require(rawValue.length <= 100_000) { "Extension storage value is too large." }
                            val storedValue = runCatching { org.json.JSONTokener(rawValue).nextValue() }.getOrNull()
                            val envelope = JSONObject()
                                .put("created", System.currentTimeMillis())
                                .put("value", storedValue)
                            if (expires != null) envelope.put("expires", expires)
                            val existingKeys = sessionValues?.keys
                                ?: prefs!!.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
                            require(key in existingKeys || existingKeys.size < 500) {
                                "Extension storage exceeded the 500-key limit."
                            }
                            val currentSize = sessionValues?.values?.sumOf { it.length }
                                ?: prefs!!.all.entries.filter { it.key.startsWith(prefix) }
                                    .sumOf { it.value.toString().length }
                            val previousSize = if (sessionValues != null) {
                                sessionValues[key]?.length ?: 0
                            } else {
                                prefs!!.getString(prefix + key, null)?.length ?: 0
                            }
                            require(currentSize - previousSize + envelope.toString().length <= 2_097_152) {
                                "Extension storage exceeded the 2 MB limit."
                            }
                            if (sessionValues != null) sessionValues[key] = envelope.toString()
                            else prefs!!.edit().putString(prefix + key, envelope.toString()).apply()
                            "null"
                        }
                        "get" -> {
                            require(key.length <= 500) { "Extension storage key is too long." }
                            val stored = sessionValues?.get(key) ?: prefs?.getString(prefix + key, null)
                            if (stored == null) "null" else {
                                val envelope = JSONObject(stored)
                                val expiry = envelope.optLong("expires", 0L)
                                if (expiry > 0L && System.currentTimeMillis() >= expiry) {
                                    if (sessionValues != null) sessionValues.remove(key)
                                    else prefs!!.edit().remove(prefix + key).apply()
                                    "null"
                                } else if (raw) envelope.toString()
                                else {
                                    val value = envelope.opt("value")
                                    if (value == null) "null"
                                    else if (value is JSONObject || value is JSONArray) value.toString()
                                    else if (value is String) JSONObject.quote(value)
                                    else value.toString()
                                }
                            }
                        }
                        "delete" -> {
                            if (sessionValues != null) sessionValues.remove(key)
                            else prefs!!.edit().remove(prefix + key).apply()
                            "null"
                        }
                        "clearAll" -> {
                            if (sessionValues != null) sessionValues.clear()
                            else prefs!!.all.keys.filter { it.startsWith(prefix) }
                                .forEach { prefs!!.edit().remove(it).apply() }
                            "null"
                        }
                        "getAllKeys" -> {
                            val keys = sessionValues?.keys?.toList()
                                ?: prefs!!.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
                            JSONArray(keys).toString()
                        }
                        else -> throw IllegalArgumentException("Unsupported extension storage operation.")
                    }
                    return output
                }
            }
            storageFunction.parentScope = scope
            storageFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnStorage", storageFunction)
            val aesFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val mode = args.getOrNull(0)?.toString().orEmpty()
                    require(mode == "encrypt" || mode == "decrypt") { "Unsupported AES-GCM operation." }
                    val key = jsByteArray(args.getOrNull(1))
                    val nonce = jsByteArray(args.getOrNull(2))
                    val aadValue = args.getOrNull(3)
                    val aad = if (aadValue == null || aadValue.toString() == "null") {
                        null
                    } else {
                        jsByteArray(aadValue)
                    }
                    val input = jsByteArray(args.getOrNull(4))
                    require(key.size == 16 || key.size == 24 || key.size == 32) {
                        "AES-GCM key must be 128, 192, or 256 bits."
                    }
                    require(nonce.isNotEmpty() && nonce.size <= 32) { "AES-GCM nonce length is invalid." }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        if (mode == "encrypt") Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        GCMParameterSpec(128, nonce),
                    )
                    if (aad != null) cipher.updateAAD(aad)
                    val output = cipher.doFinal(input)
                    return context.newArray(
                        callScope,
                        output.map { (it.toInt() and 0xff) as Any }.toTypedArray(),
                    )
                }
            }
            aesFunction.parentScope = scope
            aesFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnAesGcm", aesFunction)
            val compatibilityBootstrap = """
                (function() {
                  // Rhino does not provide the browser/WHATWG URL global. LNReader bundles
                  // commonly use new URL(path, base) while resolving links and API endpoints.
                  if (typeof this.URL === 'undefined') {
                    function QNURLSearchParams(query, owner) {
                      this._pairs = [];
                      this._owner = owner || null;
                      // The WHATWG constructor accepts strings, iterable pairs, and
                      // plain records. LNReader multisource plugins commonly pass a
                      // record here for PHP chapter-archive POST bodies, e.g.
                      // new URLSearchParams({ novelId: id, acode: code }).
                      if (query && typeof query === 'object') {
                        if (query instanceof QNURLSearchParams) {
                          this._pairs = query._pairs.map(function(pair) { return [pair[0], pair[1]]; });
                        } else if (Array.isArray(query)) {
                          query.forEach(function(pair) {
                            if (Array.isArray(pair) && pair.length >= 2) {
                              this._pairs.push([String(pair[0]), String(pair[1])]);
                            }
                          }, this);
                        } else {
                          Object.keys(query).forEach(function(key) {
                            var value = query[key];
                            if (Array.isArray(value)) {
                              value.forEach(function(entry) {
                                this._pairs.push([key, String(entry)]);
                              }, this);
                            } else {
                              this._pairs.push([key, String(value)]);
                            }
                          }, this);
                        }
                        return;
                      }
                      var raw = String(query || '').replace(/^\?/, '');
                      if (raw) raw.split('&').forEach(function(part) {
                        if (!part) return;
                        var at = part.indexOf('=');
                        var key = at < 0 ? part : part.slice(0, at);
                        var value = at < 0 ? '' : part.slice(at + 1);
                        try { key = decodeURIComponent(key.replace(/\+/g, ' ')); } catch (_) {}
                        try { value = decodeURIComponent(value.replace(/\+/g, ' ')); } catch (_) {}
                        this._pairs.push([key, value]);
                      }, this);
                    }
                    QNURLSearchParams.prototype._sync = function() {
                      if (this._owner) this._owner.search = this.toString() ? '?' + this.toString() : '';
                    };
                    QNURLSearchParams.prototype.get = function(key) {
                      key = String(key);
                      for (var i=0;i<this._pairs.length;i++) if (this._pairs[i][0] === key) return this._pairs[i][1];
                      return null;
                    };
                    QNURLSearchParams.prototype.getAll = function(key) {
                      key=String(key); return this._pairs.filter(function(p){return p[0]===key;}).map(function(p){return p[1];});
                    };
                    QNURLSearchParams.prototype.has = function(key) { return this.get(key) !== null; };
                    QNURLSearchParams.prototype.append = function(k,v) { this._pairs.push([String(k),String(v)]); this._sync(); };
                    QNURLSearchParams.prototype.set = function(k,v) {
                      k=String(k); var first=true;
                      this._pairs=this._pairs.filter(function(p){if(p[0]!==k)return true;if(first){first=false;return false;}return false;});
                      this._pairs.push([k,String(v)]); this._sync();
                    };
                    QNURLSearchParams.prototype.delete = function(k) { k=String(k); this._pairs=this._pairs.filter(function(p){return p[0]!==k;}); this._sync(); };
                    QNURLSearchParams.prototype.toString = function() {
                      return this._pairs.map(function(p){return encodeURIComponent(p[0]).replace(/%20/g,'+')+'='+encodeURIComponent(p[1]).replace(/%20/g,'+');}).join('&');
                    };
                    function QNURL(input, base) {
                      if (!(this instanceof QNURL)) throw new TypeError("URL constructor must be called with 'new'");
                      var value=String(input), baseValue=base == null ? '' : (base instanceof QNURL ? base.href : String(base));
                      if (!/^[a-z][a-z0-9+.-]*:/i.test(value)) {
                        if (!baseValue) throw new TypeError('Invalid URL');
                        var bm=baseValue.match(/^([a-z][a-z0-9+.-]*:)\/\/([^\/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/i);
                        if (!bm) throw new TypeError('Invalid base URL');
                        if (value.indexOf('//')===0) value=bm[1]+value;
                        else if (value.charAt(0)==='#') value=bm[1]+'//'+bm[2]+(bm[3]||'/')+(bm[4]||'')+value;
                        else if (value.charAt(0)==='?') value=bm[1]+'//'+bm[2]+(bm[3]||'/')+value;
                        else {
                          var path=value.split(/[?#]/)[0], suffix=value.slice(path.length);
                          var joined=path.charAt(0)==='/'?path:((bm[3]||'/').replace(/[^/]*$/,'')+path);
                          var stack=[];
                          joined.split('/').forEach(function(part){if(part==='..')stack.pop();else if(part!=='.')stack.push(part);});
                          var normalized=stack.join('/'); if(normalized.charAt(0)!=='/')normalized='/'+normalized;
                          value=bm[1]+'//'+bm[2]+normalized+suffix;
                        }
                      }
                      var m=value.match(/^([a-z][a-z0-9+.-]*:)\/\/([^\/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/i);
                      if(!m) throw new TypeError('Invalid URL: '+value);
                      var authority=m[2], at=authority.lastIndexOf('@'), hostpart=at>=0?authority.slice(at+1):authority;
                      var colon=hostpart.lastIndexOf(':'), hostname=hostpart, port='';
                      if(colon>0 && /^\d+$/.test(hostpart.slice(colon+1))){hostname=hostpart.slice(0,colon);port=hostpart.slice(colon+1);}
                      this.protocol=m[1].toLowerCase(); this.username=at>=0?authority.slice(0,at).split(':')[0]:''; this.password=at>=0&&authority.slice(0,at).indexOf(':')>=0?authority.slice(0,at).split(':').slice(1).join(':'):'';
                      this.hostname=hostname.toLowerCase(); this.port=port; this.host=this.hostname+(port?':'+port:'');
                      this.pathname=m[3]||'/'; this.search=m[4]||''; this.hash=m[5]||'';
                      this.origin=this.protocol+'//'+this.host; this.searchParams=new QNURLSearchParams(this.search, this);
                    }
                    Object.defineProperty(QNURL.prototype,'href',{get:function(){var q=this.searchParams.toString();return this.origin+this.pathname+(q?'?'+q:'')+(this.hash||'');},set:function(v){var u=new QNURL(v);var self=this;['protocol','username','password','hostname','port','host','pathname','search','hash','origin','searchParams'].forEach(function(k){self[k]=u[k];});}});
                    QNURL.prototype.toString=function(){return this.href;};
                    QNURL.prototype.toJSON=function(){return this.href;};
                    this.URL=QNURL;
                    this.URLSearchParams=QNURLSearchParams;
                  }
                  // LNReader multisrc plugins use FormData for PHP/AJAX chapter-list
                  // endpoints. Keep this shim string-only (no file/blob uploads), then
                  // serialize it to a bounded multipart request in fetchApi below.
                  function QNFormData() {
                    this.__qnFormData = true;
                    this._entries = [];
                  }
                  QNFormData.prototype.append = function(name, value) {
                    this._entries.push([String(name), String(value)]);
                  };
                  QNFormData.prototype.set = function(name, value) {
                    name = String(name);
                    this.delete(name);
                    this.append(name, value);
                  };
                  QNFormData.prototype.get = function(name) {
                    name = String(name);
                    for (var i = 0; i < this._entries.length; i++)
                      if (this._entries[i][0] === name) return this._entries[i][1];
                    return null;
                  };
                  QNFormData.prototype.getAll = function(name) {
                    name = String(name);
                    return this._entries.filter(function(entry) { return entry[0] === name; })
                      .map(function(entry) { return entry[1]; });
                  };
                  QNFormData.prototype.has = function(name) {
                    return this.get(name) !== null;
                  };
                  QNFormData.prototype.delete = function(name) {
                    name = String(name);
                    this._entries = this._entries.filter(function(entry) { return entry[0] !== name; });
                  };
                  QNFormData.prototype.forEach = function(callback, thisArg) {
                    this._entries.forEach(function(entry) {
                      callback.call(thisArg, entry[1], entry[0], this);
                    }, this);
                  };
                  this.FormData = QNFormData;

                  // LNReader plugins use timers for retry delays and cooperative yields.
                  // Queue callbacks and drain them after the plugin operation starts. This
                  // avoids recursive async-generator continuation when a plugin yields with
                  // setTimeout(0), while retaining the bridge's deterministic sync contract.
                  var qnTimerSequence = 0;
                  var qnTimerQueue = [];
                  function qnScheduleTimer(callback, delay, args) {
                    if (typeof callback !== 'function') throw new TypeError('Timer callback must be a function');
                    var requestedDelay = Number(delay == null ? 0 : delay);
                    if (!isFinite(requestedDelay) || requestedDelay < 0 || requestedDelay > 10000)
                      throw new Error('Extension timer delay exceeds the 10 second limit.');
                    var id = ++qnTimerSequence;
                    qnTimerQueue.push({id:id, callback:callback, delay:requestedDelay, args:args});
                    return id;
                  }
                  this.setTimeout = function(callback, delay) {
                    return qnScheduleTimer(callback, delay, Array.prototype.slice.call(arguments, 2));
                  };
                  this.clearTimeout = function(id) {
                    qnTimerQueue = qnTimerQueue.filter(function(timer) { return timer.id !== id; });
                  };
                  this.setImmediate = function(callback) {
                    return qnScheduleTimer(callback, 0, Array.prototype.slice.call(arguments, 1));
                  };
                  this.clearImmediate = this.clearTimeout;
                  this.__qnRunTimers = function() {
                    var executed = 0;
                    while (qnTimerQueue.length) {
                      if (++executed > 10000)
                        throw new Error('Extension exceeded the 10000 timer-callback limit.');
                      var timer = qnTimerQueue.shift();
                      __qnDelay(timer.delay);
                      timer.callback.apply(undefined, timer.args);
                    }
                  };
                  this.console = {
                    log: function() {}, info: function() {},
                    warn: function() {}, error: function() {}
                  };

                  function QNPromise(executor) {
                    var self = this;
                    self.state = 0; self.value = void 0; self.handlers = [];
                    function settle(state, value) {
                      if (self.state !== 0) return;
                      if (value === self) return settle(2, new TypeError('Promise cycle'));
                      if (value && typeof value.then === 'function') {
                        try { return value.then(resolve, reject); } catch (e) { return reject(e); }
                      }
                      self.state = state; self.value = value;
                      self.handlers.forEach(run);
                    }
                    function resolve(value) { settle(1, value); }
                    function reject(error) { settle(2, error); }
                    function run(handler) {
                      var callback = self.state === 1 ? handler.ok : handler.fail;
                      if (typeof callback !== 'function') {
                        (self.state === 1 ? handler.resolve : handler.reject)(self.value); return;
                      }
                      try { handler.resolve(callback(self.value)); } catch (e) { handler.reject(e); }
                    }
                    self.then = function(ok, fail) {
                      return new QNPromise(function(resolveNext, rejectNext) {
                        var handler = {ok:ok, fail:fail, resolve:resolveNext, reject:rejectNext};
                        if (self.state === 0) self.handlers.push(handler); else run(handler);
                      });
                    };
                    self.catch = function(fail) { return self.then(null, fail); };
                    try { executor(resolve, reject); } catch (e) { reject(e); }
                  }
                  QNPromise.resolve = function(value) {
                    return new QNPromise(function(resolve) { resolve(value); });
                  };
                  QNPromise.reject = function(error) {
                    return new QNPromise(function(resolve, reject) { reject(error); });
                  };
                  QNPromise.all = function(values) {
                    return new QNPromise(function(resolve, reject) {
                      if (!values || typeof values.length !== 'number')
                        return reject(new TypeError('Promise.all expects an array-like value'));
                      var length = values.length, remaining = length, results = [];
                      if (length === 0) return resolve(results);
                      for (var i = 0; i < length; i++) (function(index) {
                        QNPromise.resolve(values[index]).then(function(value) {
                          results[index] = value;
                          remaining--;
                          if (remaining === 0) resolve(results);
                        }, reject);
                      })(i);
                    });
                  };
                  this.Promise = QNPromise;
                  this.require = function(name) {
                    if (name === '@libs/fetch') {
                      var fetchModule = {
                      fetchApi: function(url, init, responseEncoding) {
                        var method = init && init.method ? String(init.method).toUpperCase() : 'GET';
                        if (method !== 'GET' && method !== 'POST')
                          throw new Error('Only GET and POST requests are supported by this extension bridge.');
                        var headers = init && init.headers ? init.headers : {};
                        if (headers && typeof headers.forEach === 'function') {
                          var normalized = {};
                          headers.forEach(function(value, key) { normalized[key] = value; });
                          headers = normalized;
                        }
                        var request = {method:method, headers:headers};
                        if (init && init.body != null) {
                          if (typeof init.body === 'string') {
                            request.body = init.body;
                          } else if (init.body && init.body.__qnFormData === true &&
                              Array.isArray(init.body._entries)) {
                            var boundary = '----QuickNovelFormBoundary' +
                              String(Math.floor(Math.random() * 10000000000000000));
                            var parts = init.body._entries.map(function(entry) {
                              var fieldName = String(entry[0]).replace(/"/g, '%22');
                              return '--' + boundary + '\r\n' +
                                'Content-Disposition: form-data; name="' + fieldName + '"\r\n\r\n' +
                                String(entry[1]) + '\r\n';
                            });
                            request.body = parts.join('') + '--' + boundary + '--\r\n';
                            var hasContentType = Object.keys(request.headers).some(function(key) {
                              return key.toLowerCase() === 'content-type';
                            });
                            if (!hasContentType) {
                              request.headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary;
                            }
                          } else {
                            throw new Error('Only string and simple FormData request bodies are supported by this extension bridge.');
                          }
                        }
                        return Promise.resolve(JSON.parse(__qnFetch(String(url), JSON.stringify(request), responseEncoding))).then(function(r) {
                          var responseHeaders = r.headers || {};
                          return {
                            url: r.url, status: r.statusCode, ok: r.statusCode >= 200 && r.statusCode < 300,
                            statusText: r.statusText || String(r.statusCode), body: r.body,
                            text: function() { return Promise.resolve(r.body); },
                            json: function() { return Promise.resolve(JSON.parse(r.body)); },
                            headers: {
                              get: function(key) {
                                var normalized = String(key).toLowerCase();
                                return responseHeaders[normalized] ||
                                  (normalized === 'content-type' ? r.contentType : null);
                              },
                              has: function(key) {
                                return this.get(key) != null;
                              },
                              forEach: function(callback) {
                                Object.keys(responseHeaders).forEach(function(key) {
                                  callback(responseHeaders[key], key);
                                });
                              }
                            }
                          };
                        });
                      },
                      fetchText: function(url, init, encoding) {
                        try {
                          return fetchModule.fetchApi(url, init, encoding).then(function(response) {
                            return response.ok ? response.text() : '';
                          }).catch(function() { return ''; });
                        } catch (error) {
                          return Promise.resolve('');
                        }
                      },
                      fetchFile: function(url, init) {
                        try {
                          var method = init && init.method ? String(init.method).toUpperCase() : 'GET';
                          if (method !== 'GET') throw new Error('fetchFile currently supports GET requests only.');
                          var headers = init && init.headers ? init.headers : {};
                          if (headers && typeof headers.forEach === 'function') {
                            var normalized = {};
                            headers.forEach(function(value, key) { normalized[key] = value; });
                            headers = normalized;
                          }
                          var request = {method:method,headers:headers};
                          return Promise.resolve(JSON.parse(
                            __qnFetch(String(url), JSON.stringify(request), null, 'base64')
                          )).then(function(response) {
                            return response.statusCode >= 200 && response.statusCode < 300
                              ? (response.bodyBase64 || '') : '';
                          }).catch(function() { return ''; });
                        } catch (error) {
                          return Promise.resolve('');
                        }
                      },
                      fetchProto: function() {
                        throw new Error('LNReader fetchProto requires protobufjs and binary POST support, which are not supported by this QuickNovel runtime yet.');
                      }
                    };
                    fetchModule.default = fetchModule;
                    return fetchModule;
                    }
                    if (name === 'cheerio') {
                      function call(command, id, value, index) {
                        return JSON.parse(__qnHtml(command, id, value == null ? '' : String(value), index == null ? '' : index));
                      }
                      function wrap(id) {
                        var selection = {_qnSelectionId:id};
                        Object.defineProperty(selection, 'length', {get:function(){return call('length',id);}});
                        selection.text = function(){return call('text',id);};
                        selection.html = function(){return call('html',id);};
                        selection.attr = function(name,value){
                          if (arguments.length > 1) { call('attrSet',id,JSON.stringify([name,value])); return selection; }
                          return call('attr',id,name);
                        };
                        selection.hasClass = function(name){return call('hasClass',id,name);};
                        selection.first = function(){return wrap(call('first',id));};
                        selection.last = function(){return wrap(call('last',id));};
                        selection.eq = function(index){return wrap(call('eq',id,'',index));};
                        selection.find = function(selector){
                          var found=wrap(call('find',id,selector)); found._qnPrevId=id; return found;
                        };
                        selection.children = function(selector){return wrap(call('children',id,selector || ''));};
                        selection.contents = function(){return wrap(call('contents',id));};
                        selection.parent = function(){return wrap(call('parent',id));};
                        selection.next = function(){return wrap(call('next',id));};
                        selection.prev = function(){return wrap(call('prev',id));};
                        selection.nextUntil = function(selector){
                          return wrap(call('nextUntil',id,String(selector || '*')));
                        };
                        selection.toArray = function(){return selection.get();};
                        selection.addBack = function(){
                          return selection._qnPrevId == null ? selection :
                            wrap(call('merge',id,JSON.stringify([id,selection._qnPrevId])));
                        };
                        selection.filter = function(predicate){
                          if (typeof predicate === 'function') {
                            var ids=[];
                            for (var i=0;i<selection.length;i++) {
                              var item=wrapNode(call('eq',id,'',i));
                              if (predicate.call(item,i,item)) ids.push(item._qnSelectionId);
                            }
                            return wrap(call('merge',id,JSON.stringify(ids)));
                          }
                          return wrap(call('filter',id,String(predicate)));
                        };
                        selection.has = function(selector){return wrap(call('has',id,String(selector)));};
                        selection.removeAttr = function(name){call('removeAttr',id,name);return selection;};
                        selection.replaceWith = function(content){
                          var html=content && content._qnSelectionId != null
                            ? call('outerHtml',content._qnSelectionId) : String(content == null ? '' : content);
                          call('replaceWith',id,html); return selection;
                        };
                        selection.before = function(content){call('before',id,String(content == null ? '' : content));return selection;};
                        selection.after = function(content){call('after',id,String(content == null ? '' : content));return selection;};
                        selection.append = function(content){call('append',id,String(content == null ? '' : content));return selection;};
                        selection.remove = function(){call('remove',id);return selection;};
                        selection.each = function(callback) {
                          for (var i=0;i<selection.length;i++) {
                            var item=wrapNode(call('eq',id,'',i));
                            callback.call(item,i,item);
                          }
                          return selection;
                        };
                        selection.map = function(callback) {
                          var values=[];
                          for (var i=0;i<selection.length;i++) {
                            var item=wrapNode(call('eq',id,'',i));
                            values.push(callback.call(item,i,item));
                          }
                          return {get:function(){return values;},toArray:function(){return values;}};
                        };
                        selection.get = function(index) {
                          if (index == null) { var all=[]; for(var i=0;i<selection.length;i++) all.push(wrapNode(call('eq',id,'',i))); return all; }
                          return wrapNode(call('eq',id,'',index));
                        };
                        return selection;
                      }
                      function wrapNode(id) {
                        var node = wrap(id);
                        Object.defineProperty(node, 'type', {get:function(){return call('nodeType',id);}});
                        Object.defineProperty(node, 'nodeType', {get:function(){
                          var type=call('nodeType',id);
                          return type==='tag'?1:type==='text'?3:type==='comment'?8:9;
                        }});
                        Object.defineProperty(node, 'name', {get:function(){return call('nodeName',id);}});
                        Object.defineProperty(node, 'data', {get:function(){return call('nodeData',id);}});
                        Object.defineProperty(node, 'next', {get:function(){
                          var nextId = call('nextNode',id);
                          return nextId == null ? null : wrapNode(nextId);
                        }});
                        return node;
                      }
                      function load(html) {
                        var root = call('load',0,String(html == null ? '' : html));
                        function $(selector) {
                          if (selector && selector._qnSelectionId != null) return selector;
                          return wrap(call('select',root,typeof selector === 'string' ? selector : '*'));
                        }
                        $.root = function(){return wrap(root);};
                        $.html = function(){return call('html',root);};
                        $.text = function(){return call('text',root);};
                        return $;
                      }
                      return {load:load,default:{load:load}};
                    }
                    if (name === 'htmlparser2') {
                      function Parser(handlers, options) {
                        this.handlers = handlers || {};
                        this.source = '';
                      }
                      // LNReader multisrc bundles use the htmlparser2 Parser instance
                      // method while rebuilding chapter HTML. Keep this list aligned with
                      // HTML's void elements: they have no closing tag and must not emit one.
                      var voidElements = {
                        area:true, base:true, br:true, col:true, embed:true, hr:true,
                        img:true, input:true, link:true, meta:true, param:true,
                        source:true, track:true, wbr:true
                      };
                      Parser.prototype.isVoidElement = function(name) {
                        return Object.prototype.hasOwnProperty.call(
                          voidElements, String(name == null ? '' : name).toLowerCase()
                        );
                      };
                      Parser.prototype.write = function(chunk) {
                        this.source += String(chunk == null ? '' : chunk);
                        return this;
                      };
                      Parser.prototype.end = function(chunk) {
                        if (chunk != null) this.source += String(chunk);
                        var events = JSON.parse(__qnHtmlEvents(this.source));
                        for (var i=0;i<events.length;i++) {
                          var event = events[i];
                          var handlerName = event.type === 'open' ? 'onopentag' :
                            event.type === 'text' ? 'ontext' : 'onclosetag';
                          var handler = this.handlers[handlerName];
                          if (typeof handler !== 'function') continue;
                          if (event.type === 'open') handler.call(this.handlers,event.name,event.attribs);
                          else if (event.type === 'text') handler.call(this.handlers,event.text);
                          else if (event.type === 'close') handler.call(this.handlers,event.name);
                        }
                        this.source = '';
                        return this;
                      };
                      return {Parser:Parser,default:{Parser:Parser}};
                    }
                    if (name === '@libs/storage') {
                      function call(command, key, value, expires, raw, area) {
                        return JSON.parse(__qnStorage(command, String(key == null ? '' : key),
                          value == null ? '' : String(value), expires == null ? '' : String(expires),
                          raw ? 'true' : 'false', area || ''));
                      }
                      var storage = {
                        set: function(key, value, expires) {
                          var expiry = expires instanceof Date ? expires.getTime() : expires;
                          return call('set', key, JSON.stringify(value), expiry, false);
                        },
                        get: function(key, raw) {
                          var value = call('get', key, '', '', raw);
                          return value === null ? undefined : value;
                        },
                        delete: function(key) { return call('delete', key); },
                        clearAll: function() { return call('clearAll'); },
                        getAllKeys: function() { return call('getAllKeys'); }
                      };
                      function createWebStorage(area) {
                        var webStorage = {
                          getItem: function(key) {
                            var value = call('get', key, '', '', false, area);
                            return value == null ? null : String(value);
                          },
                          setItem: function(key, value) {
                            call('set', key, JSON.stringify(String(value)), '', false, area);
                          },
                          removeItem: function(key) { call('delete', key, '', '', false, area); },
                          clear: function() { call('clearAll', '', '', '', false, area); },
                          key: function(index) {
                            var keys = call('getAllKeys', '', '', '', false, area);
                            return keys[index] == null ? null : keys[index];
                          },
                          get: function(key) {
                            var value = call('get', key, '', '', false, area);
                            return value == null ? undefined : value;
                          },
                          set: function(key, value) {
                            call('set', key, JSON.stringify(String(value)), '', false, area);
                          }
                        };
                        Object.defineProperty(webStorage, 'length', {
                          get: function() { return call('getAllKeys', '', '', '', false, area).length; }
                        });
                        return webStorage;
                      }
                      return {
                        storage: storage,
                        localStorage: createWebStorage('local'),
                        sessionStorage: createWebStorage('session')
                      };
                    }
                    if (name === 'dayjs') {
                      var months = ['January','February','March','April','May','June','July','August','September','October','November','December'];
                      var shortMonths = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
                      function parseDate(input, format) {
                        if (input == null || input === '') return input == null ? new Date() : new Date(NaN);
                        if (input instanceof Date) return new Date(input.getTime());
                        if (typeof input === 'number') return new Date(input);
                        var text = String(input);
                        if (format === 'DD.MM.YYYY') {
                          var match = text.match(/^(\d{1,2})\.(\d{1,2})\.(\d{4})$/);
                          if (!match) return new Date(NaN);
                          return new Date(Number(match[3]), Number(match[2])-1, Number(match[1]));
                        }
                        return new Date(text);
                      }
                      function makeDate(date) {
                        function valid() { return !isNaN(date.getTime()); }
                        function pad(value) { return value < 10 ? '0' + value : String(value); }
                        function unitName(unit) {
                          var original = String(unit || 'millisecond');
                          if (original === 'M' || original.toLowerCase().indexOf('month') === 0) return 'month';
                          unit = original.toLowerCase();
                          if (unit === 'd' || unit.indexOf('day') === 0) return 'day';
                          if (unit === 'h' || unit.indexOf('hour') === 0) return 'hour';
                          if (unit === 'm' || unit.indexOf('minute') === 0) return 'minute';
                          if (unit === 's' || unit.indexOf('second') === 0) return 'second';
                          if (unit === 'w' || unit.indexOf('week') === 0) return 'week';
                          if (unit === 'y' || unit.indexOf('year') === 0) return 'year';
                          return 'millisecond';
                        }
                        var api = {
                          isValid: valid,
                          valueOf: function() { return date.getTime(); },
                          unix: function() { return Math.floor(date.getTime()/1000); },
                          toDate: function() { return new Date(date.getTime()); },
                          toISOString: function() { return valid() ? date.toISOString() : null; },
                          date: function() { return date.getDate(); },
                          month: function() { return date.getMonth(); },
                          year: function() { return date.getFullYear(); },
                          format: function(pattern) {
                            if (!valid()) return 'Invalid Date';
                            pattern = pattern || 'YYYY-MM-DDTHH:mm:ssZ';
                            if (pattern === 'LL') pattern = 'MMMM D, YYYY';
                            if (pattern === 'LLL') pattern = 'MMMM D, YYYY h:mm A';
                            var hour = date.getHours(), offset = -date.getTimezoneOffset();
                            var zone = (offset >= 0 ? '+' : '-') + pad(Math.floor(Math.abs(offset)/60)) + ':' + pad(Math.abs(offset)%60);
                            var values = {
                              YYYY:String(date.getFullYear()), YY:String(date.getFullYear()).slice(-2),
                              MMMM:months[date.getMonth()], MMM:shortMonths[date.getMonth()],
                              MM:pad(date.getMonth()+1), M:String(date.getMonth()+1),
                              DD:pad(date.getDate()), D:String(date.getDate()),
                              HH:pad(hour), H:String(hour), hh:pad(hour%12 || 12), h:String(hour%12 || 12),
                              mm:pad(date.getMinutes()), m:String(date.getMinutes()),
                              ss:pad(date.getSeconds()), s:String(date.getSeconds()),
                              A:hour < 12 ? 'AM' : 'PM', a:hour < 12 ? 'am' : 'pm', Z:zone
                            };
                            return pattern.replace(/YYYY|MMMM|MMM|YY|MM|M|DD|D|HH|H|hh|h|mm|m|ss|s|A|a|Z/g, function(token) { return values[token]; });
                          },
                          add: function(amount, unit) {
                            var copy = new Date(date.getTime()), n = Number(amount), u = unitName(unit);
                            if (u === 'year' || u === 'month') {
                              // Day.js clamps calendar arithmetic to the last valid day
                              // (e.g. Jan 31 + 1 month becomes Feb 28/29, not March).
                              var monthsToAdd = Math.round(u === 'year' ? n * 12 : n);
                              var originalDay = copy.getDate();
                              copy.setDate(1);
                              copy.setMonth(copy.getMonth()+monthsToAdd);
                              var lastDay = new Date(copy.getFullYear(), copy.getMonth()+1, 0).getDate();
                              copy.setDate(Math.min(originalDay,lastDay));
                            }
                            else if (u === 'week') copy.setDate(copy.getDate()+n*7);
                            else if (u === 'day') copy.setDate(copy.getDate()+n);
                            else if (u === 'hour') copy.setHours(copy.getHours()+n);
                            else if (u === 'minute') copy.setMinutes(copy.getMinutes()+n);
                            else if (u === 'second') copy.setSeconds(copy.getSeconds()+n);
                            else copy.setTime(copy.getTime()+n);
                            return makeDate(copy);
                          },
                          subtract: function(amount, unit) { return api.add(-Number(amount), unit); },
                          startOf: function(unit) {
                            var copy = new Date(date.getTime()), u = unitName(unit);
                            if (u === 'year') { copy.setMonth(0); copy.setDate(1); copy.setHours(0,0,0,0); }
                            else if (u === 'month') { copy.setDate(1); copy.setHours(0,0,0,0); }
                            else if (u === 'week') {
                              copy.setDate(copy.getDate()-copy.getDay());
                              copy.setHours(0,0,0,0);
                            }
                            else if (u === 'day') copy.setHours(0,0,0,0);
                            else if (u === 'hour') copy.setMinutes(0,0,0);
                            else if (u === 'minute') copy.setSeconds(0,0);
                            else if (u === 'second') copy.setMilliseconds(0);
                            return makeDate(copy);
                          },
                          endOf: function(unit) {
                            var u = unitName(unit), next = api.startOf(unit).add(1, unit);
                            return makeDate(new Date(next.valueOf()-1));
                          },
                          diff: function(other, unit) {
                            var otherDate = parseDate(other), delta = date.getTime() - otherDate.getTime(), u = unitName(unit);
                            if (u === 'month' || u === 'year') {
                              var months = (date.getFullYear()-otherDate.getFullYear())*12 +
                                (date.getMonth()-otherDate.getMonth());
                              var candidate = new Date(otherDate.getTime());
                              var originalDay = candidate.getDate();
                              candidate.setDate(1);
                              candidate.setMonth(candidate.getMonth()+months);
                              var lastDay = new Date(candidate.getFullYear(), candidate.getMonth()+1, 0).getDate();
                              candidate.setDate(Math.min(originalDay,lastDay));
                              if (delta >= 0 && candidate.getTime() > date.getTime()) months--;
                              else if (delta < 0 && candidate.getTime() < date.getTime()) months++;
                              return Math.trunc(u === 'year' ? months/12 : months);
                            }
                            var divisor = u === 'week' ? 604800000 : u === 'day' ? 86400000 : u === 'hour' ? 3600000 : u === 'minute' ? 60000 : u === 'second' ? 1000 : 1;
                            return Math.trunc(delta/divisor);
                          },
                          isBefore: function(other) { return date.getTime() < parseDate(other).getTime(); },
                          isAfter: function(other) { return date.getTime() > parseDate(other).getTime(); },
                          isSame: function(other) { return date.getTime() === parseDate(other).getTime(); }
                        };
                        return api;
                      }
                      function dayjs(input, format) { return makeDate(parseDate(input, format)); }
                      dayjs.default = dayjs;
                      return dayjs;
                    }
                    if (name === '@libs/isAbsoluteUrl') return {
                      isUrlAbsolute: function(url) { return /^https?:\/\//i.test(String(url)); }
                    };
                    if (name === 'urlencode') {
                      var urlencode = {
                        encode: function(value) { return encodeURIComponent(String(value)); },
                        decode: function(value) { return decodeURIComponent(String(value)); }
                      };
                      urlencode.default = urlencode;
                      return urlencode;
                    }
                    if (name === '@libs/utils') {
                      function utf8ToBytes(value) {
                        var encoded = unescape(encodeURIComponent(String(value)));
                        var bytes = [];
                        for (var i = 0; i < encoded.length; i++) bytes.push(encoded.charCodeAt(i) & 255);
                        return bytes;
                      }
                      function bytesToUtf8(value) {
                        var encoded = '';
                        for (var i = 0; i < value.length; i++) {
                          var byte = Number(value[i]) & 255;
                          encoded += '%' + (byte < 16 ? '0' : '') + byte.toString(16);
                        }
                        return decodeURIComponent(encoded);
                      }
                      return {utf8ToBytes:utf8ToBytes, bytesToUtf8:bytesToUtf8};
                    }
                    if (name === '@libs/aes') {
                      function gcm(key, nonce, aad) {
                        return {
                          encrypt: function(plaintext) { return __qnAesGcm('encrypt', key, nonce, aad == null ? null : aad, plaintext); },
                          decrypt: function(ciphertext) { return __qnAesGcm('decrypt', key, nonce, aad == null ? null : aad, ciphertext); }
                        };
                      }
                      return {gcm:gcm};
                    }
                    if (name === '@libs/defaultCover') return {defaultCover: ''};
                    // Published NovelFire-family bundles still import this historical alias.
                    if (name === '@/types/constants') return {defaultCover: ''};
                    if (name === '@libs/novelStatus') return {
                      NovelStatus: {
                        Unknown:'Unknown', Ongoing:'Ongoing', Completed:'Completed',
                        Licensed:'Licensed', PublishingFinished:'Publishing Finished',
                        Cancelled:'Cancelled', OnHiatus:'On Hiatus', STUB:'STUB',
                        Inactive:'Inactive',
                        // Backwards-compatible aliases used by older published bundles.
                        Paused:'On Hiatus', Dropped:'Cancelled'
                      }
                    };
                    if (name === '@libs/filterInputs') return {
                      FilterTypes: {
                        Picker:'Picker', CheckboxGroup:'Checkbox',
                        ExcludableCheckboxGroup:'XCheckbox',
                        Switch:'Switch', TextInput:'Text', Input:'Text'
                      }
                    };
                    throw new Error("Unsupported LNReader module import: " + name);
                  };
                }).call(this);
            """.trimIndent()
            cx.evaluateString(scope, compatibilityBootstrap, "qn-compatibility.js", 1, null)


            cx.evaluateString(scope, source, "extension-${extension.id}.js", 1, null)
            ScriptableObject.putProperty(scope, "__qnOperation", operation)
            ScriptableObject.putProperty(scope, "__qnArgumentsJson", arguments.toString())
            val output = if (operation == "transformChapterHtml") {
                val html = arguments.optString(0)
                require(html.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) {
                    "Custom JavaScript input HTML exceeds the 2 MB limit."
                }
                ScriptableObject.putProperty(scope, "__qnTransformHtml", html)
                cx.evaluateString(
                    scope,
                    "var \$ = require('cheerio').load(__qnTransformHtml); var loadedCheerio = \$;",
                    "extension-custom-dom.js",
                    1,
                    null,
                )
                if (!customScript.isNullOrBlank()) {
                    cx.evaluateString(scope, customScript, "extension-custom.js", 1, null)
                }
                val transformed = cx.evaluateString(scope, "\$.html()", "extension-custom-result.js", 1, null)
                JSONObject.quote(Context.toString(transformed))
            } else {
                val invocation = """
                (function() {
                  var exported = module.exports;
                  var plugin = exported && (exported.default || exported);
                  if (!plugin) throw new Error('Bundle does not export a plugin object.');
                  var args = JSON.parse(__qnArgumentsJson);

                  if (__qnOperation === 'getImageRequestInit') {
                    return JSON.stringify(plugin.imageRequestInit || {});
                  }

                  if (__qnOperation === 'getPluginFilters') {
                    var filterDefinitions = plugin.filters || {};
                    return JSON.stringify({
                      filters: filterDefinitions,
                      order: Object.keys(filterDefinitions)
                    });
                  }

                  if (__qnOperation === 'getPluginSettings') {
                    var definitions = plugin.pluginSettings || {};
                    var settingsStore = require('@libs/storage').storage;
                    var output = {};
                    Object.keys(definitions).forEach(function(key) {
                      var definition = definitions[key] || {};
                      var defaultValue = definition.value;
                      var currentValue = settingsStore.get(key);
                      if (typeof currentValue === 'undefined') {
                        currentValue = defaultValue;
                        settingsStore.set(key, defaultValue);
                      }
                      output[key] = {
                        label: typeof definition.label === 'string' ? definition.label : key,
                        type: typeof definition.type === 'string' ? definition.type : 'Text',
                        value: currentValue,
                        options: Array.isArray(definition.options) ? definition.options : []
                      };
                    });
                    return JSON.stringify(output);
                  }

                  if (__qnOperation === 'setPluginSetting') {
                    var settingKey = String(args[0] == null ? '' : args[0]);
                    var value = args[1];
                    var settingDefinitions = plugin.pluginSettings || {};
                    if (!Object.prototype.hasOwnProperty.call(settingDefinitions, settingKey)) {
                      throw new Error('Unknown plugin setting: ' + settingKey);
                    }
                    var settingDefinition = settingDefinitions[settingKey] || {};
                    var settingType = settingDefinition.type || 'Text';
                    var options = Array.isArray(settingDefinition.options) ? settingDefinition.options : [];
                    if (settingType === 'Text' && typeof value !== 'string') {
                      throw new Error('Text plugin settings must be strings.');
                    }
                    if (settingType === 'Switch' && typeof value !== 'boolean') {
                      throw new Error('Switch plugin settings must be booleans.');
                    }
                    if (settingType === 'Select' &&
                        (typeof value !== 'string' || !options.some(function(option) { return option.value === value; }))) {
                      throw new Error('Select plugin setting value is not one of its declared options.');
                    }
                    if (settingType === 'CheckboxGroup' &&
                        (!Array.isArray(value) || value.some(function(selected) {
                          return typeof selected !== 'string' ||
                            !options.some(function(option) { return option.value === selected; });
                        }))) {
                      throw new Error('CheckboxGroup plugin setting values must be declared option strings.');
                    }
                    if (['Text','Switch','Select','CheckboxGroup'].indexOf(settingType) < 0) {
                      throw new Error('Unsupported plugin setting type: ' + settingType);
                    }
                    require('@libs/storage').storage.set(settingKey, value);
                    return JSON.stringify(true);
                  }

                  if (typeof plugin[__qnOperation] !== 'function') {
                    throw new Error('Bundle does not export operation: ' + __qnOperation);
                  }
                  if (__qnOperation === 'popularNovels' && args[1] && args[1].filters &&
                      Object.keys(args[1].filters).length === 0) {
                    args[1].filters = plugin.filters || {};
                  }
                  var result = plugin[__qnOperation].apply(plugin, args);
                  if (result && typeof result.then === 'function') {
                    var resolved = false, rejected = null;
                    result.then(function(value) { result = value; resolved = true; },
                                function(error) { rejected = error; });
                    if (!resolved && !rejected) __qnRunTimers();
                    if (rejected) throw rejected;
                    if (!resolved) throw new Error('Extension operation did not resolve after its queued timers.');
                  }
                  __qnRunTimers();
                  return JSON.stringify(result === undefined ? null : result);
                })()
            """.trimIndent()
            val result = cx.evaluateString(scope, invocation, "extension-call.js", 1, null)
            Context.toString(result)
            }
            output
        }
        require(output.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) {
            "Extension result exceeds the 2 MB limit."
        }
        // Ensure the operation produced valid JSON before it crosses into app models.
        val parsedResult = org.json.JSONTokener(output).nextValue()
        require(parsedResult != null) { "Extension returned invalid JSON." }
        ExecutionResult(output)
    }

    internal fun approvedHostsForBundle(site: String, source: String): Set<String> {
        val siteHost = URI(site).host?.lowercase()?.trimEnd('.')
            ?: throw IllegalArgumentException("Extension site URL is invalid.")
        val hosts = linkedSetOf(siteHost)
        val literalUrl = Regex("""https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?""", RegexOption.IGNORE_CASE)
        literalUrl.findAll(source).forEach { match ->
            runCatching {
                URI(match.value).host
                    ?.lowercase()
                    ?.trimEnd('.')
                    ?.takeIf { it.isNotBlank() }
                    ?.let(hosts::add)
            }
        }
        return hosts
    }

    private fun encodeBase64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            val hasSecond = index + 1 < bytes.size
            val hasThird = index + 2 < bytes.size
            val second = if (hasSecond) bytes[index + 1].toInt() and 0xff else 0
            val third = if (hasThird) bytes[index + 2].toInt() and 0xff else 0
            output.append(alphabet[first ushr 2])
            output.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            output.append(if (hasSecond) alphabet[((second and 0x0f) shl 2) or (third ushr 6)] else '=')
            output.append(if (hasThird) alphabet[third and 0x3f] else '=')
            index += 3
        }
        return output.toString()
    }

    /**
     * Small Cheerio-compatible selector surface backed by Jsoup. Only bounded HTML and CSS
     * selectors cross the bridge; Java objects are never exposed to the JavaScript scope.
     */
    private class HtmlBridge(private val baseUrl: String) {
        private val maxHtmlChars = 8 * 1024 * 1024
        private val maxSelectorChars = 500
        private val maxResults = 1_000
        private val maxDocuments = 20
        private val maxSelections = 2_000
        private var nextId = 1
        private val documents = mutableMapOf<Int, Document>()
        private val selections = mutableMapOf<Int, List<Node>>()

        // LNReader's :icontains is not part of Jsoup's selector dialect. Translate it
        // to a Java-regex selector with case-insensitive matching, preserving arbitrary
        // text (including punctuation) as literal content rather than regex syntax.
        private fun normalizeSelector(selector: String): String =
            Regex(""":icontains\(\s*(['"])(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)
                .replace(selector) { match ->
                    val literalText = Regex.escape(match.groupValues[2])
                    ":matches((?i).*" + literalText + ".*)"
                }.replace(Regex("""\+\s*:matches"""), "+ :matches")

        fun parseEvents(html: String): String {
            require(html.length <= maxHtmlChars) { "HTML input exceeds the 8 MB limit." }
            val document = Jsoup.parse(html, baseUrl)
            val events = JSONArray()
            var visited = 0
            fun walk(node: Node, depth: Int) {
                visited++
                require(visited <= 10_000) { "HTML parser exceeded the node limit." }
                require(depth <= 256) { "HTML parser exceeded the nesting-depth limit." }
                when (node) {
                    is Document -> node.childNodes().forEach { walk(it, depth + 1) }
                    is Element -> {
                        val attributes = JSONObject()
                        node.attributes().asList().take(100).forEach { attribute ->
                            attributes.put(attribute.key.take(100), attribute.value.take(10_000))
                        }
                        events.put(JSONObject().put("type", "open").put("name", node.tagName()).put("attribs", attributes))
                        node.childNodes().forEach { walk(it, depth + 1) }
                        events.put(JSONObject().put("type", "close").put("name", node.tagName()))
                    }
                    is TextNode -> events.put(JSONObject().put("type", "text").put("text", node.getWholeText().take(100_000)))
                    is DataNode -> events.put(JSONObject().put("type", "text").put("text", node.getWholeData().take(100_000)))
                    else -> node.childNodes().forEach { walk(it, depth + 1) }
                }
                require(events.length() <= 20_000) { "HTML parser exceeded the event limit." }
            }
            walk(document, 0)
            val result = events.toString()
            require(result.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) {
                "HTML parser output exceeds the 2 MB limit."
            }
            return result
        }

        fun invoke(command: String, selectionId: Int, value: String?, index: Int?): String {
            val nodes = selections[selectionId].orEmpty()
            val result: Any? = when (command) {
                "load" -> {
                    val html = value.orEmpty()
                    require(html.length <= maxHtmlChars) { "HTML input exceeds the 8 MB limit." }
                    require(documents.size < maxDocuments) { "Extension exceeded the HTML document limit." }
                    val id = nextId++
                    documents[id] = Jsoup.parse(html, baseUrl)
                    id
                }
                "select" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    val found = documents[selectionId]?.select(selector)
                        ?: nodes.filterIsInstance<Element>().flatMap { parent ->
                            parent.select(selector).filterNot { it === parent }
                        }
                    storeNodes(found)
                }
                "find" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    storeNodes(nodes.filterIsInstance<Element>().flatMap { parent ->
                        parent.select(selector).filterNot { it === parent }
                    })
                }
                "children" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.length <= maxSelectorChars) { "CSS selector is too long." }
                    val children = nodes.filterIsInstance<Element>().flatMap { it.children() }
                    storeNodes(if (selector.isBlank()) children else children.filter { it.`is`(selector) })
                }
                "contents" -> storeNodes(
                    (documents[selectionId]?.let { listOf(it as Node) } ?: nodes)
                        .flatMap { it.childNodes() }
                )
                "parent" -> storeNodes(nodes.mapNotNull { it.parentNode() }.distinct())
                "next" -> storeNodes(nodes.filterIsInstance<Element>().mapNotNull { it.nextElementSibling() })
                "prev" -> storeNodes(nodes.filterIsInstance<Element>().mapNotNull { it.previousElementSibling() })
                "nextUntil" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    val collected = mutableListOf<Element>()
                    nodes.filterIsInstance<Element>().forEach { start ->
                        var sibling = start.nextElementSibling()
                        var visited = 0
                        while (sibling != null) {
                            val currentSibling = sibling ?: break
                            if (currentSibling.`is`(selector)) break
                            collected.add(currentSibling)
                            sibling = currentSibling.nextElementSibling()
                            visited += 1
                            require(visited <= maxResults) {
                                "Cheerio nextUntil exceeded the sibling limit."
                            }
                        }
                    }
                    storeNodes(collected.distinct())
                }
                "nextNode" -> storeNodes(nodes.firstOrNull()?.let { sibling(it, 1) }?.let(::listOf).orEmpty())
                "merge" -> {
                    val ids = JSONArray(value.orEmpty())
                    storeNodes((0 until ids.length()).flatMap { selections[ids.optInt(it)].orEmpty() })
                }
                "filter" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.length <= maxSelectorChars) { "CSS selector is too long." }
                    storeNodes(nodes.filterIsInstance<Element>().filter { it.`is`(selector) })
                }
                "has" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    storeNodes(nodes.filterIsInstance<Element>().filter { it.select(selector).isNotEmpty() })
                }
                "first" -> storeNodes(nodes.take(1))
                "last" -> storeNodes(nodes.takeLast(1))
                "eq" -> {
                    val requested = index ?: 0
                    val resolved = if (requested < 0) nodes.size + requested else requested
                    storeNodes(nodes.getOrNull(resolved)?.let { listOf(it) }.orEmpty())
                }
                "nodeType" -> nodes.firstOrNull()?.let(::nodeType)
                "nodeData" -> nodes.firstOrNull()?.let(::nodeData)
                "length" -> documents[selectionId]?.let { 1 } ?: nodes.size
                "text" -> documents[selectionId]?.text()?.take(100_000)
                    ?: nodes.joinToString("") { nodeText(it) }.take(100_000)
                "html" -> documents[selectionId]?.html()?.take(100_000)
                    ?: nodes.firstOrNull()?.let { node ->
                        if (node is Element) node.html().take(100_000) else node.outerHtml().take(100_000)
                    }.orEmpty()
                "attr" -> nodes.filterIsInstance<Element>().firstOrNull()?.let { element ->
                    value?.let { if (element.hasAttr(it)) element.attr(it).take(100_000) else null }
                }
                "attrSet" -> {
                    val pair = JSONArray(value.orEmpty())
                    val name = pair.optString(0)
                    val attributeValue = pair.optString(1)
                    require(name.isNotBlank() && name.length <= 200 && attributeValue.length <= 100_000) {
                        "HTML attribute name or value is invalid."
                    }
                    nodes.filterIsInstance<Element>().forEach { it.attr(name, attributeValue) }
                    selectionId
                }
                "removeAttr" -> {
                    nodes.filterIsInstance<Element>().forEach { it.removeAttr(value.orEmpty()) }
                    selectionId
                }
                "nodeName" -> nodes.firstOrNull()?.let { node ->
                    if (node is Element) node.tagName() else node.nodeName()
                }
                "outerHtml" -> nodes.firstOrNull()?.outerHtml()?.take(100_000)
                "replaceWith" -> {
                    nodes.forEach { node -> node.before(value.orEmpty()); node.remove() }
                    selectionId
                }
                "before" -> {
                    nodes.forEach { it.before(value.orEmpty()) }
                    selectionId
                }
                "after" -> {
                    nodes.asReversed().forEach { it.after(value.orEmpty()) }
                    selectionId
                }
                "append" -> {
                    nodes.filterIsInstance<Element>().forEach { it.append(value.orEmpty()) }
                    selectionId
                }
                "hasClass" -> nodes.filterIsInstance<Element>().firstOrNull()?.hasClass(value.orEmpty()) ?: false
                "remove" -> {
                    nodes.forEach { it.remove() }
                    selectionId
                }
                else -> throw IllegalArgumentException("Unsupported HTML selector operation.")
            }
            return when (result) {
                null -> "null"
                is String -> JSONObject.quote(result)
                is Number, is Boolean -> result.toString()
                else -> result.toString()
            }
        }

        private fun nodeType(node: Node): String = when (node) {
            is Element -> "tag"
            is TextNode, is DataNode -> "text"
            is Comment -> "comment"
            else -> "root"
        }

        private fun nodeData(node: Node): String? = when (node) {
            is TextNode -> node.getWholeText()
            is DataNode -> node.getWholeData()
            is Comment -> node.getData()
            else -> null
        }

        private fun nodeText(node: Node): String = when (node) {
            is Element -> node.text()
            is TextNode -> node.getWholeText()
            is DataNode -> node.getWholeData()
            is Comment -> node.getData()
            else -> ""
        }

        private fun sibling(node: Node, offset: Int): Node? {
            val siblings = node.parentNode()?.childNodes().orEmpty()
            val current = siblings.indexOf(node)
            return if (current < 0) null else siblings.getOrNull(current + offset)
        }

        private fun store(elements: List<Element>): Int = storeNodes(elements)

        private fun storeNodes(nodes: List<Node>): Int {
            require(selections.size < maxSelections) { "Extension exceeded the HTML selection limit." }
            val id = nextId++
            selections[id] = nodes.take(maxResults)
            return id
        }
    }

    private class BudgetedContextFactory(
        private val instructionLimit: Int,
        private val operation: String,
    ) : ContextFactory() {
        private var instructions = 0

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            instructions += instructionCount
            if (instructions > instructionLimit) {
                throw IllegalStateException(
                    "Extension exceeded its JavaScript instruction budget during $operation " +
                        "($instructionLimit instructions)."
                )
            }
        }
    }
}
