package app.gamenative.inputcontrols

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ControlProfileSlotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun foldedSlotUsesUnfoldedProfileUntilItHasItsOwn() {
        val manager = InputControlsManager(context)
        val root = Files.createTempDirectory("profile-slots").toFile()
        val container = Container("profile-slots").apply { rootDir = root }
        val ids = mutableSetOf<Int>()
        try {
            val library = ControlProfileService.createBlank(context, manager, "Slots").also { ids += it.id }
            val unfolded = ControlProfileService.applyProfile(context, container, manager, library).also { ids += it.id }
            assertEquals(unfolded.id, ControlProfileService.selectedProfile(container, manager, ControlProfileSlot.FOLDED)?.id)

            val folded = ControlProfileService.ensureWorkingProfile(context, container, manager, ControlProfileSlot.FOLDED)!!
                .also { ids += it.id }
            assertNotEquals(unfolded.id, folded.id)
            assertEquals(unfolded.id.toString(), container.getExtra(ControlProfileSlot.UNFOLDED.extraKey, "0"))
            assertEquals(folded.id.toString(), container.getExtra(ControlProfileSlot.FOLDED.extraKey, "0"))
            assertEquals(folded.id, ControlProfileService.ensureWorkingProfile(context, container, manager, ControlProfileSlot.FOLDED)?.id)
        } finally {
            ids.forEach { ControlsProfile.getProfileFile(context, it).delete() }
            root.deleteRecursively()
        }
    }

    @Test
    fun applyingToTheCoverSlotLeavesTheOthersAlone() {
        val manager = InputControlsManager(context)
        val root = Files.createTempDirectory("profile-cover-slot").toFile()
        val container = Container("profile-cover-slot").apply { rootDir = root }
        val ids = mutableSetOf<Int>()
        try {
            assertNull(ControlProfileService.selectedProfile(container, manager, ControlProfileSlot.COVER))
            val library = ControlProfileService.createBlank(context, manager, "Cover").also { ids += it.id }
            val cover = ControlProfileService.applyProfile(
                context, container, manager, library, setOf(ControlProfileSection.ON_SCREEN), ControlProfileSlot.COVER,
            ).also { ids += it.id }

            assertEquals(cover.id.toString(), container.getExtra(ControlProfileSlot.COVER.extraKey, "0"))
            assertEquals("0", container.getExtra(ControlProfileSlot.UNFOLDED.extraKey, "0"))
            assertEquals("0", container.getExtra(ControlProfileSlot.FOLDED.extraKey, "0"))
        } finally {
            ids.forEach { ControlsProfile.getProfileFile(context, it).delete() }
            root.deleteRecursively()
        }
    }

    @Test
    fun gameAreaTravelsWithTheOnScreenLayout() {
        val manager = InputControlsManager(context)
        val root = Files.createTempDirectory("profile-game-area").toFile()
        val container = Container("profile-game-area").apply { rootDir = root }
        val ids = mutableSetOf<Int>()
        try {
            val library = ControlProfileService.createBlank(context, manager, "Trimmed").also { ids += it.id }
            val json = ControlProfileService.readProfileJson(context, library)
            json.put(ControlProfileService.KEY_GAME_AREA, JSONObject().put("bottom", 50))
            ControlsProfile.getProfileFile(context, library.id).writeText(json.toString())

            val working = ControlProfileService.applyProfile(context, container, manager, library).also { ids += it.id }
            val trim = ControlProfileService.readProfileJson(context, working).getJSONObject(ControlProfileService.KEY_GAME_AREA)
            assertEquals(50, trim.getInt("bottom"))

            val invalid = JSONObject()
                .put("name", "Bad trim")
                .put("elements", JSONArray())
                .put(ControlProfileService.KEY_GAME_AREA, JSONObject().put("left", 99))
            assertThrows(IllegalArgumentException::class.java) { ControlProfileService.validate(invalid) }
        } finally {
            ids.forEach { ControlsProfile.getProfileFile(context, it).delete() }
            root.deleteRecursively()
        }
    }
}
