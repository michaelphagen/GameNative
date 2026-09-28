package com.winlator.widget

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.inputcontrols.Binding
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class InputControlsViewGyroTargetTest {
    @Test
    fun `gyro presses go to the target view`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = mockk<InputControlsView>(relaxed = true)
        val cover = InputControlsView(context)
        cover.setGyroModifierTarget(target)
        val source = Any()

        cover.handleInputEvent(Binding.GYRO_MODIFIER, true, 0f, source)
        cover.handleInputEvent(Binding.GYRO_MODIFIER, false, 0f, source)

        verify { target.setGyroModifierPressed(source, true) }
        verify { target.setGyroModifierPressed(source, false) }
    }
}
