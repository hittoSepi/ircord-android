package fi.ircord.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import fi.ircord.android.ui.navigation.IrcordNavGraph
import fi.ircord.android.ui.theme.IrcordTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IrcordTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IrcordNavGraph()
                }
            }
        }
    }
}
