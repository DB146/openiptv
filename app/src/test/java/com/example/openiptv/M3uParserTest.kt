package com.example.openiptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Playlist snippets are modelled on real-world IPTV lists; hosts and keys are made up. */
class M3uParserTest {
    private val kid = "00112233445566778899aabbccddeeff"
    private val key = "0f0e0d0c0b0a09080706050403020100"
    private val dollar = '$' // raw strings can't escape "$", and catch-up templates contain "${start}"

    private fun parse(vararg lines: String) = M3uParser.parseDocument(lines.joinToString("\n"))

    // ---- header ----------------------------------------------------------------

    @Test fun headerUrlTvgIsParsedAndValidated() {
        val d = parse(
            """#EXTM3U url-tvg="https://epg.example.com/a.xml,http://epg.example.com/b.xml.gz ftp://bad.example.com/c.xml"""",
        )
        assertEquals(listOf("https://epg.example.com/a.xml", "http://epg.example.com/b.xml.gz"), d.header.epgUrls)
        assertTrue(d.header.attributes.containsKey("url-tvg"))
    }

    @Test fun headerAliasesAndMissingHeader() {
        assertEquals(listOf("https://e.example.com/x.xml"), parse("""#EXTM3U x-tvg-url="https://e.example.com/x.xml"""").header.epgUrls)
        assertEquals(emptyList<String>(), parse("#EXTM3U").header.epgUrls)
        assertEquals(emptyList<String>(), parse("#EXTINF:-1,A", "https://h/a.m3u8").header.epgUrls)
    }

    // ---- #EXTINF ---------------------------------------------------------------

    @Test fun extinfAttributesAreParsed() {
        val c = parse(
            "#EXTM3U",
            """#EXTINF:-1 tvg-id="vtv8hd" group-title="VTV" tvg-logo="https://img.example.com/l.png?w=800&fit=scale-down&q=85" catchup="append" catchup-days="2" catchup-source="https://dvr.example.com/v1/${dollar}{start}/${dollar}{offset}", VTV8""",
            "https://cdn.example.com/live/vtv8.m3u8",
        ).channels.single()
        assertEquals(1, c.number)
        assertEquals("VTV8", c.name)
        assertEquals("vtv8hd", c.tvgId)
        assertEquals("VTV", c.group)
        assertEquals("https://img.example.com/l.png?w=800&fit=scale-down&q=85", c.logo)
        assertEquals("append", c.catchup)
        assertEquals(2, c.catchupDays)
        assertEquals("https://dvr.example.com/v1/\${start}/\${offset}", c.catchupSource)
        assertEquals(StreamType.HLS, c.streamType)
    }

    @Test fun catchupDefaultWithoutSource() {
        val c = parse("""#EXTINF:-1 catchup="default" catchup-days="6", X""", "https://h/x.m3u8").channels.single()
        assertEquals("default", c.catchup)
        assertEquals(6, c.catchupDays)
        assertNull(c.catchupSource)
    }

    @Test fun badCatchupDaysIsIgnored() {
        assertNull(parse("""#EXTINF:-1 catchup-days="abc", X""", "https://h/x.m3u8").channels.single().catchupDays)
        assertNull(parse("""#EXTINF:-1 catchup-days="-3", X""", "https://h/x.m3u8").channels.single().catchupDays)
    }

    @Test fun titleKeepsItsOwnCommas() {
        assertEquals("Name, With Comma", parse("""#EXTINF:-1 tvg-id="x", Name, With Comma""", "https://h/x.m3u8").channels.single().name)
    }

    @Test fun commaInsideQuotedAttributeIsNotTheTitleSeparator() {
        val c = parse("""#EXTINF:-1 group-title="News, Sports" tvg-id="n", Channel N""", "https://h/x.m3u8").channels.single()
        assertEquals("News, Sports", c.group)
        assertEquals("Channel N", c.name)
    }

    @Test fun emptyAttributesAreNullAndZeroDurationIsFine() {
        val c = parse("""#EXTINF:0 tvg-id="" tvg-logo="" group-title="Intl", Filmbox""", "https://h/x.m3u8").channels.single()
        assertNull(c.tvgId)
        assertNull(c.logo)
        assertEquals("Filmbox", c.name)
    }

    @Test fun unknownAttributesAreKept() {
        val c = parse("""#EXTINF:-1 tvg-id="a" type="stream", A""", "https://h/x.m3u8").channels.single()
        assertEquals("stream", c.attributes["type"])
    }

    @Test fun nameFallsBackToTvgIdThenHost() {
        assertEquals("abc", parse("""#EXTINF:-1 tvg-id="abc",""", "https://h/x.m3u8").channels.single().name)
        assertEquals("h.example.com", parse("#EXTINF:-1,", "https://h.example.com/x.m3u8").channels.single().name)
    }

    @Test fun unbalancedQuoteDoesNotCrash() {
        assertEquals("Name", parse("""#EXTINF:-1 tvg-id="x, Name""", "https://h/x.m3u8").channels.single().name)
    }

    // ---- #EXTVLCOPT ------------------------------------------------------------

    @Test fun userAgentAndReferrerBecomeRequestHeaders() {
        val c = parse(
            "#EXTINF:-1,A",
            "#EXTVLCOPT:http-user-agent=Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko)",
            "#EXTVLCOPT:http-referrer=https://site.example.com/?a=b",
            "https://h/a.m3u8",
        ).channels.single()
        assertEquals("Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko)", c.userAgent)
        assertEquals("https://site.example.com/?a=b", c.referer)
        assertEquals(
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko)",
                "Referer" to "https://site.example.com/?a=b",
            ),
            c.requestHeaders,
        )
    }

