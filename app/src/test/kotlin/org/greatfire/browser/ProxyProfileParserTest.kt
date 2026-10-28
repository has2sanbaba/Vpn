package org.greatfire.browser

import org.greatfire.browser.envoy.ShadowsocksRunner
import org.greatfire.browser.envoy.SingBoxRunner
import org.greatfire.browser.vpn.ProxyProfile
import org.greatfire.browser.vpn.ProxyProfileParser
import org.greatfire.browser.vpn.TransportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxyProfileParserTest {

    @Test
    fun parsesGreatFireEnvoyUrlAndShadowsocksIncluding2022() {
        val envoyUri = "envoy://?url=https%3A%2F%2Fcdn.example.org%2Fenvoy%2F&address=104.16.1.1&header_Host=front.example.org"
        val envoyProfile = ProxyProfileParser.parseSingleUri(envoyUri)
        assertNotNull(envoyProfile)
        assertEquals(TransportKind.ENVOY_URL, envoyProfile!!.kind)
        assertEquals("https://cdn.example.org/envoy/", envoyProfile.envoyUrl)
        assertEquals("104.16.1.1", envoyProfile.envoyAddress)
        assertEquals("front.example.org", envoyProfile.frontHost)

        // SIP002 Shadowsocks URI
        val credsB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("chacha20-ietf-poly1305:s3cr3tPass!".toByteArray())
        val ssUri = "ss://$credsB64@198.51.100.42:8388#Germany-Node"
        val ssProfile = ProxyProfileParser.parseSingleUri(ssUri)
        assertNotNull(ssProfile)
        assertEquals(TransportKind.SHADOWSOCKS, ssProfile!!.kind)
        assertEquals("Germany-Node", ssProfile.name)
        assertEquals("198.51.100.42", ssProfile.host)
        assertEquals(8388, ssProfile.port)

        val ssJson = ShadowsocksRunner.parseShadowsocksUri(ssUri)
        assertNotNull(ssJson)
        assertEquals("chacha20-ietf-poly1305", ssJson!!.getString("method"))
        assertEquals("s3cr3tPass!", ssJson.getString("password"))
        assertEquals(8388, ssJson.getInt("server_port"))

        // Shadowsocks-2022 with colon-separated multi-PSK inside password
        val ss2022Raw = "ss://2022-blake3-aes-128-gcm:iK2%2B3L4%3D%3AxY9z0A%3D%3D@198.51.100.55:443#%F0%9F%87%A9%F0%9F%87%AA%20SS-2022"
        val ss2022 = ProxyProfileParser.parseSingleUri(ss2022Raw)
        assertNotNull(ss2022)
        assertEquals("2022-blake3-aes-128-gcm", ss2022!!.username)
        assertEquals("iK2+3L4=:xY9z0A==", ss2022.password)
    }

    @Test
    fun parsesVlessRealityTrojanTuicAndBuildsSingBoxV114Config() {
        // Real-world VLESS XTLS-Reality URI with unescaped spaces & emoji in #fragment
        val realityUri =
            "vless://b831381d-6324-4d53-ad4f-8cda48b30811@185.199.108.153:443" +
                "?encryption=none&flow=xtls-rprx-vision&security=reality" +
                "&sni=www.microsoft.com&fp=chrome&pbk=PUBKEY_TEST_12345&sid=a1b2c3d4" +
                "&type=tcp#🇩🇪 آلمان Reality پرسرعت"

        val reality = ProxyProfileParser.parseSingleUri(realityUri)
        assertNotNull(reality)
        assertEquals(TransportKind.VLESS, reality!!.kind)
        assertEquals("185.199.108.153", reality.host)
        assertEquals(443, reality.port)
        assertEquals("www.microsoft.com", reality.sni)
        assertEquals("reality", reality.extra["security"])
        assertEquals("PUBKEY_TEST_12345", reality.extra["pbk"])
        assertEquals("a1b2c3d4", reality.extra["sid"])
        assertEquals("xtls-rprx-vision", reality.extra["flow"])

        // Validate Sing-Box v1.14.4 JSON configuration schema
        val sbJson = SingBoxRunner.buildSingBoxConfig(reality, 29080)
        assertEquals("dns-bootstrap", sbJson.getJSONObject("route").getString("default_domain_resolver"))
        val out = sbJson.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", out.getString("type"))
        assertEquals("xtls-rprx-vision", out.getString("flow"))
        val tls = out.getJSONObject("tls")
        assertTrue(tls.getBoolean("enabled"))
        assertEquals("PUBKEY_TEST_12345", tls.getJSONObject("reality").getString("public_key"))

        // VLESS WebSocket with ?ed=2048 early data & TLS record fragmentation
        val wsUri = "vless://b831381d-6324-4d53-ad4f-8cda48b30811@104.21.10.20:443?security=tls&type=ws&host=worker.example.dev&sni=worker.example.dev&path=%2Fws%3Fed%3D2048#CF-WS"
        val wsProfile = ProxyProfileParser.parseSingleUri(wsUri)
        assertNotNull(wsProfile)
        val wsOut = SingBoxRunner.buildSingBoxConfig(wsProfile!!, 29081)
            .getJSONArray("outbounds").getJSONObject(0)
        assertTrue(wsOut.getJSONObject("tls").getBoolean("record_fragment"))
        assertEquals("/ws", wsOut.getJSONObject("transport").getString("path"))
        assertEquals(2048, wsOut.getJSONObject("transport").getInt("max_early_data"))

        // Trojan TLS + gRPC
        val trojanUri = "trojan://myTrojanPass@198.51.100.80:443?security=tls&type=grpc&serviceName=gunTun&sni=trojan.example.com#Trojan-NL"
        val trojan = ProxyProfileParser.parseSingleUri(trojanUri)
        assertNotNull(trojan)
        assertEquals(TransportKind.TROJAN, trojan!!.kind)
        assertEquals("myTrojanPass", trojan.password)

        // TUIC v5
        val tuicUri = "tuic://11111111-2222-3333-4444-555555555555:tuicSecret@198.51.100.90:8443?congestion_control=bbr&alpn=h3&sni=tuic.example.org#TUIC-Fast"
        val tuic = ProxyProfileParser.parseSingleUri(tuicUri)
        assertNotNull(tuic)
        assertEquals(TransportKind.TUIC, tuic!!.kind)
        assertEquals("tuicSecret", tuic.password)
    }

    @Test
    fun parsesVmessXhttpHysteria2AndGreatFireSchemes() {
        val vmessJson = """
            {"v":"2","ps":"Frankfurt-WS","add":"203.0.113.10","port":"443","id":"11111111-2222-3333-4444-555555555555","net":"ws","host":"ws.example.com","path":"/tunnel","tls":"tls","sni":"ws.example.com"}
        """.trimIndent()
        val vmessUri = "vmess://" + Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val vmess = ProxyProfileParser.parseSingleUri(vmessUri)
        assertNotNull(vmess)
        assertEquals(TransportKind.V2RAY_WS, vmess!!.kind)
        assertEquals("Frankfurt-WS", vmess.name)
        assertEquals("203.0.113.10", vmess.host)
        assertEquals(443, vmess.port)
        assertEquals("/tunnel", vmess.path)

        val xhttpUri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@198.51.100.77:443?type=xhttp&sni=cdn.test.dev&path=%2Fxray#XHTTP-Fast"
        val xhttp = ProxyProfileParser.parseSingleUri(xhttpUri)
        assertNotNull(xhttp)
        assertEquals(TransportKind.XRAY_XHTTP, xhttp!!.kind)
        assertEquals("XHTTP-Fast", xhttp.name)
        assertEquals("/xray", xhttp.path)

        val hy2Uri = "hy2://authToken123@198.51.100.99:8443?sni=quic.example.net#QUIC-HY2"
        val hy2 = ProxyProfileParser.parseSingleUri(hy2Uri)
        assertNotNull(hy2)
        assertEquals(TransportKind.HYSTERIA2, hy2!!.kind)
        assertEquals("authToken123", hy2.password)
        assertTrue(hy2.rawUri.startsWith("hysteria2://"))
        assertEquals(8443, hy2.port)

        val v2srtp = ProxyProfileParser.parseSingleUri(
            "v2srtp://192.0.2.55:14433?id=99999999-8888-7777-6666-555555555555#GF-SRTP"
        )
        assertNotNull(v2srtp)
        assertEquals(TransportKind.V2RAY_SRTP, v2srtp!!.kind)
    }

    @Test
    fun parsesBase64SubscriptionAndRoundTripsJson() {
        val rawBlock = """
            # Subscription comment
            socks5://user1:pass1@192.0.2.10:1080#LocalSocks
            snowflake://?front=www.sstatic.net
            masque://secretToken@198.51.100.88:443
        """.trimIndent()
        val encodedSub = Base64.getEncoder().encodeToString(rawBlock.toByteArray())

        val parsed = ProxyProfileParser.parseMany(encodedSub)
        assertEquals(3, parsed.size)
        assertEquals(TransportKind.SOCKS5, parsed[0].kind)
        assertEquals(TransportKind.SNOWFLAKE, parsed[1].kind)
        assertEquals(TransportKind.MASQUE, parsed[2].kind)

        val roundTripped = ProxyProfile.fromJson(parsed[0].toJson())
        assertNotNull(roundTripped)
        assertEquals(parsed[0].host, roundTripped!!.host)
        assertEquals(parsed[0].username, roundTripped.username)
    }
}
