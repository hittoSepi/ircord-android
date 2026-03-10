package fi.ircord.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fi.ircord.android.ui.screen.auth.SetupScreen
import fi.ircord.android.ui.screen.channels.ChannelListScreen
import fi.ircord.android.ui.screen.chat.ChatScreen
import fi.ircord.android.ui.screen.settings.SettingsScreen
import fi.ircord.android.ui.screen.verify.SafetyNumberScreen
import fi.ircord.android.ui.screen.voice.CallScreen

object IrcordRoutes {
    const val SETUP = "setup"
    const val CHAT = "chat/{channelId}"
    const val CHANNEL_LIST = "channels"
    const val VOICE = "voice/{channelId}"
    const val CALL = "call/{peerId}"
    const val SETTINGS = "settings"
    const val SAFETY_NUMBER = "safety_number/{peerId}"

    fun chat(channelId: String) = "chat/$channelId"
    fun voice(channelId: String) = "voice/$channelId"
    fun call(peerId: String) = "call/$peerId"
    fun safetyNumber(peerId: String) = "safety_number/$peerId"
}

@Composable
fun IrcordNavGraph() {
    val navController = rememberNavController()

    // TODO: check if setup is complete, navigate to CHAT instead
    val startDestination = IrcordRoutes.SETUP

    NavHost(navController = navController, startDestination = startDestination) {

        composable(IrcordRoutes.SETUP) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(IrcordRoutes.chat("general")) {
                        popUpTo(IrcordRoutes.SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = IrcordRoutes.CHAT,
            arguments = listOf(navArgument("channelId") { type = NavType.StringType })
        ) { backStackEntry ->
            val channelId = backStackEntry.arguments?.getString("channelId") ?: "general"
            ChatScreen(
                channelId = channelId,
                onOpenDrawer = { navController.navigate(IrcordRoutes.CHANNEL_LIST) },
                onNavigateToSettings = { navController.navigate(IrcordRoutes.SETTINGS) },
                onNavigateToVoice = { navController.navigate(IrcordRoutes.voice(it)) },
            )
        }

        composable(IrcordRoutes.CHANNEL_LIST) {
            ChannelListScreen(
                onChannelSelected = { channelId ->
                    navController.navigate(IrcordRoutes.chat(channelId)) {
                        popUpTo(IrcordRoutes.CHANNEL_LIST) { inclusive = true }
                    }
                },
                onNavigateToSettings = { navController.navigate(IrcordRoutes.SETTINGS) },
            )
        }

        composable(
            route = IrcordRoutes.CALL,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
            CallScreen(
                peerId = peerId,
                onHangup = { navController.popBackStack() },
            )
        }

        composable(IrcordRoutes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = IrcordRoutes.SAFETY_NUMBER,
            arguments = listOf(navArgument("peerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val peerId = backStackEntry.arguments?.getString("peerId") ?: ""
            SafetyNumberScreen(
                peerId = peerId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
