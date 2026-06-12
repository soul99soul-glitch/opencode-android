package com.opencode.android.ui.screen.chat

import com.opencode.android.data.model.Message
import com.opencode.android.data.model.MessageInfo
import com.opencode.android.data.model.MessagePart
import com.opencode.android.data.model.ToolState
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
    fun reasoningDeltaKeepsStableReasoningPartBeforeText() {
        val holder = ChatStateHolder("s1")
        holder.onLocalSend(
            now = 1,
            bubbleText = "hello",
            sendText = "hello",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "hello")),
        )

        holder.onReasoningDeltaFlush("thinking")
        holder.onReasoningDeltaFlush("thinking more")
        holder.onStreamDeltaFlush("answer")

        val assistant = holder.messages.last()
        assertEquals(listOf("reasoning", "text"), assistant.visibleParts.map { it.type })
        assertEquals("reasoning:stream", assistant.visibleParts.first().renderId)
        assertEquals("thinking more", assistant.visibleParts.first().text)
        assertEquals("answer", assistant.visibleParts.last().text)
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
    fun sameServerAssistantCanReceiveTextAfterReasoningOnlySnapshot() {
        val holder = ChatStateHolder("s1")
        holder.onLocalSend(
            now = 1,
            bubbleText = "hello",
            sendText = "hello",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "hello")),
        )

        holder.onServerMessages(
            listOf(
                user("u1", "hello"),
                Message(
                    info = MessageInfo(id = "a1", role = "assistant"),
                    parts = listOf(MessagePart(type = "reasoning", id = "r1", text = "thinking")),
                ),
            ),
        )
        holder.onServerMessages(
            listOf(
                user("u1", "hello"),
                Message(
                    info = MessageInfo(id = "a1", role = "assistant"),
                    parts = listOf(
                        MessagePart(type = "reasoning", id = "r1", text = "thinking"),
                        MessagePart(type = "text", text = "final answer"),
                    ),
                ),
            ),
        )

        val assistant = holder.messages.single { it.role == "assistant" }
        assertEquals("a1", assistant.serverId)
        assertEquals(listOf("reasoning", "text"), assistant.visibleParts.map { it.type })
        assertEquals("final answer", assistant.visibleParts.last().text)
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
        assertEquals(
            listOf("reasoning", "text", "tool"),
            released.visibleParts.sortedBy { it.sourceOrder }.map { it.type },
        )
    }

    @Test
    fun multiStepThinkingStaysBeforeFinalTextInServerOrder() {
        val holder = holderWithStreamingText("final answer")
        holder.onServerMessages(
            listOf(
                Message(
                    info = MessageInfo(id = "a1", role = "assistant"),
                    parts = listOf(
                        MessagePart(type = "reasoning", id = "r1", text = "think 1"),
                        MessagePart(
                            type = "tool",
                            tool = "bash",
                            callID = "c1",
                            state = ToolState(status = "completed", output = "line1\nline2\nline3"),
                        ),
                        MessagePart(type = "reasoning", id = "r2", text = "think 2"),
                        MessagePart(type = "text", text = "final answer"),
                    ),
                ),
            ),
        )

        holder.releaseDeferredParts()
        val ordered = holder.messages.last().visibleParts.sortedBy { it.sourceOrder }
        assertEquals(listOf("reasoning", "tool", "reasoning", "text"), ordered.map { it.type })
        assertEquals("think 2", ordered[2].text)
        assertTrue(ordered.indexOfFirst { it.type == "text" } > ordered.indexOfLast { it.type == "reasoning" })
    }

    @Test
    fun coldLoadedAssistantFinalTextRendersAfterThinkingAndToolParts() {
        val holder = ChatStateHolder("s1")

        holder.loadServerMessages(
            listOf(
                Message(
                    info = MessageInfo(id = "a1", role = "assistant"),
                    parts = listOf(
                        MessagePart(type = "text", text = "final answer"),
                        MessagePart(type = "reasoning", text = "thinking"),
                        MessagePart(type = "tool", tool = "bash", callID = "c1"),
                        MessagePart(type = "reasoning", text = "more thinking"),
                    ),
                )
            )
        )

        val parts = holder.messages.single().visibleParts
        assertEquals(listOf("reasoning", "tool", "reasoning", "text"), parts.map { it.type })
        assertEquals("final answer", parts.last().text)
    }

    @Test
    fun streamingAssistantFinalTextStaysAfterReleasedToolParts() {
        val holder = holderWithStreamingText("final answer")

        holder.onServerMessages(
            listOf(
                Message(
                    info = MessageInfo(id = "a1", role = "assistant"),
                    parts = listOf(
                        MessagePart(type = "text", text = "final answer"),
                        MessagePart(type = "reasoning", text = "thinking"),
                        MessagePart(type = "tool", tool = "bash", callID = "c1"),
                        MessagePart(type = "reasoning", text = "more thinking"),
                    ),
                )
            )
        )

        holder.releaseDeferredParts()

        val parts = holder.messages.single { it.role == "assistant" }.visibleParts
        assertEquals(listOf("reasoning", "tool", "reasoning", "text"), parts.map { it.type })
        assertEquals("final answer", parts.last().text)
    }

    @Test
    fun intermediateTextKeepsPositionWhileFinalTextRendersLast() {
        val holder = ChatStateHolder("s1")

        holder.loadServerMessages(
            listOf(
                Message(
                    info = MessageInfo(id = "a1", role = "assistant"),
                    parts = listOf(
                        MessagePart(type = "text", text = "before tool"),
                        MessagePart(type = "tool", tool = "bash", callID = "c1"),
                        MessagePart(type = "text", text = "final answer"),
                    ),
                )
            )
        )

        val parts = holder.messages.single().visibleParts
        assertEquals(listOf("text", "tool", "text"), parts.map { it.type })
        assertEquals("before tool", parts.first().text)
        assertEquals("final answer", parts.last().text)
        assertEquals("final-text", parts.last().renderId)
    }

    @Test
    fun reasoningSnapshotWithoutTextKeepsStreamingTextChannel() {
        val holder = holderWithStreamingText("partial answer")

        holder.onServerMessages(
            listOf(
                Message(
                    info = MessageInfo(id = "a1", role = "assistant"),
                    parts = listOf(MessagePart(type = "reasoning", text = "still thinking")),
                )
            )
        )

        val parts = holder.messages.single { it.role == "assistant" }.visibleParts
        assertEquals(listOf("reasoning", "text"), parts.map { it.type })
        assertEquals("partial answer", parts.last().text)
    }

    @Test
    fun serverTextOnlySnapshotPreservesStreamedReasoning() {
        val holder = ChatStateHolder("s1")
        holder.onLocalSend(
            now = 1,
            bubbleText = "hello",
            sendText = "hello",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "hello")),
        )
        holder.onReasoningDeltaFlush("streamed thinking")

        holder.onServerMessages(listOf(user("u1", "hello"), assistant("a1", "final answer")))

        val parts = holder.messages.single { it.role == "assistant" }.visibleParts
        assertEquals(listOf("reasoning", "text"), parts.map { it.type })
        assertEquals("streamed thinking", parts.first().text)
        assertEquals("final answer", parts.last().text)
    }

    @Test
    fun unknownRoleTextSnapshotMatchingActiveUserIsRejected() {
        val holder = ChatStateHolder("s1")
        holder.onLocalSend(
            now = 1,
            bubbleText = "hello",
            sendText = "hello",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "hello")),
        )

        assertFalse(
            holder.shouldApplyAssistantTextSnapshot(
                messageId = null,
                role = null,
                text = "hello",
            ),
        )
    }

    @Test
    fun unknownRoleTextSnapshotAfterAssistantBindCanBeAppliedByMessageId() {
        val holder = ChatStateHolder("s1")
        holder.onLocalSend(
            now = 1,
            bubbleText = "hello",
            sendText = "hello",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "hello")),
        )
        holder.onServerMessages(listOf(user("u1", "hello"), assistant("a1", "partial")))

        assertTrue(
            holder.shouldApplyAssistantTextSnapshot(
                messageId = "a1",
                role = null,
                text = "final answer",
            ),
        )
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
