package com.lagradost.quicknovel.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.net.URI

/**
 * Instruction-budgeted JavaScript execution for extension bundles.
 *
 * This is the execution core only: LNReader's Metro module loader and its fetch/Cheerio
 * shims must be installed before arbitrary LNReader repository bundles can run. Unknown
 * imports fail closed rather than being silently replaced with fake implementations.
 */
object ExtensionJsRuntime {
    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_ARGUMENT_BYTES = 256 * 1024
    private const val MAX_RESULT_BYTES = 2 * 1024 * 1024
    private const val MAX_INSTRUCTIONS = 2_000_000
    private const val OBSERVER_THRESHOLD = 10_000
    private val allowedOperations = setOf(
        "searchNovels",
        "parseNovel",
        "parseChapter",
        "popularNovels",
        "parsePage",
        "resolveUrl",
    )

    data class ExecutionResult(val json: String)

    internal fun execute(
        extension: InstalledExtension,
        operation: String,
        argumentsJson: String,
        testFetchOverride: ((String) -> ExtensionHttpClient.TextResponse)? = null,
    ): Result<ExecutionResult> = runCatching {
        require(operation in allowedOperations) { "Unsupported extension operation." }
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

        val factory = BudgetedContextFactory(MAX_INSTRUCTIONS)
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
            val approvedHost = URI(extension.site).host?.lowercase()
                ?: throw IllegalArgumentException("Extension site URL is invalid.")
            var fetchCount = 0
            val fetchFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    fetchCount += 1
                    require(fetchCount <= 20) { "Extension exceeded the 20-request operation limit." }
                    val requestedUrl = args.firstOrNull()?.toString().orEmpty()
                    require(requestedUrl.isNotBlank()) { "Extension fetch URL is missing." }
                    val headersJson = args.getOrNull(1)?.toString().orEmpty()
                    val requestHeaders = runCatching {
                        val json = JSONObject(headersJson)
                        buildMap {
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                put(key, json.optString(key, ""))
                            }
                        }
                    }.getOrDefault(emptyMap())
                    val response = testFetchOverride?.invoke(requestedUrl) ?: ExtensionHttpClient.getText(
                        requestedUrl,
                        headers = requestHeaders,
                        allowedHosts = setOf(approvedHost),
                    ).getOrThrow()
                    return JSONObject()
                        .put("url", response.url)
                        .put("statusCode", response.statusCode)
                        .put("contentType", response.contentType)
                        .put("body", response.body)
                        .toString()
                }
            }
            fetchFunction.parentScope = scope
            fetchFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnFetch", fetchFunction)
            val htmlBridge = HtmlBridge(extension.site)
            val htmlFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val command = args.getOrNull(0)?.toString().orEmpty()
                    val selectionId = args.getOrNull(1)?.toString()?.toIntOrNull() ?: 0
                    val value = args.getOrNull(2)?.toString()
                    val index = args.getOrNull(3)?.toString()?.toIntOrNull()
                    return htmlBridge.invoke(command, selectionId, value, index)
                }
            }
            htmlFunction.parentScope = scope
            htmlFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnHtml", htmlFunction)
            val compatibilityBootstrap = """
                (function() {
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
                    if (name === '@libs/fetch') return {
                      fetchApi: function(url, init) {
                        if (init && init.method && String(init.method).toUpperCase() !== 'GET')
                          throw new Error('Only GET requests are supported by this extension bridge.');
                        var headers = init && init.headers ? init.headers : {};
                        if (headers && typeof headers.forEach === 'function') {
                          var normalized = {};
                          headers.forEach(function(value, key) { normalized[key] = value; });
                          headers = normalized;
                        }
                        return Promise.resolve(JSON.parse(__qnFetch(String(url), JSON.stringify(headers)))).then(function(r) {
                          return {
                            url: r.url, status: r.statusCode, ok: r.statusCode >= 200 && r.statusCode < 300,
                            statusText: String(r.statusCode), body: r.body,
                            text: function() { return Promise.resolve(r.body); },
                            json: function() { return Promise.resolve(JSON.parse(r.body)); },
                            headers: { get: function(key) {
                              return String(key).toLowerCase() === 'content-type' ? r.contentType : null;
                            } }
                          };
                        });
                      },
                      fetchText: function(url, init) {
                        return this.fetchApi(url, init).then(function(response) {
                          return response.ok ? response.text() : '';
                        }).catch(function() { return ''; });
                      }
                    };
                    if (name === 'cheerio') {
                      function call(command, id, value, index) {
                        return JSON.parse(__qnHtml(command, id, value == null ? '' : String(value), index == null ? '' : index));
                      }
                      function wrap(id) {
                        var selection = {_qnSelectionId:id};
                        Object.defineProperty(selection, 'length', {get:function(){return call('length',id);}});
                        selection.text = function(){return call('text',id);};
                        selection.html = function(){return call('html',id);};
                        selection.attr = function(name){return call('attr',id,name);};
                        selection.hasClass = function(name){return call('hasClass',id,name);};
                        selection.first = function(){return wrap(call('first',id));};
                        selection.last = function(){return wrap(call('last',id));};
                        selection.eq = function(index){return wrap(call('eq',id,'',index));};
                        selection.find = function(selector){return wrap(call('find',id,selector));};
                        selection.children = function(selector){return wrap(call('children',id,selector || ''));};
                        selection.parent = function(){return wrap(call('parent',id));};
                        selection.next = function(){return wrap(call('next',id));};
                        selection.prev = function(){return wrap(call('prev',id));};
                        selection.remove = function(){call('remove',id);return selection;};
                        selection.each = function(callback) {
                          for (var i=0;i<selection.length;i++) {
                            var item=selection.eq(i);
                            callback.call(item,i,item);
                          }
                          return selection;
                        };
                        selection.map = function(callback) {
                          var values=[];
                          for (var i=0;i<selection.length;i++) values.push(callback.call(selection.eq(i),i,selection.eq(i)));
                          return {get:function(){return values;},toArray:function(){return values;}};
                        };
                        selection.get = function(index) {
                          if (index == null) { var all=[]; for(var i=0;i<selection.length;i++) all.push(selection.eq(i)); return all; }
                          return selection.eq(index);
                        };
                        return selection;
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
                    if (name === '@libs/defaultCover') return {defaultCover: ''};
                    if (name === '@libs/novelStatus') return {
                      NovelStatus: {Ongoing:'ongoing', Completed:'completed', Paused:'paused', Dropped:'dropped'}
                    };
                    if (name === '@libs/filterInputs') return {
                      FilterTypes: {
                        Picker:'Picker', CheckboxGroup:'CheckboxGroup',
                        ExcludableCheckboxGroup:'ExcludableCheckboxGroup',
                        Switch:'Switch', TextInput:'TextInput', Input:'Input'
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
            val invocation = """
                (function() {
                  var exported = module.exports;
                  var plugin = exported && (exported.default || exported);
                  if (!plugin || typeof plugin[__qnOperation] !== 'function') {
                    throw new Error('Bundle does not export operation: ' + __qnOperation);
                  }
                  var args = JSON.parse(__qnArgumentsJson);
                  var result = plugin[__qnOperation].apply(plugin, args);
                  if (result && typeof result.then === 'function') {
                    var resolved = false, rejected = null;
                    result.then(function(value) { result = value; resolved = true; },
                                function(error) { rejected = error; });
                    if (rejected) throw rejected;
                    if (!resolved) throw new Error('Extension operation did not resolve synchronously.');
                  }
                  return JSON.stringify(result === undefined ? null : result);
                })()
            """.trimIndent()
            val result = cx.evaluateString(scope, invocation, "extension-call.js", 1, null)
            Context.toString(result)
        }
        require(output.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) {
            "Extension result exceeds the 2 MB limit."
        }
        // Ensure the operation produced valid JSON before it crosses into app models.
        val parsedResult = org.json.JSONTokener(output).nextValue()
        require(parsedResult != null) { "Extension returned invalid JSON." }
        ExecutionResult(output)
    }

    /**
     * Small Cheerio-compatible selector surface backed by Jsoup. Only bounded HTML and CSS
     * selectors cross the bridge; Java objects are never exposed to the JavaScript scope.
     */
    private class HtmlBridge(private val baseUrl: String) {
        private val maxHtmlChars = 2 * 1024 * 1024
        private val maxSelectorChars = 500
        private val maxResults = 1_000
        private var nextId = 1
        private val documents = mutableMapOf<Int, Document>()
        private val selections = mutableMapOf<Int, List<Element>>()

        fun invoke(command: String, selectionId: Int, value: String?, index: Int?): String {
            val result: Any? = when (command) {
                "load" -> {
                    val html = value.orEmpty()
                    require(html.length <= maxHtmlChars) { "HTML input exceeds the 2 MB limit." }
                    val id = nextId++
                    documents[id] = Jsoup.parse(html, baseUrl)
                    id
                }
                "select" -> {
                    val selector = value.orEmpty()
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    val found = documents[selectionId]?.select(selector)
                        ?: selections[selectionId].orEmpty().flatMap { parent ->
                            parent.select(selector).filterNot { it === parent }
                        }
                    store(found)
                }
                "find" -> {
                    val selector = value.orEmpty()
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    store(selections[selectionId].orEmpty().flatMap { parent ->
                        parent.select(selector).filterNot { it === parent }
                    })
                }
                "children" -> {
                    val selector = value.orEmpty()
                    require(selector.length <= maxSelectorChars) { "CSS selector is too long." }
                    val children = selections[selectionId].orEmpty().flatMap { it.children() }
                    store(if (selector.isBlank()) children else children.filter { it.`is`(selector) })
                }
                "parent" -> store(selections[selectionId].orEmpty().mapNotNull { it.parent() }.distinct())
                "next" -> store(selections[selectionId].orEmpty().mapNotNull { it.nextElementSibling() })
                "prev" -> store(selections[selectionId].orEmpty().mapNotNull { it.previousElementSibling() })
                "first" -> store(selections[selectionId].orEmpty().take(1))
                "last" -> store(selections[selectionId].orEmpty().takeLast(1))
                "eq" -> {
                    val elements = selections[selectionId].orEmpty()
                    val requested = index ?: 0
                    val resolved = if (requested < 0) elements.size + requested else requested
                    store(elements.getOrNull(resolved)?.let { listOf(it) }.orEmpty())
                }
                "length" -> documents[selectionId]?.let { 1 } ?: selections[selectionId].orEmpty().size
                "text" -> documents[selectionId]?.text()?.take(100_000)
                    ?: selections[selectionId].orEmpty().joinToString("") { it.text() }.take(100_000)
                "html" -> documents[selectionId]?.html()?.take(100_000)
                    ?: selections[selectionId].orEmpty().firstOrNull()?.html()?.take(100_000).orEmpty()
                "attr" -> selections[selectionId].orEmpty().firstOrNull()?.let { element ->
                    value?.let { if (element.hasAttr(it)) element.attr(it).take(100_000) else null }
                }
                "hasClass" -> selections[selectionId].orEmpty().firstOrNull()?.hasClass(value.orEmpty()) ?: false
                "remove" -> {
                    selections[selectionId].orEmpty().forEach { it.remove() }
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

        private fun store(elements: List<Element>): Int {
            val id = nextId++
            selections[id] = elements.take(maxResults)
            return id
        }
    }

    private class BudgetedContextFactory(
        private val instructionLimit: Int,
    ) : ContextFactory() {
        private var instructions = 0

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            instructions += instructionCount
            if (instructions > instructionLimit) {
                throw IllegalStateException("Extension exceeded its JavaScript instruction budget.")
            }
        }
    }
}
