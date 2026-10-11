package com.adminstack.psieparki.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import com.adminstack.psieparki.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * A monkey that knows Compose: thousands of random taps on whatever is clickable, hostile
 * text in every field, the back button, rotations and dark mode, against the real app
 * with the real data, in seconds instead of the hours a software emulator needs.
 *
 * Only the map tab is left out: MapLibre is native code that Robolectric cannot load,
 * and the emulator monkey covers it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class ComposeMonkeyTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val hostileText = listOf(
        "", " ", "k.u.r.w.a", "🐕".repeat(200), "x".repeat(5_000), "‮awruk", "%s%n%x", "'; DROP TABLE parks;--",
        "<script>alert(1)</script>", "Łódź", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
        "\u0000\u0001\u0002", "Rybnik", "boguszowice", "ﷺ".repeat(50), "0".repeat(300), "à".repeat(100),
    )

    /** Taps that leave the app or load native code: counted as done, not performed. */
    private val avoid = setOf("Mapa", "Na mapie", "Dodaj wybieg", "Wybieg jest tutaj")

    private fun label(node: SemanticsNodeInteraction): String = runCatching {
        val c = node.fetchSemanticsNode().config
        (c.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "") +
            (c.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "")
    }.getOrDefault("")

    private fun monkey(seed: Int, steps: Int) {
        val rnd = Random(seed)
        repeat(steps) { step ->
            when (rnd.nextInt(100)) {
                in 0..69 -> {
                    val nodes = compose.onAllNodes(hasClickAction(), useUnmergedTree = false)
                    val count = nodes.fetchSemanticsNodes(atLeastOneRootRequired = false).size
                    if (count > 0) {
                        val node = nodes[rnd.nextInt(count)]
                        val l = label(node)
                        if (avoid.none { l.contains(it) }) staleOk { node.performClick() }
                    }
                }
                in 70..84 -> {
                    val fields = compose.onAllNodes(hasSetTextAction())
                    val count = fields.fetchSemanticsNodes(atLeastOneRootRequired = false).size
                    if (count > 0) staleOk { fields[rnd.nextInt(count)].performTextReplacement(hostileText.random(rnd)) }
                }
                in 85..94 -> Espresso.pressBackUnconditionally()
                in 95..97 -> {
                    RuntimeEnvironment.setQualifiers(if (rnd.nextBoolean()) "+land" else "+port")
                    compose.activityRule.scenario.recreate()
                }
                else -> {
                    RuntimeEnvironment.setQualifiers(if (rnd.nextBoolean()) "+night" else "+notnight")
                    compose.activityRule.scenario.recreate()
                }
            }
            // Pressing back on the start screen finishes the activity: start it again,
            // like a person reopening the app.
            if (compose.activityRule.scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED).not()) {
                compose.activityRule.scenario.recreate()
            }
            compose.waitForIdle()
            screens += SCREENS.filter { t -> compose.onAllNodes(androidx.compose.ui.test.hasText(t, substring = true)).fetchSemanticsNodes(false).isNotEmpty() }
            if (step % 100 == 0) println("monkey seed=$seed step=$step")
        }
        println("monkey seed=$seed reached: $screens")
        // A monkey stuck on one screen proves nothing: it must have walked the app.
        org.junit.Assert.assertTrue("reached only $screens", screens.size >= 5)
    }

    /**
     * The one failure tolerated: the node picked a moment ago is gone (the screen moved
     * on). Anything the app itself throws still fails the test.
     */
    private fun staleOk(action: () -> Unit) {
        try {
            action()
        } catch (e: AssertionError) {
            if (e.message?.contains("node") != true && e.message?.contains("Node") != true) throw e
        }
    }

    private val screens = mutableSetOf<String>()

    private companion object {
        /** Words only one screen shows: proof the monkey went there. */
        val SCREENS = listOf("Wybiegi dla psów w Polsce", "Najbliżej:", "Weterynarz w pobliżu", "Schroniska",
            "Wybierz miejscowość", "Moje zgłoszenia", "Skąd są dane", "w linii prostej", "Brak danych")
    }

    @Test fun monkey1() = monkey(seed = 1, steps = 800)
    @Test fun monkey2() = monkey(seed = 2, steps = 800)
    @Test fun monkey3() = monkey(seed = 3, steps = 800)
    @Test fun monkey4() = monkey(seed = 4, steps = 800)
    @Test fun monkey5() = monkey(seed = 5, steps = 800)

    @Suppress("unused")
    private val keepImport = SemanticsActions.OnClick
}
