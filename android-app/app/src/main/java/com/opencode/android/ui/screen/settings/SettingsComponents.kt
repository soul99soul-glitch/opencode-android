package com.opencode.android.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.R
import com.opencode.android.data.model.ConnectionMode
import com.opencode.android.data.model.LocalProviderPreset
import com.opencode.android.data.model.McpConfigSource
import com.opencode.android.ui.component.MonoLabel
import com.opencode.android.ui.component.pressable
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType

@Composable
internal fun SectionHeader(text: String) {
    val c = LocalOcColors.current
    MonoLabel(text, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
}

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    val c = LocalOcColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .background(c.surface, RoundedCornerShape(14.dp))
            .padding(vertical = 2.dp),
    ) {
        content()
    }
}

@Composable
internal fun SettingsRow(label: String, value: String) {
    val c = LocalOcColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = OcType.body,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 104.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            value,
            style = OcType.mono,
            color = c.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun EditableSettingsRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalOcColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = OcType.body,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 104.dp),
        )
        Spacer(Modifier.width(14.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = OcType.mono.copy(color = c.ink2, textAlign = TextAlign.End),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (value.isBlank()) {
                        Text(stringResource(R.string.settings_placeholder_empty), style = OcType.mono, color = c.ink4, textAlign = TextAlign.End)
                    }
                    inner()
                }
            },
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
internal fun InlineAction(label: String, onClick: () -> Unit) {
    val c = LocalOcColors.current
    Text(
        label,
        style = OcType.monoStrong.copy(fontSize = 11.sp),
        color = c.accent,
        modifier = Modifier.pressable { onClick() },
    )
}

@Composable
internal fun ThemeOption(label: String, selected: Boolean, c: com.opencode.android.ui.theme.OcColors, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .then(if (selected) Modifier.background(c.raised) else Modifier)
            .pressable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OcType.monoStrong.copy(fontSize = 12.sp), color = if (selected) c.ink else c.ink3)
    }
}

internal fun ConnectionMode.shortLabel(): String =
    when (this) {
        ConnectionMode.LAN -> "LAN"
        ConnectionMode.LOCAL_BUNDLED -> "Local"
        ConnectionMode.LOCAL_EXTERNAL -> "External"
    }

@Composable
internal fun ModeCycleButton(
    label: String,
    c: com.opencode.android.ui.theme.OcColors,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .widthIn(min = 76.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(c.surface2)
            .pressable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = OcType.monoStrong.copy(fontSize = 12.sp),
            color = c.accent,
        )
    }
}

@Composable
internal fun ProviderPresetRow(
    preset: LocalProviderPreset,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalOcColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.settings_section_provider), style = OcType.body, color = c.ink, modifier = Modifier.widthIn(min = 104.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                preset.displayName,
                style = OcType.mono,
                color = c.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            Text(
                preset.apiBaseUrl.shortEndpoint(),
                style = OcType.mono.copy(fontSize = 10.sp),
                color = c.ink4,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(if (expanded) "−" else "+", style = OcType.mono, color = c.ink4)
    }
}

@Composable
internal fun ProviderPresetOption(
    preset: LocalProviderPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalOcColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.bg else Color.Transparent)
            .pressable { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preset.displayName,
                    style = OcType.monoStrong.copy(fontSize = 12.sp),
                    color = if (selected) c.accent else c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preset.defaultEnabled) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.local_provider_default), style = OcType.mono.copy(fontSize = 10.sp), color = c.ink4)
                }
            }
            Text(
                preset.apiBaseUrl.shortEndpoint(),
                style = OcType.mono.copy(fontSize = 10.sp),
                color = c.ink4,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (selected) stringResource(R.string.local_provider_selected) else stringResource(R.string.local_provider_use),
            style = OcType.mono.copy(fontSize = 11.sp),
            color = if (selected) c.accent else c.ink4,
        )
    }
}

@Composable
internal fun ProviderModelRow(
    model: String,
    expanded: Boolean,
    loading: Boolean,
    hasCandidates: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalOcColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .pressable { onClick() }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.local_provider_model), style = OcType.body, color = c.ink, modifier = Modifier.widthIn(min = 104.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            when {
                model.isNotBlank() -> model
                loading -> stringResource(R.string.local_provider_fetching)
                hasCandidates -> stringResource(R.string.local_provider_choose)
                else -> stringResource(R.string.local_provider_choose)
            },
            style = OcType.mono,
            color = if (model.isNotBlank()) c.ink2 else c.ink4,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (expanded) "−" else "+", style = OcType.mono, color = c.ink4)
    }
}

