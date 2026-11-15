package app.scanova.core.network

import app.scanova.core.common.result.ApiErrorCode
import app.scanova.core.common.result.AppError
import app.scanova.core.common.result.AppResult
import app.scanova.core.network.auth.SessionEvent
import app.scanova.core.network.auth.SessionEvents
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TokenRefreshTest {

    private val authority = TestAuthority()
    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore(storedTokens("access-old", "refresh-old"))
    private val sessionEvents = SessionEvents()
    private lateinit var remote: ScanovaRemote

    private val refreshCalls = AtomicInteger()
    private val refreshBodies = CopyOnWriteArrayList<String>()
    private val meCalls = CopyOnWriteArrayList<String?>()

    /** What the fake server answers on `POST /auth/refresh`. */
    @Volatile
    private var refreshResponse: () -> MockResponse = { json(200, tokensJson("access-new", "refresh-new")) }

    /** Lets a test hold the refresh open to prove that other calls wait for it. */
    @Volatile
    private var refreshDelayMs: Long = 0

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/v1/auth/refresh" -> {
                    refreshCalls.incrementAndGet()
                    refreshBodies += request.body?.utf8().orEmpty()
                    if (refreshDelayMs > 0) Thread.sleep(refreshDelayMs)
                    refreshResponse()
                }
                "/api/v1/me" -> {
                    val authorization = request.headers["Authorization"]
                    meCalls += authorization
                    if (authorization == "Bearer access-new") {
                        json(200, """{"data":$USER_JSON,"error":null}""")
                    } else {
                        errorEnvelope(401, "UNAUTHENTICATED")
                    }
                }
                "/api/v1/me/password" -> errorEnvelope(401, "INVALID_CREDENTIALS")
                "/api/v1/me/sessions" -> errorEnvelope(401, "SESSION_REVOKED")
                else -> errorEnvelope(404, "NOT_FOUND")
            }
        }
        server.startHttps(authority)
        remote = remoteFor(ServerConfig(server.address(), authority.root.certificate), tokens, sessionEvents)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `an expired access token is refreshed once and the call is replayed`() = runBlocking {
        val result = remote.me()

        assertEquals("member@example.org", (result as AppResult.Success).value.email)
        assertEquals(listOf<String?>("Bearer access-old", "Bearer access-new"), meCalls.toList())
        assertEquals(1, refreshCalls.get())
        assertEquals(listOf("""{"refreshToken":"refresh-old"}"""), refreshBodies.toList())
        // The rotated pair replaced the old one.
        assertEquals("access-new", tokens.current()?.accessToken)
        assertEquals("refresh-new", tokens.current()?.refreshToken)
    }

    @Test
    fun `many calls failing together share a single refresh`() = runBlocking {
        // A single-use refresh token must be spent exactly once: a second
        // refresh with the old token would make the server revoke the session.
        refreshDelayMs = 300

        val results = (1..12).map { async(Dispatchers.IO) { remote.me() } }.awaitAll()

        assertTrue(results.all { it is AppResult.Success })
        assertEquals(1, refreshCalls.get())
        assertEquals("refresh-new", tokens.current()?.refreshToken)
    }

    @Test
    fun `a rejected refresh ends the session and reports it`() = runBlocking {
        refreshResponse = { errorEnvelope(401, "INVALID_REFRESH_TOKEN") }
        val events = CopyOnWriteArrayList<SessionEvent>()
        val collector = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            sessionEvents.events.collect { events += it }
        }

        val result = remote.me()

        assertEquals(AppError.Api(ApiErrorCode.UNAUTHENTICATED, 401), (result as AppResult.Failure).error)
        assertNull(tokens.current())
        assertEquals(listOf<SessionEvent>(SessionEvent.Ended), events.toList())
        assertEquals(1, refreshCalls.get())
        collector.cancel()
    }

    @Test
    fun `a refresh that cannot reach a decision keeps the session`() = runBlocking {
        refreshResponse = { MockResponse.Builder().code(503).body("busy").build() }

        val result = remote.me()

        assertEquals(AppError.Api(ApiErrorCode.UNAUTHENTICATED, 401), (result as AppResult.Failure).error)
        assertEquals("refresh-old", tokens.current()?.refreshToken)
    }

    @Test
    fun `a revoked session is ended without attempting a refresh`() = runBlocking {
        val result = remote.sessions()

        assertEquals(AppError.Api(ApiErrorCode.SESSION_REVOKED, 401), (result as AppResult.Failure).error)
        assertNull(tokens.current())
        assertEquals(0, refreshCalls.get())
    }

    @Test
    fun `a 401 that is the call's own answer does not trigger a refresh or a replay`() = runBlocking {
        val result = remote.changePassword("wrong-current", "new-password")

        assertEquals(AppError.Api(ApiErrorCode.INVALID_CREDENTIALS, 401), (result as AppResult.Failure).error)
        assertEquals(0, refreshCalls.get())
        assertEquals(1, server.requestCount)
        assertEquals("refresh-old", tokens.current()?.refreshToken)
    }

    @Test
    fun `a failed login is never retried with a refresh`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = errorEnvelope(401, "UNAUTHENTICATED")
        }

        val result = remote.serverInfo()

        assertEquals(AppError.Api(ApiErrorCode.UNAUTHENTICATED, 401), (result as AppResult.Failure).error)
        assertEquals(1, server.requestCount)
        assertEquals("refresh-old", tokens.current()?.refreshToken)
    }
}
