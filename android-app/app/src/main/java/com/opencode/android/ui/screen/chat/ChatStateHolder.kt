package com.opencode.android.ui.screen.chat

import androidx.compose.runtime.mutableStateListOf
import com.opencode.android.data.model.Message
import com.opencode.android.data.model.MessagePart
import com.opencode.android.data.model.ModelRef
import com.opencode.android.data.model.ToolState

enum class MessagePhase {
    LocalPending,
    Streaming,
    Settling,
    Settled,
    Failed,
}

data class ChatDisplayMessage(
    val renderId: String,
    val serverId: String? = null,
    val role: String,
    val sessionID: String? = null,
    val agent: String? = null,
    val providerID: String? = null,
    val modelID: String? = null,
    val phase: MessagePhase = MessagePhase.Settled,
    val visibleParts: List<ChatDisplayPart> = emptyList(),
    val deferredParts: List<ChatDisplayPart> = emptyList(),
    val error: String? = null,
)

data class ChatDisplayPart(
    val renderId: String,
    val serverPartId: String? = null,
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
    val sessionID: String? = null,
    val messageID: String? = null,
    val tool: String? = null,
    val callID: String? = null,
    val state: ToolState? = null,
    /** Original index in the server message `parts` list — drives render order. */
    val sourceOrder: Int = Int.MAX_VALUE,
)

data class LocalSendResult(
    val userRenderId: String,
    val assistantRenderId: String,
)

data class ServerMergeResult(
    val selectedModel: ModelRef? = null,
)

