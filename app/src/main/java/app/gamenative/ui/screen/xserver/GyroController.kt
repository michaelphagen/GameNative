package app.gamenative.ui.screen.xserver

import android.os.Handler
import android.os.Looper
import android.view.View
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.GyroInput
import com.winlator.xserver.XServer

/**
 * Standalone gyroscope aiming runtime. Independent of the on-screen touch controls: it stays
 * alive during gameplay and can be always-on or activated by triggers, so gyro works even when
 * the touch controller is hidden.
 *
 * Activation triggers are gamepad buttons (which are set by BOTH physical controllers and
 * on-screen buttons, so one trigger covers either source), plus optional touchpad-touch triggers
 * and any input bound to the GYRO action. Output is written to dedicated additive fields on the
 * shared [com.winlator.inputcontrols.GamepadState] so it combines with — never overwrites —
 * touch/physical stick input on the same stick.
 */
class GyroController(
    private var profile: ControlsProfile?,
    private val xServer: XServer?,
    private val viewProvider: () -> View?,
) {
    companion object {
        // Angular velocity (rad/s) that maps to full stick deflection at sensitivity 1.0x
        private const val GYRO_FULL_DEFLECTION_RATE = 2.0f
        private const val TICK_MS = 1000L / 60L
    }

    /** The profile whose gyro settings this runtime is applying. */
    val activeProfile: ControlsProfile? get() = profile

    private val handler = Handler(Looper.getMainLooper())
    private var gyroInput: GyroInput? = null
    private var running = false
    private var wasActive = false

    // GYRO-binding trigger (a touch or hardware button bound to the GYRO action)
    @Volatile private var bindingTriggerActive = false

    // Toggle-trigger latches, parallel to profile.gyroTriggers
    private var toggleLatched = BooleanArray(0)
    private var togglePrevPressed = BooleanArray(0)

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    fun setProfile(profile: ControlsProfile?) {
        this.profile = profile
        profile?.ensureGyroSettingsLoaded()
        resetToggles()
        bindingTriggerActive = false
        // Clear any lingering gyro contribution from the previous profile
        profile?.gamepadState?.let { it.gyroLX = 0f; it.gyroLY = 0f; it.gyroRX = 0f; it.gyroRY = 0f }
        syncRunning()
    }

    private fun resetToggles() {
        val n = profile?.gyroTriggers?.size ?: 0
        toggleLatched = BooleanArray(n)
        togglePrevPressed = BooleanArray(n)
    }

    /** Called when a GYRO-bound button (touch or hardware) is pressed/released. */
    fun setBindingTriggerActive(active: Boolean) {
        bindingTriggerActive = active
        if (active) syncRunning()
    }

    /** Start or stop the loop to match whether gyro could ever produce output. */
    fun syncRunning() {
        profile?.ensureGyroSettingsLoaded()
        val shouldRun = profile?.isGyroEnabled == true
        if (shouldRun && !running) {
            running = true
            handler.postDelayed(tickRunnable, TICK_MS)
        } else if (!shouldRun && running) {
            running = false
            handler.removeCallbacks(tickRunnable)
            deactivate()
        }
    }

    fun cleanup() {
        running = false
        handler.removeCallbacks(tickRunnable)
        gyroInput?.setActive(false)
        clearOutput()
    }

    private fun ensureGyroInput(): GyroInput? {
        if (gyroInput == null) {
            val view = viewProvider() ?: return null
            gyroInput = GyroInput(view)
        }
        return gyroInput
    }

    private fun tick() {
        val profile = this.profile
        if (profile == null || !profile.isGyroEnabled) {
            deactivate()
            return
        }

        val active = isGyroActive(profile)
        val gyro = ensureGyroInput()
        gyro?.setActive(active)

        if (!active) {
            if (wasActive) {
                clearOutput()
                gyro?.setActive(false)
                commit()
                wasActive = false
            }
            return
        }
        wasActive = true

        val state = profile.gamepadState
        if (gyro != null && gyro.isActive) {
            val sens = profile.gyroSensitivity / GYRO_FULL_DEFLECTION_RATE
            val gx = gyro.getRate(profile.gyroAxisX) * sens * (if (profile.isGyroInvertX) 1f else -1f)
            val gy = gyro.getRate(profile.gyroAxisY) * sens * (if (profile.isGyroInvertY) 1f else -1f)
            val cx = gx.coerceIn(-1f, 1f)
            val cy = gy.coerceIn(-1f, 1f)
            if (profile.isGyroOutputLeftStick) {
                state.gyroLX = cx; state.gyroLY = cy; state.gyroRX = 0f; state.gyroRY = 0f
            } else {
                state.gyroRX = cx; state.gyroRY = cy; state.gyroLX = 0f; state.gyroLY = 0f
            }
        }
        commit()
    }

    private fun isGyroActive(profile: ControlsProfile): Boolean {
        if (profile.isGyroAlwaysOn) return true
        if (bindingTriggerActive) return true

        // Touch-control triggers (touchpad or stick being touched). Check the profile currently
        // shown on screen as well as our own: edit-controls flows can swap the view to a
        // game-specific profile instance whose elements are the ones actually receiving touches.
        for (element in profile.getElements()) {
            if (element.isGyroTriggerAsserted) return true
        }
        val viewProfile = app.gamenative.PluviaApp.inputControlsView?.profile
        if (viewProfile != null && viewProfile !== profile) {
            for (element in viewProfile.getElements()) {
                if (element.isGyroTriggerAsserted) return true
            }
        }

        // Gamepad-button triggers (physical controller or on-screen button — same state bits)
        val triggers = profile.gyroTriggers
        if (triggers.size != toggleLatched.size) resetToggles()
        val state = profile.gamepadState
        var active = false
        for (i in triggers.indices) {
            val trigger = triggers[i]
            val pressed = isButtonPressed(state, trigger.button)
            if (trigger.toggle) {
                if (pressed && !togglePrevPressed[i]) toggleLatched[i] = !toggleLatched[i]
                togglePrevPressed[i] = pressed
                if (toggleLatched[i]) active = true
            } else {
                if (pressed) active = true
            }
        }
        return active
    }

    private fun isButtonPressed(state: com.winlator.inputcontrols.GamepadState, button: Binding): Boolean {
        return when (button) {
            Binding.GAMEPAD_DPAD_UP -> state.dpad[0]
            Binding.GAMEPAD_DPAD_RIGHT -> state.dpad[1]
            Binding.GAMEPAD_DPAD_DOWN -> state.dpad[2]
            Binding.GAMEPAD_DPAD_LEFT -> state.dpad[3]
            else -> {
                val idx = button.ordinal - Binding.GAMEPAD_BUTTON_A.ordinal
                if (idx in 0..ExternalController.IDX_BUTTON_R2.toInt()) state.isPressed(idx) else false
            }
        }
    }

    private fun deactivate() {
        gyroInput?.setActive(false)
        if (wasActive) {
            clearOutput()
            commit()
            wasActive = false
        }
    }

    private fun clearOutput() {
        profile?.gamepadState?.let { it.gyroLX = 0f; it.gyroLY = 0f; it.gyroRX = 0f; it.gyroRY = 0f }
    }

    private fun commit() {
        val winHandler = xServer?.winHandler ?: return
        val state = profile?.gamepadState
        winHandler.currentController?.state?.copy(state)
        winHandler.sendGamepadState()
        winHandler.sendVirtualGamepadState(state)
    }
}
