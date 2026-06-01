package com.opencode.android.ui.screen.chat

import com.opencode.android.data.model.Message
import com.opencode.android.data.model.MessageInfo
import com.opencode.android.data.model.MessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatStateHolderTest {

    @Test
    fun streamDeltaKeepsStableAssistantAndTextPartIds() {
        val holder = ChatStateHolder("s1")
        holder.onLocalSend(
            now = 1,
            bubbleText = "hello",
            sendText = "hello",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "hello")),
        )

        holder.onStreamDeltaFlush("hel")
        holder.onStreamDeltaFlush("hello")

        val assistant = holder.messages.last()
        assertEquals("local_assistant_1", assistant.renderId)
        assertEquals("text:0", assistant.visibleParts.first().renderId)
        assertEquals("hello", assistant.visibleParts.first().text)
    }

    @Test
    fun serverTextPatchKeepsRenderIdsForSameTailAndDifferentText() {
        val holder = holderWithStreamingText("hello")

        holder.onServerMessages(listOf(assistant("a1", "hello")))
        val same = holder.messages.last()
        assertEquals("local_assistant_1", same.renderId)
        assertEquals("text:0", same.visibleParts.first().renderId)
        assertEquals("hello", same.visibleParts.first().text)

        holder.onServerMessages(listOf(assistant("a2", "hello world")))
        val tail = holder.messages.last()
        assertEquals("local_assistant_1", tail.renderId)
        assertEquals("text:0", tail.visibleParts.first().renderId)
        assertEquals("hello world", tail.visibleParts.first().text)

        holder.onServerMessages(listOf(assistant("a3", "replacement")))
        val replaced = holder.messages.last()
        assertEquals("local_assistant_1", replaced.renderId)
        assertEquals("text:0", replaced.visibleParts.first().renderId)
        assertEquals("replacement", replaced.visibleParts.first().text)
    }

    @Test
    fun emptyServerShellsAreDropped() {
        val holder = ChatStateHolder("s1")
        holder.loadServerMessages(
            listOf(
                Message(info = MessageInfo(id = "u1", role = "user"), parts = listOf(MessagePart(type = "text", text = ""))),
                Message(info = MessageInfo(id = "a1", role = "assistant"), parts = listOf(MessagePart(type = "text", text = ""))),
            )
        )

        assertTrue(holder.messages.isEmpty())
    }

    @Test
    fun duplicateServerSyncIsIdempotent() {
        val holder = holderWithStreamingText("hello")

        holder.onServerMessages(listOf(user("u1", "hello"), assistant("a1", "hello")))
        holder.onServerMessages(listOf(user("u1", "hello"), assistant("a1", "hello")))

        assertEquals(2, holder.messages.size)
        assertEquals("u1", holder.messages.first().serverId)
        assertEquals("a1", holder.messages.last().serverId)
    }

    @Test
    fun identicalUserTextAcrossTurnsDoesNotDedupeColdLoadedHistory() {
        val holder = ChatStateHolder("s1")

        holder.loadServerMessages(
            listOf(
                user("u1", "repeat"),
                assistant("a1", "one"),
                user("u2", "repeat"),
                assistant("a2", "two"),
            )
        )

        assertEquals(4, holder.messages.size)
        assertEquals(listOf("u1", "a1", "u2", "a2"), holder.messages.map { it.renderId })
    }

    @Test
    fun resetForSessionClearsConsumedServerIds() {
        val holder = holderWithStreamingText("hello")
        holder.onServerMessages(listOf(assistant("a1", "hello")))
        holder.finishSettling()

        holder.resetForSession("s2")
        holder.onLocalSend(
            now = 2,
            bubbleText = "again",
            sendText = "again",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "again")),
        )
        holder.onStreamDeltaFlush("again")
        holder.onServerMessages(listOf(assistant("a1", "again")))

        assertEquals("a1", holder.messages.last().serverId)
    }

    @Test
    fun reasoningVisibleAndToolDeferredUntilRelease() {
        val holder = holderWithStreamingText("answer")

        holder.onServerMessages(
            listOf(
                Message(
                    info = MessageInfo(id = "a1", role = "assistant"),
                    parts = listOf(
                        MessagePart(type = "reasoning", text = "thinking"),
                        MessagePart(type = "text", text = "answer"),
                        MessagePart(type = "tool", tool = "read", callID = "c1"),
                    ),
                )
            )
        )

        val settling = holder.messages.last()
        assertNotNull(settling.visibleParts.firstOrNull { it.type == "reasoning" })
        assertNull(settling.visibleParts.firstOrNull { it.type == "tool" })
        assertNotNull(settling.deferredParts.firstOrNull { it.type == "tool" })

        holder.releaseDeferredParts()
        val released = holder.messages.last()
        assertNotNull(released.visibleParts.firstOrNull { it.type == "tool" })
        assertTrue(released.deferredParts.isEmpty())
    }

    @Test
    fun abortRemovesEmptyAssistantSlot() {
        val holder = ChatStateHolder("s1")
        holder.onLocalSend(
            now = 1,
            bubbleText = "hello",
            sendText = "hello",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "hello")),
        )

        holder.onAbort()

        assertEquals(1, holder.messages.size)
        assertFalse(holder.messages.any { it.role == "assistant" })
    }

    private fun holderWithStreamingText(text: String): ChatStateHolder {
        val holder = ChatStateHolder("s1")
        holder.onLocalSend(
            now = 1,
            bubbleText = "hello",
            sendText = "hello",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "hello")),
        )
        holder.onStreamDeltaFlush(text)
        return holder
    }

    private fun user(id: String, text: String): Message {
        return Message(
            info = MessageInfo(id = id, role = "user"),
            parts = listOf(MessagePart(type = "text", text = text)),
        )
    }

    private fun assistant(id: String, text: String): Message {
        return Message(
            info = MessageInfo(id = id, role = "assistant", providerID = "p", modelID = "m"),
            parts = listOf(MessagePart(type = "text", text = text)),
        )
    }
}
