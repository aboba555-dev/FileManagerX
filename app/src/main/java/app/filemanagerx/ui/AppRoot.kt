package app.filemanagerx.ui

import android.os.Environment
import android.util.Base64
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.filemanagerx.data.settings.AppSettings
import app.filemanagerx.di.AppContainer
import app.filemanagerx.ui.analyzer.AnalyzerScreen
import app.filemanagerx.ui.analyzer.AnalyzerViewModel
import app.filemanagerx.ui.archive.ArchiveScreen
import app.filemanagerx.ui.archive.ArchiveViewModel
import app.filemanagerx.ui.browser.BrowserScreen
import app.filemanagerx.ui.browser.BrowserViewModel
import app.filemanagerx.ui.components.LocalThumbnails
import app.filemanagerx.ui.components.containerViewModel
import app.filemanagerx.ui.duplicates.DuplicatesScreen
import app.filemanagerx.ui.duplicates.DuplicatesViewModel
import app.filemanagerx.ui.editor.EditorScreen
import app.filemanagerx.ui.editor.EditorViewModel
import app.filemanagerx.ui.home.HomeScreen
import app.filemanagerx.ui.settings.AboutScreen
import app.filemanagerx.ui.settings.SettingsScreen
import app.filemanagerx.ui.theme.FileManagerXTheme
import app.filemanagerx.ui.trash.TrashScreen
import app.filemanagerx.ui.welcome.WelcomeScreen
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val TRASH = "trash"
    const val ARG = "path"
    const val BROWSER = "browser/{$ARG}"
    const val ARCHIVE = "archive/{$ARG}"
    const val EDITOR = "editor/{$ARG}"
    const val ANALYZER = "analyzer/{$ARG}"
    const val DUPLICATES = "duplicates/{$ARG}"

    fun browser(path: String) = "browser/${encode(path)}"
    fun archive(path: String) = "archive/${encode(path)}"
    fun editor(path: String) = "editor/${encode(path)}"
    fun analyzer(path: String) = "analyzer/${encode(path)}"
    fun duplicates(path: String) = "duplicates/${encode(path)}"

    // Paths contain slashes, which navigation routes do not tolerate; URL-safe Base64 does.
    private fun encode(path: String): String =
        Base64.encodeToString(path.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun decode(entry: NavBackStackEntry): String {
        val raw = entry.arguments?.getString(ARG).orEmpty()
        return String(Base64.decode(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    }
}

@Composable
fun AppRoot(container: AppContainer, incomingArchive: String?, onIncomingConsumed: () -> Unit) {
    val settingsState by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = settingsState ?: AppSettings()

    FileManagerXTheme(settings) {
        // Wait for the first stored settings so the theme and the welcome gate never flash defaults.
        if (settingsState != null) {
            CompositionLocalProvider(LocalThumbnails provides if (settings.thumbnails) container.thumbnails else null) {
                AppContent(container, settings, incomingArchive, onIncomingConsumed)
            }
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
    }
}

@Composable
private fun AppContent(container: AppContainer, settings: AppSettings, incomingArchive: String?, onIncomingConsumed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var hasAllFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    LifecycleResumeEffect(Unit) {
        hasAllFiles = Environment.isExternalStorageManager()
        onPauseOrDispose { }
    }

    LaunchedEffect(container) {
        container.tasks.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message.resolve(context))
        }
    }

    val gatePassed = hasAllFiles || settings.welcomeDone
    Box(Modifier.fillMaxSize()) {
        if (!gatePassed) {
            WelcomeScreen(onSkip = { scope.launch { container.settings.setWelcomeDone(true) } })
        } else {
            AppNavigation(incomingArchive, onIncomingConsumed)
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp),
            snackbar = { Snackbar(it, shape = RoundedCornerShape(18.dp)) },
        )
    }
}

@Composable
private fun AppNavigation(incomingArchive: String?, onIncomingConsumed: () -> Unit) {
    val nav: NavHostController = rememberNavController()

    LaunchedEffect(incomingArchive) {
        if (incomingArchive != null) {
            nav.navigate(Routes.archive(incomingArchive))
            onIncomingConsumed()
        }
    }

    val slide = tween<IntOffset>(280)
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { slideInHorizontally(slide) { it / 10 } + fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { slideOutHorizontally(slide) { it / 10 } + fadeOut(tween(200)) },
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenFolder = { nav.navigate(Routes.browser(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenTrash = { nav.navigate(Routes.TRASH) },
                onAnalyze = { nav.navigate(Routes.analyzer(it)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onOpenAbout = { nav.navigate(Routes.ABOUT) })
        }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.TRASH) { TrashScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.BROWSER, arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType })) { entry ->
            val path = Routes.decode(entry)
            val vm = containerViewModel { BrowserViewModel(it, path) }
            BrowserScreen(
                vm = vm,
                onExit = { nav.popBackStack() },
                onOpenArchive = { nav.navigate(Routes.archive(it)) },
                onOpenEditor = { nav.navigate(Routes.editor(it)) },
                onAnalyze = { nav.navigate(Routes.analyzer(it)) },
                onFindDuplicates = { nav.navigate(Routes.duplicates(it)) },
            )
        }
        composable(Routes.ANALYZER, arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType })) { entry ->
            val path = Routes.decode(entry)
            val vm = containerViewModel { AnalyzerViewModel(it, path) }
            AnalyzerScreen(vm = vm, onExit = { nav.popBackStack() })
        }
        composable(Routes.DUPLICATES, arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType })) { entry ->
            val path = Routes.decode(entry)
            val vm = containerViewModel { DuplicatesViewModel(it, path) }
            DuplicatesScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(Routes.ARCHIVE, arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType })) { entry ->
            val path = Routes.decode(entry)
            val vm = containerViewModel { ArchiveViewModel(it, path) }
            ArchiveScreen(vm = vm, onExit = { nav.popBackStack() })
        }
        composable(Routes.EDITOR, arguments = listOf(navArgument(Routes.ARG) { type = NavType.StringType })) { entry ->
            val path = Routes.decode(entry)
            val vm = containerViewModel { EditorViewModel(it, path) }
            EditorScreen(vm = vm, onExit = { nav.popBackStack() })
        }
    }
}
