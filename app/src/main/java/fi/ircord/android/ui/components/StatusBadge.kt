package fi.ircord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import fi.ircord.android.domain.model.PresenceStatus
import fi.ircord.android.ui.theme.IrcordSpacing
import fi.ircord.android.ui.theme.IrcordTheme

@Composable
fun StatusBadge(
    status: PresenceStatus,
    modifier: Modifier = Modifier,
) {
    val semantic = IrcordTheme.semanticColors
    val color = when (status) {
        PresenceStatus.ONLINE -> semantic.statusOnline
        PresenceStatus.AWAY -> semantic.statusAway
        PresenceStatus.OFFLINE -> semantic.statusOffline
    }

    Box(
        modifier = modifier
            .size(IrcordSpacing.statusDotSize)
            .clip(CircleShape)
            .background(color),
    )
}
