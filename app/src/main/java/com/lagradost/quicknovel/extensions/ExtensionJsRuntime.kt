package com.lagradost.quicknovel.extensions

import org.json.JSONArray
import org.json.JSONObject
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
    )

    data class ExecutionResult(val json: String)

    fun execute(
        extension: InstalledExtension,
        operation: String,
        argumentsJson: String,
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
            ScriptableObject.putProperty(scope, "__qnFetch", object : BaseFunction() {
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
                    val response = ExtensionHttpClient.getText(
                        requestedUrl,
                        allowedHosts = setOf(approvedHost),
                    ).getOrThrow()
                    return JSONObject()
                        .put("url", response.url)
                        .put("statusCode", response.statusCode)
                        .put("contentType", response.contentType)
                        .put("body", response.body)
                        .toString()
                }
            })
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
                  this.Promise = QNPromise;
                  this.require = function(name) {
                    if (name === '@libs/fetch') return {
                      fetchApi: function(url, init) {
                        if (init && init.method && String(init.method).toUpperCase() !== 'GET')
                          throw new Error('Only GET requests are supported by this extension bridge.');
                        return Promise.resolve(JSON.parse(__qnFetch(String(url)))).then(function(r) {
                          return {
                            url: r.url, status: r.statusCode, ok: r.statusCode >= 200 && r.statusCode < 300,
                            text: function() { return Promise.resolve(r.body); },
                            json: function() { return Promise.resolve(JSON.parse(r.body)); },
                            headers: { get: function(key) {
                              return String(key).toLowerCase() === 'content-type' ? r.contentType : null;
                            } }
                          };
                        });
                      }
                    };
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
