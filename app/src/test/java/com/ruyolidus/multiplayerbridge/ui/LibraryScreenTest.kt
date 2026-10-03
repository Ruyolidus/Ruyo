package com.ruyolidus.multiplayerbridge.ui

import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.ruyolidus.multiplayerbridge.LibraryUiState
import com.ruyolidus.multiplayerbridge.data.AppIdentity
import com.ruyolidus.multiplayerbridge.data.ImportedGame
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyLibraryHasWorkingImportAndAnHonestFeatureStatus() {
        var imports = 0
        compose.setContent {
            BridgeTheme(dark = false) {
                BridgeScreen(LibraryUiState(loading = false), onImport = { imports++ }, onRemove = {}, onRetry = {})
            }
        }
        compose.onNodeWithText("Import a game").assertIsDisplayed().performClick()
        assertEquals(1, imports)
        screenshot("library-light", "bridge-root")
        compose.onNodeWithContentDescription("About this build").performClick()
        compose.onNodeWithText("Available now").assertIsDisplayed()
    }

    @Test fun importedGameCanBeOpenedAndRemovedWithConfirmation() {
        val game = ImportedGame("a".repeat(64), AppIdentity("Dames", "example.dames", "1.0", 26), "dames.apk", 2_800_000, 1)
        var removed: ImportedGame? = null
        compose.setContent {
            BridgeTheme(dark = true) {
                BridgeScreen(LibraryUiState(games = listOf(game), loading = false), onImport = {}, onRemove = { removed = it }, onRetry = {})
            }
        }
        compose.onNodeWithTag("game-${game.id}").performClick()
        compose.onNodeWithText("Saved in your library").assertIsDisplayed()
        screenshot("game-details-dark", "bridge-root")
        compose.onNodeWithText("Remove saved copy").performScrollTo().performClick()
        assertNull(removed)
        compose.onNodeWithText("Remove", useUnmergedTree = true).performClick()
        assertEquals(game, removed)
    }

    private fun screenshot(name: String, tag: String) {
        val file = File(System.getProperty("bridge.screenshots"), "$name.png")
        file.parentFile.mkdirs()
        compose.onNodeWithTag(tag).captureRoboImage(file.absolutePath)
        assertTrue(file.length() > 1000)
    }
}
