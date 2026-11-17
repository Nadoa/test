package com.tokisync.viewer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GasException(message: String) : IOException(message)

/** 코루틴 취소 시 HTTP 호출도 함께 취소 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * TokiSync GAS 서버 클라이언트 (웹 뷰어 useGAS.js 포팅)
 *
 * 요청: POST https://script.google.com/macros/s/{gasId}/exec
 *       Content-Type: text/plain, body = { type, folderId, apiKey, protocolVersion: 3, ...payload }
 * 응답: { status: "success" | "error", body: ... }
 */
class GasClient(
    private val settings: SettingsStore,
    private val http: OkHttpClient,
) {
    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiresAt: Long = 0L

    private fun baseUrl(): String {
        val id = settings.gasId.value
        if (id.isBlank()) throw GasException("GAS 배포 ID가 설정되지 않았습니다.")
        return "https://script.google.com/macros/s/$id/exec"
    }

    /** body 값을 그대로 반환 (JSONObject / JSONArray / String / null) */
    suspend fun request(type: String, payload: JSONObject = JSONObject()): Any? {
        val body = JSONObject(payload.toString()).apply {
            put("type", type)
            put("folderId", settings.folderId.value)
            put("apiKey", settings.apiKey.value)
            put("protocolVersion", 3)
        }
        val req = Request.Builder()
            .url(baseUrl())
            .post(body.toString().toRequestBody("text/plain;charset=utf-8".toMediaType()))
            .build()

        val text = http.newCall(req).await().use { res ->
            if (!res.isSuccessful) throw GasException("HTTP ${res.code} ($type)")
            withContext(Dispatchers.IO) { res.body?.string().orEmpty() }
        }
        val json = withContext(Dispatchers.Default) {
            try {
                JSONObject(text)
            } catch (e: Exception) {
                val hint = if (text.contains("<html", ignoreCase = true)) {
                    " — GAS가 HTML을 반환했습니다. 웹 앱 배포 권한(모든 사용자)과 배포 ID를 확인하세요."
                } else ""
                throw GasException("서버 응답을 해석할 수 없습니다$hint")
            }
        }
        if (json.optString("status") == "error") {
            throw GasException(json.opt("body")?.toString() ?: "Unknown Server Error")
        }
        val result = json.opt("body")
        return if (result == JSONObject.NULL) null else result
    }

    suspend fun serverInfo(): JSONObject? = request("get_server_info") as? JSONObject

    /** 라이브러리 전체 (페이지네이션 continuationToken 처리 포함) */
    suspend fun getLibrary(bypassCache: Boolean): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var token: String? = null
        var step = 1
        while (true) {
            val payload = JSONObject()
            if (bypassCache) payload.put("bypassCache", true)
            if (token != null) payload.put("continuationToken", token)

            when (val res = request("view_get_library", payload)) {
                is JSONArray -> {
                    all += res.objects()
                    break
                }
                is JSONObject -> {
                    res.optJSONArray("list")?.let { all += it.objects() }
                    val next = res.optString("continuationToken")
                    if (res.optString("status") == "continue" && next.isNotBlank()) {
                        token = next
                        step++
                        continue
                    }
                    break
                }
                else -> break
            }
        }
        val unique = all.distinctBy { it.optString("id") }
        if (step > 1) {
            // 여러 번에 나눠 재구성했을 때는 웹 뷰어처럼 인덱스를 저장해 둔다.
            runCatching {
                request("view_save_index", JSONObject().put("seriesList", JSONArray(unique)))
            }
        }
        return unique
    }

    suspend fun getBooks(seriesId: String, bypassCache: Boolean): List<JSONObject> {
        val res = request(
            "view_get_books",
            JSONObject().put("seriesId", seriesId).put("bypassCache", bypassCache)
        )
        return (res as? JSONArray)?.objects().orEmpty()
    }

    /** _toki_cache.json 직접 조회 (폴더 스캔 없이 빠름) */
    suspend fun getBooksByCache(cacheFileId: String): List<JSONObject> {
        val res = request("view_get_books_by_cache", JSONObject().put("cacheFileId", cacheFileId))
        return (res as? JSONArray)?.objects().orEmpty()
    }

    /** { data(base64), hasMore, totalSize, nextOffset } */
    suspend fun getChunk(fileId: String, offset: Long, length: Long): JSONObject? {
        val res = request(
            "view_get_chunk",
            JSONObject().put("fileId", fileId).put("offset", offset).put("length", length)
        )
        return res as? JSONObject
    }

    /** Drive API 직접 접근용 OAuth 토큰 (약 1시간 유효, 50분 캐시) */
    suspend fun getToken(forceRefresh: Boolean = false): String = tokenMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (!forceRefresh && cached != null && now < tokenExpiresAt) return@withLock cached
        val res = request("view_get_token") as? JSONObject
            ?: throw GasException("토큰 응답이 비어 있습니다.")
        val token = res.optString("token")
        if (token.isBlank()) throw GasException("토큰을 받지 못했습니다.")
        cachedToken = token
        tokenExpiresAt = now + 50 * 60 * 1000L
        token
    }

    fun invalidateToken() {
        cachedToken = null
        tokenExpiresAt = 0L
    }

    suspend fun getReadHistory(): JSONArray? = request("view_history_get") as? JSONArray

    suspend fun saveReadHistory(history: JSONArray) {
        request("view_history_save", JSONObject().put("history", history))
    }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun parseJsonArray(text: String): JSONArray? =
    runCatching { JSONTokener(text).nextValue() as? JSONArray }.getOrNull()
