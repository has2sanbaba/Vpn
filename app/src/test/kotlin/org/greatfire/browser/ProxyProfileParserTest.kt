package org.greatfire.browser

import org.greatfire.browser.envoy.ShadowsocksRunner
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
    fun parsesGreatFireEnvoyUrlAndShadowsocks() {
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
    }

    @Test
    fun parsesVmessVlessHysteria2AndGreatFireSchemes() {
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

        val vlessUri = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@198.51.100.77:443?type=xhttp&sni=cdn.test.dev&path=%2Fxray#XHTTP-Fast"
        val vless = ProxyProfileParser.parseSingleUri(vlessUri)
        assertNotNull(vless)
        assertEquals(TransportKind.XRAY_XHTTP, vless!!.kind)
        assertEquals("XHTTP-Fast", vless.name)
        assertEquals("/xray", vless.path)

        val hy2Uri = "hy2://authToken123@198.51.100.99:8443?sni=quic.example.net#QUIC-HY2"
        val hy2 = ProxyProfileParser.parseSingleUri(hy2Uri)
        assertNotNull(hy2)
        assertEquals(TransportKind.HYSTERIA2, hy2!!.kind)
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
