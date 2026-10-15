package ai.vox.app.core

import ai.vox.app.core.model.Capability
import ai.vox.app.core.session.CapabilityController
import ai.vox.app.core.session.SessionManager
import ai.vox.app.core.session.UsageKind
import ai.vox.app.core.session.UsageMeter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeController(override val capability: Capability, val failStart: Boolean = false, val failStop: Boolean = false) : CapabilityController {
    var running = false
    var stops = 0
    override suspend fun start(): Result<Unit> {
        if (failStart) return Result.failure(IllegalStateException("denied"))
        running = true
        return Result.success(Unit)
    }
    override suspend fun stop() {
        stops++
        running = false
        if (failStop) error("boom")
    }
}

class SessionManagerTest {
    private fun all() = Capability.entries.map { FakeController(it) }

    @Test fun `nothing is enabled until the user activates`() = runTest {
        val c = all()
        val s = SessionManager(c)
        assertFalse(s.state.value.active)
        assertTrue(s.enable(Capability.VOICE).isFailure)
        assertTrue(c.none { it.running })
    }

    @Test fun `end stops every capability and returns to inactive`() = runTest {
        val c = all()
        val s = SessionManager(c)
        s.activate()
        Capability.entries.forEach { assertTrue(s.enable(it).isSuccess) }
        assertTrue(c.all { it.running })
        s.end()
        assertTrue(c.none { it.running })
        assertFalse(s.state.value.active)
        assertTrue(s.state.value.enabled.isEmpty())
    }

    @Test fun `end still stops the rest when one controller throws`() = runTest {
        val bad = FakeController(Capability.SCREEN, failStop = true)
        val good = FakeController(Capability.VOICE)
        val s = SessionManager(listOf(bad, good))
        s.activate(); s.enable(Capability.SCREEN); s.enable(Capability.VOICE)
        s.end()
        assertFalse(good.running)
        assertEquals(1, bad.stops)
    }

    @Test fun `failed start leaves capability disabled`() = runTest {
        val s = SessionManager(listOf(FakeController(Capability.CAMERA, failStart = true)))
        s.activate()
        assertTrue(s.enable(Capability.CAMERA).isFailure)
        assertFalse(s.isEnabled(Capability.CAMERA))
    }

    @Test fun `disable stops only that capability`() = runTest {
        val a = FakeController(Capability.VOICE); val b = FakeController(Capability.SCREEN)
        val s = SessionManager(listOf(a, b))
        s.activate(); s.enable(Capability.VOICE); s.enable(Capability.SCREEN)
        s.disable(Capability.VOICE)
        assertFalse(a.running); assertTrue(b.running)
        assertEquals(setOf(Capability.SCREEN), s.state.value.enabled)
    }

    @Test fun `activate resets usage`() = runTest {
        var resets = 0
        val s = SessionManager(all(), onUsageReset = { resets++ })
        s.activate(); s.activate()
        assertEquals(1, resets)
    }

    @Test fun `usage meter enforces limits without partial counting`() {
        val m = UsageMeter { mapOf(UsageKind.LIVE_FRAME to 3) }
        assertTrue(m.tryConsume(UsageKind.LIVE_FRAME, 2))
        assertFalse(m.tryConsume(UsageKind.LIVE_FRAME, 2))
        assertTrue(m.tryConsume(UsageKind.LIVE_FRAME, 1))
        assertEquals(3, m.used(UsageKind.LIVE_FRAME))
        m.reset()
        assertEquals(0, m.used(UsageKind.LIVE_FRAME))
    }
}
