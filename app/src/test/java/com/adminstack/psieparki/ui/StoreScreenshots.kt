package com.adminstack.psieparki.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.adminstack.psieparki.data.Feature
import com.adminstack.psieparki.data.LatLon
import com.adminstack.psieparki.data.ParkQuery
import com.adminstack.psieparki.data.ParksFile
import com.adminstack.psieparki.data.ParksState
import com.adminstack.psieparki.data.distanceFrom
import com.adminstack.psieparki.data.localities
import com.adminstack.psieparki.data.parksJson
import com.adminstack.psieparki.data.rank
import com.adminstack.psieparki.ui.theme.PsieParkiTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The raw screenshots for the Play listing, rendered from the real screens and the real
 * data/parks.json:
 *
 *     ./gradlew testDebugUnitTest -Pscreenshots --tests '*StoreScreenshots*'
 *     python play/make_store_assets.py
 *
 * There is no emulator in CI, so Robolectric draws the Compose UI natively. MapLibre's
 * OpenGL map cannot run there; the map tab gets a picture of the same map instead,
 * rendered by MapLibre GL JS from the same style, data and layer colours
 * (play/map/render.mjs), and only that picture is swapped in: the card, buttons and
 * chrome around it are the app's own.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshots {
    private val root = File(System.getProperty("screenshots.dir") ?: "build/screenshots")
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "data/parks.json").exists() }
    private val parks = parksJson.decodeFromString(ParksFile.serializer(), File(repo, "data/parks.json").readText())

    // Rybnik's market square: where the app comes from, and a city with a few parks.
    private val here = LatLon(50.0971, 18.5418)
    private val origin = Origin(here, "Twoja lokalizacja", fromDevice = true)

    private data class Device(val dir: String, val qualifiers: String)

    private val phone = Device("raw-phone", "w360dp-h640dp-port-xxhdpi")
    private val tablet7 = Device("raw-tablet7", "w617dp-h1097dp-port-280dpi")
    private val tablet10 = Device("raw-tablet10", "w720dp-h1280dp-port-xhdpi")

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getProperty("roborazzi.test.record") == "true")

    private fun shot(device: Device, name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(device.qualifiers)
        captureRoboImage(File(root, "${device.dir}/$name.png").path) {
            PsieParkiTheme(dark = dark) { Surface(Modifier.fillMaxSize()) { content() } }
        }
    }

    private fun listUi(query: ParkQuery = ParkQuery()) = ListUi(
        parks = ParksState(parks.parks, parks.osm_base, loading = false),
        ranked = rank(parks.parks, query, here),
        origin = origin,
        query = query,
    )

    private fun park(id: String) = parks.parks.first { it.id == id }

    @Test
    fun phone() {
        shot(phone, "01-najblizsze") { ListScreen(listUi(), ListActions()) }
        shot(phone, "02-mapa") { MapShot("phone") }
        shot(phone, "03-szczegoly") { Detail("node/5655915525") }
        shot(phone, "04-filtry") { ListScreen(listUi(ParkQuery(text = "Warszawa", features = setOf(Feature.FENCED))), ListActions()) }
        shot(phone, "05-miejscowosci") { PlacePickerScreen(localities(parks.parks), {}, {}) }
        shot(phone, "06-ciemny-motyw", dark = true) { ListScreen(listUi(), ListActions()) }
    }

    @Test
    fun tablets() {
        for (device in listOf(tablet7, tablet10)) {
            shot(device, "01-najblizsze") { ListScreen(listUi(), ListActions()) }
            shot(device, "02-mapa") { MapShot(device.dir.removePrefix("raw-")) }
            shot(device, "03-szczegoly") { Detail("node/5655915525") }
            shot(device, "04-miejscowosci") { PlacePickerScreen(localities(parks.parks), {}, {}) }
        }
    }

    @Composable
    private fun Detail(id: String) {
        val p = park(id)
        DetailScreen(p, p.distanceFrom(here), DetailActions())
    }

    @Composable
    private fun MapShot(device: String) {
        val picture = File(repo, "play/map/$device.png")
        val bitmap = BitmapFactory.decodeFile(picture.path)?.asImageBitmap()
            ?: error("${picture.path} missing: run node play/map/render.mjs first")
        MapScreen(
            parks = parks.parks,
            origin = here,
            target = null,
            selected = park("node/5655915525"),
            onSelect = {},
            actions = MapActions(),
            map = { m -> Image(bitmap, contentDescription = null, modifier = m, contentScale = ContentScale.Crop) },
        )
    }
}
