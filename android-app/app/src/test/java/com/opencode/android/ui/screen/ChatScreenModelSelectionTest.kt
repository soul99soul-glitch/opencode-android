package com.opencode.android.ui.screen

import com.opencode.android.data.model.ConnectionMode
import com.opencode.android.data.model.LocalProviderDefaults
import com.opencode.android.data.model.LocalProviderProfile
import com.opencode.android.data.model.Message
import com.opencode.android.data.model.MessageInfo
import com.opencode.android.data.model.MessagePart
import com.opencode.android.data.model.ModelInfo
import com.opencode.android.data.model.ModelRef
import com.opencode.android.data.model.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScreenModelSelectionTest {
    @Test
    fun localBundledDoesNotInventAndroidLocalModelWhenRuntimeDidNotExposeProvider() {
        val result = resolveModelForSend(
            selectedModel = ModelRef(LocalProviderDefaults.PROVIDER_ID, "glm-5.1"),
            providers = listOf(
                Provider(
                    id = "opencode",
                    models = mapOf("big-pickle" to ModelInfo(id = "big-pickle")),
                ),
            ),
            endpointMode = ConnectionMode.LOCAL_BUNDLED,
            localProviderProfile = LocalProviderProfile(
                enabled = true,
                modelIds = listOf("glm-5.1"),
            ),
        )

        assertNull(result)
    }

    @Test
    fun localBundledUsesConfiguredLocalModelOnlyWhenRuntimeExposesIt() {
        val result = resolveModelForSend(
            selectedModel = null,
            providers = listOf(
                Provider(
                    id = LocalProviderDefaults.PROVIDER_ID,
                    models = mapOf("glm-5.1" to ModelInfo(id = "glm-5.1")),
                ),
            ),
            endpointMode = ConnectionMode.LOCAL_BUNDLED,
            localProviderProfile = LocalProviderProfile(
                enabled = true,
                modelIds = listOf("glm-5.1"),
            ),
        )

        assertEquals(ModelRef(LocalProviderDefaults.PROVIDER_ID, "glm-5.1"), result)
    }

    @Test
    fun keepsSelectedModelWhenProviderListContainsIt() {
        val selected = ModelRef("opencode", "big-pickle")

        val result = resolveModelForSend(
            selectedModel = selected,
            providers = listOf(
                Provider(
                    id = "opencode",
                    models = mapOf("big-pickle" to ModelInfo(id = "big-pickle")),
                ),
            ),
            endpointMode = ConnectionMode.LOCAL_BUNDLED,
            localProviderProfile = LocalProviderProfile(
                enabled = true,
                modelIds = listOf("glm-5.1"),
            ),
        )

        assertEquals(selected, result)
    }

    @Test
    fun detectsDanglingEmptyAssistant() {
        assertEquals(
            true,
            hasDanglingEmptyAssistant(
                listOf(
                    Message(
                        info = MessageInfo(id = "a1", role = "assistant"),
                        parts = emptyList(),
                    ),
                ),
            ),
        )
    }

    @Test
    fun doesNotTreatReasoningOnlyAssistantAsDangling() {
        assertEquals(
            false,
            hasDanglingEmptyAssistant(
                listOf(
                    Message(
                        info = MessageInfo(id = "a1", role = "assistant"),
                        parts = listOf(MessagePart(type = "reasoning", text = "thinking")),
                    ),
                ),
            ),
        )
    }

    @Test
    fun eventSessionIdReadsNestedPartSessionId() {
        val props = Json.parseToJsonElement(
            """
            {
              "part": {
                "type": "reasoning",
                "sessionID": "ses_1",
                "text": "thinking"
              }
            }
            """.trimIndent(),
        ).jsonObject

        assertEquals("ses_1", props.eventSessionId())
    }

    @Test
    fun streamPartSnapshotReadsUpdatedReasoningPart() {
        val props = Json.parseToJsonElement(
            """
            {
              "part": {
                "type": "reasoning",
                "sessionID": "ses_1",
                "text": "thinking more"
              }
            }
            """.trimIndent(),
        ).jsonObject

        assertEquals(StreamPartSnapshot("reasoning", "thinking more"), props.streamPartSnapshot())
    }

    @Test
    fun updatedUserTextSnapshotExposesRoleAndMessageIdForHolderGate() {
        val props = Json.parseToJsonElement(
            """
            {
              "message": { "id": "u1", "role": "user" },
              "part": {
                "type": "text",
                "sessionID": "ses_1",
                "messageID": "u1",
                "text": "user prompt"
              }
            }
            """.trimIndent(),
        ).jsonObject
        val snapshot = props.streamPartSnapshot()

        assertEquals(StreamPartSnapshot("text", "user prompt", "u1"), snapshot)
        assertEquals("user", props.eventMessageRole())
        assertEquals("u1", props.eventMessageId())
    }

    @Test
    fun updatedAssistantTextSnapshotExposesRoleAndMessageIdForHolderGate() {
        val props = Json.parseToJsonElement(
            """
            {
              "message": { "id": "a1", "role": "assistant" },
              "part": {
                "type": "text",
                "sessionID": "ses_1",
                "messageID": "a1",
                "text": "assistant text"
              }
            }
            """.trimIndent(),
        ).jsonObject
        val snapshot = props.streamPartSnapshot()

        assertEquals(StreamPartSnapshot("text", "assistant text", "a1"), snapshot)
        assertEquals("assistant", props.eventMessageRole())
        assertEquals("a1", props.eventMessageId())
    }

    @Test
    fun messagePartSnapshotFallsBackWhenToolStateShapeIsUnknown() {
        val props = Json.parseToJsonElement(
            """
            {
              "sessionID": "ses_1",
              "messageID": "a1",
              "part": {
                "id": "tool_1",
                "type": "tool",
                "messageID": "a1",
                "tool": "bash",
                "callID": "c1",
                "state": { "status": "completed", "output": { "nested": true } }
              }
            }
            """.trimIndent(),
        ).jsonObject

        val part = props.messagePartSnapshot(Json { ignoreUnknownKeys = true })

        assertEquals("tool", part?.type)
        assertEquals("tool_1", part?.id)
        assertEquals("a1", part?.messageID)
        assertEquals("bash", part?.tool)
        assertEquals("c1", part?.callID)
    }

    @Test
    fun removedPartEventIdsAreParsedFromProperties() {
        val props = Json.parseToJsonElement(
            """
            {
              "sessionID": "ses_1",
              "messageID": "a1",
              "partID": "p1"
            }
            """.trimIndent(),
        ).jsonObject

        assertEquals("a1", props.eventMessageId())
        assertEquals("p1", props.eventPartId())
    }
}
