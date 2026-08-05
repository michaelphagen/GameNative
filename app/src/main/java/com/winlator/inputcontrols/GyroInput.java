package com.winlator.inputcontrols;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Display;
import android.view.Surface;
import android.view.View;

/**
 * Wraps the device gyroscope and exposes screen-space angular velocity for gyro aiming.
 * Device axes are remapped by display rotation so the three axes always mean the same thing
 * relative to the screen regardless of how the device is held:
 *   YAW   — turning around the screen's vertical axis (look left/right)
 *   PITCH — tilting around the screen's horizontal axis (look up/down)
 *   ROLL  — spinning around the axis pointing out of the screen (steering-wheel motion)
 */
public class GyroInput implements SensorEventListener {
    /** Selectable source axes for mapping onto a stick axis. */
    public enum Axis { YAW, PITCH, ROLL }

    private final SensorManager sensorManager;
    private final Sensor sensor;
    private final View view;
    private boolean active = false;
    private volatile float yawRate;   // rad/s around screen vertical axis
    private volatile float pitchRate; // rad/s around screen horizontal axis
    private volatile float rollRate;  // rad/s around screen normal (out of screen)

    public GyroInput(View view) {
        this.view = view;
        sensorManager = (SensorManager) view.getContext().getSystemService(Context.SENSOR_SERVICE);
        sensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
    }

    public boolean isAvailable() {
        return sensor != null;
    }

    /** Register/unregister the hardware listener. Idempotent; safe to call every tick. */
    public void setActive(boolean active) {
        if (this.active == active || sensor == null) return;
        this.active = active;
        if (active) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
        else {
            sensorManager.unregisterListener(this);
            yawRate = 0;
            pitchRate = 0;
            rollRate = 0;
        }
    }

    public boolean isActive() {
        return active;
    }

    public float getYawRate() {
        return yawRate;
    }

    public float getPitchRate() {
        return pitchRate;
    }

    public float getRollRate() {
        return rollRate;
    }

    /** Current rate for a selected screen-space axis. */
    public float getRate(Axis axis) {
        switch (axis) {
            case YAW:   return yawRate;
            case PITCH: return pitchRate;
            case ROLL:  return rollRate;
            default:    return 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0]; // angular velocity around device X (portrait: screen horizontal)
        float y = event.values[1]; // angular velocity around device Y (portrait: screen vertical)
        // Roll is around the device Z axis (out of screen); unaffected by display rotation.
        rollRate = event.values[2];
        Display display = view.getDisplay();
        int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        switch (rotation) {
            case Surface.ROTATION_90:
                pitchRate = y;
                yawRate = -x;
                break;
            case Surface.ROTATION_180:
                pitchRate = -x;
                yawRate = -y;
                break;
            case Surface.ROTATION_270:
                pitchRate = -y;
                yawRate = x;
                break;
            default:
                pitchRate = x;
                yawRate = y;
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
