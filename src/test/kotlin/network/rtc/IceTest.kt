package org.koremc.network.rtc

import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IceTest {
    @Test
    fun `host candidates use rfc 8445 priorities`() {
        val candidates = hostCandidates(19132, listOf(InetAddress.getByName("203.0.113.7"), InetAddress.getByName("198.51.100.4")))

        assertEquals("1 1 udp 2130706431 203.0.113.7 19132 typ host", candidates[0].toString())
        assertEquals("2 1 udp 2130706175 198.51.100.4 19132 typ host", candidates[1].toString())
    }

    @Test
    fun `ipv4 candidates come before ipv6`() {
        val candidates = hostCandidates(19132, listOf(InetAddress.getByName("2001:db8::1"), InetAddress.getByName("203.0.113.7")))

        assertTrue(candidates.first().address is Inet4Address)
        assertTrue(candidates[0].priority > candidates[1].priority)
    }

    @Test
    fun `credentials are long enough and random`() {
        val first = IceCredentials.random()
        val second = IceCredentials.random()

        assertTrue(first.ufrag.length >= 4 && first.pwd.length >= 22)
        assertTrue((first.ufrag + first.pwd).all { it.isLetterOrDigit() })
        assertNotEquals(first, second)
    }
}
