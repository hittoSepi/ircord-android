package fi.ircord.android.ui.screen.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import fi.ircord.android.domain.model.Message
import fi.ircord.android.ui.theme.IrcordSpacing
import fi.ircord.android.ui.theme.IrcordTheme
import fi.ircord.android.ui.theme.nickColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
) {
    val semantic = IrcordTheme.semanticColors
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeStr = timeFormat.format(Date(message.timestamp))
    val nickCol = nickColor(message.senderId, semantic.nickPalette)

    Column(modifier = modifier) {
        // IRC-style: [time] nick  message
        Row {
            Text(
                text = timeStr,
                style = MaterialTheme.typography.labelSmall,
                color = semantic.timestamp,
            )
            Spacer(Modifier.width(IrcordSpacing.sm))
            Text(
                text = message.senderId,
                style = MaterialTheme.typography.titleSmall,
                color = nickCol,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(IrcordSpacing.messageNickWidth),
            )
            Spacer(Modifier.width(IrcordSpacing.sm))
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Link preview
        message.linkPreview?.let { preview ->
            LinkPreviewCard(
                title = preview.title ?: "",
                description = preview.description ?: "",
                url = preview.url,
                modifier = Modifier.padding(
                    start = IrcordSpacing.previewCardMarginStart,
                    top = IrcordSpacing.xs,
                ),
            )
        }
    }
}
