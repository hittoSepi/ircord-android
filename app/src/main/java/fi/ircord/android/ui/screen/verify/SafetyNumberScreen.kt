package fi.ircord.android.ui.screen.verify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import fi.ircord.android.ui.theme.IrcordSpacing
import fi.ircord.android.ui.theme.MonoStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafetyNumberScreen(
    peerId: String,
    onBack: () -> Unit,
) {
    // Mock safety number
    val safetyNumber = "05820  27193  83627\n49201  71920  38471\n\n62910  48271  93827\n10492  82736  19204"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verify: You \u2194 $peerId") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(IrcordSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "If the numbers below match the ones on ${peerId}'s device, your connection is secure.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(IrcordSpacing.xl))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = safetyNumber,
                    style = MonoStyle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(IrcordSpacing.xl),
                )
            }

            Spacer(Modifier.height(IrcordSpacing.lg))

            Text(
                text = "Compare this number with $peerId in person or via a trusted call.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(IrcordSpacing.xxl))

            Button(
                onClick = { /* TODO: mark as verified */ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Mark as Verified")
            }

            Spacer(Modifier.height(IrcordSpacing.md))

            OutlinedButton(
                onClick = { /* TODO: copy to clipboard */ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy to Clipboard")
            }

            Spacer(Modifier.height(IrcordSpacing.xl))

            Text(
                text = "Last verified: never",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
