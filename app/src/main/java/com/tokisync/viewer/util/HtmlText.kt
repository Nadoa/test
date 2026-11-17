package com.tokisync.viewer.util

/**
 * EPUB(XHTML) → 문단 목록 변환기.
 * WebView 없이 네이티브 Text 로 그리기 위해 문단/제목/삽화만 뽑아냅니다.
 */
object HtmlText {

    sealed interface Raw {
        data class Para(val text: String) : Raw
        data class Heading(val text: String) : Raw
        data class Image(val src: String) : Raw
    }

    private const val H_MARK = '\u0001'
    private const val I_MARK = '\u0002'

    private val HEAD = Regex("(?is)<head\\b.*?</head>")
    private val SCRIPT_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val WS = Regex("\\s+")
    private val IMG = Regex("(?is)<img\\b[^>]*?\\bsrc\\s*=\\s*([\"'])(.*?)\\1[^>]*>")
    private val SVG_IMAGE = Regex("(?is)<image\\b[^>]*?\\b(?:xlink:)?href\\s*=\\s*([\"'])(.*?)\\1[^>]*>")
    private val HEADING = Regex("(?is)<h([1-6])\\b[^>]*>(.*?)</h\\1\\s*>")
    private val BR = Regex("(?i)<br\\s*/?>")
    private val BLOCK_END = Regex("(?i)</(p|div|li|blockquote|tr|section|article|dd|dt|pre)\\s*>|<(hr)\\b[^>]*>")
    private val TAG = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "hellip" to "…", "middot" to "·", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "laquo" to "«", "raquo" to "»",
        "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™", "times" to "×", "deg" to "°",
        "thinsp" to " ", "ensp" to " ", "emsp" to " ", "zwj" to "", "zwnj" to "", "shy" to "",
    )

    fun toBlocks(html: String): List<Raw> {
        var s = html
        s = COMMENT.replace(s, "")
        s = HEAD.replace(s, "")
        s = SCRIPT_STYLE.replace(s, "")
        s = WS.replace(s, " ")
        s = IMG.replace(s) { "\n$I_MARK${it.groupValues[2]}\n" }
        s = SVG_IMAGE.replace(s) { "\n$I_MARK${it.groupValues[2]}\n" }
        s = HEADING.replace(s) { m ->
            val inner = TAG.replace(m.groupValues[2], "").trim()
            if (inner.isEmpty()) "\n" else "\n$H_MARK$inner\n"
        }
        s = BR.replace(s, "\n")
        s = BLOCK_END.replace(s, "\n")
        s = TAG.replace(s, "")

        val out = ArrayList<Raw>()
        for (line in s.split('\n')) {
            when {
                line.startsWith(I_MARK) -> {
                    val src = line.substring(1).trim()
                    if (src.isNotEmpty()) out += Raw.Image(decodeEntities(src))
                }
                line.startsWith(H_MARK) -> {
                    val t = clean(line.substring(1))
                    if (t.isNotEmpty()) out += Raw.Heading(t)
                }
                else -> {
                    val t = clean(line)
                    if (t.isNotEmpty()) out += Raw.Para(t)
                }
            }
        }
        return out
    }

    private fun clean(text: String): String = decodeEntities(text).replace('\u00A0', ' ').trim()

    fun decodeEntities(text: String): String {
        if (text.indexOf('&') < 0) return text
        return ENTITY.replace(text) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    body.substring(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
                else -> NAMED[body] ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int): String =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else ""
}
