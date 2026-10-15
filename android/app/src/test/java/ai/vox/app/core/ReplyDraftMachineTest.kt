package ai.vox.app.core

import ai.vox.app.core.notifications.ReplyDraftMachine
import ai.vox.app.core.notifications.ReplyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyDraftMachineTest {
    private fun drafted(m: ReplyDraftMachine, text: String = "On my way") {
        assertTrue(m.startDrafting()); m.draftReady(text)
    }

    @Test fun `cannot send without confirmation step`() {
        val m = ReplyDraftMachine(canReply = true)
        drafted(m)
        assertNull(m.confirmSend())               // skipping confirmation is impossible
        assertTrue(m.state.value is ReplyState.Drafted)
    }

    @Test fun `happy path returns exactly the confirmed text once`() {
        val m = ReplyDraftMachine(true)
        drafted(m, "  Sure, 5pm works  ")
        assertTrue(m.requestSend())
        assertEquals(ReplyState.Confirming("Sure, 5pm works"), m.state.value)
        assertEquals("Sure, 5pm works", m.confirmSend())
        assertNull(m.confirmSend())               // can't double-send
        m.sendSucceeded()
        assertEquals(ReplyState.Sent, m.state.value)
    }

    @Test fun `non replyable notifications can never draft or send`() {
        val m = ReplyDraftMachine(false)
        assertFalse(m.startDrafting()); assertFalse(m.edit("hi")); assertFalse(m.requestSend())
        assertNull(m.confirmSend())
    }

    @Test fun `blank drafts cannot be sent`() {
        val m = ReplyDraftMachine(true)
        drafted(m, "   ")
        assertFalse(m.requestSend())
    }

    @Test fun `editing during confirmation cancels approval`() {
        val m = ReplyDraftMachine(true)
        drafted(m)
        m.requestSend()
        m.edit("Actually no")
        assertNull(m.confirmSend())
        assertEquals(ReplyState.Drafted("Actually no"), m.state.value)
    }

    @Test fun `cancel works until sending and failure keeps the text`() {
        val m = ReplyDraftMachine(true)
        drafted(m); m.requestSend(); m.cancel()
        assertEquals(ReplyState.Idle, m.state.value)
        drafted(m, "hello"); m.requestSend(); m.confirmSend()
        m.cancel()                                 // ignored mid-send
        assertTrue(m.state.value is ReplyState.Sending)
        m.sendFailed("app refused")
        assertEquals(ReplyState.Failed("app refused", "hello"), m.state.value)
        m.backToEditing()
        assertEquals(ReplyState.Drafted("hello"), m.state.value)
    }
}
