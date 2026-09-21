package com.hu.nav.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hu.nav.BuildConfig
import com.hu.nav.di.AppContainer
import com.hu.nav.ui.journey.JourneyScreen
import com.hu.nav.ui.route.RouteConfirmScreen
import com.hu.nav.ui.route.RouteViewModel
import com.hu.nav.ui.search.SearchScreen
import com.hu.nav.ui.search.SearchViewModel
import com.hu.nav.ui.settings.SettingsScreen

object Routes {
    const val Search = "search"
    const val Route = "route"
    const val Journey = "journey"
    const val Settings = "settings"
}

@Composable
fun AppNav(container: AppContainer) {
    val navController = rememberNavController()
    val factory = remember(container) { AppViewModelFactory(container) }
    NavHost(
        navController = navController,
        startDestination = Routes.Search,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        composable(Routes.Search) {
            val vm: SearchViewModel = viewModel(factory = factory)
            SearchScreen(
                viewModel = vm,
                missingKey = BuildConfig.AMAP_KEY.isBlank(),
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onPoiSelected = { poi ->
                    vm.select(poi)
                    navController.navigate(Routes.Route)
                },
            )
        }
        composable(Routes.Route) {
            val vm: RouteViewModel = viewModel(factory = factory)
            RouteConfirmScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onStart = {
                    if (vm.confirm() == null) return@RouteConfirmScreen
                    navController.navigate(Routes.Journey) {
                        popUpTo(Routes.Search)
                    }
                },
            )
        }
        composable(Routes.Journey) {
            JourneyScreen(
                engine = container.engine,
                session = container.session,
                onOpenSettings = { navController.navigate(Routes.Settings) },
                onStop = {
                    navController.popBackStack(Routes.Search, inclusive = false)
                },
            )
        }
        composable(Routes.Settings) {
            SettingsScreen(
                store = container.settings,
                engine = container.engine,
                tts = container.tts,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

class AppViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(SearchViewModel::class.java) -> SearchViewModel(
                search = container.search,
                locationClient = container.location,
                session = container.session,
                historyStore = container.searchHistory,
                tts = container.tts,
            )
            modelClass.isAssignableFrom(RouteViewModel::class.java) -> RouteViewModel(
                naviClient = container.navi,
                search = container.search,
                session = container.session,
                tts = container.tts,
            )
            else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
        } as T
    }
}
