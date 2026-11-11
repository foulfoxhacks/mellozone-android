package com.foulfoxhacks.mellozone.core

import android.content.Context
import com.foulfoxhacks.mellozone.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** An error reported by the engine. [code] is stable: `network`, `offline`, `not_found`… */
class CoreException(val code: String, message: String) : Exception(message) {
    val isConnectivity: Boolean get() = code == "network" || code == "offline" || code == "http"
}

/** Typed, coroutine-friendly facade over the Rust engine. */
object Core {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val EMPTY = JsonObject(emptyMap())

    /** Blocking. Call from a background thread (or use [call]). */
    fun callRaw(method: String, payload: JsonObject = EMPTY): JsonElement {
        val raw = NativeCore.nativeCall(method, payload.toString())
            ?: throw CoreException("internal", "The app engine did not answer.")
        val envelope = json.parseToJsonElement(raw).jsonObject
        if (envelope["ok"]?.jsonPrimitive?.booleanOrNull == true) {
            return envelope["data"] ?: JsonNull
        }
        val error = envelope["error"] as? JsonObject
        throw CoreException(
            code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: "internal",
            message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: "Something went wrong.",
        )
    }

    suspend inline fun <reified T> call(method: String, payload: JsonObject): T =
        withContext(Dispatchers.IO) { json.decodeFromJsonElement<T>(callRaw(method, payload)) }

    suspend inline fun <reified T> call(method: String): T = call(method, JsonObject(emptyMap()))

    /** Starts the engine. Fast (reads two small files); safe on the main thread. */
    fun init(context: Context) {
        callRaw(
            "init",
            buildJsonObject {
                put("files_dir", context.filesDir.absolutePath)
                put("cache_dir", context.cacheDir.absolutePath)
                put("app_version", BuildConfig.VERSION_NAME)
            },
        )
    }

    /** [mode]: `cache_first` (instant when saved), `network` (refresh) or `cache_only`. */
    suspend fun page(path: String, mode: String): PageResult =
        call("page", buildJsonObject { put("path", path); put("mode", mode) })

    suspend fun journal(mode: String): JournalResult =
        call("journal", buildJsonObject { put("mode", mode) })

    suspend fun directory(mode: String): DirectoryResult =
        call("directory", buildJsonObject { put("mode", mode) })

    suspend fun search(query: String, limit: Int = 40): SearchResult =
        call("search", buildJsonObject { put("query", query); put("limit", limit) })

    suspend fun route(href: String): RouteResult =
        call("route", buildJsonObject { put("href", href) })

    /** Returns a local file path for the image, downloading it if allowed. */
    suspend fun imagePath(url: String, allowNetwork: Boolean): String =
        call<ImageResult>(
            "image",
            buildJsonObject { put("url", url); put("allow_network", allowNetwork) },
        ).path

    suspend fun savedList(): List<SavedItem> = call<SavedResult>("saved_list").items

    suspend fun savedToggle(path: String, title: String): SavedResult =
        call("saved_toggle", buildJsonObject { put("path", path); put("title", title) })

    suspend fun settings(): Settings = call("settings_get")

    suspend fun setSetting(key: String, value: JsonPrimitive): Settings =
        call("settings_set", JsonObject(mapOf(key to value)))

    suspend fun syncStart(): SyncStatus = call("sync_start")
    suspend fun syncStatus(): SyncStatus = call("sync_status")
    suspend fun syncCancel(): SyncStatus = call("sync_cancel")

    suspend fun cacheStats(): StoreStats = call<CacheStats>("cache_stats").storage

    suspend fun cacheClear(what: String): StoreStats =
        call<CacheStats>("cache_clear", buildJsonObject { put("what", what) }).storage

    suspend fun diag(): DiagInfo = call("diag")
    suspend fun logs(): List<LogEntry> = call<Logs>("logs").entries
    suspend fun logsClear() { call<Logs>("logs_clear") }
    suspend fun selfTest(): SelfTest = call("self_test")
}