class ChatStateHolder(
    private var sessionId: String,
) {
    val messages = mutableStateListOf<ChatDisplayMessage>()

    private var activeLocalUserId: String? = null
    private var activeLocalAssistantId: String? = null
    private var activeUserBubbleText: String? = null
    private var activeSendText: String? = null
    private val consumedServerMessageIds = mutableSetOf<String>()

    fun resetForSession(newSessionId: String = sessionId) {
        sessionId = newSessionId
        messages.clear()
        activeLocalUserId = null
        activeLocalAssistantId = null
        activeUserBubbleText = null
        activeSendText = null
        consumedServerMessageIds.clear()
    }

    fun loadServerMessages(serverMessages: List<Message>) {
        messages.clear()
        consumedServerMessageIds.clear()
        messages.addAll(
            serverMessages
                .filter { it.hasVisibleContent() }
                .map { it.toDisplayMessage(renderId = it.info.id, phase = MessagePhase.Settled) }
        )
        clearActive()
    }

    fun onLocalSend(
        now: Long,
        bubbleText: String,
        sendText: String,
        displayAgent: String?,
        sendAgent: String,
        userParts: List<MessagePart>,
    ): LocalSendResult {
        val userRenderId = "local_user_$now"
        val assistantRenderId = "local_assistant_$now"

        activeLocalUserId = userRenderId
        activeLocalAssistantId = assistantRenderId
        activeUserBubbleText = bubbleText
        activeSendText = sendText

        val displayUserParts = userParts.toDisplayParts(includeEmptyText = false)
        messages.add(
            ChatDisplayMessage(
                renderId = userRenderId,
                role = "user",
                agent = displayAgent,
                phase = MessagePhase.LocalPending,
                visibleParts = displayUserParts,
            )
        )
        messages.add(
            ChatDisplayMessage(
                renderId = assistantRenderId,
                role = "assistant",
                sessionID = sessionId,
                agent = sendAgent,
                phase = MessagePhase.Streaming,
                visibleParts = listOf(
                    ChatDisplayPart(renderId = TEXT_PART_RENDER_ID, type = "text", text = "", sourceOrder = Int.MAX_VALUE),
                ),
            )
        )

        return LocalSendResult(userRenderId, assistantRenderId)
    }

    fun onStreamDeltaFlush(text: String) {
        if (text.isBlank()) return
        val assistantId = activeLocalAssistantId ?: return
        updateMessage(assistantId) { msg ->
            msg.copy(
                phase = MessagePhase.Streaming,
                visibleParts = msg.visibleParts.withStableText(text),
            )
        }
    }

    fun onCompleted() {
        val assistantId = activeLocalAssistantId ?: return
        updateMessage(assistantId) { msg ->
            msg.copy(phase = MessagePhase.Settling)
        }
    }

    fun onAbort() {
        val assistantId = activeLocalAssistantId
        if (assistantId != null) {
            val idx = messages.indexOfFirst { it.renderId == assistantId }
            if (idx >= 0) {
                val msg = messages[idx]
                if (!msg.hasVisibleContent()) {
                    messages.removeAt(idx)
                } else {
                    messages[idx] = msg.copy(phase = MessagePhase.Failed)
                }
            }
        }
        clearActive()
    }

    fun onSendFailure(): Boolean {
        val assistantId = activeLocalAssistantId ?: return true
        val idx = messages.indexOfFirst { it.renderId == assistantId }
        if (idx < 0) {
            clearActive()
            return true
        }
        val msg = messages[idx]
        if (!msg.hasVisibleContent()) {
            messages.removeAt(idx)
            clearActive()
            return true
        }
        return false
    }

    fun onServerMessages(serverMessages: List<Message>): ServerMergeResult {
        removeInvisibleNonActiveMessages()

        val existingServerIds = messages.mapNotNull { it.serverId }.toMutableSet()
        messages
            .filterNot { it.renderId.startsWith("local_") }
            .mapTo(existingServerIds) { it.renderId }

        val serverUser = serverMessages.lastOrNull { isDuplicateOfActiveUser(it) }
        if (serverUser != null) {
            bindServerUser(serverUser)
        }

        var selectedModel: ModelRef? = null
        val serverAssistant = serverMessages.lastOrNull {
            it.info.role == "assistant" &&
                it.info.id !in existingServerIds &&
                it.info.id !in consumedServerMessageIds &&
                it.hasVisibleContent()
        }
        if (serverAssistant != null && bindServerAssistant(serverAssistant)) {
            val info = serverAssistant.info
            if (info.providerID != null && info.modelID != null) {
                selectedModel = ModelRef(info.providerID, info.modelID)
            }
        }

        val activeLocalUserStillVisible = activeLocalUserId?.let { id ->
            messages.any { it.renderId == id }
        } == true
        val currentServerIds = messages.mapNotNull { it.serverId }.toMutableSet()
        messages
            .filterNot { it.renderId.startsWith("local_") }
            .mapTo(currentServerIds) { it.renderId }

        serverMessages
            .filter { it.hasVisibleContent() }
            .filter { it.info.id !in currentServerIds }
            .filter { it.info.id !in consumedServerMessageIds }
            .filterNot { activeLocalUserStillVisible && isDuplicateOfActiveUser(it) }
            .forEach { messages.add(it.toDisplayMessage(renderId = it.info.id, phase = MessagePhase.Settled)) }

        return ServerMergeResult(selectedModel = selectedModel)
    }

    fun releaseDeferredParts() {
        messages.indices.forEach { idx ->
            val msg = messages[idx]
            if (msg.deferredParts.isNotEmpty()) {
                messages[idx] = msg.copy(
                    visibleParts = (msg.visibleParts + msg.deferredParts).sortedBy { it.sourceOrder },
                    deferredParts = emptyList(),
                )
            }
        }
    }

    fun finishSettling() {
        messages.indices.forEach { idx ->
            val msg = messages[idx]
            if (msg.phase == MessagePhase.Settling || msg.phase == MessagePhase.Streaming) {
                messages[idx] = msg.copy(phase = MessagePhase.Settled)
            }
        }
        clearActive()
        removeInvisibleNonActiveMessages()
    }

    private fun bindServerUser(serverUser: Message) {
        consumedServerMessageIds += serverUser.info.id
        val localUserId = activeLocalUserId ?: return
        updateMessage(localUserId) { msg ->
            msg.copy(
                serverId = serverUser.info.id,
                sessionID = serverUser.info.sessionID ?: msg.sessionID,
                agent = serverUser.info.agent ?: msg.agent,
                providerID = serverUser.info.providerID ?: msg.providerID,
                modelID = serverUser.info.modelID ?: msg.modelID,
                phase = MessagePhase.Settled,
            )
        }
    }

    private fun bindServerAssistant(serverAssistant: Message): Boolean {
        val localAssistantId = activeLocalAssistantId ?: return false
        consumedServerMessageIds += serverAssistant.info.id
        updateMessage(localAssistantId) { msg ->
            val allParts = serverAssistant.parts.toDisplayParts(includeEmptyText = false)
            val deferredParts = allParts.filter { it.type in DEFERRED_PART_TYPES }
            val immediateParts = allParts.filter { it.type !in DEFERRED_PART_TYPES }
            val visibleParts = if (serverAssistant.combinedText().isBlank() && msg.phase == MessagePhase.Streaming) {
                mergePartsByRenderId(msg.visibleParts, immediateParts).sortedBy { it.sourceOrder }
            } else {
                immediateParts.sortedBy { it.sourceOrder }
            }

            msg.copy(
                serverId = serverAssistant.info.id,
                sessionID = serverAssistant.info.sessionID ?: msg.sessionID,
                agent = serverAssistant.info.agent ?: msg.agent,
                providerID = serverAssistant.info.providerID ?: msg.providerID,
                modelID = serverAssistant.info.modelID ?: msg.modelID,
                phase = MessagePhase.Settling,
                visibleParts = visibleParts,
                deferredParts = mergePartsByRenderId(msg.deferredParts, deferredParts).sortedBy { it.sourceOrder },
            )
        }
        return true
    }

    private fun updateMessage(renderId: String, transform: (ChatDisplayMessage) -> ChatDisplayMessage) {
        val idx = messages.indexOfFirst { it.renderId == renderId }
        if (idx >= 0) {
            messages[idx] = transform(messages[idx])
        }
    }

    private fun removeInvisibleNonActiveMessages() {
        messages.removeAll { msg ->
            msg.renderId != activeLocalUserId &&
                msg.renderId != activeLocalAssistantId &&
                !msg.hasVisibleContent()
        }
    }

    private fun clearActive() {
        activeLocalUserId = null
        activeLocalAssistantId = null
        activeUserBubbleText = null
        activeSendText = null
    }

    private fun isDuplicateOfActiveUser(serverMsg: Message): Boolean {
        if (serverMsg.info.role != "user") return false
        val text = serverMsg.firstText() ?: return false
        return text == activeUserBubbleText || text == activeSendText || text.startsWith("Delegate to @")
    }

    private fun ChatDisplayMessage.hasVisibleContent(): Boolean {
        return visibleParts.any { it.hasVisibleContent() }
    }

    private fun Message.toDisplayMessage(renderId: String, phase: MessagePhase): ChatDisplayMessage {
        return ChatDisplayMessage(
            renderId = renderId,
            serverId = info.id.takeUnless { renderId == it },
            role = info.role,
            sessionID = info.sessionID,
            agent = info.agent,
            providerID = info.providerID,
            modelID = info.modelID,
            phase = phase,
            visibleParts = parts.toDisplayParts(includeEmptyText = false),
        )
    }

    private fun List<ChatDisplayPart>.withStableText(text: String): List<ChatDisplayPart> {
        val textIndex = indexOfFirst { it.renderId == TEXT_PART_RENDER_ID }
        if (textIndex >= 0) {
            val part = this[textIndex]
            if (part.text == text) return this
            return mapIndexed { index, item ->
                if (index == textIndex) item.copy(text = text) else item
            }
        }
        return listOf(
            ChatDisplayPart(renderId = TEXT_PART_RENDER_ID, type = "text", text = text, sourceOrder = Int.MAX_VALUE),
        ) + this
    }

    private fun List<MessagePart>.toDisplayParts(
        includeEmptyText: Boolean,
        allowTypes: Set<String>? = null,
    ): List<ChatDisplayPart> {
        val counters = mutableMapOf<String, Int>()
        return mapIndexedNotNull { index, part ->
            if (allowTypes != null && part.type !in allowTypes) return@mapIndexedNotNull null
            val ordinal = counters.getOrDefault(part.type, 0)
            counters[part.type] = ordinal + 1
            part.toDisplayPart(ordinal, includeEmptyText)?.copy(sourceOrder = index)
        }
    }

    private fun MessagePart.toDisplayPart(ordinal: Int, includeEmptyText: Boolean): ChatDisplayPart? {
        if (type == "text" && text.isNullOrBlank() && !includeEmptyText) return null
        if (type == "reasoning" && text.isNullOrBlank()) return null
        if (type !in VISIBLE_PART_TYPES && type !in DEFERRED_PART_TYPES) return null
        return ChatDisplayPart(
            renderId = renderIdFor(type, ordinal, id, callID),
            serverPartId = id,
            type = type,
            text = text,
            mime = mime,
            url = url,
            filename = filename,
            sessionID = sessionID,
            messageID = messageID,
            tool = tool,
            callID = callID,
            state = state,
        )
    }

    private fun ChatDisplayPart.hasVisibleContent(): Boolean {
        return when (type) {
            "text", "reasoning" -> !text.isNullOrBlank()
            "file", "image", "tool", "tool-invocation", "tool-result" -> true
            else -> false
        }
    }

    private fun Message.hasVisibleContent(): Boolean {
        return parts.any { part ->
            when (part.type) {
                "text", "reasoning" -> !part.text.isNullOrBlank()
                "file", "image", "tool", "tool-invocation", "tool-result" -> true
                else -> false
            }
        }
    }

    private fun Message.combinedText(): String {
        return parts
            .filter { it.type == "text" && !it.text.isNullOrBlank() }
            .joinToString("\n\n") { it.text.orEmpty() }
    }

    private fun Message.firstText(): String? = parts.firstOrNull { it.type == "text" }?.text

    private fun mergePartsByRenderId(
        current: List<ChatDisplayPart>,
        incoming: List<ChatDisplayPart>,
    ): List<ChatDisplayPart> {
        if (incoming.isEmpty()) return current
        val result = current.toMutableList()
        incoming.forEach { part ->
            val idx = result.indexOfFirst { it.renderId == part.renderId }
            if (idx >= 0) {
                if (result[idx] != part) result[idx] = part
            } else {
                result.add(part)
            }
        }
        return result.sortedBy { it.sourceOrder }
    }

    private fun renderIdFor(type: String, ordinal: Int, id: String?, callID: String?): String {
        return when (type) {
            "text" -> "text:$ordinal"
            "reasoning" -> "reasoning:${id ?: ordinal}"
            "tool", "tool-invocation", "tool-result" -> "$type:${callID ?: id ?: ordinal}"
            "file", "image" -> "$type:${id ?: ordinal}"
            else -> "$type:${id ?: ordinal}"
        }
    }

    private companion object {
        const val TEXT_PART_RENDER_ID = "text:0"
        val VISIBLE_PART_TYPES = setOf("text", "reasoning", "file", "image", "tool", "tool-invocation", "tool-result")
        val DEFERRED_PART_TYPES = setOf("tool", "tool-invocation", "tool-result", "step-start", "step-finish")
    }
}
