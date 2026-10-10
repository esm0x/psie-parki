package com.adminstack.psieparki.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adminstack.psieparki.data.Feature
import com.adminstack.psieparki.data.LatLon
import com.adminstack.psieparki.data.Park
import com.adminstack.psieparki.data.distanceFrom
import com.adminstack.psieparki.data.formatDistance
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature as GeoFeature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** What the map is asked to show; a new value moves the camera. */
data class MapTarget(val point: LatLon, val zoom: Double, val token: Long = System.nanoTime())

data class MapActions(
    val onOpen: (String) -> Unit = {},
    val onNavigate: (Park) -> Unit = {},
    val onLocate: () -> Unit = {},
)

/**
 * The map tab. [map] draws the map itself; it is a parameter so that screenshot tests,
 * which cannot run MapLibre's OpenGL renderer, can put a picture of the same map there.
 */
@Composable
fun MapScreen(
    parks: List<Park>,
    origin: LatLon?,
    target: MapTarget?,
    selected: Park?,
    onSelect: (Park?) -> Unit,
    actions: MapActions,
    map: @Composable (Modifier) -> Unit = { m -> ParksMap(parks, origin, target, onSelect, m) },
) {
    Box(Modifier.fillMaxSize()) {
        map(Modifier.fillMaxSize())

        SmallFloatingActionButton(
            onClick = actions.onLocate,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp),
            containerColor = MaterialTheme.colorScheme.surface,
        ) { Icon(Icons.Outlined.MyLocation, contentDescription = "Moja lokalizacja") }

        selected?.let { park ->
            Card(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(park.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(
                                origin?.let { formatDistance(park.distanceFrom(it)) },
                                park.place,
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onSelect(null) }) { Icon(Icons.Outlined.Close, contentDescription = "Zamknij") }
                    }
                    val present = Feature.entries.filter { it.test(park) == true }
                    if (present.isNotEmpty()) FeaturePills(present)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Button(onClick = { actions.onNavigate(park) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.Directions, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Prowadź")
                        }
                        OutlinedButton(onClick = { actions.onOpen(park.id) }, modifier = Modifier.weight(1f)) { Text("Szczegóły") }
                    }
                }
            }
        }
    }
}

const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private val POLAND = LatLon(52.07, 19.48)

private const val SRC_PARKS = "parks"
private const val SRC_ME = "me"
private const val LAYER_CLUSTERS = "parks-clusters"
private const val LAYER_COUNT = "parks-count"
private const val LAYER_POINTS = "parks-points"

@Composable
fun ParksMap(
    parks: List<Park>,
    origin: LatLon?,
    target: MapTarget?,
    onSelect: (Park?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentSelect by rememberUpdatedState(onSelect)
    val byId = remember(parks) { parks.associateBy { it.id } }
    val currentById by rememberUpdatedState(byId)
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleRef by remember { mutableStateOf<Style?>(null) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).also { view ->
            view.onCreate(null)
            view.getMapAsync { map ->
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                val start = origin ?: POLAND
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(start.lat, start.lon))
                    .zoom(if (origin != null) 11.5 else 5.2)
                    .build()
                map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { style ->
                    addParkLayers(style)
                    styleRef = style
                }
                map.addOnMapClickListener { point ->
                    val screen = map.projection.toScreenLocation(point)
                    val cluster = map.queryRenderedFeatures(screen, LAYER_CLUSTERS).firstOrNull()
                    if (cluster != null) {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, map.cameraPosition.zoom + 2))
                        return@addOnMapClickListener true
                    }
                    val hit = map.queryRenderedFeatures(screen, LAYER_POINTS).firstOrNull()
                    currentSelect(hit?.getStringProperty("id")?.let { currentById[it] })
                    true
                }
                mapRef = map
            }
        }
    }

    // MapView needs every lifecycle call forwarded, or it leaks its GL surface.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(styleRef, parks) {
        val style = styleRef ?: return@LaunchedEffect
        (style.getSource(SRC_PARKS) as? GeoJsonSource)?.setGeoJson(parksCollection(parks))
    }
    LaunchedEffect(styleRef, origin) {
        val style = styleRef ?: return@LaunchedEffect
        val me = origin?.let { listOf(GeoFeature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) }.orEmpty()
        (style.getSource(SRC_ME) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(me))
    }
    LaunchedEffect(mapRef, target) {
        val map = mapRef ?: return@LaunchedEffect
        val t = target ?: return@LaunchedEffect
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(t.point.lat, t.point.lon), t.zoom), 600)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun parksCollection(parks: List<Park>): FeatureCollection =
    FeatureCollection.fromFeatures(
        parks.map { p ->
            GeoFeature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also {
                it.addStringProperty("id", p.id)
                it.addBooleanProperty("fenced", p.fenced == true)
            }
        },
    )

private fun addParkLayers(style: Style) {
    val green = AndroidColor.parseColor("#2E6B3F")
    val orange = AndroidColor.parseColor("#E08A1E")
    style.addSource(
        GeoJsonSource(
            SRC_PARKS,
            FeatureCollection.fromFeatures(emptyList()),
            GeoJsonOptions().withCluster(true).withClusterMaxZoom(11).withClusterRadius(48),
        ),
    )
    style.addSource(GeoJsonSource(SRC_ME, FeatureCollection.fromFeatures(emptyList())))

    style.addLayer(
        CircleLayer(LAYER_CLUSTERS, SRC_PARKS)
            .withFilter(Expression.has("point_count"))
            .withProperties(
                PropertyFactory.circleColor(green),
                PropertyFactory.circleOpacity(0.88f),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleRadius(
                    Expression.step(
                        Expression.get("point_count"),
                        Expression.literal(15f),
                        Expression.stop(10, 19f),
                        Expression.stop(50, 24f),
                        Expression.stop(200, 30f),
                    ),
                ),
            ),
    )
    style.addLayer(
        SymbolLayer(LAYER_COUNT, SRC_PARKS)
            .withFilter(Expression.has("point_count"))
            .withProperties(
                PropertyFactory.textField(Expression.toString(Expression.get("point_count_abbreviated"))),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(13f),
                PropertyFactory.textColor(AndroidColor.WHITE),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAllowOverlap(true),
            ),
    )
    style.addLayer(
        CircleLayer(LAYER_POINTS, SRC_PARKS)
            .withFilter(Expression.not(Expression.has("point_count")))
            .withProperties(
                PropertyFactory.circleColor(
                    Expression.switchCase(Expression.get("fenced"), Expression.color(green), Expression.color(orange)),
                ),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
                PropertyFactory.circleStrokeWidth(2.5f),
            ),
    )
    style.addLayer(
        CircleLayer("me", SRC_ME).withProperties(
            PropertyFactory.circleColor(AndroidColor.parseColor("#1A73E8")),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}
