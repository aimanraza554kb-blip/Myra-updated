package com.myra.assistant.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.myra.assistant.ui.chat.ChatScreen
import com.myra.assistant.ui.home.HomeScreen
import com.myra.assistant.ui.home.HomeViewModel
import com.myra.assistant.ui.settings.SettingsScreen
import com.myra.assistant.ui.settings.SettingsViewModel

/** Top-level navigation between the voice screen, chat history and settings. */
@Composable
fun MyraNavHost() {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                viewModel = homeViewModel,
                onOpenChat = { navController.navigate("chat") },
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable("chat") {
            ChatScreen(viewModel = homeViewModel, onBack = { navController.popBackStack() })
        }
        composable("settings") {
            val settingsViewModel: SettingsViewModel = viewModel()
            SettingsScreen(viewModel = settingsViewModel, onBack = { navController.popBackStack() })
        }
    }
}
