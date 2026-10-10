package com.adminstack.psieparki

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.adminstack.psieparki.data.Report
import com.adminstack.psieparki.data.distanceFrom
import com.adminstack.psieparki.ui.AboutActions
import com.adminstack.psieparki.ui.AddParkScreen
import com.adminstack.psieparki.ui.HelperActions
import com.adminstack.psieparki.ui.HelpersScreen
import com.adminstack.psieparki.ui.dial
import com.adminstack.psieparki.ui.sharePark
import com.adminstack.psieparki.ui.MySubmissionsScreen
import com.adminstack.psieparki.ui.VisitCard
import com.adminstack.psieparki.ui.AboutScreen
import com.adminstack.psieparki.ui.DetailActions
import com.adminstack.psieparki.ui.DetailScreen
import com.adminstack.psieparki.ui.ListActions
import com.adminstack.psieparki.ui.ListScreen
import com.adminstack.psieparki.ui.MapActions
import com.adminstack.psieparki.ui.MapScreen
import com.adminstack.psieparki.ui.MapTarget
import com.adminstack.psieparki.ui.ParksViewModel
import com.adminstack.psieparki.ui.PlacePickerScreen
import com.adminstack.psieparki.ui.navigateTo
import com.adminstack.psieparki.ui.openUrl
import com.adminstack.psieparki.ui.sendMail
import com.adminstack.psieparki.ui.theme.PsieParkiTheme
import com.adminstack.psieparki.data.LatLon
import com.adminstack.psieparki.data.Park

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PsieParkiTheme { AppRoot() } }
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    LIST("list", "Lista", Icons.AutoMirrored.Outlined.FormatListBulleted),
    MAP("map", "Mapa", Icons.Outlined.Map),
    VET("vet", "Weterynarz", Icons.Outlined.MedicalServices),
    MORE("more", "Więcej", Icons.Outlined.MoreHoriz),
}

