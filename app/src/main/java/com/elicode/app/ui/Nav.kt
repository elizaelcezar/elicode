package com.elicode.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elicode.app.AppGraph
import com.elicode.app.ui.screens.AgentScreen
import com.elicode.app.ui.screens.BuildScreen
import com.elicode.app.ui.screens.DiagnosticsScreen
import com.elicode.app.ui.screens.EditorScreen
import com.elicode.app.ui.screens.GitHubScreen
import com.elicode.app.ui.screens.GitScreen
import com.elicode.app.ui.screens.OnboardingScreen
import com.elicode.app.ui.screens.PreviewScreen
import com.elicode.app.ui.screens.ProjectsScreen
import com.elicode.app.ui.screens.SettingsScreen
import com.elicode.app.ui.screens.TerminalScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val PROJECTS = "projects"
    const val EDITOR = "editor"
    const val TERMINAL = "terminal"
    const val AGENT = "agent"
    const val PREVIEW = "preview"
    const val BUILD = "build"
    const val GIT = "git"
    const val GITHUB = "github"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.PROJECTS, "Projects", Icons.Default.Folder),
    Tab(Routes.EDITOR, "Editor", Icons.Default.PlayArrow),
    Tab(Routes.TERMINAL, "Terminal", Icons.Default.Terminal),
    Tab(Routes.AGENT, "AI", Icons.Default.SmartToy),
    Tab(Routes.BUILD, "Build", Icons.Default.Build)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EliCodeNav(graph: AppGraph, nav: NavHostController = rememberNavController()) {
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showChrome = route != Routes.ONBOARDING

    fun go(r: String) {
        nav.navigate(r) {
            launchSingleTop = true
            restoreState = true
            if (r in tabs.map { it.route }) {
                popUpTo(Routes.PROJECTS) { saveState = true }
            }
        }
    }

    Scaffold(
        topBar = {
            if (showChrome) {
                TopAppBar(
                    title = {
                        Text("EliCode" + (graph.session.project?.let { " · ${it.name}" } ?: ""))
                    },
                    actions = {
                        IconButton(onClick = { go(Routes.PREVIEW) }) {
                            Text("▶", modifier = Modifier)
                        }
                        IconButton(onClick = { go(Routes.GIT) }) { Text("⎇") }
                        IconButton(onClick = { go(Routes.GITHUB) }) { Text("🐙") }
                        IconButton(onClick = { go(Routes.SETTINGS) }) { Text("⚙") }
                    }
                )
            }
        },
        bottomBar = {
            if (showChrome) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { go(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                            enabled = t.route != Routes.EDITOR || graph.session.project != null
                        )
                    }
                }
            }
        }
    ) { pad ->
        androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
            NavHost(
                navController = nav,
                startDestination = if (graph.prefs.onboardingDone) Routes.PROJECTS else Routes.ONBOARDING
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(graph) { go(Routes.PROJECTS) }
                }
                composable(Routes.PROJECTS) {
                    ProjectsScreen(graph) { go(Routes.EDITOR) }
                }
                composable(Routes.EDITOR) { EditorScreen(graph) }
                composable(Routes.TERMINAL) { TerminalScreen(graph) }
                composable(Routes.AGENT) { AgentScreen(graph) }
                composable(Routes.PREVIEW) { PreviewScreen(graph) }
                composable(Routes.BUILD) { BuildScreen(graph) }
                composable(Routes.GIT) { GitScreen(graph) }
                composable(Routes.GITHUB) {
                    GitHubScreen(graph) { go(Routes.EDITOR) }
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(graph) { go(Routes.DIAGNOSTICS) }
                }
                composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(graph) }
            }
        }
    }
}
