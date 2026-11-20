package com.example.openiptv

import java.io.ByteArrayOutputStream

/** One ClearKey pair: a 16-byte key id and its 16-byte content key. */
class ClearKeyEntry(val kid: ByteArray, val key: ByteArray)

/**
 * Parses the value of #KODIPROP:inputstream.adaptive.license_key for license_type=clearkey.
 * Pure Kotlin (no Android classes) so it can be unit-tested on the JVM.
 *
 * Two formats are accepted:
 *  - hex pairs:  `kid_hex32:key_hex32[,kid_hex32:key_hex32...]`
 *  - JSON:       `{"keys":[{"kty":"oct","kid":"<base64>","k":"<base64>"}],"type":"temporary"}`
 *    where kid/k may be base64url or plain base64 (with or without padding), in any key order.
 */
object ClearKeySpec {
    private val HEX32 = Regex("^[0-9a-fA-F]{32}$")

    /** Returns null if [spec] is malformed or has no keys. */
    fun parse(spec: String): List<ClearKeyEntry>? {
        val s = spec.trim()
        val entries = if (s.startsWith("{")) parseJson(s) else parseHexPairs(s)
        return entries?.takeIf { it.isNotEmpty() }
    }

    /** The JWK set Android's ClearKey plugin expects: base64url, no padding. */
    fun toLicenseJson(entries: List<ClearKeyEntry>): ByteArray {
        val keys = entries.joinToString(",") {
            """{"kty":"oct","kid":"${base64UrlEncode(it.kid)}","k":"${base64UrlEncode(it.key)}"}"""
        }
        return """{"keys":[$keys],"type":"temporary"}""".toByteArray(Charsets.UTF_8)
    }

    private fun parseHexPairs(s: String): List<ClearKeyEntry>? {
        val out = ArrayList<ClearKeyEntry>()
        for (pair in s.split(',')) {
            val parts = pair.trim().split(':')
            if (parts.size != 2) return null
            val kid = parts[0].trim()
            val key = parts[1].trim()
            if (!HEX32.matches(kid) || !HEX32.matches(key)) return null
            out.add(ClearKeyEntry(hexToBytes(kid), hexToBytes(key)))
        }
        return out
    }

    private fun parseJson(s: String): List<ClearKeyEntry>? {
        val root = MiniJson.parse(s) as? Map<*, *> ?: return null
        val keys = root["keys"] as? List<*> ?: return null
        val out = ArrayList<ClearKeyEntry>()
        for (item in keys) {
            val obj = item as? Map<*, *> ?: return null
            val kty = obj["kty"]
            if (kty != null && kty != "oct") return null
            val kid = (obj["kid"] as? String)?.let { base64Decode(it) } ?: return null
            val key = (obj["k"] as? String)?.let { base64Decode(it) } ?: return null
            if (kid.size != 16 || key.size != 16) return null
            out.add(ClearKeyEntry(kid, key))
        }
        return out
    }

    private fun hexToBytes(hex: String) =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Accepts both base64 alphabets, padded or not. Returns null on invalid input. */
    internal fun base64Decode(input: String): ByteArray? {
        val s = input.trim().trimEnd('=').replace('-', '+').replace('_', '/')
        if (s.isEmpty() || s.length % 4 == 1) return null
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s) {
            val v = STD.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }

    internal fun base64UrlEncode(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < b.size) {
            val b0 = b[i].toInt() and 0xFF
            val b1 = if (i + 1 < b.size) b[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < b.size) b[i + 2].toInt() and 0xFF else 0
            sb.append(URL_SAFE[b0 shr 2])
            sb.append(URL_SAFE[((b0 and 3) shl 4) or (b1 shr 4)])
            if (i + 1 < b.size) sb.append(URL_SAFE[((b1 and 15) shl 2) or (b2 shr 6)])
            if (i + 2 < b.size) sb.append(URL_SAFE[b2 and 63])
            i += 3
        }
        return sb.toString()
    }
}

/**
 * Minimal strict JSON reader (objects, arrays, strings, numbers, booleans, null) so the parser has no
 * dependency on org.json, which is a stub on plain JVM unit tests. Playlists are remote input, so
 * nesting depth is capped. Returns null on any syntax error.
 */
internal object MiniJson {
    private const val MAX_DEPTH = 32

    private class Bad : Exception()

    fun parse(text: String): Any? = try {
        val r = Reader(text)
        val v = r.value(0)
        r.skipWs()
        if (!r.atEnd()) throw Bad()
        v
    } catch (e: Bad) {
        null
    }

    private class Reader(val s: String) {
        var i = 0

        fun atEnd() = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw Bad()
            skipWs()
            if (atEnd()) throw Bad()
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw Bad()
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return m }
            while (true) {
                skipWs()
                if (peek() != '"') throw Bad()
                val k = str()
                skipWs()
                if (peek() != ':') throw Bad()
                i++
                m[k] = value(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw Bad()
                }
            }
        }

        fun arr(depth: Int): List<Any?> {
            i++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { i++; return l }
            while (true) {
                l.add(value(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw Bad()
                }
            }
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw Bad()
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> throw Bad()
                    c != '\\' -> sb.append(c)
                    else -> {
                        if (atEnd()) throw Bad()
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw Bad()
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw Bad()
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw Bad()
                        }
                    }
                }
            }
        }

        fun num(): Double {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw Bad()
        }

        fun literal(word: String, result: Any?): Any? {
            if (!s.startsWith(word, i)) throw Bad()
            i += word.length
            return result
        }

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
    }
}
