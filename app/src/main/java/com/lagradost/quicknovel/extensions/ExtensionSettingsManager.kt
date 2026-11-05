package com.lagradost.quicknovel.extensions

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class ExtensionSettingOption(
    val label: String,
    val value: String,
)

data class ExtensionSetting(
    val key: String,
    val label: String,
    val type: String,
    val value: Any?,
    val options: List<ExtensionSettingOption>,
)

/**
 * Reads and persists the user-configurable settings declared by an LNReader plugin.
 *
 * Defaults and edits use the same per-extension storage bridge as plugin execution, so a
 * newly-created runtime sees the saved values when the plugin bundle is evaluated again.
 */
object ExtensionSettingsManager {
    fun getSettings(
        context: Context,
        extension: InstalledExtension,
    ): Result<List<ExtensionSetting>> = runCatching {
        val result = ExtensionJsRuntime.execute(
            extension = extension,
            operation = "getPluginSettings",
            argumentsJson = "[]",
            storageContext = context.applicationContext,
        ).getOrThrow()

        val json = JSONObject(result.json)
        buildList {
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val setting = json.optJSONObject(key) ?: continue
                val optionsJson = setting.optJSONArray("options") ?: JSONArray()
                val options = buildList {
                    for (index in 0 until optionsJson.length()) {
                        val option = optionsJson.optJSONObject(index) ?: continue
                        val label = option.optString("label").take(200)
                        val value = option.optString("value").take(500)
                        if (label.isNotBlank() && value.isNotBlank()) {
                            add(ExtensionSettingOption(label, value))
                        }
                    }
                }
                add(
                    ExtensionSetting(
                        key = key.take(500),
                        label = setting.optString("label", key).take(500),
                        type = setting.optString("type", "Text").take(40),
                        value = fromJsonValue(setting.opt("value")),
                        options = options,
                    )
                )
            }
        }
    }

    fun saveSettings(
        context: Context,
        extension: InstalledExtension,
        settings: List<ExtensionSetting>,
    ): Result<Unit> = runCatching {
        require(settings.size <= 100) { "An extension may not define more than 100 settings." }
        settings.forEach { setting ->
            val arguments = JSONArray()
                .put(setting.key)
                .put(setting.value)
            ExtensionJsRuntime.execute(
                extension = extension,
                operation = "setPluginSetting",
                argumentsJson = arguments.toString(),
                storageContext = context.applicationContext,
            ).getOrThrow()
        }
    }

    private fun fromJsonValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                add(fromJsonValue(value.opt(index)))
            }
        }
        is JSONObject -> buildMap {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, fromJsonValue(value.opt(key)))
            }
        }
        is String, is Boolean, is Number -> value
        else -> runCatching { JSONTokener(value.toString()).nextValue() }.getOrNull()
    }
}