    @Test fun referrerSpellingVariantsAreAccepted() {
        for (name in listOf("http-referrer", "http-referer", "http-user-referer", "http-user-referrer", "HTTP-User-Referer")) {
            val c = parse("#EXTINF:-1,A", "#EXTVLCOPT:$name=https://r.example.com/", "https://h/a.m3u8").channels.single()
            assertEquals(name, "https://r.example.com/", c.referer)
        }
    }

    @Test fun defaultUserAgentWhenNoneGiven() {
        val c = parse("#EXTINF:-1,A", "https://h/a.m3u8").channels.single()
        assertNull(c.userAgent)
        assertEquals(mapOf("User-Agent" to Channel.DEFAULT_USER_AGENT), c.requestHeaders)
    }

    @Test fun otherVlcOptionsAreKeptButNotSentAsHeaders() {
        val c = parse("#EXTINF:-1,A", "#EXTVLCOPT:network-caching=1500", "https://h/a.m3u8").channels.single()
        assertEquals("1500", c.vlcOptions["network-caching"])
        assertEquals(setOf("User-Agent"), c.requestHeaders.keys)
    }

    @Test fun optionsDoNotLeakIntoTheNextChannel() {
        val d = parse(
            "#EXTINF:-1,A", "#EXTVLCOPT:http-user-agent=Only/A", "#KODIPROP:inputstream.adaptive.manifest_type=mpd", "https://h/a",
            "#EXTINF:-1,B", "https://h/b",
        )
        assertEquals("Only/A", d.channels[0].userAgent)
        assertNull(d.channels[1].userAgent)
        assertNull(d.channels[1].manifestType)
    }

    @Test fun directivesAreCaseInsensitive() {
        val c = parse("#extinf:-1,A", "#extvlcopt:HTTP-USER-AGENT=Foo/1", "https://h/a.m3u8").channels.single()
        assertEquals("Foo/1", c.userAgent)
    }

    // ---- #KODIPROP -------------------------------------------------------------

