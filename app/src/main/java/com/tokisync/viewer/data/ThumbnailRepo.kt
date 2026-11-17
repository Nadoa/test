package com.tokisync.viewer.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 표지 이미지 해석.
 * 웹 뷰어는 lh3.googleusercontent.com 공개 링크를 쓰지만, 앱에는 브라우저 로그인 쿠키가 없으므로
 * Drive API(토큰) → 실패 시 GAS 청크 API 순으로 받아 로컬 파일로 캐시합니다.
 */
class ThumbnailRepo(
    context: Context,
    private val gas: GasClient,
    private val http: OkHttpClient,
) {
    private val dir = File(context.filesDir, "thumbs").apply { mkdirs() }
    private val gate = Semaphore(4)
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /** Coil 에 넘길 model (File 또는 URL 문자열). 없으면 null */
    suspend fun resolve(series: Series): Any? {
        val id = series.thumbnailId
        if (id.isNotBlank()) {
            val f = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".img")
            if (f.exists() && f.length() > 0) return f
            if (failed.contains(id)) return fallback(series)
            return gate.withPermit {
                try {
                    if (!f.exists()) download(id, f)
                    f
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += id
                    fallback(series)
                }
            }
        }
        return fallback(series)
    }

    private fun fallback(series: Series): Any? {
        val t = series.thumbnail
        return when {
            t.startsWith("http") -> t
            t.startsWith("data:image") -> {
                val f = File(dir, "data_" + series.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".img")
                if (!f.exists()) {
                    runCatching { f.writeBytes(Base64.getDecoder().decode(t.substringAfter("base64,"))) }
                }
                if (f.exists()) f else null
            }
            else -> null
        }
    }

    private suspend fun download(fileId: String, out: File) {
        val tmp = File(dir, out.name + ".tmp")
        try {
            val token = gas.getToken()
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(req).await().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                withContext(Dispatchers.IO) {
                    r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 토큰을 못 받거나 Drive 직접 접근이 막힌 경우: GAS 청크 API 로 받기
            val r = gas.getChunk(fileId, 0, 4L * 1024 * 1024) ?: throw e
            withContext(Dispatchers.IO) { tmp.writeBytes(Base64.getDecoder().decode(r.optString("data"))) }
        }
        if (!tmp.renameTo(out)) tmp.delete()
    }
}
