package com.spendai.app.ui.download

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifies the in-app model downloader against a [MockWebServer]:
 *  - a fresh download writes to .part and renames on success
 *  - a 206 response respects a Range request and appends to the partial
 *  - a 200 response when the server ignores Range restarts from zero
 *  - HTTP 4xx surfaces as a failure
 */
class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: ModelDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ModelDownloader()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun bodyBuffer(s: String): Buffer = Buffer().write(s.toByteArray())

    @Test
    fun `fresh download writes file and renames partial`() = kotlinx.coroutines.runBlocking {
        val payload = "hello-world"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.toByteArray().size.toString())
                .setBody(bodyBuffer(payload)),
        )

        val dest = File(tempFolder.root, "model.litertlm")
        val result = downloader.download(server.url("/file").toString(), dest)

        assertTrue(result.isSuccess)
        assertTrue(dest.exists())
        assertEquals(payload.toByteArray().size.toLong(), dest.length())
        assertFalse(File(dest.parentFile, dest.name + ".part").exists())
        assertEquals(payload, dest.readText())
    }

    @Test
    fun `partial file leads to Range request and 206 append`() = kotlinx.coroutines.runBlocking {
        val alreadyWritten = "hello-"
        val rest = "world"
        val partial = File(tempFolder.root, "model.litertlm.part")
        partial.writeBytes(alreadyWritten.toByteArray())

        val total = alreadyWritten.toByteArray().size + rest.toByteArray().size
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Length", rest.toByteArray().size.toString())
                .setHeader("Content-Range", "bytes ${alreadyWritten.length}-${total - 1}/$total")
                .setBody(bodyBuffer(rest)),
        )

        val dest = File(tempFolder.root, "model.litertlm")
        val result = downloader.download(server.url("/file").toString(), dest)

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("bytes=${alreadyWritten.length}-", recorded.getHeader("Range"))
        assertEquals(total.toLong(), dest.length())
        assertEquals("hello-world", dest.readText())
    }

    @Test
    fun `200 when partial exists discards partial and writes fresh payload`() = kotlinx.coroutines.runBlocking {
        val stale = "stale-bytes"
        val partial = File(tempFolder.root, "model.litertlm.part")
        partial.writeBytes(stale.toByteArray())

        val payload = "fresh-start"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", payload.toByteArray().size.toString())
                .setBody(bodyBuffer(payload)),
        )

        val dest = File(tempFolder.root, "model.litertlm")
        val result = downloader.download(server.url("/file").toString(), dest)

        assertTrue(result.isSuccess)
        // The downloader sends a Range header when a .part is present;
        // the server may ignore it and return 200. The contract we
        // lock down is the on-disk content, not the request shape.
        server.takeRequest()
        assertEquals("fresh-start", dest.readText())
    }

    @Test
    fun `http 404 surfaces as failure and leaves no dest file`() = kotlinx.coroutines.runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val dest = File(tempFolder.root, "model.litertlm")
        val result = downloader.download(server.url("/missing").toString(), dest)

        assertTrue(result.isFailure)
        assertFalse(dest.exists())
    }

}
