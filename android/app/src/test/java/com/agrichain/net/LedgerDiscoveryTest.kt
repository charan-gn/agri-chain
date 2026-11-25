package com.agrichain.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LedgerDiscoveryTest {

    @Test
    fun candidatesPreferSavedAndNormalize() {
        assertEquals(
            listOf(
                "http://192.168.1.5:8000",
                "http://127.0.0.1:8000",
                "http://10.0.2.2:8000",
            ),
            LedgerDiscovery.candidates("http://192.168.1.5:8000/"),
        )
    }

    @Test
    fun candidatesDropDuplicateSavedUrl() {
        assertEquals(
            listOf("http://127.0.0.1:8000", "http://10.0.2.2:8000"),
            LedgerDiscovery.candidates("  http://127.0.0.1:8000/  "),
        )
    }

    @Test
    fun parsesLanReply() {
        assertEquals(
            "http://192.168.31.71:8000",
            LedgerDiscovery.parseReply(
                """{"service":"agrichain","baseUrl":"http://192.168.31.71:8000/"}"""
            ),
        )
    }

    @Test
    fun rejectsForeignOrBrokenReplies() {
        assertNull(LedgerDiscovery.parseReply("""{"service":"other","baseUrl":"http://x:8000"}"""))
        assertNull(LedgerDiscovery.parseReply("""{"baseUrl":"http://x:8000"}"""))
        assertNull(LedgerDiscovery.parseReply("""{"service":"agrichain"}"""))
        assertNull(LedgerDiscovery.parseReply("""{"service":"agrichain","baseUrl":"ftp://x"}"""))
        assertNull(LedgerDiscovery.parseReply("not json"))
    }

    @Test
    fun probeHitsHealthAndAccepts200() {
        val paths = mutableListOf<String>()
        val (thread, port) = serveOnce(200, """{"ok":true}""") { paths += it }
        try {
            assertTrue(LedgerDiscovery.probe("http://127.0.0.1:$port", timeoutMs = 2_000))
        } finally {
            thread.join(2_000)
        }
        assertEquals(1, paths.size)
        assertTrue(paths.single(), paths.single().startsWith("GET /health "))
    }

    @Test
    fun probeRejectsErrorsAndClosedPorts() {
        val (thread, port) = serveOnce(503, "busy") { }
        try {
            assertFalse(LedgerDiscovery.probe("http://127.0.0.1:$port", timeoutMs = 2_000))
        } finally {
            thread.join(2_000)
        }
        assertFalse(LedgerDiscovery.probe("http://127.0.0.1:1", timeoutMs = 300))
    }

    /** Minimal one-shot HTTP server: the unit tests only need a status and a path. */
    private fun serveOnce(
        status: Int,
        body: String,
        onRequest: (String) -> Unit,
    ): Pair<Thread, Int> {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    onRequest(reader.readLine().orEmpty())
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 $status Test\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().write(bytes)
                    socket.getOutputStream().flush()
                }
            }
            server.close()
        }
        thread.isDaemon = true
        thread.start()
        return thread to server.localPort
    }
}
