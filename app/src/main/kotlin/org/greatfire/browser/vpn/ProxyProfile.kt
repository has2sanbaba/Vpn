package org.greatfire.browser.vpn

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

enum class TransportKind(val badge: String) {
    SMART_AUTO("Smart Auto • Envoy + Anti-DPI"),
    FREEBROWSER_ANTI_DPI("FreeBrowser • DoH + TLS Fragment"),
    CRONET_GENEVA("Envoy Cronet • Geneva Evasion"),
    SNOWFLAKE("Tor Snowflake • WebRTC"),
    V2RAY_WS("IEnvoyProxy • V2Ray WS"),
    V2RAY_HTTP("IEnvoyProxy • V2Ray HTTP"),
    V2RAY_SRTP("IEnvoyProxy • V2Ray SRTP"),
    V2RAY_WECHAT("IEnvoyProxy • V2Ray WeChat"),
    XRAY_XHTTP("IEnvoyProxy • XRay XHTTP"),
    HYSTERIA2("IEnvoyProxy • Hysteria2 QUIC"),
    SHADOWSOCKS("Envoy • Shadowsocks Rust"),
    ENVOY_URL("GreatFire • Envoy HTTPS"),
    ENVOY_ECH("IEnvoyProxy • Envoy ECH"),
    MASQUE("IEnvoyProxy • MASQUE"),
    LYREBIRD_PT("IEnvoyProxy • Pluggable Transport"),
    SOCKS5("SOCKS5 Tunnel"),
    HTTP_PROXY("HTTP CONNECT Proxy")
}

data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: TransportKind,
    val builtIn: Boolean = false,
    val rawUri: String = "",
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val uuid: String = "",
    val path: String = "",
    val sni: String = "",
    val frontHost: String = "",
    val allowInsecure: Boolean = false,
    val utlsFingerprint: String = "chrome",
    val envoyUrl: String = "",
    val envoyAddress: String = "",
    val genevaStrategy: Int = 0,
    val fragSize: Int = 24,
    val fragDelayMs: Int = 4,
    val dohServer: String = "",
    val extra: Map<String, String> = emptyMap(),
    val lastLatencyMs: Long = -1L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("builtIn", builtIn)
        put("rawUri", rawUri)
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
        put("uuid", uuid)
        put("path", path)
        put("sni", sni)
        put("frontHost", frontHost)
        put("allowInsecure", allowInsecure)
        put("utlsFingerprint", utlsFingerprint)
        put("envoyUrl", envoyUrl)
        put("envoyAddress", envoyAddress)
        put("genevaStrategy", genevaStrategy)
        put("fragSize", fragSize)
        put("fragDelayMs", fragDelayMs)
        put("dohServer", dohServer)
        put("lastLatencyMs", lastLatencyMs)
        val extraObj = JSONObject()
        extra.forEach { (k, v) -> extraObj.put(k, v) }
        put("extra", extraObj)
    }

    companion object {
        fun fromJson(obj: JSONObject): ProxyProfile? = runCatching {
            val extraMap = mutableMapOf<String, String>()
            obj.optJSONObject("extra")?.let { eo ->
                val keys = eo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    extraMap[k] = eo.optString(k, "")
                }
            }
            ProxyProfile(
                id = obj.getString("id"),
                name = obj.getString("name"),
                kind = TransportKind.valueOf(obj.getString("kind")),
                builtIn = obj.optBoolean("builtIn", false),
                rawUri = obj.optString("rawUri", ""),
                host = obj.optString("host", ""),
                port = obj.optInt("port", 0),
                username = obj.optString("username", ""),
                password = obj.optString("password", ""),
                uuid = obj.optString("uuid", ""),
                path = obj.optString("path", ""),
                sni = obj.optString("sni", ""),
                frontHost = obj.optString("frontHost", ""),
                allowInsecure = obj.optBoolean("allowInsecure", false),
                utlsFingerprint = obj.optString("utlsFingerprint", "chrome"),
                envoyUrl = obj.optString("envoyUrl", ""),
                envoyAddress = obj.optString("envoyAddress", ""),
                genevaStrategy = obj.optInt("genevaStrategy", 0),
                fragSize = obj.optInt("fragSize", 24),
                fragDelayMs = obj.optInt("fragDelayMs", 4),
                dohServer = obj.optString("dohServer", ""),
                extra = extraMap,
                lastLatencyMs = obj.optLong("lastLatencyMs", -1L)
            )
        }.getOrNull()
    }
}

object ProxyProfileParser {

