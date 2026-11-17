package com.tokisync.viewer.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.Xml
import com.tokisync.viewer.util.HtmlText
import com.tokisync.viewer.util.NaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 회차 파일(CBZ/ZIP/EPUB) 다운로드·보관·압축 해제 (웹 뷰어 useFetcher.js 포팅)
 *
 * 다운로드 경로:
 *  1) Drive API 직접 다운로드 — GAS `view_get_token` 으로 받은 OAuth 토큰 사용 (빠름)
 *  2) 실패 시 GAS `view_get_chunk` 분할 다운로드 (base64, 웹 뷰어와 동일 방식)
 *
 * 보관: files/books/{fileId}.book (오프라인 열람용, 용량 한도 초과 시 오래된 것부터 삭제)
 * 해제: cache/pages/{fileId}/ (최근 몇 개만 유지)
 */
class EpisodeStore(
    context: Context,
    private val gas: GasClient,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
) {
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val pagesRoot = File(context.cacheDir, "pages").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val _downloaded = MutableStateFlow(scanDownloaded())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()

    /** 진행 중인 다운로드: fileId → 0..1 (알 수 없으면 -1) */
    private val _active = MutableStateFlow<Map<String, Float>>(emptyMap())
    val active: StateFlow<Map<String, Float>> = _active.asStateFlow()

    private fun bookFile(fileId: String) = File(booksDir, "${safe(fileId)}.book")
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun scanDownloaded(): Set<String> =
        booksDir.listFiles { f -> f.name.endsWith(".book") }
            ?.map { it.name.removeSuffix(".book") }?.toSet().orEmpty()

    fun isDownloaded(fileId: String) = _downloaded.value.contains(safe(fileId))

    // ---------------------------------------------------------------- download

    suspend fun ensureDownloaded(ep: Episode, onProgress: (Float) -> Unit = {}): File {
        val target = bookFile(ep.id)
        val mutex = locks.getOrPut(ep.id) { Mutex() }
        val file = mutex.withLock {
            if (target.exists() && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            val tmp = File(booksDir, "${safe(ep.id)}.part")
            tmp.delete()
            val report: (Float) -> Unit = { p ->
                _active.update { it + (ep.id to p) }
                onProgress(p)
            }
            report(-1f)
            try {
                val prefs = settings.viewer.value
                var done = false
                if (prefs.directDrive) {
                    done = try {
                        downloadDirect(ep.id, tmp, report)
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct Drive download failed, falling back to GAS chunks", e)
                        tmp.delete()
                        false
                    }
                }
                if (!done) downloadViaGas(ep.id, ep.size, tmp, prefs.downloadThreads, report)
                if (!isZip(tmp)) throw IOException("압축 파일 형식이 아닙니다 (CBZ/ZIP/EPUB만 지원)")
                if (!tmp.renameTo(target)) throw IOException("파일을 저장하지 못했습니다")
                _downloaded.update { it + safe(ep.id) }
                target
            } finally {
                tmp.delete()
                _active.update { it - ep.id }
            }
        }
        enforceLimit(exclude = ep.id)
        return file
    }

    private fun driveRequest(fileId: String, token: String) = Request.Builder()
        .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true")
        .header("Authorization", "Bearer $token")
        .build()

    private suspend fun downloadDirect(fileId: String, out: File, onProgress: (Float) -> Unit) {
        var res = http.newCall(driveRequest(fileId, gas.getToken())).await()
        if (res.code == 401 || res.code == 403) {
            res.close()
            res = http.newCall(driveRequest(fileId, gas.getToken(forceRefresh = true))).await()
        }
        res.use { r ->
            if (!r.isSuccessful) throw IOException("Drive HTTP ${r.code}")
            val body = r.body ?: throw IOException("Drive 응답 본문 없음")
            val total = body.contentLength()
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(out).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var written = 0L
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                            if (total > 0 && written - lastReport > 256 * 1024) {
                                lastReport = written
                                onProgress(written.toFloat() / total)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadViaGas(
        fileId: String,
        size: Long,
        out: File,
        threads: Int,
        onProgress: (Float) -> Unit,
    ) {
        if (size <= 0L) {
            downloadSequential(fileId, out, onProgress)
            return
        }
        val count = max(6, ceil(size.toDouble() / MAX_CHUNK).toInt())
        val chunk = ceil(size.toDouble() / count).toLong()
        val tasks = (0 until count).map { i ->
            val start = i * chunk
            start to min(chunk, size - start)
        }.filter { it.second > 0 }

        withContext(Dispatchers.IO) {
            RandomAccessFile(out, "rw").use { raf ->
                raf.setLength(size)
                val queue = Channel<Pair<Long, Long>>(Channel.UNLIMITED)
                tasks.forEach { queue.trySend(it) }
                queue.close()
                val finished = AtomicInteger(0)
                coroutineScope {
                    repeat(min(threads.coerceIn(1, 4), tasks.size)) {
                        launch {
                            for ((start, length) in queue) {
                                val bytes = retry(3) {
                                    val r = gas.getChunk(fileId, start, length)
                                        ?: throw IOException("빈 청크 응답")
                                    Base64.getDecoder().decode(r.optString("data"))
                                }
                                synchronized(raf) {
                                    raf.seek(start)
                                    raf.write(bytes)
                                }
                                onProgress(finished.incrementAndGet().toFloat() / tasks.size)
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun downloadSequential(fileId: String, out: File, onProgress: (Float) -> Unit) {
        withContext(Dispatchers.IO) {
            FileOutputStream(out).use { output ->
                var offset = 0L
                while (true) {
                    ensureActive()
                    val r = retry(3) { gas.getChunk(fileId, offset, MAX_CHUNK) } ?: break
                    val bytes = Base64.getDecoder().decode(r.optString("data"))
                    output.write(bytes)
                    offset = r.optLong("nextOffset", offset + bytes.size)
                    val total = r.optLong("totalSize", 0L)
                    if (total > 0) onProgress(offset.toFloat() / total)
                    if (!r.optBoolean("hasMore", false) || bytes.isEmpty()) break
                }
            }
        }
    }

    private suspend fun <T> retry(times: Int, block: suspend () -> T): T {
        var last: Exception? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < times - 1) delay(1500L * (attempt + 1))
            }
        }
        throw last ?: IOException("다운로드 실패")
    }

    private fun isZip(f: File): Boolean {
        if (!f.exists() || f.length() < 4) return false
        return f.inputStream().use { s ->
            val b0 = s.read()
            val b1 = s.read()
            b0 == 'P'.code && b1 == 'K'.code
        }
    }

    // ---------------------------------------------------------------- storage

    fun usageBytes(): Long =
        booksDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun delete(fileIds: Collection<String>) {
        for (id in fileIds) {
            bookFile(id).delete()
            File(pagesRoot, safe(id)).deleteRecursively()
        }
        _downloaded.value = scanDownloaded()
    }

    fun clearAll() {
        booksDir.listFiles()?.forEach { it.delete() }
        pagesRoot.listFiles()?.forEach { it.deleteRecursively() }
        _downloaded.value = scanDownloaded()
    }

    private fun enforceLimit(exclude: String) {
        val limit = settings.viewer.value.cacheLimitMb.toLong() * 1024 * 1024
        val files = booksDir.listFiles { f -> f.name.endsWith(".book") }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total <= limit) break
            if (f.name == "${safe(exclude)}.book") continue
            total -= f.length()
            f.delete()
            File(pagesRoot, f.name.removeSuffix(".book")).deleteRecursively()
        }
        _downloaded.value = scanDownloaded()
    }

    // ---------------------------------------------------------------- extract

    suspend fun openContent(fileId: String, book: File): ReaderContent = withContext(Dispatchers.IO) {
        val outDir = File(pagesRoot, safe(fileId))
        val meta = File(outDir, "content.json")
        loadExtracted(outDir, meta)?.let {
            outDir.setLastModified(System.currentTimeMillis())
            return@withContext it
        }
        outDir.deleteRecursively()
        outDir.mkdirs()
        val content = try {
            openZip(book).use { zip -> extract(zip, outDir) }
        } catch (e: Exception) {
            outDir.deleteRecursively()
            throw e
        }
        meta.writeText(serialize(content, outDir).toString())
        prunePages(keep = 5, exclude = outDir.name)
        content
    }

    /** UTF-8 이름이 깨진 압축파일은 CP949로 다시 시도 */
    private fun openZip(file: File): ZipFile {
        var zip: ZipFile? = null
        return try {
            zip = ZipFile(file)
            zip.entries().asSequence().forEach { it.name }
            zip
        } catch (e: Exception) {
            runCatching { zip?.close() }
            try {
                ZipFile(file, Charset.forName("MS949"))
            } catch (_: Exception) {
                throw IOException("압축 파일을 열 수 없습니다: ${e.message}", e)
            }
        }
    }

    private fun extract(zip: ZipFile, outDir: File): ReaderContent {
        val names = zip.entries().asSequence()
            .filter { !it.isDirectory }
            .map { it.name }
            .filter { !isHidden(it) }
            .toList()
            .sortedWith(NaturalOrder)
        val isEpub = zip.getEntry("mimetype") != null || names.any { it.endsWith(".opf", ignoreCase = true) }
        return if (isEpub) parseEpub(zip, names, outDir) else extractImages(zip, names, outDir)
    }

    private fun extractImages(zip: ZipFile, names: List<String>, outDir: File): ReaderContent {
        val pages = ArrayList<PageImage>()
        var idx = 0
        for (name in names) {
            if (!isImage(name)) continue
            val out = File(outDir, String.format(Locale.US, "%04d.%s", idx++, ext(name)))
            val page = copyImage(zip, name, out) ?: continue
            pages += page
        }
        if (pages.isEmpty()) throw IOException("이미지가 없습니다")
        return ReaderContent.Images(pages)
    }

    private fun copyImage(zip: ZipFile, entryName: String, out: File): PageImage? {
        val entry = zip.getEntry(entryName) ?: return null
        zip.getInputStream(entry).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            out.delete()
            return null
        }
        return PageImage(out, opts.outWidth, opts.outHeight)
    }

    private fun parseEpub(zip: ZipFile, names: List<String>, outDir: File): ReaderContent {
        val images = names.filter(::isImage)
        val texts = names.filter(::isHtml)
        // 이미지 위주 EPUB(만화)는 이미지 뷰어로 (웹 뷰어와 같은 기준)
        if (images.size > 5 && images.size >= texts.size) return extractImages(zip, names, outDir)

        val order = readSpine(zip)?.filter { zip.getEntry(it) != null }?.takeIf { it.isNotEmpty() } ?: texts
        val blocks = ArrayList<TextBlock>()
        var picIdx = 0
        for (path in order) {
            val html = zip.getInputStream(zip.getEntry(path) ?: continue).use { it.readBytes() }
                .toString(Charsets.UTF_8)
            val raw = HtmlText.toBlocks(html)
            if (raw.isEmpty()) continue
            if (blocks.isNotEmpty() && blocks.last() != TextBlock.Divider) blocks += TextBlock.Divider
            val baseDir = path.substringBeforeLast('/', "")
            for (r in raw) {
                when (r) {
                    is HtmlText.Raw.Para -> blocks += TextBlock.Paragraph(r.text)
                    is HtmlText.Raw.Heading -> blocks += TextBlock.Heading(r.text)
                    is HtmlText.Raw.Image -> {
                        val entryName = resolvePath(baseDir, r.src)
                        if (!isImage(entryName)) continue
                        val out = File(outDir, String.format(Locale.US, "pic_%04d.%s", picIdx++, ext(entryName)))
                        copyImage(zip, entryName, out)?.let { blocks += TextBlock.Picture(it) }
                    }
                }
            }
        }
        while (blocks.lastOrNull() == TextBlock.Divider) blocks.removeAt(blocks.lastIndex)
        if (blocks.isEmpty()) throw IOException("EPUB 본문이 비어 있습니다")
        return ReaderContent.Text(blocks)
    }

    /** META-INF/container.xml → OPF → spine 순서의 본문 파일 경로 */
    private fun readSpine(zip: ZipFile): List<String>? = runCatching {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        var opfPath: String? = null
        zip.getInputStream(container).use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name.substringAfter(':') == "rootfile") {
                    opfPath = p.getAttributeValue(null, "full-path")
                    break
                }
            }
        }
        val opf = opfPath ?: return null
        val opfEntry = zip.getEntry(opf) ?: return null
        val manifest = HashMap<String, String>()
        val spine = ArrayList<String>()
        zip.getInputStream(opfEntry).use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name.substringAfter(':')) {
                    "item" -> {
                        val id = p.getAttributeValue(null, "id")
                        val href = p.getAttributeValue(null, "href")
                        if (id != null && href != null) manifest[id] = href
                    }
                    "itemref" -> p.getAttributeValue(null, "idref")?.let { spine += it }
                }
            }
        }
        val base = opf.substringBeforeLast('/', "")
        spine.mapNotNull { manifest[it] }.map { resolvePath(base, it) }.filter(::isHtml)
    }.getOrNull()

    private fun resolvePath(baseDir: String, href: String): String {
        val clean = Uri.decode(href.substringBefore('#'))
        if (clean.startsWith("/")) return clean.trimStart('/')
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts += baseDir.split('/')
        for (seg in clean.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun prunePages(keep: Int, exclude: String) {
        val dirs = pagesRoot.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        dirs.drop(keep).filter { it.name != exclude }.forEach { it.deleteRecursively() }
    }

    // ---------------------------------------------------------------- serialize

    private fun serialize(content: ReaderContent, dir: File): JSONObject = when (content) {
        is ReaderContent.Images -> JSONObject()
            .put("type", "images")
            .put("pages", JSONArray(content.pages.map { pageJson(it) }))
        is ReaderContent.Text -> JSONObject()
            .put("type", "text")
            .put("blocks", JSONArray(content.blocks.map { b ->
                when (b) {
                    is TextBlock.Paragraph -> JSONObject().put("t", "p").put("v", b.text)
                    is TextBlock.Heading -> JSONObject().put("t", "h").put("v", b.text)
                    is TextBlock.Picture -> pageJson(b.image).put("t", "img")
                    TextBlock.Divider -> JSONObject().put("t", "hr")
                }
            }))
    }

    private fun pageJson(p: PageImage) = JSONObject().put("f", p.file.name).put("w", p.width).put("h", p.height)

    private fun pageFrom(dir: File, o: JSONObject): PageImage? {
        val f = File(dir, o.optString("f"))
        return if (f.exists()) PageImage(f, o.optInt("w"), o.optInt("h")) else null
    }

    private fun loadExtracted(dir: File, meta: File): ReaderContent? {
        if (!meta.exists()) return null
        return runCatching {
            val o = JSONObject(meta.readText())
            when (o.optString("type")) {
                "images" -> {
                    val arr = o.getJSONArray("pages").objects()
                    val pages = arr.map { pageFrom(dir, it) ?: return null }
                    ReaderContent.Images(pages)
                }
                "text" -> {
                    val blocks = o.getJSONArray("blocks").objects().mapNotNull { b ->
                        when (b.optString("t")) {
                            "p" -> TextBlock.Paragraph(b.optString("v"))
                            "h" -> TextBlock.Heading(b.optString("v"))
                            "img" -> pageFrom(dir, b)?.let { TextBlock.Picture(it) }
                            "hr" -> TextBlock.Divider
                            else -> null
                        }
                    }
                    ReaderContent.Text(blocks)
                }
                else -> null
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "EpisodeStore"
        private const val MAX_CHUNK = 8L * 1024 * 1024

        private val IMAGE_EXT = Regex("(?i)\\.(jpe?g|png|webp|gif|bmp|avif)$")
        private val HTML_EXT = Regex("(?i)\\.(xhtml|html|htm)$")

        fun isImage(name: String) = IMAGE_EXT.containsMatchIn(name)
        fun isHtml(name: String) = HTML_EXT.containsMatchIn(name)
        fun isHidden(name: String) = name.split('/').any { it.startsWith(".") || it == "__MACOSX" }
        fun ext(name: String) = name.substringAfterLast('.', "jpg").lowercase(Locale.US)
    }
}
