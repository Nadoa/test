package com.tokisync.viewer.data

import org.json.JSONObject
import java.io.File

/** 시리즈(작품) — GAS `view_get_library` 응답 항목 */
data class Series(
    val id: String,
    val sourceId: String,
    val name: String,
    val category: String,
    val author: String,
    val thumbnail: String,
    val thumbnailId: String,
    val cacheFileId: String,
    val booksCount: Int,
    val lastModified: String,
) {
    val categoryKey: String get() = category.lowercase()

    companion object {
        fun fromJson(o: JSONObject): Series {
            val meta = o.optJSONObject("metadata")
            val category = o.optString("category").ifBlank { meta?.optString("category").orEmpty() }
                .ifBlank { "Unknown" }
            val name = o.optString("name").ifBlank { o.optString("title") }
            return Series(
                id = o.optString("id"),
                sourceId = o.optString("sourceId"),
                name = name,
                category = category,
                author = o.optString("author").ifBlank { meta?.optString("author").orEmpty() },
                thumbnail = o.optString("thumbnail"),
                thumbnailId = o.optString("thumbnailId"),
                cacheFileId = o.optString("cacheFileId"),
                booksCount = when {
                    o.has("booksCount") -> o.optInt("booksCount")
                    else -> o.optInt("itemsCount")
                },
                lastModified = o.optString("lastModified"),
            )
        }
    }
}

/** 회차 — GAS `view_get_books` 응답 항목 */
data class Episode(
    val id: String,
    val seriesId: String,
    val name: String,
    val number: Double,
    val episodeTitle: String,
    val size: Long,
    val mediaType: String,
) {
    val isFolder: Boolean get() = mediaType == "application/folder"

    val displayTitle: String
        get() {
            val base = name.substringBeforeLast('.', name)
            return base
        }

    companion object {
        fun fromJson(o: JSONObject, fallbackSeriesId: String): Episode {
            val media = o.optJSONObject("media")
            return Episode(
                id = o.optString("id"),
                seriesId = o.optString("seriesId").ifBlank { fallbackSeriesId },
                name = o.optString("name"),
                number = o.optDouble("number", 0.0).let { if (it.isNaN()) 0.0 else it },
                episodeTitle = o.optString("episodeTitle"),
                size = o.optLong("size", 0L),
                mediaType = media?.optString("mediaType").orEmpty(),
            )
        }
    }
}

/** 열람 기록 1건. 웹 뷰어와 같은 `read_history.json` 형식이며, 모르는 필드도 그대로 보존합니다. */
class HistoryEntry(val json: JSONObject) {
    val episodeId: String get() = json.optString("episodeId")
    val seriesId: String get() = json.optString("seriesId")
    val lastReadAt: String get() = json.optString("lastReadAt")
    val markerIndex: Int? get() = if (json.has("markerIndex") && !json.isNull("markerIndex")) json.optInt("markerIndex") else null
    /** 앱에서 추가로 기록하는 전체 항목 수 (진행률 표시용, 웹 뷰어는 무시함) */
    val totalCount: Int? get() = if (json.has("appTotal")) json.optInt("appTotal") else null

    val percent: Int?
        get() {
            val idx = markerIndex ?: return null
            val total = totalCount ?: return null
            if (total <= 1) return null
            return (((idx + 1).toFloat() / total) * 100).toInt().coerceIn(0, 100)
        }
}

enum class ReaderMode { SCROLL, PAGE, NOVEL }

data class PageImage(val file: File, val width: Int, val height: Int)

sealed interface TextBlock {
    data class Paragraph(val text: String) : TextBlock
    data class Heading(val text: String) : TextBlock
    data class Picture(val image: PageImage) : TextBlock
    data object Divider : TextBlock
}

sealed interface ReaderContent {
    val size: Int

    data class Images(val pages: List<PageImage>) : ReaderContent {
        override val size: Int get() = pages.size
    }

    data class Text(val blocks: List<TextBlock>) : ReaderContent {
        override val size: Int get() = blocks.size
    }
}
