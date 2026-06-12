package com.opencode.android.ui.screen.chat

import androidx.compose.runtime.mutableStateListOf
import com.opencode.android.data.model.Message
import com.opencode.android.data.model.MessageInfo
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
    val isFinalText: Boolean = false,
    val sourceOrder: Int = Int.MAX_VALUE,
)

data class LocalSendResult(
    val userRenderId: String,
    val assistantRenderId: String,
)

data class ServerMergeResult(
    val selectedModel: ModelRef? = null,
)

sealed interface ChatTimelineEvent {
    data class MessageUpdated(val info: MessageInfo) : ChatTimelineEvent
    data class PartUpdated(val part: MessagePart) : ChatTimelineEvent
    data class PartDelta(
        val sessionID: String,
        val messageID: String,
        val partID: String,
        val field: String,
        val delta: String,
    ) : ChatTimelineEvent
    data class PartRemoved(val messageID: String, val partID: String) : ChatTimelineEvent
    data class SessionStatusChanged(val sessionID: String, val status: String) : ChatTimelineEvent
}

data class ChatSessionStatus(
    val sessionID: String,
    val status: String,
)

data class ChatTimelineDiagnostic(
    val type: String,
    val detail: String,
)

private data class TimelinePart(
    val key: String,
    val part: MessagePart,
    val order: Int,
    val hasLiveText: Boolean = false,
)

private data class TimelineMessage(
    val info: MessageInfo,
    val order: Int,
    val parts: List<TimelinePart> = emptyList(),
)