    /**
     * Parses a single or multi-line or Base64-encoded subscription block into ProxyProfile entries.
     */
    fun parseMany(input: String, customNamePrefix: String = ""): List<ProxyProfile> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        // If entire payload is base64-encoded subscription text, decode first
        val expanded = if (!trimmed.contains("://") && looksLikeBase64Block(trimmed)) {
            decodeBase64Utf8(trimmed) ?: trimmed
        } else {
            trimmed
        }

        val results = mutableListOf<ProxyProfile>()
        expanded.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .forEachIndexed { idx, line ->
                val hint = if (customNamePrefix.isNotBlank()) {
                    if (idx == 0) customNamePrefix else "$customNamePrefix #${idx + 1}"
                } else ""
                parseSingleUri(line, hint)?.let { results.add(it) }
            }
        return results
    }

    fun parseSingleUri(rawLine: String, customName: String = ""): ProxyProfile? {
        val raw = rawLine.trim()
        val scheme = raw.substringBefore("://", "").lowercase()
        if (scheme.isEmpty()) return null

        return when (scheme) {
            "vmess" -> parseVmess(raw, customName)
            "vless", "xray+xhttp" -> parseVlessOrXray(raw, customName)
            "v2ws", "v2http", "v2srtp", "v2wechat" -> parseGreatFireV2Ray(raw, scheme, customName)
            "ss" -> parseShadowsocks(raw, customName)
            "hysteria2", "hy2" -> parseHysteria2(raw, customName)
            "envoy", "envoy+https" -> parseEnvoyUrl(raw, customName)
            "ech+https", "envoy+ech" -> parseEnvoyEch(raw, customName)
            "masque" -> parseMasque(raw, customName)
            "snowflake" -> parseSnowflake(raw, customName)
            "obfs4", "meek", "webtunnel" -> parseLyrebirdPt(raw, scheme, customName)
            "socks5", "socks", "socks5h" -> parseStandardProxy(raw, TransportKind.SOCKS5, customName)
            "http", "proxy+http" -> parseStandardProxy(
                raw.replaceFirst("^proxy\\+http".toRegex(RegexOption.IGNORE_CASE), "http"),
                TransportKind.HTTP_PROXY,
                customName
            )
            else -> null
        }
    }

    private fun parseVmess(raw: String, customName: String): ProxyProfile? = runCatching {
        val b64 = raw.substringAfter("://").substringBefore('#')
        val jsonStr = decodeBase64Utf8(b64) ?: return null
        val obj = JSONObject(jsonStr)
        val host = obj.optString("add").trim()
        val port = obj.optString("port").toIntOrNull() ?: obj.optInt("port", 443)
        val id = obj.optString("id").trim()
        if (host.isEmpty() || id.isEmpty() || port !in 1..65535) return null

        val netType = obj.optString("net", "ws").lowercase()
        val path = obj.optString("path", "/")
        val hostHeader = obj.optString("host", "")
        val sni = obj.optString("sni", hostHeader.ifBlank { host })
        val fp = obj.optString("fp", "chrome")
        val ps = customName.ifBlank { obj.optString("ps").ifBlank { "VMess $host:$port" } }

        val kind = if (netType == "http" || netType == "h2") {
            TransportKind.V2RAY_HTTP
        } else {
            TransportKind.V2RAY_WS
        }

        ProxyProfile(
            name = ps,
            kind = kind,
            rawUri = raw,
            host = host,
            port = port,
            uuid = id,
            path = path.ifBlank { "/" },
            sni = sni,
            frontHost = hostHeader,
            utlsFingerprint = fp.ifBlank { "chrome" }
        )
    }.getOrNull()

    private fun parseVlessOrXray(raw: String, customName: String): ProxyProfile? = runCatching {
        val normalized = if (raw.startsWith("xray+xhttp://", true)) {
            "vless://" + raw.substringAfter("://")
        } else raw
        val uri = URI(normalized)
        val uuid = uri.userInfo?.trim().orEmpty()
        val host = uri.host?.trim().orEmpty()
        val port = if (uri.port > 0) uri.port else 443
        if (uuid.isEmpty() || host.isEmpty()) return null

        val params = parseQueryMap(uri.rawQuery)
        val type = params["type"]?.lowercase().orEmpty()
        val path = params["path"] ?: "/"
        val sni = params["sni"] ?: params["servername"] ?: host
        val hostHdr = params["host"].orEmpty()
        val allowInsecure = params["allowinsecure"] == "1" || params["allowinsecure"] == "true"
        val fragName = uri.rawFragment?.let { urlDecode(it) }.orEmpty()
        val name = customName.ifBlank { fragName.ifBlank { "XRay/VLESS $host:$port" } }

        val kind = if (type == "ws") TransportKind.V2RAY_WS else TransportKind.XRAY_XHTTP

        ProxyProfile(
            name = name,
            kind = kind,
            rawUri = raw,
            host = host,
            port = port,
            uuid = uuid,
            path = if (path.startsWith('/')) path else "/$path",
            sni = sni,
            frontHost = hostHdr,
            allowInsecure = allowInsecure,
            utlsFingerprint = params["fp"] ?: "chrome"
        )
    }.getOrNull()

    private fun parseGreatFireV2Ray(raw: String, scheme: String, customName: String): ProxyProfile? = runCatching {
        val uri = URI(raw)
        val host = uri.host?.trim().orEmpty()
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryMap(uri.rawQuery)
        val id = params["id"] ?: uri.userInfo.orEmpty()
        if (host.isEmpty() || id.isEmpty()) return null

        val rawPath = params["path"].orEmpty()
        val path = when {
            rawPath.isEmpty() -> "/"
            rawPath.startsWith("/") -> rawPath
            else -> "/$rawPath"
        }
        val kind = when (scheme) {
            "v2http" -> TransportKind.V2RAY_HTTP
            "v2srtp" -> TransportKind.V2RAY_SRTP
            "v2wechat" -> TransportKind.V2RAY_WECHAT
            else -> TransportKind.V2RAY_WS
        }
        val frag = uri.rawFragment?.let { urlDecode(it) }.orEmpty()
        val label = customName.ifBlank { frag.ifBlank { "${scheme.uppercase()} $host:$port" } }

        ProxyProfile(
            name = label,
            kind = kind,
            rawUri = raw,
            host = host,
            port = port,
            uuid = id,
            path = path,
            sni = params["sni"] ?: host,
            frontHost = params["host"].orEmpty(),
            allowInsecure = params["allowInsecure"] == "true" || params["allowInsecure"] == "1"
        )
    }.getOrNull()

    private fun parseShadowsocks(raw: String, customName: String): ProxyProfile? {
        val withoutScheme = raw.substring(5)
        val tag = withoutScheme.substringAfter('#', "").let { urlDecode(it) }
        // Validate via ShadowsocksRunner parser structure
        val body = withoutScheme.substringBefore('#').substringBefore('?')
        val hostPort = if (body.contains('@')) {
            body.substringAfterLast('@')
        } else {
            val dec = decodeBase64Utf8(body) ?: return null
            if (!dec.contains('@')) return null
            dec.substringAfterLast('@')
        }
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null

        val name = customName.ifBlank { tag.ifBlank { "Shadowsocks $host:$port" } }
        return ProxyProfile(
            name = name,
            kind = TransportKind.SHADOWSOCKS,
            rawUri = raw,
            host = host,
            port = port
        )
    }

    private fun parseHysteria2(raw: String, customName: String): ProxyProfile? = runCatching {
        val normalized = if (raw.startsWith("hy2://", true)) {
            "hysteria2://" + raw.substring(6)
        } else raw
        val uri = URI(normalized)
        val host = uri.host?.trim().orEmpty()
        val port = if (uri.port > 0) uri.port else 443
        if (host.isEmpty()) return null
        val params = parseQueryMap(uri.rawQuery)
        val frag = uri.rawFragment?.let { urlDecode(it) }.orEmpty()
        val name = customName.ifBlank { frag.ifBlank { "Hysteria2 $host:$port" } }

        ProxyProfile(
            name = name,
            kind = TransportKind.HYSTERIA2,
            rawUri = normalized,
            host = host,
            port = port,
            sni = params["sni"].orEmpty(),
            extra = params
        )
    }.getOrNull()

    private fun parseEnvoyUrl(raw: String, customName: String): ProxyProfile? = runCatching {
        if (raw.startsWith("envoy+https://", true)) {
            val httpsUrl = "https://" + raw.substringAfter("://")
            val u = URI(httpsUrl)
            return ProxyProfile(
                name = customName.ifBlank { "Envoy HTTPS ${u.host ?: "Proxy"}" },
                kind = TransportKind.ENVOY_URL,
                rawUri = raw,
                host = u.host.orEmpty(),
                port = if (u.port > 0) u.port else 443,
                envoyUrl = httpsUrl
            )
        }
        val query = raw.substringAfter('?', "")
        val params = parseQueryMap(query)
        val targetEnvoyUrl = params["url"].orEmpty()
        if (targetEnvoyUrl.isBlank()) return null
        val address = params["address"].orEmpty()
        val headerHost = params["header_Host"] ?: params["header_host"] ?: ""
        val parsedTarget = runCatching { URI(targetEnvoyUrl) }.getOrNull()

        ProxyProfile(
            name = customName.ifBlank { "Envoy ${parsedTarget?.host ?: "Server"}" },
            kind = TransportKind.ENVOY_URL,
            rawUri = raw,
            host = parsedTarget?.host.orEmpty(),
            port = parsedTarget?.port?.takeIf { it > 0 } ?: 443,
            frontHost = headerHost,
            envoyUrl = targetEnvoyUrl,
            envoyAddress = address
        )
    }.getOrNull()

    private fun parseEnvoyEch(raw: String, customName: String): ProxyProfile? = runCatching {
        val httpsUrl = "https://" + raw.substringAfter("://")
        val uri = URI(httpsUrl)
        val params = parseQueryMap(uri.rawQuery)
        ProxyProfile(
            name = customName.ifBlank { "Envoy ECH ${uri.host ?: "Proxy"}" },
            kind = TransportKind.ENVOY_ECH,
            rawUri = raw,
            host = uri.host.orEmpty(),
            port = if (uri.port > 0) uri.port else 443,
            envoyUrl = httpsUrl.substringBefore('?'),
            extra = params
        )
    }.getOrNull()

    private fun parseMasque(raw: String, customName: String): ProxyProfile? = runCatching {
        val uri = URI(raw)
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) return null
        val port = if (uri.port > 0) uri.port else 443
        val token = uri.userInfo ?: parseQueryMap(uri.rawQuery)["token"].orEmpty()
        ProxyProfile(
            name = customName.ifBlank { "MASQUE $host:$port" },
            kind = TransportKind.MASQUE,
            rawUri = raw,
            host = host,
            port = port,
            password = token
        )
    }.getOrNull()

    private fun parseSnowflake(raw: String, customName: String): ProxyProfile? = runCatching {
        val query = raw.substringAfter('?', "").substringBefore('#')
        val params = parseQueryMap(query)
        ProxyProfile(
            name = customName.ifBlank { "Tor Snowflake Bridge" },
            kind = TransportKind.SNOWFLAKE,
            rawUri = raw,
            frontHost = params["front"].orEmpty(),
            extra = params
        )
    }.getOrNull()

    private fun parseLyrebirdPt(raw: String, scheme: String, customName: String): ProxyProfile? {
        val params = parseQueryMap(raw.substringAfter('?', "").substringBefore('#')).toMutableMap()
        params["pt"] = scheme
        return ProxyProfile(
            name = customName.ifBlank { "Pluggable Transport (${scheme.uppercase()})" },
            kind = TransportKind.LYREBIRD_PT,
            rawUri = raw,
            extra = params
        )
    }

    private fun parseStandardProxy(raw: String, kind: TransportKind, customName: String): ProxyProfile? = runCatching {
        val uri = URI(raw)
        val host = uri.host?.trim().orEmpty()
        val port = uri.port
        if (host.isEmpty() || port !in 1..65535) return null
        val userInfo = uri.userInfo.orEmpty()
        val user = if (userInfo.contains(':')) urlDecode(userInfo.substringBefore(':')) else urlDecode(userInfo)
        val pass = if (userInfo.contains(':')) urlDecode(userInfo.substringAfter(':')) else ""
        val frag = uri.rawFragment?.let { urlDecode(it) }.orEmpty()
        val prefix = if (kind == TransportKind.SOCKS5) "SOCKS5" else "HTTP Proxy"

        ProxyProfile(
            name = customName.ifBlank { frag.ifBlank { "$prefix $host:$port" } },
            kind = kind,
            rawUri = raw,
            host = host,
            port = port,
            username = user,
            password = pass
        )
    }.getOrNull()

    private fun parseQueryMap(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isNotBlank()) {
                val k = urlDecode(pair.substringBefore('='))
                val v = urlDecode(pair.substringAfter('=', ""))
                map[k] = v
            }
        }
        return map
    }

    private fun urlDecode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun looksLikeBase64Block(s: String): Boolean {
        val compact = s.replace("\r", "").replace("\n", "").trim()
        if (compact.length < 16) return false
        return compact.all {
            it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '-' || it == '_' || it == '='
        }
    }

    /**
     * Pure Kotlin RFC 4648 Base64/Base64URL decoder — works identically on Android 5.0 (API 21),
     * Android 16 (API 36), and standalone JVM unit tests with zero platform dependencies.
     */
    fun decodeBase64Utf8(input: String): String? {
        val cleaned = input.trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .replace('-', '+')
            .replace('_', '/')
            .trimEnd('=')

        if (cleaned.isEmpty()) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = ByteArray(cleaned.length * 3 / 4)
        var outIdx = 0
        var buffer = 0
        var bitsCollected = 0

        for (ch in cleaned) {
            val value = alphabet.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                if (outIdx < out.size) {
                    out[outIdx++] = ((buffer shr bitsCollected) and 0xFF).toByte()
                }
            }
        }
        return runCatching { String(out, 0, outIdx, Charsets.UTF_8) }.getOrNull()
    }
}
