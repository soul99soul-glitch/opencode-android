package com.opencode.android.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencode.android.data.model.LocalProviderPreset
import com.opencode.android.data.model.LocalProviderPresets
import com.opencode.android.ui.component.Hairline
import com.opencode.android.ui.component.pressable
import com.opencode.android.ui.theme.LocalOcColors
import com.opencode.android.ui.theme.OcType

@Composable
internal fun SettingsLocalProviderSection(
    providerEnabledDraft: Boolean,
    onToggleEnabled: () -> Unit,
    selectedProviderPreset: LocalProviderPreset,
    selectedPresetId: String,
    showProviderPresets: Boolean,
    onTogglePresets: () -> Unit,
    onSelectPreset: (LocalProviderPreset) -> Unit,
    providerBaseUrlDraft: String,
    onBaseUrlChange: (String) -> Unit,
    providerCodingBaseUrlDraft: String,
    onCodingBaseUrlChange: (String) -> Unit,
    providerApiKeyDraft: String,
    onApiKeyChange: (String) -> Unit,
    providerHasSavedKey: Boolean,
    onClearKey: () -> Unit,
    selectedProviderModel: String,
    showProviderModelPicker: Boolean,
    isFetchingProviderModels: Boolean,
    providerModelCandidates: List<String>,
    onModelRowClick: () -> Unit,
    onFetchModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    providerValidationMessage: String?,
    providerStatus: String?,
    onApply: () -> Unit,
) {
    val c = LocalOcColors.current

    SectionHeader("LOCAL PROVIDER")
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .pressable { onToggleEnabled() }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Enable", style = OcType.body, color = c.ink, modifier = Modifier.weight(1f))
            Text(
                if (providerEnabledDraft) "On" else "Off",
                style = OcType.mono,
                color = if (providerEnabledDraft) c.accent else c.ink3,
            )
        }
        Hairline()
        ProviderPresetRow(
            preset = selectedProviderPreset,
            expanded = showProviderPresets,
            onClick = { onTogglePresets() },
        )
        AnimatedVisibility(visible = showProviderPresets) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                LocalProviderPresets.ALL.forEach { preset ->
                    ProviderPresetOption(
                        preset = preset,
                        selected = preset.id == selectedPresetId,
                        onClick = { onSelectPreset(preset) },
                    )
                }
            }
        }
        Hairline()
        EditableSettingsRow(
            label = "API Base",
            value = providerBaseUrlDraft,
            onValueChange = onBaseUrlChange,
        )
        Hairline()
        EditableSettingsRow(
            label = "Coding Base",
            value = providerCodingBaseUrlDraft,
            onValueChange = onCodingBaseUrlChange,
        )
        Hairline()
        ProviderApiKeyRow(
            hasSavedKey = providerHasSavedKey,
            value = providerApiKeyDraft,
            onValueChange = onApiKeyChange,
            onClear = { onClearKey() },
        )
        Hairline()
        ProviderModelRow(
            model = selectedProviderModel,
            expanded = showProviderModelPicker,
            loading = isFetchingProviderModels,
            hasCandidates = providerModelCandidates.isNotEmpty(),
            onClick = onModelRowClick,
        )
        AnimatedVisibility(visible = showProviderModelPicker) {
            Column(Modifier.fillMaxWidth()) {
                Hairline()
                ProviderModelCandidatePicker(
                    models = providerModelCandidates,
                    selectedModel = selectedProviderModel,
                    loading = isFetchingProviderModels,
                    onRetry = { onFetchModels() },
                    onSelect = { onSelectModel(it) },
                )
            }
        }
        Hairline()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocalAction(
                "Apply",
                enabled = !providerEnabledDraft || providerValidationMessage == null,
                modifier = Modifier.fillMaxWidth(),
            ) { onApply() }
            Text(
                providerValidationMessage ?: providerStatus ?: "—",
                style = OcType.mono.copy(fontSize = 11.sp),
                color = if (providerValidationMessage == null) c.ink3 else c.accent,
            )
        }
    }
}