class ChatTimelineReducer(
    private var sessionId: String,
) {
    private val messagesById = linkedMapOf<String, TimelineMessage>()
    private var nextMessageOrder = 0
    private var nextPartOrder = 0
    var lastSessionStatus: ChatSessionStatus? = null
        private set
    val diagnostics = mutableListOf<ChatTimelineDiagnostic>()

    fun reset(newSessionId: String = sessionId) {
        sessionId = newSessionId
        messagesById.clear()
        diagnostics.clear()
        lastSessionStatus = null
        nextMessageOrder = 0
        nextPartOrder = 0
    }

    fun loadSnapshot(messages: List<Message>) {
        reset(sessionId)
        mergeSnapshot(messages)
    }

    fun mergeSnapshot(messages: List<Message>) {
        messages.forEachIndexed { messageIndex, message ->
            apply(ChatTimelineEvent.MessageUpdated(message.info), messageIndex, null)
            message.parts.forEachIndexed { partIndex, part ->
                apply(
                    ChatTimelineEvent.PartUpdated(
                        part.withMessageDefaults(message.info.sessionID, message.info.id),
                    ),
                    preferredMessageOrder = null,
                    preferredPartOrder = partIndex,
                )
            }
        }
    }

    fun apply(event: ChatTimelineEvent) {
        apply(event, preferredMessageOrder = null, preferredPartOrder = null)
    }

    private fun apply(
        event: ChatTimelineEvent,
        preferredMessageOrder: Int?,
        preferredPartOrder: Int?,
    ) {
        when (event) {
            is ChatTimelineEvent.MessageUpdated -> upsertMessage(event.info, preferredMessageOrder)
            is ChatTimelineEvent.PartUpdated -> upsertPart(event.part, preferredPartOrder)
            is ChatTimelineEvent.PartDelta -> appendDelta(event)
            is ChatTimelineEvent.PartRemoved -> removePart(event.messageID, event.partID)
            is ChatTimelineEvent.SessionStatusChanged -> updateSessionStatus(event)
        }
    }

    fun displayMessages(phases: Map<String, MessagePhase> = emptyMap()): List<ChatDisplayMessage> {
        return messagesById.values
            .sortedBy { it.order }
            .filter { it.info.role != UNKNOWN_ROLE }
            .mapNotNull { message ->
                val displayParts = ChatDisplayMapper.toDisplayParts(
                    info = message.info,
                    parts = message.parts,
                    keepEmptyAssistantText = phases[message.info.id] == MessagePhase.Streaming,
                )
                if (displayParts.isEmpty()) return@mapNotNull null
                ChatDisplayMessage(
                    renderId = message.info.id,
                    role = message.info.role,
                    sessionID = message.info.sessionID,
                    agent = message.info.agent,
                    providerID = message.info.providerID,
                    modelID = message.info.modelID,
                    phase = phases[message.info.id] ?: MessagePhase.Settled,
                    visibleParts = displayParts,
                )
            }
    }

    fun messageInfo(messageId: String): MessageInfo? = messagesById[messageId]?.info

    private fun upsertMessage(info: MessageInfo, preferredOrder: Int?) {
        if (!belongsToSession(info.sessionID)) return
        val existing = messagesById[info.id]
        val order = existing?.order ?: preferredOrder ?: nextMessageOrder++
        if (order >= nextMessageOrder) nextMessageOrder = order + 1
        messagesById[info.id] = TimelineMessage(
            info = info,
            order = order,
            parts = existing?.parts.orEmpty(),
        )
    }

    private fun upsertPart(part: MessagePart, preferredOrder: Int?) {
        if (!belongsToSession(part.sessionID)) return
        val messageID = part.messageID ?: run {
            diagnostics += ChatTimelineDiagnostic("unknown_part", "part ${part.id.orEmpty()} has no messageID")
            return
        }
        val existingMessage = messagesById[messageID]
        if (existingMessage == null) {
            messagesById[messageID] = TimelineMessage(
                info = MessageInfo(id = messageID, role = UNKNOWN_ROLE, sessionID = part.sessionID),
                order = nextMessageOrder++,
            )
        }
        val message = messagesById.getValue(messageID)
        val key = part.stableKey(messageID, preferredOrder, message.parts)
        val incoming = part.withMessageDefaults(message.info.sessionID, messageID)
        val parts = message.parts.toMutableList()
        val index = parts.indexOfFirst { it.key == key }
        if (index >= 0) {
            val current = parts[index]
            parts[index] = current.copy(part = mergePart(current.part, incoming, current.hasLiveText))
        } else {
            val order = preferredOrder ?: nextPartOrder++
            if (order >= nextPartOrder) nextPartOrder = order + 1
            parts += TimelinePart(
                key = key,
                part = incoming,
                order = order,
            )
        }
        messagesById[messageID] = message.copy(parts = parts.sortedBy { it.order })
    }

    private fun appendDelta(event: ChatTimelineEvent.PartDelta) {
        if (!belongsToSession(event.sessionID)) return
        if (event.field != "text" && event.field != "reasoning") return
        val message = messagesById[event.messageID]
        if (message == null) {
            diagnostics += ChatTimelineDiagnostic("orphan_delta", "${event.messageID}/${event.partID}")
            return
        }
        val parts = message.parts.toMutableList()
        val index = parts.indexOfFirst { it.key == event.partID || it.part.id == event.partID }
        if (index < 0) {
            diagnostics += ChatTimelineDiagnostic("orphan_delta", "${event.messageID}/${event.partID}")
            return
        }
        val current = parts[index]
        parts[index] = current.copy(
            part = current.part.copy(text = current.part.text.orEmpty() + event.delta),
            hasLiveText = true,
        )
        messagesById[event.messageID] = message.copy(parts = parts)
    }

    private fun removePart(messageID: String, partID: String) {
        val message = messagesById[messageID] ?: return
        messagesById[messageID] = message.copy(
            parts = message.parts.filterNot { it.key == partID || it.part.id == partID },
        )
    }

    private fun mergePart(current: MessagePart, incoming: MessagePart, hasLiveText: Boolean): MessagePart {
        val currentText = current.text.orEmpty()
        val incomingText = incoming.text.orEmpty()
        if (hasLiveText && currentText.isNotBlank()) {
            val staleOrDivergentSnapshot =
                incomingText.isBlank() ||
                    currentText.startsWith(incomingText) ||
                    (incomingText.length <= currentText.length && !incomingText.startsWith(currentText))
            if (staleOrDivergentSnapshot) {
                return incoming.copy(text = current.text)
            }
        }
        return incoming
    }

    private fun updateSessionStatus(event: ChatTimelineEvent.SessionStatusChanged) {
        if (!belongsToSession(event.sessionID)) return
        lastSessionStatus = ChatSessionStatus(event.sessionID, event.status)
    }

    private fun belongsToSession(candidate: String?): Boolean = candidate == null || candidate == sessionId

    private fun MessagePart.withMessageDefaults(defaultSessionID: String?, defaultMessageID: String): MessagePart {
        return copy(
            sessionID = sessionID ?: defaultSessionID ?: sessionId,
            messageID = messageID ?: defaultMessageID,
        )
    }

    private fun MessagePart.stableKey(messageID: String, preferredOrder: Int?, existingParts: List<TimelinePart>): String {
        id?.let { return it }
        callID?.let { return "$type:$it" }
        if (preferredOrder != null) return "$messageID:$type:$preferredOrder"
        val sameType = existingParts.filter { it.part.type == type && it.part.id == null && it.part.callID == null }
        if (sameType.size == 1) return sameType.single().key
        if (sameType.isEmpty()) return "$messageID:$type:${existingParts.size}"
        return "$messageID:$type:${existingParts.size}"
    }

    private companion object {
        const val UNKNOWN_ROLE = "unknown"
    }
}

