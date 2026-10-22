package com.lightningstudio.watchrss.phone.cloud

import android.icu.text.IDNA
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Matches rss.rs canonicalize_source_url + SHA256; pool UUIDs are only fetch locators. */
internal object CloudRssSourceIdentity {
    fun canonicalUrl(raw: String): String {
        require(raw.toByteArray(Charsets.UTF_8).size <= 4096)
        val parsed = parse(raw)
        require(!parsed.hasCredentials) { "invalid_rss_url" }
        require(parsed.address == null || publicAddress(parsed.address)) { "rss_private_address_blocked" }
        return parsed.canonical
    }

    /** Literal private addresses and authenticated feeds stay local; this never performs DNS. */
    fun isLocalOnly(raw: String): Boolean = try {
        val parsed = parse(raw)
        parsed.hasCredentials || (parsed.address != null && !publicAddress(parsed.address))
    } catch (_: IllegalArgumentException) { false }

    fun sourceKey(raw: String) = CloudSnapshotCodec.sha256(canonicalUrl(raw).toByteArray(Charsets.UTF_8))

    fun canonicalEntryLink(raw: String?): String? = try { raw?.let { parse(it).canonical } }
        catch (_: IllegalArgumentException) { null }

    /** Backend canonicalize_entry_link, followed by the existing cross-client article identity projection. */
    fun articleIdentity(raw: String): String {
        val parsed = parse(raw)
        val query = parsed.query?.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()
        return "${parsed.scheme}://${parsed.host.removePrefix("www.")}${parsed.path}$query"
    }

    private data class Parsed(val canonical: String, val scheme: String, val host: String, val path: String,
        val query: String?, val address: ByteArray?, val hasCredentials: Boolean)