    @Test fun clearKeyHexPairWithManifestAndLicenseType() {
        val c = parse(
            "#EXTINF:-1,A",
            "#KODIPROP:inputstream.adaptive.manifest_type=mpd",
            "#KODIPROP:inputstream.adaptive.license_type=clearkey",
            "#KODIPROP:inputstream.adaptive.license_key=$kid:$key",
            "https://h/tv/?id=1",
        ).channels.single()
        assertEquals("mpd", c.manifestType)
        assertEquals("clearkey", c.licenseType)
        assertEquals("$kid:$key", c.licenseKey)
        assertEquals(StreamType.DASH, c.streamType)
        assertTrue(c.usesClearKey)
    }

    @Test fun clearKeyJsonKeyKeepsItsEqualsSigns() {
        val json = """{"keys":[{"kty":"oct","kid":"ABEiM0RVZneImaq7zN3u_w==","k":"Dw4NDAsKCQgHBgUEAwIBAA=="}],"type":"temporary"}"""
        val d = parse(
            "#EXTINF:-1,A",
            "#KODIPROP:inputstream.adaptive.manifest_type=mpd",
            "#KODIPROP:inputstream.adaptive.license_type=clearkey",
            "#KODIPROP:inputstream.adaptive.license_key=$json",
            "https://h/tv/?id=1",
        )
        assertEquals(json, d.channels.single().licenseKey)
        assertEquals(emptyList<ParseIssue>(), d.issues)
    }

    @Test fun misspelledInpustreamKeysAreAccepted() {
        val c = parse("#EXTINF:-1,A", "#KODIPROP:inpustream.adaptive.manifest_type=mpd", "https://h/tv/?id=1").channels.single()
        assertEquals("mpd", c.manifestType)
        assertEquals(StreamType.DASH, c.streamType)
    }

    @Test fun propsMayComeBeforeExtinf() {
        val c = parse(
            "#KODIPROP:inputstream.adaptive.license_key=$kid:$key",
            "#EXTVLCOPT:http-user-agent=Early/1",
            "#EXTINF:-1,A",
            "https://h/a.mpd",
        ).channels.single()
        assertEquals("$kid:$key", c.licenseKey)
        assertEquals("Early/1", c.userAgent)
        assertTrue("key without license_type is treated as ClearKey", c.usesClearKey)
    }

    @Test fun invalidClearKeyIsReportedWithoutEchoingTheKey() {
        val d = parse(
            "#EXTINF:-1,A",
            "#KODIPROP:inputstream.adaptive.license_type=clearkey",
            "#KODIPROP:inputstream.adaptive.license_key=deadbeef:cafebabe",
            "https://h/a.mpd",
        )
        assertEquals(1, d.channels.size) // still listed; the player explains the problem
        val issue = d.issues.single()
        assertTrue(issue.message.contains("ClearKey"))
        assertFalse(issue.message.contains("deadbeef"))
    }

    @Test fun unsupportedLicenseTypeIsReported() {
        val d = parse(
            "#EXTINF:-1,A",
            "#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha",
            "#KODIPROP:inputstream.adaptive.license_key=https://lic.example.com/",
            "https://h/a.mpd",
        )
        assertFalse(d.channels.single().usesClearKey)
        assertTrue(d.issues.single().message.contains("not supported"))
    }

    @Test fun unknownManifestTypeFallsBackToUrlDetection() {
        val d = parse("#EXTINF:-1,A", "#KODIPROP:inputstream.adaptive.manifest_type=ism", "https://h/a.m3u8")
        assertEquals(StreamType.HLS, d.channels.single().streamType)
        assertEquals(1, d.issues.size)
    }

    // ---- stream types and URL validation ----------------------------------------

