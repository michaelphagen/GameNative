package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlElementTouchpadTest {
    private val view = mockk<InputControlsView>(relaxed = true)
    private val tick = slot<Runnable>()

    private fun touchpad(configure: ControlElement.() -> Unit = {}): ControlElement {
        every { view.snappingSize } returns 10
        every { view.postDelayed(capture(tick), any()) } returns true
        return ControlElement(view).apply {
            setType(ControlElement.Type.TOUCHPAD)
            setX(100)
            setY(100)
            configure()
        }
    }

    @Test
    fun `swiping right deflects the right stick and lifting recenters it`() {
        val element = touchpad { touchpadMomentum = 0f }

        assertTrue(element.handleTouchDown(1, 100f, 100f))
        element.handleTouchMove(1, 110f, 100f)
        tick.captured.run()
        verify { view.handleInputEvent(Binding.GAMEPAD_RIGHT_THUMB_RIGHT, true, match { it > 0f }) }

        element.handleTouchUp(1)
        repeat(10) { tick.captured.run() }
        verify { view.handleInputEvent(Binding.GAMEPAD_RIGHT_THUMB_RIGHT, true, 0f) }
    }

    @Test
    fun `screen-wide touchpad claims touches outside its bounds`() {
        val element = touchpad { isTouchpadScreenWide = true }
        val regular = touchpad()

        assertFalse(regular.handleScreenWideTouchDown(1, 900f, 900f))
        assertTrue(element.handleScreenWideTouchDown(1, 900f, 900f))
        assertFalse(element.handleScreenWideTouchDown(2, 800f, 800f))
    }

    @Test
    fun `touch binding is held only while a finger is on the pad`() {
        val element = touchpad {
            touchpadMomentum = 1f
            setBindingAt(ControlElement.TOUCHPAD_TOUCH_BINDING, Binding.GYRO_MODIFIER)
        }

        assertTrue(element.handleTouchDown(1, 100f, 100f))
        verify(exactly = 1) { view.handleInputEvent(Binding.GYRO_MODIFIER, true, 0f, any()) }

        element.handleTouchMove(1, 130f, 100f)
        tick.captured.run()
        element.handleTouchUp(1)
        verify(exactly = 1) { view.handleInputEvent(Binding.GYRO_MODIFIER, false, 0f, any()) }

        // The flick keeps gliding without pressing the binding again
        repeat(5) { tick.captured.run() }
        assertTrue(element.cancelTouch())
        verify(exactly = 1) { view.handleInputEvent(Binding.GYRO_MODIFIER, true, 0f, any()) }
        verify(exactly = 1) { view.handleInputEvent(Binding.GYRO_MODIFIER, false, 0f, any()) }
    }

    @Test
    fun `touch binding defaults to none`() {
        val element = touchpad()

        element.handleTouchDown(1, 100f, 100f)
        element.handleTouchUp(1)

        assertTrue(element.getBindingComboAt(ControlElement.TOUCHPAD_TOUCH_BINDING).isEmpty)
        verify(exactly = 0) { view.handleInputEvent(any<Binding>(), any<Boolean>()) }
        verify(exactly = 0) { view.handleInputEvent(any<BindingCombo>(), any<Boolean>()) }
        verify(exactly = 0) { view.handleInputEvent(any<Binding>(), any(), any(), any()) }
    }
}