    private fun parse(raw: String): Parsed {
        // Rust url removes ASCII tab/CR/LF throughout special URLs. Do not decode existing escapes.
        val input = raw.trim().filterNot { it == '\t' || it == '\r' || it == '\n' }
        val match = requireNotNull(Regex("(?i)^https?:[/\\\\]*([^/\\\\?#]*)").find(input)) { "invalid_rss_url" }
        val authority = match.groupValues[1]
        val hostPort = authority.substringAfterLast('@')
        val rawHost = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]"
            else hostPort.substringBefore(':')
        require(rawHost.isNotEmpty()) { "invalid_rss_url" }
        val ipv6 = if (rawHost.startsWith("[")) ipv6Bytes(rawHost.removeSurrounding("[", "]")) else null
        val host = if (ipv6 != null) "[${serializeIpv6(ipv6)}]" else {
            val unicode = java.net.URLDecoder.decode(rawHost.replace("+", "%2B"), "UTF-8")
            val ascii = if (unicode.any { it.code > 127 }) {
                val info = IDNA.Info(); val output = StringBuilder()
                IDNA.getUTS46Instance(IDNA.CHECK_BIDI or IDNA.CHECK_CONTEXTJ or IDNA.NONTRANSITIONAL_TO_ASCII)
                    .nameToASCII(unicode, output, info)
                require(!info.hasErrors()) { "invalid_rss_url" }
                output.toString()
            } else unicode.lowercase(java.util.Locale.ROOT)
            require(ascii.none { it.code <= 32 || it.code == 127 || it in "#%/:<>?@[\\]^|" }) { "invalid_rss_url" }
            canonicalIpv4(ascii) ?: ascii
        }
        // OkHttp additionally escapes | and ^. Shield only literal occurrences after the authority;
        // %7C/%5E in the input must remain encoded and must produce different source/article keys.
        var marker = "watchrssLiteral"
        while (input.contains(marker, ignoreCase = true)) marker += "X"
        val tail = input.substring(match.range.last + 1).replace("|", "${marker}Pipe").replace("^", "${marker}Caret")
        val prefix = input.substringBefore(':').lowercase(java.util.Locale.ROOT) + "://"
        val credentials = authority.substringBeforeLast('@', "").let { if ('@' in authority) "$it@" else "" }
        val port = hostPort.removePrefix(rawHost)
        val url = (prefix + credentials + host + port + tail).toHttpUrl().newBuilder().fragment(null).build()
        fun unshield(value: String) = value.replace("${marker}Pipe", "|").replace("${marker}Caret", "^")
        val path = unshield(url.encodedPath)
        val query = url.encodedQuery?.let(::unshield)
        // Serialize the original 128-bit host ourselves: InetAddress/OkHttp collapse mapped IPv6 to IPv4.
        val canonicalHost = if (ipv6 != null) host else url.host
        val defaultPort = if (url.scheme == "https") 443 else 80
        val authorityPort = if (url.port == defaultPort) "" else ":${url.port}"
        val canonicalCredentials = if (url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty()) "" else
            url.encodedUsername + (if (url.encodedPassword.isNotEmpty()) ":${url.encodedPassword}" else "") + "@"
        val canonical = "${url.scheme}://$canonicalCredentials$canonicalHost$authorityPort$path" + (query?.let { "?$it" } ?: "")
        val address = ipv6 ?: canonicalIpv4(url.host)?.let { it.split('.').map(String::toInt).map(Int::toByte).toByteArray() }
        return Parsed(canonical, url.scheme, canonicalHost, path, query, address,
            url.username.isNotEmpty() || url.password.isNotEmpty() || (':' in credentials.substringBeforeLast('@')))
    }

    private fun ipv6Bytes(host: String): ByteArray {
        require('%' !in host && host.count { it == ':' } >= 2)
        fun groups(part: String): List<Int> = if (part.isEmpty()) emptyList() else part.split(':').flatMap { group ->
            if ('.' in group) {
                require(host.endsWith(group))
                val octets = group.split('.').map {
                    require(it.matches(Regex("0|[1-9][0-9]{0,2}")))
                    it.toInt().also { n -> require(n in 0..255) }
                }
                require(octets.size == 4)
                listOf((octets[0] shl 8) or octets[1], (octets[2] shl 8) or octets[3])
            } else {
                require(group.matches(Regex("[0-9a-fA-F]{1,4}")))
                listOf(group.toInt(16))
            }
        }
        val split = host.split("::")
        require(split.size <= 2)
        val left = groups(split[0]); val right = if (split.size == 2) groups(split[1]) else emptyList()
        val missing = 8 - left.size - right.size
        require(if (split.size == 2) missing > 0 else missing == 0)
        return (left + List(missing) { 0 } + right).flatMap { listOf((it ushr 8).toByte(), it.toByte()) }.toByteArray()
    }

    private fun serializeIpv6(bytes: ByteArray): String {
        val groups = (0..7).map { ((bytes[it * 2].toInt() and 255) shl 8) or (bytes[it * 2 + 1].toInt() and 255) }
        var best = -1; var length = 1; var i = 0
        while (i < 8) {
            if (groups[i] != 0) { i++; continue }
            val start = i
            while (i < 8 && groups[i] == 0) i++
            if (i - start > length) { best = start; length = i - start }
        }
        if (best < 0) return groups.joinToString(":") { it.toString(16) }
        return groups.take(best).joinToString(":") { it.toString(16) } + "::" + groups.drop(best + length).joinToString(":") { it.toString(16) }
    }
    /** WHATWG URL IPv4 syntax, also used by Rust url (hex/octal/short numeric addresses). */
    private fun canonicalIpv4(host: String): String? {
        if (':' in host) return null
        val parts=host.removeSuffix(".").split('.')
        val last=parts.last()
        if (!(last.all { it in '0'..'9' } || last.startsWith("0x",true))) return null
        require(parts.size in 1..4)
        val numbers=parts.map { part ->
            val radix=if (part.startsWith("0x",true)) 16 else if (part.length>1 && part.startsWith('0')) 8 else 10
            val digits=if (radix==16) part.drop(2) else if (radix==8) part.drop(1) else part
            (if (digits.isEmpty()) 0L else digits.toLongOrNull(radix)).also { require(it!=null && it in 0..0xffffffffL) }!!
        }
        require(numbers.dropLast(1).all { it<=255 } && numbers.last() < (1L shl (8*(5-parts.size))))
        var address=numbers.last()
        numbers.dropLast(1).forEachIndexed { index,n -> address += n shl (8*(3-index)) }
        return (3 downTo 0).joinToString(".") { ((address ushr (it*8)) and 255).toString() }
    }
    private fun publicAddress(raw: ByteArray): Boolean {
        val b = raw.map { it.toInt() and 255 }
        if (b.size == 4) return !(b[0] in listOf(0,10,127) || b[0]>=224 ||
            (b[0]==169 && b[1]==254) || (b[0]==172 && b[1] in 16..31) || (b[0]==192 && b[1]==168) ||
            (b[0]==192 && b[1]==0 && b[2] in listOf(0,2)) || (b[0]==192 && b[1]==88 && b[2]==99) ||
            (b[0]==100 && b[1] in 64..127) || (b[0]==198 && b[1] in 18..19) ||
            (b[0]==198 && b[1]==51 && b[2]==100) || (b[0]==203 && b[1]==0 && b[2]==113))
        if (b.size != 16) return false
        if (b.take(12).all { it==0 } || (b.take(10).all { it==0 } && b[10]==255 && b[11]==255)) return publicAddress(raw.takeLast(4).toByteArray())
        val a=(b[0] shl 8) or b[1]; val c=(b[2] shl 8) or b[3]
        return a and 0xe000 == 0x2000 && !(a==0x2001 && (c<0x0200 || c==0x0db8)) && a!=0x2002 && !(a==0x3fff && c<0x1000)
    }
}
