package com.myra.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.myra.assistant.data.model.GeminiModel
import com.myra.assistant.data.model.Personality
import com.myra.assistant.data.model.VoiceOption
import com.myra.assistant.ui.components.GlassCard

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    var apiKey by remember { mutableStateOf(viewModel.apiKey()) }
    var userName by remember { mutableStateOf(viewModel.userName()) }
    var userProfile by remember { mutableStateOf(viewModel.userProfile()) }
    var custom by remember { mutableStateOf(viewModel.customPersonality()) }
    var prime by remember { mutableStateOf(viewModel.primeContacts()) }
    var model by remember { mutableStateOf(viewModel.model()) }
    var voice by remember { mutableStateOf(viewModel.voice()) }
    var personality by remember { mutableStateOf(viewModel.personality()) }
    var handsFree by remember { mutableStateOf(viewModel.handsFree()) }
    var wakeWord by remember { mutableStateOf(viewModel.wakeWord()) }
    var continuous by remember { mutableStateOf(viewModel.continuous()) }
    var overlay by remember { mutableStateOf(viewModel.overlay()) }
    var learning by remember { mutableStateOf(viewModel.learningMode()) }
    var debug by remember { mutableStateOf(viewModel.debugLogs()) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gemini API key")
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; viewModel.setApiKey(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("AIza...") }
                )
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model")
                GeminiModel.entries.forEach { option ->
                    ChoiceRow(option.displayName, model == option) { model = option; viewModel.setModel(option) }
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Personality")
                Personality.entries.forEach { option ->
                    ChoiceRow(option.displayName, personality == option) { personality = option; viewModel.setPersonality(option) }
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice")
                VoiceOption.entries.forEach { option ->
                    ChoiceRow(option.voiceName, voice == option) { voice = option; viewModel.setVoice(option) }
                }
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(userName, { userName = it; viewModel.setUserName(it) }, Modifier.fillMaxWidth(), label = { Text("Your name") })
                OutlinedTextField(userProfile, { userProfile = it; viewModel.setUserProfile(it) }, Modifier.fillMaxWidth(), label = { Text("About you (profile)") })
                OutlinedTextField(custom, { custom = it; viewModel.setCustomPersonality(it) }, Modifier.fillMaxWidth(), label = { Text("Custom personality style") })
                OutlinedTextField(prime, { prime = it; viewModel.setPrimeContacts(it) }, Modifier.fillMaxWidth(), label = { Text("Prime contacts (comma separated)") })
            }
        }

        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow("Hands-free mode", handsFree) { handsFree = it; viewModel.setHandsFree(it) }
                ToggleRow("Wake word (Hey MYRA)", wakeWord) { wakeWord = it; viewModel.setWakeWord(it) }
                ToggleRow("Continuous listening", continuous) { continuous = it; viewModel.setContinuous(it) }
                ToggleRow("Floating overlay bubble", overlay) { overlay = it; viewModel.setOverlay(it) }
                ToggleRow("Learning mode", learning) { learning = it; viewModel.setLearningMode(it) }
                ToggleRow("Debug logs", debug) { debug = it; viewModel.setDebugLogs(it) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
