package com.flashdrop

import android.content.Context
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Same HTTP API as the Python prototype, so the same web UI (assets/index.html) works unchanged. */
class FlashServer(
    private val ctx: Context,
    root: File,
    private val token: String,
    port: Int
) : NanoHTTPD(port) {

    private val rootPath: String = root.canonicalPath
    private val trashName = ".flashdrop_trash"

    private class Up(val part: File, val target: File, val total: Long) {
        val got = ConcurrentHashMap<Long, Long>()
    }

    private val uploads = ConcurrentHashMap<String, Up>()
    private val clip = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
    private class Forbidden : Exception()

    // ---------- helpers ----------
    private fun json(o: Any, status: Status = Status.OK): Response =
        newFixedLengthResponse(status, "application/json", o.toString())

    private fun text(status: Status, t: String): Response =
        newFixedLengthResponse(status, "text/plain", t)

    private fun IHTTPSession.q(k: String): String? = parameters[k]?.firstOrNull()

    private fun safe(rel: String): File {
        val f = File(rootPath, rel.trimStart('/', '\\')).canonicalFile
        if (f.path != rootPath && !f.path.startsWith(rootPath + File.separator)) throw Forbidden()
        return f
    }

    private fun relOf(f: File): String = if (f.path == rootPath) "" else f.path.substring(rootPath.length + 1)

    private fun kind(name: String, dir: Boolean): String {
        if (dir) return "folder"
        val ext = name.substringAfterLast('.', "").lowercase()
        val map = mapOf(
            "image" to "jpg jpeg png gif webp bmp svg heic",
            "video" to "mp4 mkv avi mov webm 3gp flv",
            "audio" to "mp3 wav flac aac ogg m4a opus",
            "doc" to "pdf doc docx xls xlsx ppt pptx txt md csv rtf odt",
            "archive" to "zip rar 7z tar gz bz2",
            "app" to "apk exe msi dmg deb",
            "code" to "py js ts html css json xml c cpp java go rs sh"
        )
        for ((k, v) in map) if (ext in v.split(" ")) return k
        return "file"
    }

    private fun entry(f: File): JSONObject {
        val d = f.isDirectory
        return JSONObject()
            .put("name", f.name).put("path", relOf(f)).put("dir", d)
            .put("size", if (d) 0 else f.length())
            .put("mtime", f.lastModified() / 1000).put("kind", kind(f.name, d))
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun bodyLen(s: IHTTPSession): Long = s.headers["content-length"]?.toLongOrNull() ?: 0L

    private fun readBody(s: IHTTPSession): ByteArray {
        val n = bodyLen(s).toInt()
        val b = ByteArray(n)
        var o = 0
        val i = s.inputStream
        while (o < n) {
            val r = i.read(b, o, n - o)
            if (r < 0) break
            o += r
        }
        return b
    }

    // ---------- routing ----------
    override fun serve(s: IHTTPSession): Response {
        return try {
            var setCookie = false
            if (token.isNotEmpty()) {
                val t = s.q("t") ?: s.cookies.read("fd_token") ?: s.headers["x-token"]
                if (t != token) {
                    return text(Status.UNAUTHORIZED, "Unauthorized. Scan the QR code or use the full link shown in the FlashDrop app.")
                }
                setCookie = s.q("t") != null
            }
            val r = route(s)
            if (setCookie) r.addHeader("Set-Cookie", "fd_token=$token; Max-Age=2592000; Path=/; SameSite=Lax")
            r
        } catch (e: Forbidden) {
            text(Status.FORBIDDEN, "outside root")
        } catch (e: Exception) {
            text(Status.INTERNAL_ERROR, e.toString())
        }
    }

    private fun route(s: IHTTPSession): Response {
        val post = s.method == Method.POST
        return when (s.uri) {
            "/", "/index.html" -> newChunkedResponse(Status.OK, "text/html", ctx.assets.open("index.html"))
            "/api/info" -> info()
            "/api/ls" -> ls(s)
            "/api/search" -> search(s)
            "/api/file" -> download(s)
            "/api/zip" -> zip(s)
            "/api/upload" -> upload(s)
            "/api/upload_status" -> json(JSONObject().put("offsets", JSONArray(uploads[s.q("id")]?.got?.keys?.sorted() ?: emptyList<Long>())))
            "/api/op" -> ops(s)
            "/api/dupes" -> dupes(s)
            "/api/usage" -> usage(s)
            "/api/clip" -> if (post) clipPost(s) else clipGet()
            "/api/text" -> textFile(s)
            "/api/speedtest" -> if (post) speedUp(s) else speedDown(s)
            else -> text(Status.NOT_FOUND, "not found")
        }
    }

    // ---------- API ----------
    private fun info(): Response {
        val root = File(rootPath)
        val total = root.totalSpace
        val used = total - root.freeSpace
        val short = JSONArray()
        for (n in listOf("DCIM", "Download", "Documents", "Pictures", "Movies", "Music", "WhatsApp", "FlashDrop"))
            if (File(root, n).isDirectory) short.put(n)
        val urls = JSONArray()
        ServerState.urls.forEach { urls.put(it) }
        return json(
            JSONObject().put("host", android.os.Build.MODEL).put("root", rootPath).put("urls", urls).put("qr", "")
                .put("disk", JSONObject().put("total", total).put("used", used).put("free", root.freeSpace))
                .put("shortcuts", short)
        )
    }

    private fun ls(s: IHTTPSession): Response {
        val p = safe(s.q("path") ?: "")
        if (!p.isDirectory) return text(Status.NOT_FOUND, "no such folder")
        val hidden = s.q("hidden") == "1"
        val arr = JSONArray()
        p.listFiles()?.forEach {
            if (it.name == trashName || (it.name.startsWith(".") && !hidden)) return@forEach
            arr.put(entry(it))
        }
        return json(JSONObject().put("path", relOf(p)).put("items", arr))
    }

    private fun search(s: IHTTPSession): Response {
        val q = (s.q("q") ?: "").lowercase().trim()
        val base = safe(s.q("path") ?: "")
        val arr = JSONArray()
        var n = 0
        base.walkTopDown().onEnter { !it.name.startsWith(".") || it == base }.forEach {
            if (it == base || n >= 500) return@forEach
            if (q.isEmpty() || it.name.lowercase().contains(q)) {
                arr.put(entry(it)); n++
            }
        }
        return json(JSONObject().put("items", arr))
    }

    private fun download(s: IHTTPSession): Response {
        val f = safe(s.q("path") ?: "")
        if (!f.isFile) return text(Status.NOT_FOUND, "no such file")
        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false
        val range = s.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val r = range.substring(6).split("-")
            if (r[0].isEmpty() && r.size > 1 && r[1].isNotEmpty()) {
                start = maxOf(0L, len - r[1].toLong())
            } else {
                if (r[0].isNotEmpty()) start = r[0].toLong()
                if (r.size > 1 && r[1].isNotEmpty()) end = minOf(end, r[1].toLong())
            }
            partial = true
        }
        if (len == 0L) return newFixedLengthResponse(Status.OK, mimeOf(f.name), "")
        if (start >= len || start > end) {
            return newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain", "").also {
                it.addHeader("Content-Range", "bytes */$len")
            }
        }
        val fis = FileInputStream(f)
        fis.channel.position(start)
        val resp = newFixedLengthResponse(
            if (partial) Status.PARTIAL_CONTENT else Status.OK, mimeOf(f.name), fis, end - start + 1
        )
        resp.addHeader("Accept-Ranges", "bytes")
        if (partial) resp.addHeader("Content-Range", "bytes $start-$end/$len")
        val disp = if (s.q("inline") == "1") "inline" else "attachment"
        resp.addHeader("Content-Disposition", "$disp; filename*=UTF-8''" + URLEncoder.encode(f.name, "UTF-8").replace("+", "%20"))
        return resp
    }

    private fun zip(s: IHTTPSession): Response {
        val paths = s.parameters["path"] ?: emptyList()
        val files = mutableListOf<Pair<File, String>>()
        for (p in paths) {
            val f = safe(p)
            if (f.isFile) files.add(f to f.name)
            else if (f.isDirectory) {
                val parent = f.parentFile ?: f
                f.walkTopDown().filter { it.isFile && !it.path.contains("/$trashName/") }
                    .forEach { files.add(it to it.relativeTo(parent).path) }
            }
        }
        val pin = PipedInputStream(1 shl 20)
        val pout = PipedOutputStream(pin)
        Thread {
            try {
                ZipOutputStream(BufferedOutputStream(pout, 1 shl 16)).use { z ->
                    z.setLevel(0) // no compression = max speed
                    val buf = ByteArray(1 shl 20)
                    for ((f, n) in files) {
                        try {
                            z.putNextEntry(ZipEntry(n.replace('\\', '/')))
                            f.inputStream().use { i ->
                                while (true) {
                                    val r = i.read(buf)
                                    if (r < 0) break
                                    z.write(buf, 0, r)
                                }
                            }
                            z.closeEntry()
                        } catch (e: IOException) {
                            if (e.message?.contains("ipe") == true) throw e
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }.start()
        val name = (if (paths.size == 1) File(paths[0]).name else "FlashDrop").ifEmpty { "files" }
        val resp = newChunkedResponse(Status.OK, "application/zip", pin)
        resp.addHeader("Content-Disposition", "attachment; filename=\"$name.zip\"")
        return resp
    }

    private fun upload(s: IHTTPSession): Response {
        val folder = safe(s.q("path") ?: "")
        folder.mkdirs()
        val name = File((s.q("name") ?: "file").replace("\\", "/")).name.ifEmpty { "file" }
        val id = s.q("id") ?: return text(Status.BAD_REQUEST, "id")
        val offset = s.q("offset")?.toLongOrNull() ?: return text(Status.BAD_REQUEST, "offset")
        val total = s.q("total")?.toLongOrNull() ?: return text(Status.BAD_REQUEST, "total")

        val u = synchronized(uploads) {
            uploads.getOrPut(id) {
                val stem = name.substringBeforeLast('.', name)
                val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
                var target = File(folder, name)
                var n = 1
                while (target.exists() || File(target.path + ".part").exists()) {
                    target = File(folder, "$stem ($n)$ext"); n++
                }
                val part = File(target.path + ".part")
                RandomAccessFile(part, "rw").use { it.setLength(total) }
                Up(part, target, total)
            }
        }
        val len = bodyLen(s)
        var remaining = len
        val buf = ByteArray(256 * 1024)
        RandomAccessFile(u.part, "rw").use { raf ->
            raf.seek(offset)
            while (remaining > 0) {
                val r = s.inputStream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (r < 0) break
                raf.write(buf, 0, r)
                remaining -= r
            }
        }
        if (remaining > 0) return text(Status.BAD_REQUEST, "short body")
        u.got.putIfAbsent(offset, len)
        val done = u.got.values.sum() >= u.total
        if (done) {
            synchronized(u) { if (u.part.exists()) u.part.renameTo(u.target) }
            uploads.remove(id)
        }
        return json(JSONObject().put("ok", true).put("done", done).put("name", u.target.name))
    }

    private fun ops(s: IHTTPSession): Response {
        val d = JSONObject(String(readBody(s), Charsets.UTF_8))
        fun paths(): List<String> = (0 until d.getJSONArray("paths").length()).map { d.getJSONArray("paths").getString(it) }
        fun unique(dir: File, f: File): File {
            var t = File(dir, f.name)
            var n = 1
            while (t.exists()) {
                t = File(dir, "${f.nameWithoutExtension} ($n)" + (if (f.extension.isNotEmpty()) "." + f.extension else "")); n++
            }
            return t
        }
        when (d.getString("op")) {
            "mkdir" -> safe(d.getString("path")).mkdirs()
            "rename" -> {
                val src = safe(d.getString("path"))
                val dst = File(src.parentFile, File(d.getString("name")).name)
                if (dst.exists()) return text(Status.CONFLICT, "exists")
                src.renameTo(dst)
            }
            "delete" -> for (p in paths()) {
                val f = safe(p)
                if (d.optBoolean("permanent")) f.deleteRecursively()
                else {
                    val tr = File(rootPath, trashName); tr.mkdirs()
                    val t = File(tr, "${System.currentTimeMillis() / 1000}_${f.name}")
                    if (!f.renameTo(t)) { f.copyRecursively(t, true); f.deleteRecursively() }
                }
            }
            "move", "copy" -> {
                val dest = safe(d.getString("dest")); dest.mkdirs()
                for (p in paths()) {
                    val f = safe(p); val t = unique(dest, f)
                    if (d.getString("op") == "move") {
                        if (!f.renameTo(t)) { f.copyRecursively(t, true); f.deleteRecursively() }
                    } else f.copyRecursively(t, true)
                }
            }
            "bulkrename" -> {
                var n = d.optInt("start", 1)
                val pad = d.optInt("pad", 3)
                for (p in paths()) {
                    val f = safe(p)
                    val nn = d.getString("pattern").replace("{n}", n.toString().padStart(pad, '0')) +
                        (if (f.extension.isNotEmpty()) "." + f.extension else "")
                    f.renameTo(File(f.parentFile, nn)); n++
                }
            }
            else -> return text(Status.BAD_REQUEST, "bad op")
        }
        return json(JSONObject().put("ok", true))
    }

    private fun hash(f: File, partial: Boolean): String {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { i ->
            val buf = ByteArray(if (partial) 65536 else 1 shl 20)
            if (partial) { val r = i.read(buf); if (r > 0) md.update(buf, 0, r) }
            else while (true) { val r = i.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun dupes(s: IHTTPSession): Response {
        val base = safe(s.q("path") ?: "")
        val bySize = HashMap<Long, MutableList<File>>()
        base.walkTopDown().onEnter { !it.name.startsWith(".") || it == base }.filter { it.isFile && it.length() > 0 }
            .forEach { bySize.getOrPut(it.length()) { mutableListOf() }.add(it) }
        class G(val size: Long, val files: List<File>)
        val groups = mutableListOf<G>()
        for ((size, fs) in bySize) {
            if (fs.size < 2) continue
            val byPart = HashMap<String, MutableList<File>>()
            for (f in fs) try { byPart.getOrPut(hash(f, true)) { mutableListOf() }.add(f) } catch (_: IOException) {}
            for (cand in byPart.values) {
                if (cand.size < 2) continue
                val full = HashMap<String, MutableList<File>>()
                for (f in cand) try { full.getOrPut(hash(f, false)) { mutableListOf() }.add(f) } catch (_: IOException) {}
                for (g in full.values) if (g.size > 1) groups.add(G(size, g))
            }
        }
        groups.sortByDescending { it.size * (it.files.size - 1) }
        val arr = JSONArray()
        groups.take(200).forEach { g ->
            arr.put(JSONObject().put("size", g.size).put("files", JSONArray().also { a -> g.files.forEach { a.put(entry(it)) } }))
        }
        return json(JSONObject().put("groups", arr))
    }

    private fun usage(s: IHTTPSession): Response {
        val base = safe(s.q("path") ?: "")
        val kinds = HashMap<String, Long>()
        val all = mutableListOf<File>()
        base.walkTopDown().onEnter { !it.name.startsWith(".") || it == base }.filter { it.isFile }.forEach {
            val k = kind(it.name, false)
            kinds[k] = (kinds[k] ?: 0L) + it.length()
            all.add(it)
        }
        all.sortByDescending { it.length() }
        val ko = JSONObject(); kinds.forEach { (k, v) -> ko.put(k, v) }
        val lg = JSONArray(); all.take(30).forEach { lg.put(entry(it)) }
        return json(JSONObject().put("kinds", ko).put("largest", lg))
    }

    private fun clipGet(): Response {
        val arr = JSONArray()
        synchronized(clip) { clip.takeLast(50).forEach { arr.put(JSONObject().put("text", it.first).put("t", it.second)) } }
        return json(JSONObject().put("items", arr))
    }

    private fun clipPost(s: IHTTPSession): Response {
        val d = JSONObject(String(readBody(s), Charsets.UTF_8))
        if (d.optBoolean("clear")) clip.clear()
        else if (d.optString("text").isNotEmpty()) clip.add(d.getString("text").take(100000) to System.currentTimeMillis() / 1000)
        return json(JSONObject().put("ok", true))
    }

    private fun textFile(s: IHTTPSession): Response {
        val f = safe(s.q("path") ?: "")
        if (!f.isFile || f.length() > 2_000_000) return text(Status.BAD_REQUEST, "too large")
        return newFixedLengthResponse(Status.OK, "text/plain; charset=utf-8", f.readText())
    }

    private fun speedDown(s: IHTTPSession): Response {
        val mb = minOf(s.q("mb")?.toIntOrNull() ?: 50, 500).toLong()
        val total = mb * 1024 * 1024
        val stream = object : InputStream() {
            var left = total
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                java.util.Arrays.fill(b, off, off + n, 0)
                left -= n
                return n
            }
        }
        return newFixedLengthResponse(Status.OK, "application/octet-stream", stream, total)
    }

    private fun speedUp(s: IHTTPSession): Response {
        var left = bodyLen(s)
        var n = 0L
        val buf = ByteArray(1 shl 20)
        while (left > 0) {
            val r = s.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r; n += r
        }
        return json(JSONObject().put("bytes", n))
    }
}
