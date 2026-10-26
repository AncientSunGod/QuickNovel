package com.lagradost.quicknovel.extensions

import org.json.JSONArray
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File

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
            ScriptableObject.putProperty(scope, "require", object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val moduleName = args.firstOrNull()?.toString().orEmpty()
                    throw IllegalArgumentException(
                        "Extension import '$moduleName' is not available in the current compatibility layer."
                    )
                }
            })

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
                    throw new Error('Async extension operations require the LNReader async host bridge.');
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