@Composable
internal fun ProviderModelCandidatePicker(
    models: List<String>,
    selectedModel: String,
    loading: Boolean,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val c = LocalOcColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.local_provider_models), style = OcType.mono.copy(fontSize = 11.sp), color = c.ink4, modifier = Modifier.weight(1f))
            Text(
                if (loading) stringResource(R.string.local_provider_fetching_models) else stringResource(R.string.local_provider_retry),
                style = OcType.mono.copy(fontSize = 11.sp),
                color = if (loading) c.ink4 else c.accent,
                modifier = Modifier.pressable(enabled = !loading) { onRetry() },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (models.isEmpty()) {
                Text(
                    if (loading) stringResource(R.string.local_provider_loading_models) else stringResource(R.string.local_provider_no_models),
                    style = OcType.mono.copy(fontSize = 11.sp),
                    color = c.ink4,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            models.forEach { model ->
                val selected = model == selectedModel
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable { onSelect(model) }
                        .background(if (selected) c.accent.copy(alpha = 0.12f) else c.surface2, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model,
                        style = OcType.mono.copy(fontSize = 11.sp),
                        color = if (selected) c.accent else c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (selected) stringResource(R.string.local_provider_selected) else stringResource(R.string.local_provider_select),
                        style = OcType.mono.copy(fontSize = 10.sp),
                        color = if (selected) c.accent else c.ink4,
                    )
                }
            }
        }
    }
}

internal fun String.shortEndpoint(): String =
    removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

@Composable
internal fun ProviderApiKeyRow(
    hasSavedKey: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val c = LocalOcColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.local_provider_api_key),
            style = OcType.body,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 104.dp),
        )
        Spacer(Modifier.width(14.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            visualTransformation = PasswordVisualTransformation(),
            textStyle = OcType.mono.copy(color = c.ink2, textAlign = TextAlign.End),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (value.isBlank()) {
                        Text(
                            if (hasSavedKey) stringResource(R.string.settings_value_saved) else stringResource(R.string.settings_placeholder_empty),
                            style = OcType.mono,
                            color = if (hasSavedKey) c.ink2 else c.ink4,
                            textAlign = TextAlign.End,
                        )
                    }
                    inner()
                }
            },
        )
        if (hasSavedKey) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.pressable { onClear() }.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(stringResource(R.string.settings_action_clear), style = OcType.mono.copy(fontSize = 11.sp), color = c.accent)
            }
        }
    }
}

@Composable
internal fun LocalAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = LocalOcColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) c.surface2 else c.bg)
            .pressable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = OcType.monoStrong.copy(fontSize = 11.sp), color = if (enabled) c.accent else c.ink4)
    }
}

internal data class McpRowDraft(
    val name: String = "",
    val url: String = "",
    val token: String = "",
    val hasSavedToken: Boolean = false,
    val source: String = McpConfigSource.APP,
)

@Composable
internal fun McpServerEditor(
    row: McpRowDraft,
    fromAgent: Boolean,
    onChange: (McpRowDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val c = LocalOcColors.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            McpField(row.name, stringResource(R.string.mcp_field_name), Modifier.weight(1f)) { onChange(row.copy(name = it)) }
            if (fromAgent) {
                Spacer(Modifier.width(6.dp))
                Text("agent", style = OcType.mono.copy(fontSize = 10.sp), color = c.accent)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "✕",
                style = OcType.monoStrong.copy(fontSize = 13.sp),
                color = c.ink4,
                modifier = Modifier.pressable { onRemove() }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        McpField(row.url, stringResource(R.string.mcp_field_url_placeholder), Modifier.fillMaxWidth()) { onChange(row.copy(url = it)) }
        BasicTextField(
            value = row.token,
            onValueChange = { onChange(row.copy(token = it)) },
            singleLine = true,
            cursorBrush = SolidColor(c.accent),
            visualTransformation = PasswordVisualTransformation(),
            textStyle = OcType.mono.copy(color = c.ink2, fontSize = 12.sp),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (row.token.isBlank()) {
                    Text(
                        if (row.hasSavedToken) stringResource(R.string.mcp_token_saved) else stringResource(R.string.mcp_token_placeholder),
                        style = OcType.mono.copy(fontSize = 12.sp),
                        color = c.ink4,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
internal fun McpField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    val c = LocalOcColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        cursorBrush = SolidColor(c.accent),
        textStyle = OcType.mono.copy(color = c.ink2, fontSize = 12.sp),
        modifier = modifier,
        decorationBox = { inner ->
            if (value.isBlank()) {
                Text(placeholder, style = OcType.mono.copy(fontSize = 12.sp), color = c.ink4)
            }
            inner()
        },
    )
}
