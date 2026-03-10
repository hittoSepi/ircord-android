package fi.ircord.android.ui.screen.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import fi.ircord.android.ui.theme.IrcordSpacing
import fi.ircord.android.ui.theme.MonoStyle

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.isComplete) {
        if (state.isComplete) onSetupComplete()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(IrcordSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(IrcordSpacing.lg))

            Text(
                text = "Welcome to IrssiCord",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(IrcordSpacing.sm))

            Text(
                text = "End-to-end encrypted chat\nfor your friend group.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(IrcordSpacing.xxl))

            if (!state.isGenerating) {
                OutlinedTextField(
                    value = state.serverAddress,
                    onValueChange = viewModel::onServerAddressChanged,
                    label = { Text("Server address") },
                    placeholder = { Text("ircord.example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(IrcordSpacing.md))

                OutlinedTextField(
                    value = state.port,
                    onValueChange = viewModel::onPortChanged,
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(IrcordSpacing.md))

                OutlinedTextField(
                    value = state.nickname,
                    onValueChange = viewModel::onNicknameChanged,
                    label = { Text("Your nickname") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(IrcordSpacing.md))

                OutlinedTextField(
                    value = state.inviteCode,
                    onValueChange = viewModel::onInviteCodeChanged,
                    label = { Text("Invite code (if required)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Spacer(Modifier.height(IrcordSpacing.xl))

                Button(
                    onClick = viewModel::generateKeysAndJoin,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.serverAddress.isNotBlank() && state.nickname.isNotBlank(),
                ) {
                    Text("Generate Keys & Join")
                }

                Spacer(Modifier.height(IrcordSpacing.md))

                Text(
                    text = "Generating your identity key pair.\nThis may take a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = state.isGenerating) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Generating identity...",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Spacer(Modifier.height(IrcordSpacing.lg))

                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(IrcordSpacing.lg))

                    state.steps.forEach { step ->
                        val icon = when (step.status) {
                            StepStatus.DONE -> Icons.Default.Check
                            StepStatus.IN_PROGRESS -> null
                            StepStatus.PENDING -> Icons.Outlined.RadioButtonUnchecked
                        }
                        val color = when (step.status) {
                            StepStatus.DONE -> MaterialTheme.colorScheme.secondary
                            StepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                            StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = IrcordSpacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (icon != null) {
                                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                            Spacer(Modifier.padding(start = IrcordSpacing.sm))
                            Text(step.label, color = color, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    state.fingerprint?.let { fp ->
                        Spacer(Modifier.height(IrcordSpacing.xl))
                        Text("Your identity fingerprint:", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(IrcordSpacing.xs))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = fp,
                                style = MonoStyle,
                                modifier = Modifier.padding(IrcordSpacing.md),
                            )
                        }
                    }
                }
            }
        }
    }
}
