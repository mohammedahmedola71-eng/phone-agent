package ai.vox.app.ui

import ai.vox.app.AppContainer
import ai.vox.app.ui.components.LocalContainer
import ai.vox.app.ui.components.SessionBar
import ai.vox.app.ui.components.rememberCapabilityActions
import ai.vox.app.ui.screens.ConversationScreen
import ai.vox.app.ui.screens.HomeScreen
import ai.vox.app.ui.screens.LiveVideoScreen
import ai.vox.app.ui.screens.NotificationsScreen
import ai.vox.app.ui.screens.ScreenAwarenessScreen
import ai.vox.app.ui.screens.SettingsScreen
import ai.vox.app.ui.screens.VideoLabScreen
import ai.vox.app.ui.theme.VoxColors
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

@Composable
fun VoxRoot(container: AppContainer) {
    CompositionLocalProvider(LocalContainer provides container) {
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val onMessage: (String) -> Unit = { m -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(m) } }
        val actions = rememberCapabilityActions(container, onMessage)
        val session by container.session.state.collectAsState()
        val sharedVideo by container.sharedVideos.pending.collectAsState()
        val look by container.capture.pendingLook.collectAsState()

        LaunchedEffect(Unit) { container.chat.notices.collect { onMessage(it) } }
        // A video shared from another app lands in Video Lab; a "Look at screen" capture lands in Screen Awareness.
        LaunchedEffect(sharedVideo != null) { if (sharedVideo != null) nav.navigate("video") { launchSingleTop = true } }
        LaunchedEffect(look != null) { if (look != null) nav.navigate("screen") { launchSingleTop = true } }

        val go: (String) -> Unit = { route -> nav.navigate(route) { launchSingleTop = true } }
        val back: () -> Unit = { nav.popBackStack() }

        Box(Modifier.fillMaxSize().background(VoxColors.Backdrop)) {
            Column(Modifier.fillMaxSize()) {
                SessionBar(session, onEnd = { container.endSessionAsync() })
                NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f), enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                    composable("home") { HomeScreen(actions, go) }
                    composable("chat") { ConversationScreen(actions, back) }
                    composable("screen") { ScreenAwarenessScreen(actions, back, onMessage) }
                    composable("live") { LiveVideoScreen(actions, back, onMessage) }
                    composable("video") { VideoLabScreen(back) }
                    composable("notifications") { NotificationsScreen(actions, back, onMessage) }
                    composable("settings") { SettingsScreen(back, onMessage) }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}
