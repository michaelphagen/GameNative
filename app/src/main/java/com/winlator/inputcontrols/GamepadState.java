package com.winlator.inputcontrols;

import java.nio.ByteBuffer;

public class GamepadState {
    public float thumbLX = 0;
    public float thumbLY = 0;
    public float thumbRX = 0;
    public float thumbRY = 0;
    // Gyro aiming contribution, kept separate so the gyro runtime and touch/physical stick
    // writers never overwrite each other; summed into the thumbstick axes at send time.
    public float gyroLX = 0;
    public float gyroLY = 0;
    public float gyroRX = 0;
    public float gyroRY = 0;
    public float triggerL = 0;
    public float triggerR = 0;
    public final boolean[] dpad = new boolean[4];
    public short buttons = 0;

    private static float clampAxis(float v) {
        return v < -1f ? -1f : (v > 1f ? 1f : v);
    }

    // Combined thumbstick values (touch/physical + gyro). Every path that serializes state to
    // the game must use these, not the raw thumb fields, or gyro aiming silently disappears.
    public float getCombinedLX() { return clampAxis(thumbLX + gyroLX); }
    public float getCombinedLY() { return clampAxis(thumbLY + gyroLY); }
    public float getCombinedRX() { return clampAxis(thumbRX + gyroRX); }
    public float getCombinedRY() { return clampAxis(thumbRY + gyroRY); }

    public byte getPovHat() {
        byte povHat = -1;
        if (dpad[0] && dpad[1]) povHat = 1;
        else if (dpad[1] && dpad[2]) povHat = 3;
        else if (dpad[2] && dpad[3]) povHat = 5;
        else if (dpad[3] && dpad[0]) povHat = 7;
        else if (dpad[0]) povHat = 0;
        else if (dpad[1]) povHat = 2;
        else if (dpad[2]) povHat = 4;
        else if (dpad[3]) povHat = 6;
        return povHat;
    }

    public void writeTo(ByteBuffer buffer) {
        buffer.putShort(buttons);
        buffer.put(getPovHat());
        buffer.putShort((short)(getCombinedLX() * Short.MAX_VALUE));
        buffer.putShort((short)(getCombinedLY() * Short.MAX_VALUE));
        buffer.putShort((short)(getCombinedRX() * Short.MAX_VALUE));
        buffer.putShort((short)(getCombinedRY() * Short.MAX_VALUE));
        buffer.put((byte)(triggerL * 255));
        buffer.put((byte)(triggerR * 255));
    }

    public void setPressed(int buttonIdx, boolean pressed) {
        int flag = 1<<buttonIdx;
        if (pressed) {
            buttons |= flag;
        }
        else buttons &= ~flag;
    }

    public boolean isPressed(int buttonIdx) {
        return (buttons & (1<<buttonIdx)) != 0;
    }

    public byte getDPadX() {
        return (byte)(dpad[1] ? 1 : (dpad[3] ? -1 : 0));
    }

    public byte getDPadY() {
        return (byte)(dpad[0] ? -1 : (dpad[2] ? 1 : 0));
    }

    public void copy(GamepadState other) {
        this.thumbLX = other.thumbLX;
        this.thumbLY = other.thumbLY;
        this.thumbRX = other.thumbRX;
        this.thumbRY = other.thumbRY;
        this.gyroLX = other.gyroLX;
        this.gyroLY = other.gyroLY;
        this.gyroRX = other.gyroRX;
        this.gyroRY = other.gyroRY;
        this.triggerL = other.triggerL;
        this.triggerR = other.triggerR;
        this.buttons = other.buttons;
        System.arraycopy(other.dpad, 0, this.dpad, 0, 4);
    }
}
