package ai.vox.app.core

import ai.vox.app.core.assistant.Assistant
import ai.vox.app.core.assistant.AssistantEvent
import ai.vox.app.core.assistant.History
import ai.vox.app.core.model.AiTask
import ai.vox.app.core.model.ContentPart
import ai.vox.app.core.model.ResponseLength
import ai.vox.app.core.model.Role
import ai.vox.app.core.model.Turn
import ai.vox.app.core.net.AiException
import ai.vox.app.core.net.AiProvider
import ai.vox.app.core.net.AiReply
import ai.vox.app.core.net.AiRequest
import ai.vox.app.core.net.GatewayAiProvider
import ai.vox.app.core.net.GatewayConfig
import ai.vox.app.core.net.GatewayStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantAndGatewayTest {
    private class FakeProvider(var answer: suspend (AiRequest) -> String = { "ok" }) : AiProvider {
        val requests = mutableListOf<AiRequest>()
        override suspend fun complete(request: AiRequest): AiReply { requests += request; return AiReply(answer(request), "fake") }
        override suspend fun status() = GatewayStatus("fake", "fake", 0, 0, 0, 0, 0, 0)
    }

    private fun turn(role: Role, vararg parts: ContentPart) = Turn("x", role, "d", parts.toList(), 0)
    private val img = ContentPart.Image("AAAA")

    @Test fun `history keeps only the newest images`() {
        val turns = listOf(turn(Role.USER, ContentPart.Text("a"), img), turn(Role.ASSISTANT, ContentPart.Text("b")), turn(Role.USER, ContentPart.Text("c"), img, img))
        val pruned = History.prune(turns, 2)
        assertEquals(0, pruned[0].parts.count { it is ContentPart.Image })
        assertEquals(2, pruned[2].parts.count { it is ContentPart.Image })
        assertTrue(pruned[0].parts.any { it is ContentPart.Text && it.text.contains("no longer attached") })
    }

    @Test fun `ask adds turns, emits reply and clears busy`() = runTest {
        val p = FakeProvider { "Hello there" }
        val a = Assistant("chat", p, TestScope(StandardTestDispatcher(testScheduler)), { ResponseLength.SHORT })
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val a2 = Assistant("chat", p, scope, { ResponseLength.SHORT })
        val job = a2.ask(AiTask.CHAT, "Hi", spoken = true)
        assertTrue(a2.busy.value)
        job.join()
        assertFalse(a2.busy.value)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), a2.turns.value.map { it.role })
        assertEquals(ResponseLength.SHORT, p.requests.single().length)
        assertTrue(p.requests.single().spoken)
        assertNotNull(a)
    }

    @Test fun `errors become error turns with friendly text and are not sent back as history`() = runTest {
        val p = FakeProvider { throw AiException.Network() }
        val a = Assistant("chat", p, TestScope(StandardTestDispatcher(testScheduler)), { ResponseLength.MEDIUM })
        a.ask(AiTask.CHAT, "Hi").join()
        assertTrue(a.turns.value.last().isError)
        p.answer = { "fine" }
        a.regenerate()!!.join()
        assertEquals(listOf(Role.USER, Role.ASSISTANT), a.turns.value.map { it.role })
        assertFalse(a.turns.value.last().isError)
        assertEquals(1, p.requests.last().messages.size)
    }

    @Test fun `cancel stops the request and busy resets`() = runTest {
        val gate = CompletableDeferred<String>()
        val p = FakeProvider { gate.await() }
        val a = Assistant("chat", p, TestScope(StandardTestDispatcher(testScheduler)), { ResponseLength.MEDIUM })
        val job = a.ask(AiTask.CHAT, "Hi")
        advanceUntilIdle()
        assertTrue(a.busy.value)
        a.cancel()
        job.join()
        assertFalse(a.busy.value)
        assertEquals(1, a.turns.value.size)   // only the user turn; no half answer
    }

    @Test fun `gateway sends bearer token and maps errors`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"text":"Hi!","model":"m"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("""{"error":{"code":"rate_limited","message":"x","retryAfterSec":120}}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"x"}}"""))
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":{"code":"model_unavailable","message":"x"}}"""))
        server.start()
        try {
            val gw = GatewayAiProvider(config = { GatewayConfig(server.url("/").toString(), "tok") })
            val req = AiRequest(AiTask.SCREEN, listOf(ai.vox.app.core.model.AiMessage(Role.USER, listOf(ContentPart.Text("q"), img))), ResponseLength.DETAILED, false)
            assertEquals("Hi!", gw.complete(req).text)
            val recorded = server.takeRequest()
            assertEquals("/v1/complete", recorded.path)
            assertEquals("Bearer tok", recorded.getHeader("Authorization"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("\"task\":\"screen\"") && body.contains("\"type\":\"image\"") && body.contains("\"data\":\"AAAA\""))
            assertTrue(runCatching { gw.complete(req) }.exceptionOrNull().let { it is AiException.RateLimited && it.retryAfterSec == 120 })
            assertTrue(runCatching { gw.complete(req) }.exceptionOrNull() is AiException.Unauthorized)
            assertTrue(runCatching { gw.complete(req) }.exceptionOrNull() is AiException.ModelUnavailable)
        } finally { server.shutdown() }
    }

    @Test fun `gateway reports not configured and network failure`() = runTest {
        val req = AiRequest(AiTask.CHAT, listOf(ai.vox.app.core.model.AiMessage(Role.USER, listOf(ContentPart.Text("q")))), ResponseLength.SHORT, false)
        try { GatewayAiProvider(config = { GatewayConfig("", "") }).complete(req); fail() } catch (e: AiException.NotConfigured) {}
        try { GatewayAiProvider(config = { GatewayConfig("http://127.0.0.1:1/", "t") }).complete(req); fail() } catch (e: AiException.Network) {}
    }
}
