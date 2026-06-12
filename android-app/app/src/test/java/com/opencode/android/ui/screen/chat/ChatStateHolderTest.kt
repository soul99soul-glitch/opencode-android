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
    fun reducerStreamsTextDeltaIntoUpdatedPart() {
        val holder = ChatStateHolder("s1")

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "", sessionID = "s1", messageID = "a1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartDelta("s1", "a1", "p1", "text", "hello"))
        holder.onCompleted()
        holder.finishSettling()

        val assistant = holder.messages.single()
        assertEquals("hello", assistant.visibleParts.single().text)
        assertEquals(MessagePhase.Settled, assistant.phase)
    }

    @Test
    fun reducerKeepsThinkingToolAndFinalTextOrder() {
        val holder = ChatStateHolder("s1")

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "r1", type = "reasoning", text = "", sessionID = "s1", messageID = "a1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartDelta("s1", "a1", "r1", "text", "thinking"))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "t1", type = "tool", tool = "bash", callID = "c1", sessionID = "s1", messageID = "a1", state = ToolState(status = "running"))))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "t1", type = "tool", tool = "bash", callID = "c1", sessionID = "s1", messageID = "a1", state = ToolState(status = "completed", output = "ok"))))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "r2", type = "reasoning", text = "more thinking", sessionID = "s1", messageID = "a1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "txt", type = "text", text = "final answer", sessionID = "s1", messageID = "a1")))

        val parts = holder.messages.single().visibleParts
        assertEquals(listOf("reasoning", "tool", "reasoning", "text"), parts.map { it.type })
        assertEquals("final answer", parts.last().text)
    }

    @Test
    fun orphanDeltaDoesNotPolluteHydratedSnapshot() {
        val holder = ChatStateHolder("s1")

        holder.onTimelineEvent(ChatTimelineEvent.PartDelta("s1", "a1", "p1", "text", "ignored"))
        holder.onServerMessages(listOf(assistant("a1", "hydrated")))

        assertEquals("hydrated", holder.messages.single().visibleParts.single().text)
        assertTrue(holder.diagnostics.any { it.type == "orphan_delta" })
    }

    @Test
    fun liveDeltaSurvivesEmptySnapshot() {
        val holder = ChatStateHolder("s1")

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "", sessionID = "s1", messageID = "a1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartDelta("s1", "a1", "p1", "text", "visible live content"))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "", sessionID = "s1", messageID = "a1")))

        assertEquals("visible live content", holder.messages.single().visibleParts.single().text)
    }

    @Test
    fun userRoleTextPartDoesNotEnterAssistantOutput() {
        val holder = ChatStateHolder("s1")

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "u1", role = "user", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "user prompt", sessionID = "s1", messageID = "u1")))

        assertEquals("user", holder.messages.single().role)
        assertFalse(holder.messages.any { it.role == "assistant" })
    }

    @Test
    fun emptyReducerTextPartKeepsStreamingPlaceholderSlot() {
        val holder = ChatStateHolder("s1")
        holder.onLocalSend(
            now = 1,
            bubbleText = "hello",
            sendText = "hello",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "hello")),
        )

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "", sessionID = "s1", messageID = "a1")))

        val assistant = holder.messages.single { it.renderId == "local_assistant_1" }
        assertEquals(MessagePhase.Streaming, assistant.phase)
        assertEquals(listOf("text"), assistant.visibleParts.map { it.type })
        assertEquals("", assistant.visibleParts.single().text)
    }

    @Test
    fun activeSendBindsAssistantAfterMatchingUserNotEarlierHistory() {
        val holder = ChatStateHolder("s1")
        holder.loadServerMessages(listOf(user("u0", "old prompt"), assistant("a0", "old answer")))
        holder.onLocalSend(
            now = 2,
            bubbleText = "new prompt",
            sendText = "new prompt",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "new prompt")),
        )

        holder.onServerMessages(
            listOf(
                user("u0", "old prompt"),
                assistant("a0", "old answer"),
                user("u1", "new prompt"),
                assistant("a1", "new answer"),
            ),
        )

        val activeAssistant = holder.messages.single { it.renderId == "local_assistant_2" }
        assertEquals("a1", activeAssistant.serverId)
        assertEquals("new answer", activeAssistant.visibleParts.single { it.type == "text" }.text)
        assertEquals("old answer", holder.messages.single { it.renderId == "a0" }.visibleParts.single().text)
    }

    @Test
    fun snapshotThenLiveMessageAppendsAfterHydratedMessages() {
        val holder = ChatStateHolder("s1")
        holder.loadServerMessages(listOf(user("u0", "old"), assistant("a0", "answer")))

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "new", sessionID = "s1", messageID = "a1")))

        assertEquals(listOf("u0", "a0", "a1"), holder.messages.map { it.renderId })
    }

    @Test
    fun repeatedNoIdPartSnapshotUpdatesSingleFallbackPart() {
        val holder = ChatStateHolder("s1")

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(type = "text", text = "one", sessionID = "s1", messageID = "a1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(type = "text", text = "two", sessionID = "s1", messageID = "a1")))

        val parts = holder.messages.single().visibleParts
        assertEquals(1, parts.size)
        assertEquals("two", parts.single().text)
    }

    @Test
    fun staleShortSnapshotDoesNotOverwriteLongerLiveText() {
        val holder = ChatStateHolder("s1")

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "hello", sessionID = "s1", messageID = "a1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartDelta("s1", "a1", "p1", "text", " world"))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "hello", sessionID = "s1", messageID = "a1")))

        assertEquals("hello world", holder.messages.single().visibleParts.single().text)
    }

    @Test
    fun divergentEqualLengthSnapshotDoesNotOverwriteLiveText() {
        val holder = ChatStateHolder("s1")

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "abc", sessionID = "s1", messageID = "a1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartDelta("s1", "a1", "p1", "text", "def"))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "abcXef", sessionID = "s1", messageID = "a1")))

        assertEquals("abcdef", holder.messages.single().visibleParts.single().text)
    }

    @Test
    fun activeSendDoesNotBindHistoryWhenUserIsNotServerBound() {
        val holder = ChatStateHolder("s1")
        holder.loadServerMessages(listOf(user("u0", "old prompt"), assistant("a0", "old answer")))
        holder.onLocalSend(
            now = 2,
            bubbleText = "new prompt",
            sendText = "new prompt",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "new prompt")),
        )

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a0", role = "assistant", sessionID = "s1")))

        assertNull(holder.messages.single { it.renderId == "local_assistant_2" }.serverId)
    }

    @Test
    fun activeSendDoesNotMarkUpdatedHistoryAssistantAsStreaming() {
        val holder = ChatStateHolder("s1")
        holder.loadServerMessages(listOf(user("u0", "old prompt"), assistant("a0", "old answer")))
        holder.onLocalSend(
            now = 2,
            bubbleText = "new prompt",
            sendText = "new prompt",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "new prompt")),
        )

        holder.onTimelineEvent(
            ChatTimelineEvent.MessageUpdated(
                MessageInfo(id = "a0", role = "assistant", sessionID = "s1", providerID = "p", modelID = "m"),
            ),
        )

        assertEquals(MessagePhase.Settled, holder.messages.single { it.renderId == "a0" }.phase)
        assertEquals(MessagePhase.Streaming, holder.messages.single { it.renderId == "local_assistant_2" }.phase)
    }

    @Test
    fun activeSendKeepsHistoryReasoningSettled() {
        val holder = ChatStateHolder("s1")
        holder.loadServerMessages(listOf(user("u0", "old prompt"), assistantWithReasoning("a0", "old thinking", "old answer")))
        holder.onLocalSend(
            now = 2,
            bubbleText = "new prompt",
            sendText = "new prompt",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "new prompt")),
        )

        val history = holder.messages.single { it.renderId == "a0" }
        assertEquals(MessagePhase.Settled, history.phase)
        assertEquals(listOf("reasoning", "text"), history.visibleParts.map { it.type })
        assertEquals(MessagePhase.Streaming, holder.messages.single { it.renderId == "local_assistant_2" }.phase)
    }

    @Test
    fun activeSendWithRepeatedUserTextBindsOnlyFreshServerUser() {
        val holder = ChatStateHolder("s1")
        holder.loadServerMessages(listOf(user("u0", "repeat"), assistant("a0", "old answer")))
        holder.onLocalSend(
            now = 2,
            bubbleText = "repeat",
            sendText = "repeat",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "repeat")),
        )

        holder.onServerMessages(
            listOf(
                user("u0", "repeat"),
                assistant("a0", "old answer"),
                user("u1", "repeat"),
                assistant("a1", "new answer"),
            ),
        )

        assertEquals("u1", holder.messages.single { it.renderId == "local_user_2" }.serverId)
        assertEquals("a1", holder.messages.single { it.renderId == "local_assistant_2" }.serverId)
        assertEquals(listOf("u0", "a0", "local_user_2", "local_assistant_2"), holder.messages.map { it.renderId })
    }

    @Test
    fun assistantDeltaBeforeMatchingUserBindsWhenUserArrives() {
        val holder = ChatStateHolder("s1")
        holder.loadServerMessages(listOf(user("u0", "old prompt"), assistant("a0", "old answer")))
        holder.onLocalSend(
            now = 2,
            bubbleText = "new prompt",
            sendText = "new prompt",
            displayAgent = null,
            sendAgent = "orchestrator",
            userParts = listOf(MessagePart(type = "text", text = "new prompt")),
        )

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "new answer", sessionID = "s1", messageID = "a1")))

        assertFalse(holder.messages.any { it.renderId == "a1" })
        assertNull(holder.messages.single { it.renderId == "local_assistant_2" }.serverId)

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "u1", role = "user", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "up1", type = "text", text = "new prompt", sessionID = "s1", messageID = "u1")))

        val activeAssistant = holder.messages.single { it.renderId == "local_assistant_2" }
        assertEquals("a1", activeAssistant.serverId)
        assertEquals("new answer", activeAssistant.visibleParts.single { it.type == "text" }.text)
        assertFalse(holder.messages.any { it.renderId == "a1" })
    }

    @Test
    fun sessionStatusEventMovesActiveAssistantToSettling() {
        val holder = holderWithStreamingText("answer")

        holder.onTimelineEvent(ChatTimelineEvent.SessionStatusChanged("s1", "completed"))

        assertEquals(MessagePhase.Settling, holder.messages.single { it.role == "assistant" }.phase)
    }

    @Test
    fun partRemovedEventRemovesVisiblePart() {
        val holder = ChatStateHolder("s1")

        holder.onTimelineEvent(ChatTimelineEvent.MessageUpdated(MessageInfo(id = "a1", role = "assistant", sessionID = "s1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "r1", type = "reasoning", text = "thinking", sessionID = "s1", messageID = "a1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartUpdated(MessagePart(id = "p1", type = "text", text = "answer", sessionID = "s1", messageID = "a1")))
        holder.onTimelineEvent(ChatTimelineEvent.PartRemoved("a1", "r1"))

        assertEquals(listOf("text"), holder.messages.single().visibleParts.map { it.type })
    }

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

        holder.onServerMessages(listOf(assistant("a1", "hello world")))
        val tail = holder.messages.last()
        assertEquals("local_assistant_1", tail.renderId)
        assertEquals("text:0", tail.visibleParts.first().renderId)
        assertEquals("hello world", tail.visibleParts.first().text)

        holder.onServerMessages(listOf(assistant("a1", "replacement")))
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
    fun reasoningAndToolAreVisibleDuringStreaming() {
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
        assertNotNull(settling.visibleParts.firstOrNull { it.type == "tool" })
        assertEquals(
            listOf("reasoning", "tool", "text"),
            settling.visibleParts.map { it.type },
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

        val ordered = holder.messages.last().visibleParts
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

    private fun assistantWithReasoning(id: String, reasoning: String, text: String): Message {
        return Message(
            info = MessageInfo(id = id, role = "assistant", providerID = "p", modelID = "m"),
            parts = listOf(
                MessagePart(type = "reasoning", text = reasoning),
                MessagePart(type = "text", text = text),
            ),
        )
    }
}
