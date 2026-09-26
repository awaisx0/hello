package app.locjournal.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.locjournal.container
import app.locjournal.tracking.LocationTrackingService
import app.locjournal.tracking.Permissions
import app.locjournal.ui.common.SelectionViewModel
import app.locjournal.ui.places.PlaceEditScreen
import app.locjournal.ui.places.PlacesScreen
import app.locjournal.ui.settings.SettingsScreen
import app.locjournal.ui.stats.StatsScreen
import app.locjournal.ui.theme.LocationJournalTheme
import app.locjournal.ui.timeline.TimelineScreen
import app.locjournal.ui.visit.VisitEditScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocationJournalTheme { AppRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Restart the service if tracking is on but Android killed it (safe here: we are in the foreground).
        lifecycleScope.launch {
            if (container.settings.current().trackingEnabled && Permissions.hasLocation(this@MainActivity)) {
                runCatching { LocationTrackingService.start(this@MainActivity) }
            }
        }
    }
}

object Routes {
    const val TIMELINE = "timeline"
    const val PLACES = "places"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val VISIT = "visit/{id}?date={date}"
    const val PLACE = "place/{id}?lat={lat}&lon={lon}&name={name}"

    fun visit(id: Long, date: String? = null) = "visit/$id" + (date?.let { "?date=$it" } ?: "")

    fun place(id: Long, lat: Double? = null, lon: Double? = null, name: String? = null): String {
        val params = listOfNotNull(
            lat?.let { "lat=$it" },
            lon?.let { "lon=$it" },
            name?.let { "name=" + Uri.encode(it) },
        )
        return "place/$id" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }
}

private data class TopDest(val route: String, val label: String, val icon: ImageVector)

private val topDestinations = listOf(
    TopDest(Routes.TIMELINE, "Timeline", Icons.AutoMirrored.Filled.ViewList),
    TopDest(Routes.PLACES, "Places", Icons.Default.Place),
    TopDest(Routes.STATS, "Stats", Icons.Default.BarChart),
    TopDest(Routes.SETTINGS, "Settings", Icons.Default.Settings),
)

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val selectionVm: SelectionViewModel = viewModel()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val selection by selectionVm.selection.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            if (currentRoute in topDestinations.map { it.route }) {
                NavigationBar {
                    topDestinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, null) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.TIMELINE, modifier = Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
            composable(Routes.TIMELINE) {
                TimelineScreen(
                    selection = selection,
                    onSelect = selectionVm::select,
                    onOpenVisit = { nav.navigate(Routes.visit(it)) },
                    onAddVisit = { date -> nav.navigate(Routes.visit(-1, date.toString())) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                )
            }
            composable(
                Routes.VISIT,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("date") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                VisitEditScreen(
                    visitId = entry.arguments?.getLong("id") ?: -1,
                    initialDate = entry.arguments?.getString("date"),
                    onBack = { nav.popBackStack() },
                    onSaveAsPlace = { lat, lon, name -> nav.navigate(Routes.place(-1, lat, lon, name)) },
                )
            }
            composable(Routes.PLACES) {
                PlacesScreen(
                    onOpenPlace = { nav.navigate(Routes.place(it)) },
                    onAddPlace = { nav.navigate(Routes.place(-1)) },
                )
            }
            composable(
                Routes.PLACE,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("lat") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("lon") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                val args = entry.arguments
                PlaceEditScreen(
                    placeId = args?.getLong("id") ?: -1,
                    initialLat = args?.getString("lat")?.toDoubleOrNull(),
                    initialLon = args?.getString("lon")?.toDoubleOrNull(),
                    initialName = args?.getString("name"),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.STATS) {
                StatsScreen(selection = selection, onSelect = selectionVm::select)
            }
            composable(Routes.SETTINGS) {
                SettingsScreen()
            }
        }
    }
}