@Composable
private fun AppRoot(vm: ParksViewModel = viewModel()) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    var mapTarget by remember { mutableStateOf<MapTarget?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val version = BuildConfig.VERSION_NAME

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) vm.locate() else nav.navigate("places")
    }
    val locate = {
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val inviteDismissed by vm.inviteDismissed.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val helpers by vm.helpers.collectAsStateWithLifecycle()
    val sent by vm.sent.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    val submissionsOn = vm.submissions.enabled
    // Without a submissions worker (a build without -PzgloszeniaUrl), adding a park
    // falls back to the pre-filled e-mail of the first version.
    val addPark: () -> Unit = {
        if (!submissionsOn) {
            sendMail(context, Report.missing(ui.origin?.point, version))
        } else {
            selectedId = null
            picking = true
            ui.origin?.let { mapTarget = MapTarget(it.point, 15.5) }
            nav.navigate(Tab.MAP.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
        }
    }
    fun showOnMap(park: Park) {
        selectedId = park.id
        mapTarget = MapTarget(LatLon(park.lat, park.lon), 15.0)
        nav.navigate(Tab.MAP.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Scaffold(bottomBar = {
        if (Tab.entries.any { it.route == route }) {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    }) { padding ->
        NavHost(nav, startDestination = Tab.LIST.route, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Tab.LIST.route) {
                ListScreen(
                    ui,
                    ListActions(
                        onText = vm::setText,
                        onToggle = vm::toggle,
                        onLocate = locate,
                        onPickPlace = { nav.navigate("places") },
                        onOpen = { id -> nav.navigate("park?id=$id") },
                        onAddPark = addPark,
                        onDismissInvite = vm::dismissInvite,
                        onToggleFavourites = vm::toggleFavouritesOnly,
                    ),
                    inviteDismissed = inviteDismissed,
                )
            }
            composable(Tab.MAP.route) {
                MapScreen(
                    parks = ui.ranked.map { it.park },
                    origin = ui.origin?.point,
                    target = mapTarget,
                    selected = selectedId?.let(vm::park),
                    onSelect = { selectedId = it?.id },
                    actions = MapActions(
                        onOpen = { id -> nav.navigate("park?id=$id") },
                        onNavigate = { navigateTo(context, it) },
                        onLocate = {
                            ui.origin?.let { mapTarget = MapTarget(it.point, 12.5) }
                            locate()
                        },
                        onStartPick = addPark,
                        onPicked = { p ->
                            picking = false
                            nav.navigate("add?lat=${p.lat}&lon=${p.lon}")
                        },
                        onCancelPick = { picking = false },
                    ),
                    picking = picking,
                )
            }
            composable(Tab.VET.route) {
                HelpersScreen(
                    places = helpers.places,
                    origin = ui.origin?.point,
                    originLabel = ui.origin?.label,
                    actions = HelperActions(
                        onCall = { h -> h.dialUri?.let { dial(context, it) } },
                        onNavigate = { h -> navigateTo(context, h.lat, h.lon, h.title) },
                        onOpenUrl = { openUrl(context, it) },
                        onLocate = locate,
                        onPickPlace = { nav.navigate("places") },
                    ),
                )
            }
            composable(Tab.MORE.route) {
                AboutScreen(
                    parkCount = ui.parks.parks.size,
                    osmBase = ui.parks.osmBase,
                    refreshing = ui.parks.refreshing,
                    version = version,
                    actions = AboutActions(
                        onAddPark = addPark,
                        onMySubmissions = { nav.navigate("moje") },
                        onRefresh = { vm.refresh() },
                        onOpenUrl = { openUrl(context, it) },
                        onMail = { sendMail(context, Report.Mail(Report.EMAIL, "Psie Parki", "")) },
                    ),
                )
            }
            // The id carries a slash ("way/123"), so it travels as a query argument,
            // which navigation does not split on.
            composable("park?id={id}") { entry ->
                val id = entry.arguments?.getString("id")
                val park = id?.let(vm::park)
                DetailScreen(
                    park = park,
                    distance = park?.let { p -> ui.origin?.point?.let { p.distanceFrom(it) } },
                    favourite = park?.id in favourites,
                    actions = DetailActions(
                        onBack = { nav.popBackStack() },
                        onToggleFavourite = { vm.toggleFavourite(it.id) },
                        onShare = { sharePark(context, it) },
                        onNavigate = { navigateTo(context, it) },
                        onShowOnMap = { showOnMap(it) },
                        onReport = { sendMail(context, Report.correction(it, version)) },
                        onOpenUrl = { openUrl(context, it) },
                    ),
                    contribute = { p ->
                        if (submissionsOn) VisitCard(p, vm.submissions.answered(p.id), vm::send)
                    },
                )
            }
            composable("add?lat={lat}&lon={lon}") { entry ->
                val lat = entry.arguments?.getString("lat")?.toDoubleOrNull()
                val lon = entry.arguments?.getString("lon")?.toDoubleOrNull()
                if (lat == null || lon == null) {
                    nav.popBackStack()
                    return@composable
                }
                val point = LatLon(lat, lon)
                // The place of the nearest known park within 3 km: a good guess to show,
                // and only a guess — the moderation looks the real one up in OSM.
                val place = ui.parks.parks.minByOrNull { it.distanceFrom(point) }
                    ?.takeIf { it.distanceFrom(point) < 3_000 }?.locality
                AddParkScreen(
                    point = point,
                    place = place?.let { "Okolice: $it" },
                    onSend = vm::send,
                    onBack = { nav.popBackStack(); picking = true },
                    onDone = { nav.popBackStack() },
                )
            }
            composable("moje") {
                MySubmissionsScreen(sent, onRefresh = { vm.refreshStatuses() }, onBack = { nav.popBackStack() })
            }
            composable("places") {
                PlacePickerScreen(places, onPick = { vm.pickPlace(it); nav.popBackStack() }, onBack = { nav.popBackStack() })
            }
        }
    }
}
