package com.myra.assistant.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myra.assistant.data.model.ConnectionState
import com.myra.assistant.ui.components.AnimatedOrb
import com.myra.assistant.ui.components.AudioWaveform
import com.myra.assistant.ui.components.GlassCard
import com.myra.assistant.ui.components.ParticleBackground
import com.myra.assistant.ui.theme.MyraTextSecondary

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val amplitude by viewModel.amplitude.collectAsStateWithLifecycle()
    val input by viewModel.inputTranscript.collectAsStateWithLifecycle()
    val output by viewModel.outputTranscript.collectAsStateWithLifecycle()
    val micMuted by viewModel.micMuted.collectAsStateWithLifecycle()
    val playbackMuted by viewModel.playbackMuted.collectAsStateWithLifecycle()
    val active by viewModel.active.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        ParticleBackground()

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "MYRA", style = androidx.compose.material3.MaterialTheme.typography.displayLarge)
                Row {
                    IconButton(onClick = onOpenChat) {
                        Icon(Icons.Filled.Chat, contentDescription = "Chat")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            }

            AnimatedOrb(
                amplitude = amplitude,
                speaking = state == ConnectionState.SPEAKING,
                modifier = Modifier.fillMaxWidth(0.7f).aspectRatio(1f)
            )

            Text(
                text = statusText(state),
                color = MyraTextSecondary,
                textAlign = TextAlign.Center
            )

            AudioWaveform(
                amplitude = amplitude,
                modifier = Modifier.fillMaxWidth().height(70.dp)
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = if (input.isBlank()) "Listening..." else input,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            lastError.isNotBlank() -> "\u26A0 " + lastError
                            output.isBlank() -> "MYRA is ready."
                            else -> output
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = viewModel::toggleMic) {
                    Icon(
                        if (micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = "Toggle mic"
                    )
                }
                androidx.compose.material3.Button(onClick = viewModel::toggleSession) {
                    Text(if (active) "Stop" else "Start MYRA")
                }
                IconButton(onClick = viewModel::togglePlayback) {
                    Icon(
                        if (playbackMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = "Toggle playback"
                    )
                }
            }
        }
    }
}

private fun statusText(state: ConnectionState): String = when (state) {
    ConnectionState.IDLE -> "Tap Start to talk to MYRA"
    ConnectionState.CONNECTING -> "Connecting..."
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.LISTENING -> "Listening..."
    ConnectionState.SPEAKING -> "MYRA is speaking"
    ConnectionState.RECONNECTING -> "Reconnecting..."
    ConnectionState.ERROR -> "Something went wrong. Check your API key."
}