private object ChatDisplayMapper {
    fun toDisplayParts(
        info: MessageInfo,
        parts: List<TimelinePart>,
        keepEmptyAssistantText: Boolean = false,
    ): List<ChatDisplayPart> {
        val orderedRecords = parts.sortedBy { it.order }
        val lastTextRecordKey = if (info.role == "assistant") {
            orderedRecords.lastOrNull { it.part.type == "text" }?.key
        } else {
            null
        }
        var textOrdinal = 0
        val displayParts = orderedRecords.mapIndexedNotNull { index, record ->
            val currentTextOrdinal = if (record.part.type == "text") textOrdinal++ else -1
            record.part.toDisplayPart(
                key = record.key,
                ordinal = index,
                includeEmptyText = info.role == "assistant",
                textOrdinal = currentTextOrdinal,
                isFinalText = record.key == lastTextRecordKey,
            )
                ?.copy(sourceOrder = record.order)
        }
        if (info.role != "assistant") return displayParts.filter { it.hasVisibleContent() }
        val visibleParts = displayParts.filter { it.hasVisibleContent() }
        if (visibleParts.isNotEmpty()) return orderAssistantPartsForDisplay(visibleParts)
        if (keepEmptyAssistantText) return displayParts.firstOrNull { it.type == "text" }?.let(::listOf).orEmpty()
        return emptyList()
    }

    fun toDisplayParts(message: Message): List<ChatDisplayPart> {
        val records = message.parts.mapIndexed { index, part ->
            TimelinePart(
                key = part.id ?: part.callID?.let { "${part.type}:$it" } ?: "${message.info.id}:${part.type}:$index",
                part = part.copy(sessionID = part.sessionID ?: message.info.sessionID, messageID = part.messageID ?: message.info.id),
                order = index,
            )
        }
        return toDisplayParts(message.info, records)
    }

    fun orderAssistantPartsForDisplay(parts: List<ChatDisplayPart>): List<ChatDisplayPart> {
        val ordered = parts.sortedBy { it.sourceOrder }
        val finalText = ordered.lastOrNull { it.isFinalText } ?: return ordered
        return ordered.filterNot { it.renderId == finalText.renderId } + finalText
    }

    private fun MessagePart.toDisplayPart(
        key: String,
        ordinal: Int,
        includeEmptyText: Boolean,
        textOrdinal: Int,
        isFinalText: Boolean,
    ): ChatDisplayPart? {
        if (type == "text" && text.isNullOrBlank() && !includeEmptyText) return null
        if (type == "reasoning" && text.isNullOrBlank()) return null
        if (type !in VISIBLE_PART_TYPES) return null
        val renderId = renderIdFor(type, ordinal, key, callID, textOrdinal, isFinalText)
        return ChatDisplayPart(
            renderId = renderId,
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
            isFinalText = type == "text" && isFinalText,
        )
    }

