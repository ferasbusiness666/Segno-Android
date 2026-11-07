package io.github.ferasbusiness666.segno.lyrics

import com.grack.nanojson.JsonParser
import io.github.ferasbusiness666.segno.BuildConfig
import io.github.ferasbusiness666.segno.core.Net
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A lyrics place couldn't be asked (no connection, an error, or an answer it can't read). */
class SourceException(message: String) : IOException(message)

/** Requests for the lyrics places: one shared connection pool, a time limit per place. Blocking. */
object Web {
    /** What LRCLIB asks apps to send: who's asking. */
    val APP_AGENT = "Segno-Android/${BuildConfig.VERSION_NAME} (https://github.com/ferasbusiness666/Segno-Android)"

    private val clients = HashMap<Long, OkHttpClient>()

    private fun client(secs: Long): OkHttpClient = synchronized(clients) {
        clients.getOrPut(secs) { Net.client.newBuilder().callTimeout(secs, TimeUnit.SECONDS).build() }
    }

    private const val MAX_BYTES = 4L shl 20

    private fun call(req: Request, secs: Long): String {
        client(secs).newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw SourceException("HTTP ${r.code}")
            val body = r.body
            if (body.contentLength() > MAX_BYTES) throw SourceException("Too big an answer")
            return body.string()
        }
    }

    /** Send JSON the way a browser would and get the answer as text. */
    fun postJson(url: String, json: String, headers: List<Pair<String, String>>, secs: Long): String {
        val b = Request.Builder().url(url)
            .header("User-Agent", Net.BROWSER_UA)
            .header("Accept-Language", "en")
            .post(json.toRequestBody("application/json".toMediaType()))
        for ((k, v) in headers) b.header(k, v)
        return call(b.build(), secs)
    }

    fun getWith(url: String, headers: List<Pair<String, String>>, secs: Long): String {
        val b = Request.Builder().url(url).header("User-Agent", Net.BROWSER_UA).header("Accept-Language", "en")
        for ((k, v) in headers) b.header(k, v)
        return call(b.build(), secs)
    }

    /** An answer from a web API, told apart the way LRCLIB needs. */
    sealed interface Answer {
        data class Text(val text: String) : Answer
        data object NotFound : Answer
        /** Busy or overloaded (429, 502–504): ask again after this long. */
        data class Busy(val waitMs: Long) : Answer
        /** Any other error status. */
        data class Broken(val status: Int) : Answer
    }

    /** A small text answer from a web API. Throws IOException for a connection problem. */
    fun getApi(url: String, secs: Long = 10): Answer {
        val req = Request.Builder().url(url).header("User-Agent", APP_AGENT).build()
        client(secs).newCall(req).execute().use { r ->
            return when (val s = r.code) {
                in 200..299 -> Answer.Text(r.body.string())
                404 -> Answer.NotFound
                429, 502, 503, 504 -> {
                    val secsWait = r.header("retry-after")?.trim()?.toLongOrNull() ?: 1
                    Answer.Busy(secsWait.coerceIn(1, 5) * 1000)
                }
                else -> Answer.Broken(s)
            }
        }
    }

    /** Parse JSON into maps, lists, strings, numbers and booleans. */
    fun json(text: String): Any? = try {
        JsonParser.any().from(text)
    } catch (e: Exception) {
        throw SourceException("An answer that isn't JSON")
    }

    /** Percent-encode for a URL query, like desktop's enc. */
    fun enc(s: String): String {
        val sb = StringBuilder(s.length * 3)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
        return sb.toString()
    }
}

/** Reading JSON loosely: parts by name anywhere in the answer. */
internal object J {
    fun obj(v: Any?): Map<*, *>? = v as? Map<*, *>
    fun arr(v: Any?): List<*>? = v as? List<*>
    fun str(v: Any?): String? = v as? String
    fun num(v: Any?): Number? = v as? Number
    fun get(v: Any?, key: String): Any? = obj(v)?.get(key)

    /**
     * Every value called [key], anywhere in [v], in order. Object keys are visited sorted, as
     * desktop's serde_json does, so both apps take the same "first" one.
     */
    fun find(v: Any?, key: String, out: MutableList<Any?> = ArrayList()): MutableList<Any?> {
        when (v) {
            is Map<*, *> -> for (k in v.keys.map { it.toString() }.sorted()) {
                val x = v[k]
                if (k == key) out += x else find(x, key, out)
            }
            is List<*> -> for (x in v) find(x, key, out)
        }
        return out
    }

    /** The first value called [key] (same order as [find]), without walking the rest. */
    fun first(v: Any?, key: String): Any? = firstOr(v, key).takeIf { it !== NONE }

    private val NONE = Any()

    private fun firstOr(v: Any?, key: String): Any? {
        when (v) {
            is Map<*, *> -> for (k in v.keys.map { it.toString() }.sorted()) {
                val x = v[k]
                if (k == key) return x
                val r = firstOr(x, key)
                if (r !== NONE) return r
            }
            is List<*> -> for (x in v) {
                val r = firstOr(x, key)
                if (r !== NONE) return r
            }
        }
        return NONE
    }
}
