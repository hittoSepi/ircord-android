package fi.ircord.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fi.ircord.android.ui.navigation.IrcordNavGraph
import fi.ircord.android.ui.theme.IrcordTheme
import fi.ircord.android.ui.theme.ThemeViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val themeViewModel: ThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isDarkTheme by themeViewModel.isDarkTheme.collectAsStateWithLifecycle()
            
            IrcordAppTheme(isDarkTheme = isDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IrcordNavGraph()
                }
            }
        }
    }
}

/**
 * Wrapper that handles theme mode (system/light/dark).
 * If isDarkTheme is null, follows system setting.
 */
@Composable
private fun IrcordAppTheme(
    isDarkTheme: Boolean?,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (isDarkTheme) {
        true -> true
        false -> false
        null -> isSystemInDarkTheme() // Follow system
    }
    
    IrcordTheme(darkTheme = darkTheme, content = content)
}