    private fun renderIdFor(
        type: String,
        ordinal: Int,
        key: String,
        callID: String?,
        textOrdinal: Int,
        isFinalText: Boolean,
    ): String {
        return when (type) {
            "text" -> if (isFinalText && textOrdinal > 0) FINAL_TEXT_RENDER_ID else "text:$textOrdinal"
            "reasoning" -> "reasoning:$key"
            "tool", "tool-invocation", "tool-result" -> "$type:${callID ?: key}"
            "file", "image" -> "$type:$key"
            else -> "$type:$key"
        }
    }

    private fun ChatDisplayPart.hasVisibleContent(): Boolean {
        return when (type) {
            "text", "reasoning" -> !text.isNullOrBlank()
            "file", "image", "tool", "tool-invocation", "tool-result" -> true
            else -> false
        }
    }

    private const val TEXT_PART_RENDER_ID = "text:0"
    private const val FINAL_TEXT_RENDER_ID = "final-text"
    private val VISIBLE_PART_TYPES = setOf("text", "reasoning", "file", "image", "tool", "tool-invocation", "tool-result")
}

class ChatStateHolder(
    private var sessionId: String,
) {
    val messages = mutableStateListOf<ChatDisplayMessage>()

    private val reducer = ChatTimelineReducer(sessionId)
    private var activeLocalUserId: String? = null
    private var activeLocalAssistantId: String? = null
    private var activeUserBubbleText: String? = null
    private var activeSendText: String? = null
    private val consumedServerMessageIds = mutableSetOf<String>()
    private val serverMessagePhases = mutableMapOf<String, MessagePhase>()
    val diagnostics: List<ChatTimelineDiagnostic>
        get() = reducer.diagnostics

    fun resetForSession(newSessionId: String = sessionId) {
        sessionId = newSessionId
        messages.clear()
        activeLocalUserId = null
        activeLocalAssistantId = null
        activeUserBubbleText = null
        activeSendText = null
        consumedServerMessageIds.clear()
        serverMessagePhases.clear()
        reducer.reset(newSessionId)
    }

    fun loadServerMessages(serverMessages: List<Message>) {
        reducer.loadSnapshot(serverMessages)
        messages.clear()
        messages.addAll(reducer.displayMessages())
        consumedServerMessageIds.clear()
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

        val displayUserParts = userParts.mapIndexedNotNull { index, part ->
            ChatDisplayPart(
                renderId = part.id ?: "user:${part.type}:$index",
                serverPartId = part.id,
                type = part.type,
                text = part.text,
                mime = part.mime,
                url = part.url,
                filename = part.filename,
                sourceOrder = index,
            )
        }.filter { it.hasVisibleContent() }
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

    fun onTimelineEvent(event: ChatTimelineEvent): ServerMergeResult {
        reducer.apply(event)
        if (event is ChatTimelineEvent.SessionStatusChanged && event.status in COMPLETED_SESSION_STATUSES) {
            onCompleted()
        }
        if (event is ChatTimelineEvent.MessageUpdated && event.info.providerID != null && event.info.modelID != null) {
            serverMessagePhases[event.info.id] = phaseForServerMessage(event.info.id)
        }
        syncReducerMessages()
        val info = when (event) {
            is ChatTimelineEvent.MessageUpdated -> event.info
            is ChatTimelineEvent.PartUpdated -> event.part.messageID?.let { reducer.messageInfo(it) }
            is ChatTimelineEvent.PartDelta -> reducer.messageInfo(event.messageID)
            is ChatTimelineEvent.PartRemoved -> reducer.messageInfo(event.messageID)
            is ChatTimelineEvent.SessionStatusChanged -> null
        }
        return ServerMergeResult(selectedModel = modelRefForActiveAssistant(info))
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

    fun onReasoningDeltaFlush(text: String) {
        if (text.isBlank()) return
        val assistantId = activeLocalAssistantId ?: return
        updateMessage(assistantId) { msg ->
            msg.copy(
                phase = MessagePhase.Streaming,
                visibleParts = msg.visibleParts.withStableReasoning(text),
            )
        }
    }

    fun shouldApplyAssistantTextSnapshot(
        messageId: String?,
        role: String?,
        text: String,
    ): Boolean {
        return when (role?.lowercase()) {
            "assistant" -> true
            "user" -> false
            else -> {
                val activeAssistant = activeLocalAssistantId?.let(::findMessage)
                val activeUser = activeLocalUserId?.let(::findMessage)
                when {
                    messageId != null && activeAssistant?.matchesServerMessage(messageId) == true -> true
                    messageId != null && activeUser?.matchesServerMessage(messageId) == true -> false
                    text == activeUserBubbleText || text == activeSendText -> false
                    else -> true
                }
            }
        }
    }

    fun onCompleted() {
        val assistantId = activeLocalAssistantId ?: return
        updateMessage(assistantId) { msg -> msg.copy(phase = MessagePhase.Settling) }
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
        reducer.mergeSnapshot(serverMessages)
        bindActiveUser(serverMessages)
        syncReducerMessages()
        val info = serverMessages.lastOrNull { it.info.role == "assistant" }?.info
        return ServerMergeResult(selectedModel = modelRefForActiveAssistant(info))
    }

    private fun modelRefForActiveAssistant(info: MessageInfo?): ModelRef? {
        if (info?.providerID == null || info.modelID == null) return null
        val active = activeLocalAssistantId?.let(::findMessage)
        if (active != null && !active.matchesServerMessage(info.id)) return null
        return ModelRef(info.providerID, info.modelID)
    }

    fun finishSettling() {
        messages.indices.forEach { idx ->
            val msg = messages[idx]
            if (msg.phase == MessagePhase.Settling || msg.phase == MessagePhase.Streaming) {
                messages[idx] = msg.copy(phase = MessagePhase.Settled)
                msg.serverId?.let { serverMessagePhases[it] = MessagePhase.Settled }
            }
        }
        clearActive()
        removeInvisibleNonActiveMessages()
    }

    private fun syncReducerMessages() {
        val displayMessages = reducer.displayMessages(serverMessagePhases)
        displayMessages.filter { it.role == "user" }.forEach { serverDisplay ->
            if (isDuplicateOfUnboundActiveUser(serverDisplay)) {
                bindActiveUser(serverDisplay)
            } else {
                upsertServerDisplay(serverDisplay)
            }
        }

        val activeAssistantTarget = activeLocalAssistantId?.let { chooseActiveAssistantTarget(displayMessages) }
        displayMessages.filter { it.role != "user" }.forEach { serverDisplay ->
            if (serverDisplay.role == "assistant" && serverDisplay.renderId == activeAssistantTarget && bindActiveAssistant(serverDisplay)) return@forEach
            if (shouldDeferUnboundAssistant(serverDisplay, activeAssistantTarget)) return@forEach
            upsertServerDisplay(serverDisplay)
        }
    }

    private fun chooseActiveAssistantTarget(displayMessages: List<ChatDisplayMessage>): String? {
        val active = activeLocalAssistantId?.let(::findMessage) ?: return null
        active.serverId?.let { return it }
        val activeUserServerId = activeLocalUserId?.let(::findMessage)?.serverId
        if (activeUserServerId != null) {
            val userIndex = displayMessages.indexOfFirst { it.renderId == activeUserServerId }
            if (userIndex >= 0) {
                displayMessages.drop(userIndex + 1).firstOrNull { it.role == "assistant" }?.let { return it.renderId }
                return displayMessages.firstOrNull {
                    it.role == "assistant" &&
                        it.renderId !in consumedServerMessageIds &&
                        !isRenderedServerMessage(it.renderId)
                }?.renderId
            }
        }
        val hasExistingServerAssistant = messages.any { it.role == "assistant" && !it.renderId.startsWith("local_") }
        if (!hasExistingServerAssistant) {
            return displayMessages.lastOrNull { it.role == "assistant" }?.renderId
        }
        return null
    }

    private fun bindActiveUser(serverMessages: List<Message>) {
        if (activeLocalUserId?.let(::findMessage)?.serverId != null) return
        serverMessages.firstOrNull {
            isDuplicateOfActiveUser(it) &&
                it.info.id !in consumedServerMessageIds &&
                !isRenderedServerMessage(it.info.id)
        }?.let { serverUser ->
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
    }

    private fun bindActiveUser(serverDisplay: ChatDisplayMessage) {
        if (activeLocalUserId?.let(::findMessage)?.serverId != null) return
        consumedServerMessageIds += serverDisplay.renderId
        val localUserId = activeLocalUserId ?: return
        updateMessage(localUserId) { msg ->
            msg.copy(
                serverId = serverDisplay.renderId,
                sessionID = serverDisplay.sessionID ?: msg.sessionID,
                agent = serverDisplay.agent ?: msg.agent,
                providerID = serverDisplay.providerID ?: msg.providerID,
                modelID = serverDisplay.modelID ?: msg.modelID,
                phase = MessagePhase.Settled,
            )
        }
    }

    private fun bindActiveAssistant(serverDisplay: ChatDisplayMessage): Boolean {
        val localAssistantId = activeLocalAssistantId ?: return false
        val local = findMessage(localAssistantId) ?: return false
        consumedServerMessageIds += serverDisplay.renderId
        serverMessagePhases[serverDisplay.renderId] = local.phase.takeIf { it != MessagePhase.LocalPending } ?: MessagePhase.Streaming
        updateMessage(localAssistantId) { msg ->
            val merged = mergeActiveDisplayParts(msg.visibleParts, serverDisplay.visibleParts)
            serverDisplay.copy(
                renderId = localAssistantId,
                serverId = serverDisplay.renderId,
                phase = phaseForServerMessage(serverDisplay.renderId),
                agent = serverDisplay.agent ?: msg.agent,
                visibleParts = merged,
            )
        }
        return true
    }

    private fun mergeActiveDisplayParts(
        current: List<ChatDisplayPart>,
        incoming: List<ChatDisplayPart>,
    ): List<ChatDisplayPart> {
        val byRenderId = current.associateBy { it.renderId }.toMutableMap()
        incoming.forEach { part ->
            val existing = byRenderId[part.renderId]
            byRenderId[part.renderId] = if (
                existing != null &&
                part.type in setOf("text", "reasoning") &&
                part.text.isNullOrBlank() &&
                !existing.text.isNullOrBlank()
            ) {
                part.copy(text = existing.text)
            } else {
                part
            }
        }
        return ChatDisplayMapper.orderAssistantPartsForDisplay(byRenderId.values.toList())
    }

    private fun upsertServerDisplay(serverDisplay: ChatDisplayMessage) {
        if (serverDisplay.renderId in consumedServerMessageIds) return
        val existingByServer = messages.indexOfFirst { it.serverId == serverDisplay.renderId || it.renderId == serverDisplay.renderId }
        val next = serverDisplay.copy(phase = phaseForServerMessage(serverDisplay.renderId))
        if (existingByServer >= 0) {
            messages[existingByServer] = next.copy(
                renderId = messages[existingByServer].renderId,
                serverId = messages[existingByServer].serverId,
            )
        } else if (next.hasVisibleContent()) {
            messages.add(next)
        }
    }

    private fun phaseForServerMessage(messageId: String): MessagePhase {
        val active = activeLocalAssistantId?.let(::findMessage)
        if (active?.serverId == messageId || active?.renderId == messageId) return active.phase
        return serverMessagePhases[messageId] ?: MessagePhase.Settled
    }

    private fun updateMessage(renderId: String, transform: (ChatDisplayMessage) -> ChatDisplayMessage) {
        val idx = messages.indexOfFirst { it.renderId == renderId }
        if (idx >= 0) messages[idx] = transform(messages[idx])
    }

    private fun findMessage(renderId: String): ChatDisplayMessage? = messages.firstOrNull { it.renderId == renderId }

    private fun ChatDisplayMessage.matchesServerMessage(messageId: String): Boolean = serverId == messageId || renderId == messageId

    private fun isRenderedServerMessage(messageId: String): Boolean =
        messages.any { it.serverId == messageId || it.renderId == messageId }

    private fun shouldDeferUnboundAssistant(
        serverDisplay: ChatDisplayMessage,
        activeAssistantTarget: String?,
    ): Boolean {
        if (serverDisplay.role != "assistant") return false
        if (activeLocalAssistantId == null) return false
        if (activeAssistantTarget != null) return false
        return !isRenderedServerMessage(serverDisplay.renderId)
    }

    private fun isDuplicateOfActiveUser(message: Message): Boolean {
        if (message.info.role != "user") return false
        val text = message.parts.firstOrNull { it.type == "text" }?.text
        return !text.isNullOrBlank() && (text == activeUserBubbleText || text == activeSendText)
    }

    private fun isDuplicateOfUnboundActiveUser(message: ChatDisplayMessage): Boolean {
        if (activeLocalUserId?.let(::findMessage)?.serverId != null) return false
        return isDuplicateOfActiveUser(message)
    }

    private fun isDuplicateOfActiveUser(message: ChatDisplayMessage): Boolean {
        if (message.role != "user") return false
        val text = message.visibleParts.firstOrNull { it.type == "text" }?.text
        return !text.isNullOrBlank() && (text == activeUserBubbleText || text == activeSendText)
    }

    private fun clearActive() {
        activeLocalUserId = null
        activeLocalAssistantId = null
        activeUserBubbleText = null
        activeSendText = null
    }

    private fun removeInvisibleNonActiveMessages() {
        val activeIds = setOfNotNull(activeLocalUserId, activeLocalAssistantId)
        val iterator = messages.listIterator()
        while (iterator.hasNext()) {
            val msg = iterator.next()
            if (msg.renderId !in activeIds && !msg.hasVisibleContent()) iterator.remove()
        }
    }

    private fun ChatDisplayMessage.hasVisibleContent(): Boolean = visibleParts.any { it.hasVisibleContent() }

    private fun ChatDisplayPart.hasVisibleContent(): Boolean {
        return when (type) {
            "text", "reasoning" -> !text.isNullOrBlank()
            "file", "image", "tool", "tool-invocation", "tool-result" -> true
            else -> false
        }
    }

    private fun List<ChatDisplayPart>.withStableText(text: String): List<ChatDisplayPart> {
        val textIndex = indexOfFirst { it.renderId == TEXT_PART_RENDER_ID }
        if (textIndex >= 0) {
            val part = this[textIndex]
            if (part.text == text) return this
            return mapIndexed { index, item -> if (index == textIndex) item.copy(text = text) else item }
        }
        return listOf(ChatDisplayPart(renderId = TEXT_PART_RENDER_ID, type = "text", text = text, sourceOrder = Int.MAX_VALUE)) + this
    }

    private fun List<ChatDisplayPart>.withStableReasoning(text: String): List<ChatDisplayPart> {
        val reasoningIndex = indexOfFirst { it.renderId == REASONING_PART_RENDER_ID }
        if (reasoningIndex >= 0) {
            val part = this[reasoningIndex]
            if (part.text == text) return this
            return mapIndexed { index, item -> if (index == reasoningIndex) item.copy(text = text) else item }
        }
        val reasoningPart = ChatDisplayPart(
            renderId = REASONING_PART_RENDER_ID,
            type = "reasoning",
            text = text,
            sourceOrder = Int.MAX_VALUE - 1,
        )
        val textIndex = indexOfFirst { it.renderId == TEXT_PART_RENDER_ID }
        if (textIndex < 0) return listOf(reasoningPart) + this
        return toMutableList().also { it.add(textIndex, reasoningPart) }
    }

    private companion object {
        const val TEXT_PART_RENDER_ID = "text:0"
        const val REASONING_PART_RENDER_ID = "reasoning:stream"
        val COMPLETED_SESSION_STATUSES = setOf("idle", "completed")
    }
}
