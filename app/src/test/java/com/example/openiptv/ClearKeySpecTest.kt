package com.example.openiptv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** All keys here are made-up test vectors. */
class ClearKeySpecTest {
    private val kidHex = "00112233445566778899aabbccddeeff"
    private val keyHex = "0f0e0d0c0b0a09080706050403020100"
    private val kidB64 = "ABEiM0RVZneImaq7zN3u_w"
    private val keyB64 = "Dw4NDAsKCQgHBgUEAwIBAA"

    // 16-byte values whose plain base64 contains '+' and '/' and padding
    private val kid2Hex = "fbffbefbffbefbffbefbffbefbffbeff"
    private val key2Hex = "3ef83ff83ef83ff83ef83ff83ef83ff8"
    private val kid2Std = "+/+++/+++/+++/+++/++/w=="
    private val key2Std = "Pvg/+D74P/g++D/4Pvg/+A=="
    private val kid2Url = "-_---_---_---_---_--_w"
    private val key2Url = "Pvg_-D74P_g--D_4Pvg_-A"

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun json(spec: String) = String(ClearKeySpec.toLicenseJson(ClearKeySpec.parse(spec)!!))

    @Test fun parsesHexPair() {
        val keys = ClearKeySpec.parse("$kidHex:$keyHex")!!
        assertEquals(1, keys.size)
        assertArrayEquals(hex(kidHex), keys[0].kid)
        assertArrayEquals(hex(keyHex), keys[0].key)
    }

    @Test fun hexPairProducesBase64UrlLicense() {
        assertEquals(
            """{"keys":[{"kty":"oct","kid":"$kidB64","k":"$keyB64"}],"type":"temporary"}""",
            json("$kidHex:$keyHex"),
        )
    }

    @Test fun parsesSeveralPairsUppercaseAndSpaces() {
        val keys = ClearKeySpec.parse(" ${kidHex.uppercase()}:${keyHex.uppercase()} , $kid2Hex : $key2Hex ")!!
        assertEquals(2, keys.size)
        assertArrayEquals(hex(kid2Hex), keys[1].kid)
        assertArrayEquals(hex(key2Hex), keys[1].key)
    }

    @Test fun rejectsMalformedHexPairs() {
        for (s in listOf("", "   ", kidHex, "$kidHex:", ":$keyHex", "$kidHex:$keyHex:$keyHex", "${kidHex.dropLast(2)}:$keyHex",
            "${kidHex}00:$keyHex", "zz${kidHex.drop(2)}:$keyHex", "$kidHex:$keyHex,", "$kidHex:$keyHex,garbage")) {
            assertNull("should reject '$s'", ClearKeySpec.parse(s))
        }
    }

    @Test fun parsesJsonKeyObject() {
        val spec = """{"keys":[{"kty":"oct","kid":"$kidB64","k":"$keyB64"}],"type":"temporary"}"""
        val keys = ClearKeySpec.parse(spec)!!
        assertArrayEquals(hex(kidHex), keys[0].kid)
        assertArrayEquals(hex(keyHex), keys[0].key)
    }

    @Test fun jsonAndHexGiveTheSameLicense() {
        val j = """{"keys":[{"kty":"oct","kid":"$kidB64","k":"$keyB64"}],"type":"temporary"}"""
        assertEquals(json("$kidHex:$keyHex"), json(j))
    }

    @Test fun normalizesPlainBase64ToBase64Url() {
        // key order k-before-kid, '+' '/' and '=' padding, as seen in real playlists
        val spec = """{"keys":[{"kty":"oct","k":"$key2Std","kid":"$kid2Std"}],"type":"temporary"}"""
        val keys = ClearKeySpec.parse(spec)!!
        assertArrayEquals(hex(kid2Hex), keys[0].kid)
        assertArrayEquals(hex(key2Hex), keys[0].key)
        assertEquals(
            """{"keys":[{"kty":"oct","kid":"$kid2Url","k":"$key2Url"}],"type":"temporary"}""",
            json(spec),
        )
    }

    @Test fun jsonWithSeveralKeysAndWhitespace() {
        val spec = """
            { "keys" : [
                { "kty":"oct", "kid":"$kidB64", "k":"$keyB64" },
                { "kid":"$kid2Url", "k":"$key2Url" }
              ],
              "type" : "temporary" }
        """.trimIndent()
        assertEquals(2, ClearKeySpec.parse(spec)!!.size)
    }

    @Test fun rejectsBadJson() {
        val good = """{"keys":[{"kty":"oct","kid":"$kidB64","k":"$keyB64"}]}"""
        assertNotNull(ClearKeySpec.parse(good))
        for (s in listOf(
            "{",
            "{}",
            """{"keys":[]}""",
            """{"keys":"x"}""",
            """{"keys":[1]}""",
            """{"keys":[{"kty":"RSA","kid":"$kidB64","k":"$keyB64"}]}""",
            """{"keys":[{"kid":"$kidB64"}]}""",
            """{"keys":[{"k":"$keyB64"}]}""",
            """{"keys":[{"kid":"AAAA","k":"AAAA"}]}""", // 3-byte values, not 16
            """{"keys":[{"kid":"!!!!","k":"$keyB64"}]}""",
            """{"keys":[{"kid":"$kidB64","k":"$keyB64"},]}""", // trailing comma
            """$good garbage""",
            """{"keys":[{"kid":"$kidB64","k":"$keyB64"}]""",
        )) {
            assertNull("should reject '$s'", ClearKeySpec.parse(s))
        }
    }

    @Test fun deeplyNestedJsonIsRejectedNotACrash() {
        assertNull(ClearKeySpec.parse("""{"keys":""" + "[".repeat(50_000)))
        assertNull(ClearKeySpec.parse("""{"keys":""" + "[".repeat(50_000) + "]".repeat(50_000) + "}"))
    }

    @Test fun jsonStringEscapesAreDecoded() {
        // "K" is "K"; kty "oct" written with escapes still matches
        val spec = """{"keys":[{"kty":"oct","kid":"$kidB64","k":"$keyB64"}],"note":"a\"b\\c\n"}"""
        assertNotNull(ClearKeySpec.parse(spec))
    }

    @Test fun base64DecodeRejectsGarbage() {
        assertNull(ClearKeySpec.base64Decode(""))
        assertNull(ClearKeySpec.base64Decode("A"))      // 1 char left over is never valid
        assertNull(ClearKeySpec.base64Decode("AB*D"))
        assertArrayEquals(byteArrayOf(0x66), ClearKeySpec.base64Decode("Zg=="))
        assertArrayEquals(byteArrayOf(0x66), ClearKeySpec.base64Decode("Zg"))
    }

    @Test fun base64UrlEncodeHasNoPaddingOrUnsafeChars() {
        assertEquals("Zg", ClearKeySpec.base64UrlEncode(byteArrayOf(0x66)))
        assertEquals("Zm8", ClearKeySpec.base64UrlEncode("fo".toByteArray()))
        assertEquals("Zm9v", ClearKeySpec.base64UrlEncode("foo".toByteArray()))
        assertEquals(kid2Url, ClearKeySpec.base64UrlEncode(hex(kid2Hex)))
    }
}