    @Test fun streamTypesForEveryUrlShape() {
        val d = parse(
            "#EXTINF:-1,hls", "https://h/live/index.m3u8?token=1",
            "#EXTINF:-1,dash", "https://h/live/manifest.mpd",
            "#EXTINF:-1,php-ts", "http://line.example.com:80/play/live.php?mac=00:1A:79:3F:0C:96&stream=9&extension=ts&play_token=abc",
            "#EXTINF:-1,php-dash", "#KODIPROP:inputstream.adaptive.manifest_type=mpd", "https://h/tv/mpd.php?id=1&cdn=1",
            "#EXTINF:-1,generic-dash", "#KODIPROP:inputstream.adaptive.manifest_type=mpd", "https://h/tv/?id=2",
            "#EXTINF:-1,generic", "https://h/tv/?id=3",
        )
        assertEquals(
            listOf(StreamType.HLS, StreamType.DASH, StreamType.TS, StreamType.DASH, StreamType.DASH, StreamType.UNKNOWN),
            d.channels.map { it.streamType },
        )
        assertEquals(emptyList<ParseIssue>(), d.issues)
    }

    @Test fun httpAndHttpsAreBothKept() {
        val d = parse("#EXTINF:-1,A", "http://h/a.m3u8", "#EXTINF:-1,B", "https://h/b.m3u8")
        assertEquals(listOf("http://h/a.m3u8", "https://h/b.m3u8"), d.channels.map { it.url })
    }

    @Test fun twoUrlsOnOneLineAreRejectedWithTheLineNumber() {
        val d = parse(
            "#EXTM3U",
            "#EXTINF:-1,Good", "https://h/good.m3u8",
            "#EXTINF:-1,Broken", "https://a.example.com/ https://b.example.com/x.m3u8",
        )
        assertEquals(listOf("Good"), d.channels.map { it.name })
        val issue = d.issues.single()
        assertEquals(5, issue.line)
        assertTrue(issue.message.contains("Broken"))
        assertTrue(issue.message.contains("whitespace"))
    }

    @Test fun nonHttpSchemesAreSkipped() {
        val d = parse("#EXTINF:-1,A", "rtmp://h/live/a", "#EXTINF:-1,B", "udp://@239.0.0.1:1234", "#EXTINF:-1,C", "https://h/c.m3u8")
        assertEquals(listOf("C"), d.channels.map { it.name })
        assertEquals(2, d.issues.size)
    }

    @Test fun channelNumbersAreContiguousEvenWhenEntriesAreSkipped() {
        val d = parse("#EXTINF:-1,A", "https://h/a", "#EXTINF:-1,B", "ftp://h/b", "#EXTINF:-1,C", "https://h/c")
        assertEquals(listOf(1, 2), d.channels.map { it.number })
        assertEquals(listOf("A", "C"), d.channels.map { it.name })
    }

    @Test fun strayUrlsAndDanglingExtinfAreReported() {
        val d = parse("https://h/stray.m3u8", "#EXTINF:-1,A", "#EXTINF:-1,B", "https://h/b.m3u8", "#EXTINF:-1,Dangling")
        assertEquals(listOf("B"), d.channels.map { it.name })
        assertEquals(listOf(1, 2, 5), d.issues.map { it.line }.sorted())
    }

    // ---- file-level quirks -----------------------------------------------------

    @Test fun bomAndCrlfAreHandled() {
        val d = M3uParser.parseDocument("﻿#EXTM3U url-tvg=\"https://e.example.com/x.xml\"\r\n#EXTINF:-1,Ch\r\nhttps://h/x.m3u8\r\n")
        assertEquals(listOf("https://e.example.com/x.xml"), d.header.epgUrls)
        assertEquals("Ch", d.channels.single().name)
        assertEquals("https://h/x.m3u8", d.channels.single().url)
    }

    @Test fun commentsBlankLinesAndUnknownTagsAreIgnored() {
        val d = parse("#EXTM3U", "", "# just a comment", "#EXTGRP:Foo", "#EXTINF:-1,A", "", "#EXTGRP:Bar", "https://h/a.m3u8", "")
        assertEquals(1, d.channels.size)
        assertEquals(emptyList<ParseIssue>(), d.issues)
    }

    @Test fun parseReturnsJustTheChannels() {
        assertEquals(listOf("A"), M3uParser.parse("#EXTINF:-1,A\nhttps://h/a.m3u8").map { it.name })
        assertEquals(emptyList<Channel>(), M3uParser.parse(""))
    }
}
