package fi.ircord.android.ui.screen.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fi.ircord.android.ui.theme.IrcordSpacing
import fi.ircord.android.ui.theme.IrcordTheme

@Composable
fun LinkPreviewCard(
    title: String,
    description: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val semantic = IrcordTheme.semanticColors

    Surface(
        modifier = modifier.widthIn(max = IrcordSpacing.previewCardMaxWidth),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, semantic.previewBorder),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(IrcordSpacing.previewCardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = semantic.previewTitle,
                )
                Spacer(Modifier.width(IrcordSpacing.xs))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = semantic.previewTitle,
                    maxLines = 1,
                )
            }
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(top = IrcordSpacing.xs),
                )
            }
        }
    }
}
