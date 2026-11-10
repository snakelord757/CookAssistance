package com.cookassistance.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ImeReachabilityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val automation = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun tap(bounds: Rect) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue("Physical touch injected", automation.injectInputEvent(event, true))
            event.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(80)
        }
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun tagged(tag: String) = automation.windows.asSequence().mapNotNull { window ->
        find(window.root) { it.viewIdResourceName == tag }
    }.firstOrNull()

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun imeBounds() = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { Rect().also(it::getBoundsInScreen) }

    @Test fun landscapeLargeFontSendIsAboveRealKeyboardAndPhysicallySubmits() {
        val settings = listOf("font_scale", "accelerometer_rotation", "user_rotation")
        val previous = settings.associateWith { shell("settings get system $it") }
        val previousKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
        val previousFlags = automation.serviceInfo.flags
        var scenario: ActivityScenario<MainActivity>? = null
        val submitted = CopyOnWriteArrayList<Pair<String, String>>()
        val recipe = Recipe("1", "Classic Margherita Pizza", "Test recipe", "main", listOf(Ingredient("Dough", 1.0, "piece")), listOf("Bake"), 20, 1, "")
        val vm = CookViewModel(object : RecipeRepository {
            override val favorites = MutableStateFlow<List<Recipe>>(emptyList())
            override suspend fun list() = listOf(recipe)
            override suspend fun get(id: String) = recipe
            override suspend fun dishOfTheDay() = recipe
            override suspend fun collections() = emptyList<CollectionInfo>()
            override suspend fun byCollection(id: String) = listOf(recipe)
            override suspend fun save(recipe: Recipe) {}
            override suspend fun remove(id: String) {}
        }, object : Assistant {
            override suspend fun ask(id: String, question: String): AssistReply {
                submitted.add(id to question)
                return AssistReply(ignored = true)
            }
        })
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            shell("settings put system font_scale 2.0")
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation 1")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            SystemClock.sleep(1000)
            // MainActivity executes its production onCreate/edge-to-edge and manifest window setup.
            // Only data dependencies/content are replaced; never set softInputMode in this test.
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { activity ->
                assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, activity.resources.configuration.orientation)
                assertEquals(2f, activity.resources.configuration.fontScale, 0.01f)
                android.util.Log.i("ImeRegression", "softInputMode=${activity.window.attributes.softInputMode}")
                activity.setContent { MaterialTheme { CookApp(vm) } }
            }
            compose.waitUntil(10000) { vm.catalog.value is CatalogUiState.Content }
            compose.onNodeWithTag("recipe_list").performScrollToNode(hasTestTag("recipe_1"))
            compose.onNodeWithTag("recipe_1").performClick()
            compose.onNodeWithTag("recipe_detail").performScrollToNode(hasTestTag("cook_button"))
            compose.onNodeWithTag("cook_button").performClick()
            compose.onNodeWithTag("question_input").performScrollTo().performClick().performTextInput("First step")
            compose.waitUntil(10000) { imeBounds()?.height()?.let { it > 0 } == true }
            compose.onNodeWithTag("ask_button").performScrollTo()
            compose.waitForIdle()
            val keyboard = checkNotNull(imeBounds()) { "Real software keyboard must remain open" }
            val screenshot = automation.takeScreenshot()
            val evidence = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "ime-regression.png")
            evidence.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            val send = bounds(checkNotNull(tagged("ask_button")) { "Send is absent from accessible viewport while keyboard=$keyboard" })
            // A focused field scrolled outside the viewport is absent from accessibility windows.
            compose.onNodeWithTag("question_input").assertIsFocused()
            assertEquals("First step", vm.cooking.value.question)
            android.util.Log.i("ImeRegression", "keyboard=$keyboard send=$send")

            assertTrue("Full Send must be above IME: send=$send keyboard=$keyboard", send.height() > 0 && send.top >= 0 && send.bottom <= keyboard.top)
            tap(send)
            compose.waitUntil(10000) { submitted.isNotEmpty() }
            assertEquals(listOf("1" to "First step"), submitted.toList())
        } finally {
            scenario?.close()
            vm.viewModelScope.cancel()
            previous.forEach { (key, value) ->
                shell(if (value == "null") "settings delete system $key" else "settings put system $key $value")
            }
            shell(if (previousKeyboard == "null") "settings delete secure show_ime_with_hard_keyboard" else "settings put secure show_ime_with_hard_keyboard $previousKeyboard")
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }
}
