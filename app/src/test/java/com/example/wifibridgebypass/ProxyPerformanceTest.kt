package com.example.wifibridgebypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

class ProxyPerformanceTest {

    @Test
    fun testPrivateAddressClassification() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val hotspot1 = InetAddress.getByName("192.168.43.1")
        val hotspot2 = InetAddress.getByName("192.168.42.129")
        val lanClassA = InetAddress.getByName("10.0.0.5")
        val lanClassB = InetAddress.getByName("172.20.1.100")
        val publicIp = InetAddress.getByName("8.8.8.8")
        val publicIp2 = InetAddress.getByName("1.1.1.1")

        assertTrue("127.0.0.1 should be private/local", isPrivateOrLocalAddress(loopback))
        assertTrue("192.168.43.1 should be private/local", isPrivateOrLocalAddress(hotspot1))
        assertTrue("192.168.42.129 should be private/local", isPrivateOrLocalAddress(hotspot2))
        assertTrue("10.0.0.5 should be private/local", isPrivateOrLocalAddress(lanClassA))
        assertTrue("172.20.1.100 should be private/local", isPrivateOrLocalAddress(lanClassB))
        assertFalse("8.8.8.8 should NOT be private/local", isPrivateOrLocalAddress(publicIp))
        assertFalse("1.1.1.1 should NOT be private/local", isPrivateOrLocalAddress(publicIp2))
    }

    @Test
    fun testSocks5AuthenticationNegotiation() {
        // Valid client greeting: Version 5, 2 methods (0x00 No Auth, 0x02 User/Pass)
        val validGreeting = byteArrayOf(0x05, 0x02, 0x00, 0x02)
        val inStream = ByteArrayInputStream(validGreeting)
        val outStream = ByteArrayOutputStream()

        val success = negotiateAuthentication(inStream, outStream)
        assertTrue("Handshake with NO_AUTH must succeed", success)
        val response = outStream.toByteArray()
        assertEquals(2, response.size)
        assertEquals(0x05.toByte(), response[0]) // Version 5
        assertEquals(0x00.toByte(), response[1]) // NO_AUTH chosen
    }

    @Test
    fun testSocks5RejectUnsupportedVersion() {
        // SOCKS4 greeting
        val socks4Greeting = byteArrayOf(0x04, 0x01, 0x00, 0x50)
        val inStream = ByteArrayInputStream(socks4Greeting)
        val outStream = ByteArrayOutputStream()

        val success = negotiateAuthentication(inStream, outStream)
        assertFalse("SOCKS4 greeting must be rejected", success)
    }

    @Test
    fun testBufferPoolAcquireAndRelease() {
        val pool = ConcurrentLinkedQueue<ByteArray>()
        val count = AtomicInteger(0)
        val bufferSize = 32 * 1024

        fun acquire(): ByteArray {
            val buf = pool.poll()
            if (buf != null) {
                count.decrementAndGet()
                return buf
            }
            return ByteArray(bufferSize)
        }

        fun release(buf: ByteArray) {
            if (buf.size == bufferSize && count.get() < 16) {
                pool.offer(buf)
                count.incrementAndGet()
            }
        }

        val b1 = acquire()
        assertEquals(bufferSize, b1.size)
        release(b1)
        assertEquals(1, count.get())

        val b2 = acquire()
        assertEquals(0, count.get())
        assertTrue("Pooled buffer reference should match recycled instance", b1 === b2)
    }

    private fun isPrivateOrLocalAddress(address: InetAddress?): Boolean {
        if (address == null) return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) {
            return true
        }
        val bytes = address.address
        if (bytes.size != 4) return false
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        return (a == 10) || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private fun negotiateAuthentication(input: ByteArrayInputStream, output: ByteArrayOutputStream): Boolean {
        val version = input.read()
        if (version != 0x05) return false

        val numMethods = input.read()
        if (numMethods <= 0) return false

        val methods = ByteArray(numMethods)
        input.read(methods)

        val supportsNoAuth = methods.any { it.toInt() == 0x00 }
        if (!supportsNoAuth) {
            output.write(byteArrayOf(0x05.toByte(), 0xFF.toByte()))
            output.flush()
            return false
        }

        output.write(byteArrayOf(0x05.toByte(), 0x00.toByte()))
        output.flush()
        return true
    }
}
