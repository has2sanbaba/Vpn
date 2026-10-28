package org.greatfire.browser.vpn

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

enum class TransportKind(val badge: String) {
    SMART_AUTO("Smart Auto • اتصال تضمینی چندموتوره"),
    WARP_CLEAN_SCAN("WARP+ Clean IP • اسکن آی‌پی تمیز"),
    WARP_IN_WARP_GOOL("WARP-in-WARP (Gool) • تونل دو لایه"),
    WARP_PSIPHON_CFON("Psiphon + WARP • آی‌پی اروپا (CFON)"),
    TOR_MEEK_BRIDGE("Tor + GreatFire Meek • CDN77 HTTPS"),
    SNOWFLAKE("Tor + GreatFire Snowflake • WebRTC"),
    TOR_OBFS4_BRIDGE("Tor + GreatFire Obfs4 • Lyrebird"),
    VLESS("Sing-Box • VLESS (Reality / WS / gRPC)"),
    TROJAN("Sing-Box • Trojan TLS"),
    TUIC("Sing-Box • TUIC v5 QUIC"),
    WIREGUARD("Sing-Box • WireGuard"),
    FREEBROWSER_ANTI_DPI("FreeBrowser • DoH + TLS Fragment"),
    CRONET_GENEVA("Envoy Cronet • Geneva Evasion"),
    V2RAY_WS("Sing-Box / IEnvoyProxy • VMess WS"),
    V2RAY_HTTP("Sing-Box / IEnvoyProxy • VMess HTTP"),
    V2RAY_SRTP("IEnvoyProxy • V2Ray SRTP"),
    V2RAY_WECHAT("IEnvoyProxy • V2Ray WeChat"),
    XRAY_XHTTP("IEnvoyProxy • XRay XHTTP"),
    HYSTERIA2("Sing-Box / IEnvoyProxy • Hysteria2 QUIC"),
    SHADOWSOCKS("Envoy / Sing-Box • Shadowsocks"),
    ENVOY_URL("GreatFire • Envoy HTTPS"),
    ENVOY_ECH("IEnvoyProxy • Envoy ECH"),
    MASQUE("IEnvoyProxy • MASQUE"),
    LYREBIRD_PT("Tor + IEnvoyProxy • Pluggable Transport"),
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
            "vless", "xray+xhttp" -> parseVlessOrXray(raw, scheme, customName)
            "vmess" -> parseVmess(raw, customName)
            "trojan" -> parseTrojan(raw, customName)
            "tuic" -> parseTuic(raw, customName)
            "hysteria2", "hy2" -> parseHysteria2(raw, customName)
            "ss" -> parseShadowsocks(raw, customName)
            "wireguard", "wg" -> parseWireGuard(raw, customName)
            "v2ws", "v2http", "v2srtp", "v2wechat" -> parseGreatFireV2Ray(raw, scheme, customName)
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
        val tlsField = obj.optString("tls", "").lowercase()
        val sni = obj.optString("sni", hostHeader.ifBlank { host })
        val fp = obj.optString("fp", "chrome")
        val aid = obj.optString("aid", "0")
        val scy = obj.optString("scy", "auto")
        val alpn = obj.optString("alpn", "")
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
            utlsFingerprint = fp.ifBlank { "chrome" },
            extra = mapOf(
                "type" to netType,
                "tls" to tlsField,
                "aid" to aid,
                "scy" to scy,
                "alpn" to alpn,
                "serviceName" to path
            )
        )
    }.getOrNull()

    private fun parseVlessOrXray(raw: String, scheme: String, customName: String): ProxyProfile? = runCatching {
        val normalized = if (scheme == "xray+xhttp") {
            "vless://" + raw.substringAfter("://")
        } else raw
        val uri = parseFlexibleUri(normalized) ?: return null
        val uuid = urlDecode(uri.userInfo).trim()
        val host = uri.host.trim()
        val port = if (uri.port > 0) uri.port else 443
        if (uuid.isEmpty() || host.isEmpty() || port !in 1..65535) return null

        val params = parseQueryMapCaseInsensitive(uri.rawQuery)
        val netType = (params["type"] ?: if (scheme == "xray+xhttp") "xhttp" else "tcp").lowercase()
        val security = (params["security"] ?: if (port == 443 && scheme == "xray+xhttp") "tls" else "none").lowercase()
        val rawPath = params["path"] ?: "/"
        val sni = params["sni"] ?: params["servername"] ?: params["host"] ?: host
        val hostHdr = params["host"].orEmpty()
        val allowInsecure = params["allowinsecure"] == "1" || params["allowinsecure"] == "true" || params["insecure"] == "1"
        val fragName = uri.fragment
        val kind = if (scheme == "xray+xhttp" || netType == "xhttp" || netType == "splithttp") {
            TransportKind.XRAY_XHTTP
        } else {
            TransportKind.VLESS
        }

        val defaultTag = if (security == "reality") "VLESS-Reality $host:$port" else "VLESS $host:$port"
        val name = customName.ifBlank { fragName.ifBlank { defaultTag } }

        val extra = mutableMapOf(
            "type" to netType,
            "security" to security
        )
        params["pbk"]?.let { extra["pbk"] = it }
        params["sid"]?.let { extra["sid"] = it }
        params["spx"]?.let { extra["spx"] = it }
        params["flow"]?.let { extra["flow"] = it }
        params["servicename"]?.let { extra["serviceName"] = it }
        params["alpn"]?.let { extra["alpn"] = it }

        ProxyProfile(
            name = name,
            kind = kind,
            rawUri = raw,
            host = host,
            port = port,
            uuid = uuid,
            path = if (rawPath.startsWith('/')) rawPath else "/$rawPath",
            sni = sni,
            frontHost = hostHdr,
            allowInsecure = allowInsecure,
            utlsFingerprint = params["fp"]?.ifBlank { null } ?: "chrome",
            extra = extra
        )
    }.getOrNull()

    private fun parseTrojan(raw: String, customName: String): ProxyProfile? = runCatching {
        val uri = parseFlexibleUri(raw) ?: return null
        val password = urlDecode(uri.userInfo)
        val host = uri.host.trim()
        val port = if (uri.port > 0) uri.port else 443
        if (password.isEmpty() || host.isEmpty() || port !in 1..65535) return null

        val params = parseQueryMapCaseInsensitive(uri.rawQuery)
        val netType = (params["type"] ?: "tcp").lowercase()
        val security = (params["security"] ?: "tls").lowercase()
        val rawPath = params["path"] ?: "/"
        val hostHdr = params["host"].orEmpty()
        val sni = params["sni"] ?: params["peer"] ?: hostHdr.ifBlank { host }
        val allowInsecure = params["allowinsecure"] == "1" || params["allowinsecure"] == "true" || params["insecure"] == "1"
        val name = customName.ifBlank { uri.fragment.ifBlank { "Trojan $host:$port" } }

        val extra = mutableMapOf(
            "type" to netType,
            "security" to security
        )
        params["pbk"]?.let { extra["pbk"] = it }
        params["sid"]?.let { extra["sid"] = it }
        params["servicename"]?.let { extra["serviceName"] = it }
        params["alpn"]?.let { extra["alpn"] = it }

        ProxyProfile(
            name = name,
            kind = TransportKind.TROJAN,
            rawUri = raw,
            host = host,
            port = port,
            password = password,
            path = if (rawPath.startsWith('/')) rawPath else "/$rawPath",
            sni = sni,
            frontHost = hostHdr,
            allowInsecure = allowInsecure,
            utlsFingerprint = params["fp"]?.ifBlank { null } ?: "chrome",
            extra = extra
        )
    }.getOrNull()

    private fun parseTuic(raw: String, customName: String): ProxyProfile? = runCatching {
        val uri = parseFlexibleUri(raw) ?: return null
        val host = uri.host.trim()
        val port = if (uri.port > 0) uri.port else 443
        if (host.isEmpty() || port !in 1..65535) return null

        val userInfo = uri.userInfo
        val uuid = urlDecode(userInfo.substringBefore(':'))
        val password = if (userInfo.contains(':')) urlDecode(userInfo.substringAfter(':')) else ""
        if (uuid.isEmpty()) return null

        val params = parseQueryMapCaseInsensitive(uri.rawQuery)
        val sni = params["sni"] ?: host
        val allowInsecure = params["allow_insecure"] == "1" || params["insecure"] == "1" || params["allowinsecure"] == "true"
        val name = customName.ifBlank { uri.fragment.ifBlank { "TUIC $host:$port" } }

        ProxyProfile(
            name = name,
            kind = TransportKind.TUIC,
            rawUri = raw,
            host = host,
            port = port,
            uuid = uuid,
            password = password,
            sni = sni,
            allowInsecure = allowInsecure,
            extra = params
        )
    }.getOrNull()

    private fun parseWireGuard(raw: String, customName: String): ProxyProfile? = runCatching {
        val normalized = if (raw.startsWith("wg://", true)) {
            "wireguard://" + raw.substring(5)
        } else raw
        val uri = parseFlexibleUri(normalized) ?: return null
        val privateKey = urlDecode(uri.userInfo)
        val host = uri.host.trim()
        val port = if (uri.port > 0) uri.port else 2408
        val params = parseQueryMapCaseInsensitive(uri.rawQuery)
        val priv = privateKey.ifBlank { params["privatekey"].orEmpty() }
        if (host.isEmpty() || priv.isEmpty()) return null

        val name = customName.ifBlank { uri.fragment.ifBlank { "WireGuard $host:$port" } }
        ProxyProfile(
            name = name,
            kind = TransportKind.WIREGUARD,
            rawUri = raw,
            host = host,
            port = port,
            password = priv,
            extra = params
        )
    }.getOrNull()

    private fun parseGreatFireV2Ray(raw: String, scheme: String, customName: String): ProxyProfile? = runCatching {
        val uri = parseFlexibleUri(raw) ?: return null
        val host = uri.host.trim()
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQueryMapCaseInsensitive(uri.rawQuery)
        val id = params["id"] ?: uri.userInfo
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
        val label = customName.ifBlank { uri.fragment.ifBlank { "${scheme.uppercase()} $host:$port" } }

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
            allowInsecure = params["allowinsecure"] == "true" || params["allowinsecure"] == "1",
            extra = mapOf("engine" to "iep")
        )
    }.getOrNull()

    private fun parseShadowsocks(raw: String, customName: String): ProxyProfile? {
        val withoutScheme = raw.substring(5)
        val tag = withoutScheme.substringAfter('#', "").let { urlDecode(it) }
        val body = withoutScheme.substringBefore('#').substringBefore('?')

        val credsAndHost = if (body.contains('@')) {
            val rawUser = body.substringBeforeLast('@')
            val hostPart = body.substringAfterLast('@')
            val decodedUser = if (rawUser.contains(':')) {
                urlDecode(rawUser)
            } else {
                decodeBase64Utf8(rawUser) ?: urlDecode(rawUser)
            }
            decodedUser to hostPart
        } else {
            val dec = decodeBase64Utf8(body) ?: return null
            if (!dec.contains('@')) return null
            dec.substringBeforeLast('@') to dec.substringAfterLast('@')
        }

        val (methodPass, hostPort) = credsAndHost
        if (!methodPass.contains(':')) return null
        val method = methodPass.substringBefore(':').trim()
        val password = methodPass.substringAfter(':')

        val host = hostPort.substringBeforeLast(':').trim('[', ']')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        if (host.isBlank() || method.isBlank() || port !in 1..65535) return null

        val name = customName.ifBlank { tag.ifBlank { "Shadowsocks $host:$port" } }
        return ProxyProfile(
            name = name,
            kind = TransportKind.SHADOWSOCKS,
            rawUri = raw,
            host = host,
            port = port,
            username = method,
            password = password
        )
    }

    private fun parseHysteria2(raw: String, customName: String): ProxyProfile? = runCatching {
        val normalized = if (raw.startsWith("hy2://", true)) {
            "hysteria2://" + raw.substring(6)
        } else raw
        val uri = parseFlexibleUri(normalized) ?: return null
        val host = uri.host.trim()
        val port = if (uri.port > 0) uri.port else 443
        if (host.isEmpty()) return null
        val password = urlDecode(uri.userInfo)
        val params = parseQueryMapCaseInsensitive(uri.rawQuery)
        val allowInsecure = params["insecure"] == "1" || params["insecure"] == "true" || params["allowinsecure"] == "1"
        val name = customName.ifBlank { uri.fragment.ifBlank { "Hysteria2 $host:$port" } }

        ProxyProfile(
            name = name,
            kind = TransportKind.HYSTERIA2,
            rawUri = normalized,
            host = host,
            port = port,
            password = password,
            sni = params["sni"].orEmpty(),
            allowInsecure = allowInsecure,
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
        val uri = parseFlexibleUri(raw) ?: return null
        val host = uri.host.trim()
        if (host.isEmpty()) return null
        val port = if (uri.port > 0) uri.port else 443
        val token = uri.userInfo.ifBlank { parseQueryMapCaseInsensitive(uri.rawQuery)["token"].orEmpty() }
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
        val params = parseQueryMapCaseInsensitive(query)
        ProxyProfile(
            name = customName.ifBlank { "Tor + GreatFire Snowflake" },
            kind = TransportKind.SNOWFLAKE,
            rawUri = raw,
            frontHost = params["front"] ?: params["fronts"].orEmpty(),
            extra = params
        )
    }.getOrNull()

    private fun parseLyrebirdPt(raw: String, scheme: String, customName: String): ProxyProfile? {
        val params = parseQueryMapCaseInsensitive(raw.substringAfter('?', "").substringBefore('#')).toMutableMap()
        params["pt"] = scheme
        val kind = when (scheme) {
            "meek" -> TransportKind.TOR_MEEK_BRIDGE
            "obfs4" -> TransportKind.TOR_OBFS4_BRIDGE
            else -> TransportKind.LYREBIRD_PT
        }
        return ProxyProfile(
            name = customName.ifBlank { "Tor Bridge (${scheme.uppercase()})" },
            kind = kind,
            rawUri = raw,
            extra = params
        )
    }

    private fun parseStandardProxy(raw: String, kind: TransportKind, customName: String): ProxyProfile? = runCatching {
        val uri = parseFlexibleUri(raw) ?: return null
        val host = uri.host.trim()
        val port = uri.port
        if (host.isEmpty() || port !in 1..65535) return null
        val userInfo = uri.userInfo
        val user = if (userInfo.contains(':')) urlDecode(userInfo.substringBefore(':')) else urlDecode(userInfo)
        val pass = if (userInfo.contains(':')) urlDecode(userInfo.substringAfter(':')) else ""
        val prefix = if (kind == TransportKind.SOCKS5) "SOCKS5" else "HTTP Proxy"

        ProxyProfile(
            name = customName.ifBlank { uri.fragment.ifBlank { "$prefix $host:$port" } },
            kind = kind,
            rawUri = raw,
            host = host,
            port = port,
            username = user,
            password = pass
        )
    }.getOrNull()

    private data class ParsedUriParts(
        val userInfo: String,
        val host: String,
        val port: Int,
        val rawQuery: String,
        val fragment: String
    )

    /**
     * Tolerant URI parser that handles unescaped emojis, spaces, or Persian characters in `#fragment`
     * (common in public VLESS/Trojan subscriptions where strict `java.net.URI` throws URISyntaxException!).
     */
    private fun parseFlexibleUri(raw: String): ParsedUriParts? {
        val afterScheme = raw.substringAfter("://", "")
        if (afterScheme.isEmpty()) return null

        val fragment = if (afterScheme.contains('#')) {
            urlDecode(afterScheme.substringAfter('#'))
        } else ""
        val withoutFrag = afterScheme.substringBefore('#')

        val rawQuery = if (withoutFrag.contains('?')) {
            withoutFrag.substringAfter('?')
        } else ""
        val authorityAndPath = withoutFrag.substringBefore('?')
        val authority = authorityAndPath.substringBefore('/')

        val userInfo = if (authority.contains('@')) {
            authority.substringBeforeLast('@')
        } else ""
        val hostPort = if (authority.contains('@')) {
            authority.substringAfterLast('@')
        } else authority

        val host: String
        val port: Int
        if (hostPort.startsWith('[')) {
            host = hostPort.substringAfter('[').substringBefore(']')
            val rest = hostPort.substringAfter(']', "")
            port = if (rest.startsWith(':')) rest.substring(1).toIntOrNull() ?: -1 else -1
        } else if (hostPort.contains(':')) {
            host = hostPort.substringBeforeLast(':')
            port = hostPort.substringAfterLast(':').toIntOrNull() ?: -1
        } else {
            host = hostPort
            port = -1
        }

        if (host.isBlank()) return null
        return ParsedUriParts(userInfo, host, port, rawQuery, fragment)
    }

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

    private fun parseQueryMapCaseInsensitive(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isNotBlank()) {
                val k = urlDecode(pair.substringBefore('=')).trim().lowercase()
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
