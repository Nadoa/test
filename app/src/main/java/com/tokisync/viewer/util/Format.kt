package com.tokisync.viewer.util

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val i = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val v = bytes / 1024.0.pow(i)
    return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", v, units[i])
}
