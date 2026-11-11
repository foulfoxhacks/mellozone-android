package com.foulfoxhacks.mellozone.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Mirrors rust/src/model.rs. The engine produces these; the UI only draws them.

@Serializable
data class Span(
    val text: String = "",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val small: Boolean = false,
    val href: String? = null,
)

@Serializable
data class Action(val label: String, val href: String, val style: String = "link")

@Serializable
data class Definition(val term: String = "", val spans: List<Span> = emptyList())

@Serializable
sealed class Block {
    @Serializable
    @SerialName("heading")
    data class Heading(val level: Int = 2, val spans: List<Span> = emptyList()) : Block()

    @Serializable
    @SerialName("eyebrow")
    data class Eyebrow(val text: String = "") : Block()

    @Serializable
    @SerialName("paragraph")
    data class Paragraph(val spans: List<Span> = emptyList(), val role: String = "") : Block()

    @Serializable
    @SerialName("list")
    data class ListBlock(val ordered: Boolean = false, val items: List<List<Block>> = emptyList()) : Block()

    @Serializable
    @SerialName("quote")
    data class Quote(val blocks: List<Block> = emptyList()) : Block()

    @Serializable
    @SerialName("image")
    data class Image(
        val url: String,
        val alt: String = "",
        val caption: List<Span> = emptyList(),
        val width: Int = 0,
        val height: Int = 0,
    ) : Block()

    @Serializable
    @SerialName("card")
    data class Card(val href: String? = null, val blocks: List<Block> = emptyList()) : Block()

    @Serializable
    @SerialName("details")
    data class Details(val summary: List<Span> = emptyList(), val blocks: List<Block> = emptyList()) : Block()

    @Serializable
    @SerialName("table")
    data class Table(
        val caption: String = "",
        val headers: List<List<Span>> = emptyList(),
        val rows: List<List<List<Span>>> = emptyList(),
    ) : Block()

    @Serializable
    @SerialName("buttons")
    data class Buttons(val actions: List<Action> = emptyList()) : Block()

    @Serializable
    @SerialName("definitions")
    data class Definitions(val items: List<Definition> = emptyList()) : Block()

    @Serializable
    @SerialName("form")
    data class Form(val title: String = "", val description: String = "", val url: String = "") : Block()

    @Serializable
    @SerialName("code")
    data class Code(val text: String = "") : Block()

    @Serializable
    @SerialName("divider")
    data object Divider : Block()
}

@Serializable
data class Page(
    val url: String = "",
    val path: String = "",
    val title: String = "",
    val heading: String = "",
    val description: String = "",
    val image: String? = null,
    val kind: String = "page",
    val published: String? = null,
    val modified: String? = null,
    @SerialName("has_form") val hasForm: Boolean = false,
    val blocks: List<Block> = emptyList(),
) {
    val displayTitle: String get() = heading.ifBlank { title }
}

@Serializable
data class PageResult(
    val page: Page,
    val source: String = "cache",
    val stale: Boolean = false,
    val changed: Boolean = false,
    @SerialName("fetched_at") val fetchedAt: Long = 0,
    val warning: String? = null,
)

@Serializable
data class FeedItem(
    val path: String,
    val url: String = "",
    val title: String = "",
    val summary: String = "",
    val image: String? = null,
    @SerialName("date_published") val datePublished: String? = null,
    val tags: List<String> = emptyList(),
    val author: String = "",
)

@Serializable
data class JournalResult(
    val items: List<FeedItem> = emptyList(),
    val source: String = "cache",
    val stale: Boolean = false,
    @SerialName("fetched_at") val fetchedAt: Long = 0,
    val warning: String? = null,
)

@Serializable
data class DirEntry(val path: String, val title: String = "", val description: String = "")

@Serializable
data class DirSection(val title: String = "", val entries: List<DirEntry> = emptyList())

@Serializable
data class DirectoryResult(
    val sections: List<DirSection> = emptyList(),
    val source: String = "cache",
    val stale: Boolean = false,
    @SerialName("fetched_at") val fetchedAt: Long = 0,
    val warning: String? = null,
)

@Serializable
data class Hit(
    val path: String,
    val title: String = "",
    val snippet: String = "",
    val kind: String = "page",
    val image: String? = null,
    val score: Int = 0,
)

@Serializable
data class SearchResult(
    val query: String = "",
    val hits: List<Hit> = emptyList(),
    val documents: Int = 0,
    val micros: Long = 0,
)

@Serializable
data class RouteResult(val kind: String = "external", val path: String? = null, val url: String = "")

@Serializable
data class ImageResult(val path: String, val source: String = "cache")

@Serializable
data class SavedItem(
    val path: String,
    val title: String = "",
    val description: String = "",
    val image: String? = null,
    @SerialName("saved_at") val savedAt: Long = 0,
)

@Serializable
data class SavedResult(val saved: Boolean = false, val items: List<SavedItem> = emptyList())

@Serializable
data class Settings(
    @SerialName("text_scale") val textScale: Float = 1f,
    @SerialName("data_saver") val dataSaver: Boolean = false,
    @SerialName("pure_black") val pureBlack: Boolean = false,
    @SerialName("dev_bypass_cache") val devBypassCache: Boolean = false,
    @SerialName("dev_simulate_offline") val devSimulateOffline: Boolean = false,
    @SerialName("dev_block_outlines") val devBlockOutlines: Boolean = false,
    @SerialName("dev_verbose_log") val devVerboseLog: Boolean = false,
    @SerialName("dev_source_badge") val devSourceBadge: Boolean = false,
)

@Serializable
data class SyncStatus(
    val state: String = "idle",
    val message: String = "",
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    @SerialName("finished_at") val finishedAt: Long = 0,
) {
    val running: Boolean get() = state == "running"
}

@Serializable
data class StoreStats(
    val pages: Long = 0,
    @SerialName("page_bytes") val pageBytes: Long = 0,
    val images: Long = 0,
    @SerialName("image_bytes") val imageBytes: Long = 0,
)

@Serializable
data class CacheStats(val storage: StoreStats = StoreStats())

@Serializable
data class DiagInfo(
    @SerialName("core_version") val coreVersion: String = "",
    @SerialName("app_version") val appVersion: String = "",
    val rustc: String = "",
    val target: String = "",
    val arch: String = "",
    val profile: String = "",
    val http: String = "",
    val tls: String = "",
    val html: String = "",
    val site: String = "",
    @SerialName("uptime_secs") val uptimeSecs: Long = 0,
    val requests: Long = 0,
    val failures: Long = 0,
    @SerialName("bytes_downloaded") val bytesDownloaded: Long = 0,
    @SerialName("cache_hits") val cacheHits: Long = 0,
    @SerialName("pages_parsed") val pagesParsed: Long = 0,
    val storage: StoreStats = StoreStats(),
    @SerialName("data_dir") val dataDir: String = "",
)

@Serializable
data class LogEntry(val at: Long = 0, val level: String = "info", val tag: String = "", val message: String = "")

@Serializable
data class Logs(val entries: List<LogEntry> = emptyList())

@Serializable
data class TestResult(val name: String = "", val ok: Boolean = false, val detail: String = "", val ms: Double = 0.0)

@Serializable
data class SelfTest(val results: List<TestResult> = emptyList())
