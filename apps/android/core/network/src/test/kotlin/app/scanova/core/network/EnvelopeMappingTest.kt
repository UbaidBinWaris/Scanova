package app.scanova.core.network

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.common.util.sha256
import app.scanova.core.common.util.toHex
import app.scanova.core.network.api.CreateDocumentRequest
import app.scanova.core.network.api.DeviceDto
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnvelopeMappingTest {

    private val authority = TestAuthority()
    private val server = MockWebServer()
    private lateinit var remote: ScanovaRemote
    private val tokens = InMemoryTokenStore(storedTokens("access-1", "refresh-1"))

    @Before
    fun setUp() {
        server.startHttps(authority)
        remote = remoteFor(ServerConfig(server.address(), authority.root.certificate), tokens)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun AppResult<*>.error(): AppError = (this as AppResult.Failure).error

    @Test
    fun `success envelope yields the data`() = runTest {
        server.enqueue(
            json(
                200,
                """{"data":{"name":"Home","apiVersion":"1","serverVersion":"0.4.2",""" +
                    """"features":{"serverOcr":true,"ai":false},"somethingNew":42},"error":null,"meta":{}}""",
            ),
        )

        val result = remote.serverInfo()

        val info = (result as AppResult.Success).value
        assertEquals("Home", info.name)
        assertEquals("1", info.apiVersion)
        assertTrue(info.features.serverOcr)
        assertFalse(info.features.ai)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/server-info", request.target)
        // Public endpoint: the session's token must not be attached.
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["X-Scanova-No-Auth"])
    }

    @Test
    fun `list envelope carries the cursor from meta`() = runTest {
        val doc = """{"id":"d1","title":"Lease","tags":["home"],"pageCount":3,"sizeBytes":2048,""" +
            """"contentSha256":"ab","status":"READY","ocrStatus":"DONE","hasSearchablePdf":true,""" +
            """"capturedAt":"2026-11-10T09:00:00.000Z","createdAt":"2026-11-10T09:00:01.000Z",""" +
            """"updatedAt":"2026-11-10T09:05:00.000Z","deletedAt":null}"""
        server.enqueue(json(200, """{"data":[$doc],"error":null,"meta":{"nextCursor":"cursor-2"}}"""))

        val result = remote.listDocuments(limit = 50, cursor = "cursor-1", updatedSince = "2026-11-01T00:00:00Z")

        val page = (result as AppResult.Success).value
        assertEquals("cursor-2", page.nextCursor)
        assertEquals(listOf("Lease"), page.items.map { it.title })
        assertEquals(listOf("home"), page.items.single().tags)
        val request = server.takeRequest()
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertEquals("50", request.url.queryParameter("limit"))
        assertEquals("cursor-1", request.url.queryParameter("cursor"))
        assertEquals("2026-11-01T00:00:00Z", request.url.queryParameter("updatedSince"))
    }

    @Test
    fun `absent optional query parameters are not sent`() = runTest {
        server.enqueue(json(200, """{"data":[],"error":null,"meta":{"nextCursor":null}}"""))

        val page = (remote.listDocuments(limit = 50, cursor = null, updatedSince = null) as AppResult.Success).value

        assertNull(page.nextCursor)
        assertEquals(setOf("limit"), server.takeRequest().url.queryParameterNames)
    }

    @Test
    fun `every documented error code maps to its typed error`() = runTest {
        val cases = listOf(
            400 to ApiErrorCode.VALIDATION_FAILED,
            401 to ApiErrorCode.INVALID_CREDENTIALS,
            401 to ApiErrorCode.INVALID_REFRESH_TOKEN,
            403 to ApiErrorCode.ACCOUNT_DISABLED,
            403 to ApiErrorCode.ADMIN_WEB_ONLY,
            403 to ApiErrorCode.PASSWORD_CHANGE_REQUIRED,
            403 to ApiErrorCode.FORBIDDEN,
            404 to ApiErrorCode.NOT_FOUND,
            409 to ApiErrorCode.ID_CONFLICT,
            413 to ApiErrorCode.FILE_TOO_LARGE,
            413 to ApiErrorCode.QUOTA_EXCEEDED,
            415 to ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
            422 to ApiErrorCode.CHECKSUM_MISMATCH,
            500 to ApiErrorCode.INTERNAL_ERROR,
            503 to ApiErrorCode.SERVICE_UNAVAILABLE,
        )
        for ((status, code) in cases) {
            server.enqueue(errorEnvelope(status, code.name))

            // Login is a public call, so a 401 here is never retried or refreshed.
            val result = remote.login("member@example.org", "pw", DeviceDto("Pixel", model = "Pixel 9", appVersion = "0.1.0"))

            assertEquals(AppError.Api(code, status), result.error())
            server.takeRequest()
        }
    }

    @Test
    fun `rate limiting exposes the retry-after delay`() = runTest {
        server.enqueue(errorEnvelope(429, "RATE_LIMITED", "Retry-After" to "30"))

        val result = remote.serverInfo()

        assertEquals(AppError.Api(ApiErrorCode.RATE_LIMITED, 429, retryAfterSeconds = 30), result.error())
    }

    @Test
    fun `a code this app version does not know becomes UNKNOWN, not a crash`() = runTest {
        server.enqueue(errorEnvelope(418, "TEAPOT_MODE"))

        assertEquals(AppError.Api(ApiErrorCode.UNKNOWN, 418), remote.serverInfo().error())
    }

    @Test
    fun `a gateway error page without an envelope is treated as the service being down`() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("<html>Bad Gateway</html>").build())

        assertEquals(AppError.Api(ApiErrorCode.SERVICE_UNAVAILABLE, 502), remote.serverInfo().error())
    }

    @Test
    fun `a non-Scanova server is reported as an unexpected response`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("<html>Not here</html>").build())
        server.enqueue(MockResponse.Builder().code(200).body("<html>Welcome to my NAS</html>").build())
        server.enqueue(json(200, """{"data":null,"error":null}"""))

        assertEquals(AppError.UnexpectedResponse, remote.serverInfo().error())
        assertEquals(AppError.UnexpectedResponse, remote.serverInfo().error())
        assertEquals(AppError.UnexpectedResponse, remote.serverInfo().error())
    }

    @Test
    fun `an unreachable server is a connection error`() = runTest {
        val address = server.address()
        server.close()

        val result = remoteFor(ServerConfig(address, authority.root.certificate)).serverInfo()

        assertEquals(AppError.NoConnection, result.error())
    }

    @Test
    fun `an unreachable server while Android blocks the local network says so`() = runTest {
        val address = server.address()
        server.close()

        val result = remoteFor(ServerConfig(address, authority.root.certificate), localNetworkBlocked = true).serverInfo()

        assertEquals(AppError.LocalNetworkBlocked, result.error())
    }

    @Test
    fun `calls without a pairing fail fast`() = runTest {
        assertEquals(AppError.NotPaired, remoteFor(config = null).serverInfo().error())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `login sends the mobile client marker and device details`() = runTest {
        server.enqueue(json(200, tokensJson("a", "r")))

        val result = remote.login("member@example.org", "s3cret", DeviceDto("My phone", model = "Pixel 9", appVersion = "0.1.0"))

        assertEquals("member@example.org", (result as AppResult.Success).value.user.email)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/login", request.target)
        assertNull(request.headers["Authorization"])
        assertEquals(
            """{"email":"member@example.org","password":"s3cret","client":"mobile",""" +
                """"device":{"name":"My phone","platform":"android","model":"Pixel 9","appVersion":"0.1.0"}}""",
            request.body?.utf8(),
        )
    }

    @Test
    fun `create document omits optional fields that are not set`() = runTest {
        server.enqueue(
            json(
                201,
                """{"data":{"id":"d1","title":"T","pageCount":1,"status":"PENDING_UPLOAD",""" +
                    """"capturedAt":"2026-11-10T09:00:00Z","createdAt":"2026-11-10T09:00:00Z",""" +
                    """"updatedAt":"2026-11-10T09:00:00Z"},"error":null}""",
            ),
        )

        val result = remote.createDocument(
            CreateDocumentRequest(id = "d1", title = "T", pageCount = 1, capturedAt = "2026-11-10T09:00:00Z"),
        )

        assertEquals("PENDING_UPLOAD", (result as AppResult.Success).value.status)
        assertEquals(
            """{"id":"d1","title":"T","pageCount":1,"capturedAt":"2026-11-10T09:00:00Z"}""",
            server.takeRequest().body?.utf8(),
        )
    }

    @Test
    fun `upload streams the PDF with its checksum and length`() = runTest {
        val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(200_000) { (it % 251).toByte() }
        val hash = sha256(pdf).toHex()
        server.enqueue(
            json(
                200,
                """{"data":{"id":"d1","title":"T","pageCount":1,"status":"READY","ocrStatus":"PENDING",""" +
                    """"capturedAt":"2026-11-10T09:00:00Z","createdAt":"2026-11-10T09:00:00Z",""" +
                    """"updatedAt":"2026-11-10T09:00:00Z"},"error":null}""",
            ),
        )

        val result = remote.uploadFile("d1", hash, pdf.size.toLong()) { pdf.inputStream() }

        assertEquals("READY", (result as AppResult.Success).value.status)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/documents/d1/file", request.target)
        assertEquals("application/pdf", request.headers["Content-Type"])
        assertEquals(pdf.size.toString(), request.headers["Content-Length"])
        assertEquals(hash, request.headers["X-Content-SHA256"])
        assertEquals("Bearer access-1", request.headers["Authorization"])
        assertArrayEquals(pdf, request.body?.toByteArray())
    }

    @Test
    fun `download hands over the raw bytes and maps an error envelope`() = runTest {
        val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(50_000) { 7 }
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("Content-Type", "application/pdf").body(Buffer().write(pdf)).build(),
        )
        server.enqueue(errorEnvelope(404, "NOT_FOUND"))

        val ok = remote.downloadFile("d1", searchable = true) { it.readBytes() }
        val missing = remote.downloadFile("d2", searchable = false) { it.readBytes() }

        assertArrayEquals(pdf, (ok as AppResult.Success).value)
        assertEquals("searchable", server.takeRequest().url.queryParameter("variant"))
        assertEquals(AppError.Api(ApiErrorCode.NOT_FOUND, 404), missing.error())
        assertEquals("original", server.takeRequest().url.queryParameter("variant"))
    }
}
