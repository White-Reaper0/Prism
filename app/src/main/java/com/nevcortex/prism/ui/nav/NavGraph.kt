package com.nevcortex.prism.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nevcortex.prism.R
import com.nevcortex.prism.di.AppContainer
import com.nevcortex.prism.ui.screens.diagnostics.CameraTimingScreen
import com.nevcortex.prism.ui.screens.diagnostics.ConnectionScreen
import com.nevcortex.prism.ui.screens.diagnostics.DiagnosticsScreen
import com.nevcortex.prism.ui.screens.evidence.EvidenceScreen
import com.nevcortex.prism.ui.screens.home.HomeScreen
import com.nevcortex.prism.ui.screens.mapping.MappingScreen
import com.nevcortex.prism.ui.screens.models.ModelsScreen
import com.nevcortex.prism.ui.screens.motion.MotionScreen
import com.nevcortex.prism.ui.screens.pipeline.PipelineScreen
import com.nevcortex.prism.ui.screens.recording.RecordingScreen
import com.nevcortex.prism.ui.screens.recordings.RecordingsScreen
import com.nevcortex.prism.ui.screens.settings.SettingsScreen
import com.nevcortex.prism.ui.screens.studio.LiveStudioScreen
import com.nevcortex.prism.ui.screens.tracking.TrackingScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrismScaffold(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Dest.HOME.route
    val isPrimary = Dest.ordered.any { it.route == currentRoute }
    val isWide = LocalConfiguration.current.screenWidthDp >= 600

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (!isPrimary) {
                TopAppBar(
                    title = { Text(stringResource(Routes.title(currentRoute))) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back)
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (!isWide) {
                NavigationBar {
                    Dest.ordered.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = { navController.navigatePrimary(dest) },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { inner ->
        if (isWide) {
            Row(Modifier.fillMaxSize().padding(inner)) {
                NavigationRail {
                    Dest.ordered.forEach { dest ->
                        NavigationRailItem(
                            selected = currentRoute == dest.route,
                            onClick = { navController.navigatePrimary(dest) },
                            icon = { Icon(dest.icon, contentDescription = null) },
                            label = { Text(stringResource(dest.labelRes)) }
                        )
                    }
                }
                NavHostBody(navController, container, PaddingValues(0.dp))
            }
        } else {
            NavHostBody(navController, container, inner)
        }
    }
}

private fun NavHostController.navigatePrimary(dest: Dest) {
    navigate(dest.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun NavHostBody(
    navController: NavHostController,
    container: AppContainer,
    padding: PaddingValues
) {
    NavHost(
        navController = navController,
        startDestination = Dest.HOME.route,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
    ) {
        composable(Dest.HOME.route) {
            HomeScreen(
                container = container,
                onOpenStudio = { navController.navigatePrimary(Dest.STUDIO) },
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Dest.STUDIO.route) {
            LiveStudioScreen(
                container = container,
                onOpenTracking = { navController.navigate(Routes.TRACKING) },
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
                onOpenConnectionSetup = { navController.navigatePrimary(Dest.HOME) }
            )
        }
        composable(Dest.DIAGNOSTICS.route) {
            DiagnosticsScreen(
                container = container,
                onNavigate = { navController.navigate(it) }
            )
        }
        composable(Routes.CONNECTION) {
            ConnectionScreen(
                container = container,
                onOpenStudio = { navController.navigatePrimary(Dest.STUDIO) }
            )
        }
        composable(Routes.MOTION) {
            MotionScreen(
                container = container,
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) }
            )
        }
        composable(Routes.CAMERA) {
            CameraTimingScreen(
                container = container,
                onOpenStudio = { navController.navigatePrimary(Dest.STUDIO) }
            )
        }
        composable(Routes.TRACKING) {
            TrackingScreen(container)
        }
        composable(Routes.RECORDING) {
            RecordingScreen(
                container = container,
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) }
            )
        }
        composable(Routes.RECORDINGS) {
            RecordingsScreen(
                container = container,
                onOpenStudio = { navController.navigatePrimary(Dest.STUDIO) }
            )
        }
        composable(Routes.PIPELINE) {
            PipelineScreen(
                container = container,
                onOpenRecordings = { navController.navigate(Routes.RECORDINGS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(container)
        }
        composable(Routes.MAPPING) { MappingScreen(container) }
        composable(Routes.MODELS) { ModelsScreen(container) }
        composable(Routes.EVIDENCE) { EvidenceScreen(container) }
    }
}
