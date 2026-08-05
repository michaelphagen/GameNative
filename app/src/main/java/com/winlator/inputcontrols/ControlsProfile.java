package com.winlator.inputcontrols;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.winlator.core.FileUtils;
import com.winlator.widget.InputControlsView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ControlsProfile implements Comparable<ControlsProfile> {
    public final int id;
    private String name;
    private float cursorSpeed = 1.0f;
    private final ArrayList<ControlElement> elements = new ArrayList<>();
    private final ArrayList<ExternalController> controllers = new ArrayList<>();
    private final List<ControlElement> immutableElements = Collections.unmodifiableList(elements);
    private boolean elementsLoaded = false;
    private boolean controllersLoaded = false;
    private boolean virtualGamepad = false;
    private final Context context;
    private GamepadState gamepadState;

    // Profile-level gyro aiming settings, edited from the standalone Gyro Settings screen.
    private boolean gyroEnabled = false;
    private boolean gyroOutputLeftStick = false; // false -> right stick (camera), true -> left stick
    private float gyroSensitivity = 3.0f;
    private GyroInput.Axis gyroAxisX = GyroInput.Axis.YAW;
    private GyroInput.Axis gyroAxisY = GyroInput.Axis.PITCH;
    private boolean gyroInvertX = false;
    private boolean gyroInvertY = false;
    private boolean gyroAlwaysOn = false;
    private boolean gyroSettingsLoaded = false;
    // Gamepad-button triggers that enable gyro. Because both physical controllers and on-screen
    // gamepad buttons set the same GamepadState bits, one entry works for either source.
    private final ArrayList<GyroTrigger> gyroTriggers = new ArrayList<>();

    /** A gyro activation trigger: a gamepad button, held (momentary) or tapped (toggle). */
    public static class GyroTrigger {
        public Binding button;
        public boolean toggle;
        public GyroTrigger(Binding button, boolean toggle) {
            this.button = button;
            this.toggle = toggle;
        }
    }

    public ControlsProfile(Context context, int id) {
        this.context = context;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getCursorSpeed() {
        return cursorSpeed;
    }

    public void setCursorSpeed(float cursorSpeed) {
        this.cursorSpeed = cursorSpeed;
    }

    public boolean isVirtualGamepad() {
        return virtualGamepad;
    }

    public void setVirtualGamepad(boolean isVirtualGamepad) {
        virtualGamepad = isVirtualGamepad;
    }

    public GamepadState getGamepadState() {
        if (gamepadState == null) gamepadState = new GamepadState();
        return gamepadState;
    }

    public boolean isGyroEnabled() {
        return gyroEnabled;
    }

    public void setGyroEnabled(boolean gyroEnabled) {
        this.gyroEnabled = gyroEnabled;
    }

    public boolean isGyroOutputLeftStick() {
        return gyroOutputLeftStick;
    }

    public void setGyroOutputLeftStick(boolean gyroOutputLeftStick) {
        this.gyroOutputLeftStick = gyroOutputLeftStick;
    }

    public float getGyroSensitivity() {
        return gyroSensitivity;
    }

    public void setGyroSensitivity(float gyroSensitivity) {
        this.gyroSensitivity = gyroSensitivity;
    }

    public GyroInput.Axis getGyroAxisX() {
        return gyroAxisX;
    }

    public void setGyroAxisX(GyroInput.Axis gyroAxisX) {
        if (gyroAxisX != null) this.gyroAxisX = gyroAxisX;
    }

    public GyroInput.Axis getGyroAxisY() {
        return gyroAxisY;
    }

    public void setGyroAxisY(GyroInput.Axis gyroAxisY) {
        if (gyroAxisY != null) this.gyroAxisY = gyroAxisY;
    }

    public boolean isGyroInvertX() {
        return gyroInvertX;
    }

    public void setGyroInvertX(boolean gyroInvertX) {
        this.gyroInvertX = gyroInvertX;
    }

    public boolean isGyroInvertY() {
        return gyroInvertY;
    }

    public void setGyroInvertY(boolean gyroInvertY) {
        this.gyroInvertY = gyroInvertY;
    }

    public boolean isGyroAlwaysOn() {
        return gyroAlwaysOn;
    }

    public void setGyroAlwaysOn(boolean gyroAlwaysOn) {
        this.gyroAlwaysOn = gyroAlwaysOn;
    }

    /** Live list of gyro button triggers; mutate directly then {@link #save()}. */
    public ArrayList<GyroTrigger> getGyroTriggers() {
        return gyroTriggers;
    }

    /**
     * Share another profile's gamepad state so two views (e.g. the on-screen overlay and the
     * foldable cover screen) merge their input instead of overwriting each other's full state
     * when committing to the game.
     */
    public void setSharedGamepadState(GamepadState state) {
        if (state != null) gamepadState = state;
    }

    public ExternalController addController(String id) {
        ExternalController controller = getController(id);
        if (controller == null) {
            controller = new ExternalController();
            controller.setId(id);
            controller.setName("Physical Controller");
            controllers.add(controller);
        }
        controllersLoaded = true;
        return controller;
    }

    public void removeController(ExternalController controller) {
        if (!controllersLoaded) loadControllers();
        controllers.remove(controller);
    }

    public ExternalController getController(String id) {
        if (!controllersLoaded) loadControllers();
        for (ExternalController controller : controllers) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public ExternalController getController(int deviceId) {
        if (!controllersLoaded) loadControllers();

        // First try exact device ID match
        for (ExternalController controller : controllers) {
            if (controller.getDeviceId() == deviceId) return controller;
        }

        // Fall back to wildcard controller if no exact match
        for (ExternalController controller : controllers) {
            if (controller.getId().equals("*")) return controller;
        }

        return null;
    }

    public ArrayList<ExternalController> getControllers() {
        if (!controllersLoaded) loadControllers();
        return new ArrayList<>(controllers);
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(ControlsProfile o) {
        return Integer.compare(id, o.id);
    }

    public boolean isElementsLoaded() {
        return elementsLoaded;
    }

    public void save() {
        File file = getProfileFile(context, id);
        Log.d("ControlsProfile", "Saving profile: " + name + " (ID: " + id + ") to " + file.getAbsolutePath());

        try {
            JSONObject data = new JSONObject();
            data.put("id", id);
            data.put("name", name);
            data.put("cursorSpeed", Float.valueOf(cursorSpeed));

            // Profile-level gyro settings. Only trust the in-memory fields once they have been
            // loaded from disk; otherwise preserve whatever the file already holds so a
            // list-only load (which never populates these) can't clobber saved values.
            if (gyroSettingsLoaded) {
                JSONObject gyroJSONObject = new JSONObject();
                gyroJSONObject.put("enabled", gyroEnabled);
                gyroJSONObject.put("outputLeftStick", gyroOutputLeftStick);
                gyroJSONObject.put("sensitivity", (double) gyroSensitivity);
                gyroJSONObject.put("axisX", gyroAxisX.name());
                gyroJSONObject.put("axisY", gyroAxisY.name());
                gyroJSONObject.put("invertX", gyroInvertX);
                gyroJSONObject.put("invertY", gyroInvertY);
                gyroJSONObject.put("alwaysOn", gyroAlwaysOn);
                JSONArray triggersArray = new JSONArray();
                for (GyroTrigger trigger : gyroTriggers) {
                    if (trigger.button == null || trigger.button == Binding.NONE) continue;
                    JSONObject t = new JSONObject();
                    t.put("button", trigger.button.name());
                    t.put("toggle", trigger.toggle);
                    triggersArray.put(t);
                }
                gyroJSONObject.put("triggers", triggersArray);
                data.put("gyro", gyroJSONObject);
            }
            else if (file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                if (profileJSONObject.has("gyro")) data.put("gyro", profileJSONObject.getJSONObject("gyro"));
            }

            JSONArray elementsJSONArray = new JSONArray();
            if (!elementsLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                elementsJSONArray = profileJSONObject.getJSONArray("elements");
            }
            else for (ControlElement element : elements) elementsJSONArray.put(element.toJSONObject());
            data.put("elements", elementsJSONArray);

            JSONArray controllersJSONArray = new JSONArray();
            if (!controllersLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
                if (profileJSONObject.has("controllers")) controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            }
            else {
                for (ExternalController controller : controllers) {
                    JSONObject controllerJSONObject = controller.toJSONObject();
                    if (controllerJSONObject != null) controllersJSONArray.put(controllerJSONObject);
                }
            }
            if (controllersJSONArray.length() > 0) data.put("controllers", controllersJSONArray);

            FileUtils.writeString(file, data.toString());
            Log.d("ControlsProfile", "Profile saved successfully: " + name + " (controllers: " + controllersJSONArray.length() + ", elements: " + elementsJSONArray.length() + ")");
        }
        catch (JSONException e) {
            Log.e("ControlsProfile", "Failed to save profile: " + name + " (ID: " + id + ")", e);
        }
    }

    public static File getProfileFile(Context context, int id) {
        return new File(InputControlsManager.getProfilesDir(context), "controls-"+id+".icp");
    }

    public void addElement(ControlElement element) {
        elements.add(element);
        elementsLoaded = true;
    }

    public void removeElement(ControlElement element) {
        elements.remove(element);
        elementsLoaded = true;
    }

    public List<ControlElement> getElements() {
        return immutableElements;
    }

    public boolean isTemplate() {
        return name.toLowerCase(Locale.ENGLISH).contains("template");
    }

    public ArrayList<ExternalController> loadControllers() {
        controllers.clear();
        controllersLoaded = false;

        File file = getProfileFile(context, id);
        Log.d("ControlsProfile", "Loading controllers for profile: " + name + " (ID: " + id + ") from " + file.getAbsolutePath());

        if (!file.isFile()) {
            Log.d("ControlsProfile", "Profile file does not exist: " + name);
            return controllers;
        }

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            if (!profileJSONObject.has("controllers")) {
                Log.d("ControlsProfile", "No controllers section in profile: " + name);
                return controllers;
            }
            JSONArray controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            for (int i = 0; i < controllersJSONArray.length(); i++) {
                JSONObject controllerJSONObject = controllersJSONArray.getJSONObject(i);
                String id = controllerJSONObject.getString("id");
                ExternalController controller = new ExternalController();
                controller.setId(id);
                controller.setName(controllerJSONObject.getString("name"));

                JSONArray controllerBindingsJSONArray = controllerJSONObject.getJSONArray("controllerBindings");
                for (int j = 0; j < controllerBindingsJSONArray.length(); j++) {
                    JSONObject controllerBindingJSONObject = controllerBindingsJSONArray.getJSONObject(j);
                    ExternalControllerBinding controllerBinding = new ExternalControllerBinding();
                    controllerBinding.setKeyCode(controllerBindingJSONObject.getInt("keyCode"));
                    controllerBinding.setBinding(Binding.fromString(controllerBindingJSONObject.getString("binding")));
                    controller.addControllerBinding(controllerBinding);
                }
                controllers.add(controller);
            }
            controllersLoaded = true;
            Log.d("ControlsProfile", "Loaded " + controllers.size() + " controllers for profile: " + name);
        }
        catch (JSONException e) {
            Log.e("ControlsProfile", "Failed to load controllers for profile: " + name + " (ID: " + id + ")", e);
            e.printStackTrace();
        }
        return controllers;
    }

    public void loadElements(InputControlsView inputControlsView) {
        elements.clear();
        elementsLoaded = false;
        virtualGamepad = false;

        // Check if view has valid dimensions before loading
        if (inputControlsView.getMaxWidth() == 0 || inputControlsView.getMaxHeight() == 0) {
            Log.w("ControlsProfile", "Cannot load elements - view has no dimensions yet (width: " +
                inputControlsView.getWidth() + ", height: " + inputControlsView.getHeight() + ")");
            return;
        }

        File file = getProfileFile(context, id);
        Log.d("ControlsProfile", "Loading elements for profile: " + name + " (ID: " + id + ") from " + file.getAbsolutePath());

        if (!file.isFile()) {
            Log.d("ControlsProfile", "Profile file does not exist: " + name);
            return;
        }

        try {
            JSONObject profileJSONObject = new JSONObject(FileUtils.readString(file));
            loadGyroSettings(profileJSONObject);
            JSONArray elementsJSONArray = profileJSONObject.getJSONArray("elements");
            for (int i = 0; i < elementsJSONArray.length(); i++) {
                JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);
                ControlElement element = new ControlElement(inputControlsView);
                try {
                    element.setType(ControlElement.Type.valueOf(elementJSONObject.getString("type")));
                } catch (IllegalArgumentException e) {
                    Log.w("ControlsProfile", "Skipping element with unknown type: " + elementJSONObject.getString("type"));
                    continue;
                }
                element.setShape(ControlElement.Shape.valueOf(elementJSONObject.getString("shape")));
                element.setToggleSwitch(elementJSONObject.getBoolean("toggleSwitch"));
                element.setX((int)(elementJSONObject.getDouble("x") * inputControlsView.getMaxWidth()));
                element.setY((int)(elementJSONObject.getDouble("y") * inputControlsView.getMaxHeight()));
                element.setScale((float)elementJSONObject.getDouble("scale"));
                element.setText(elementJSONObject.getString("text"));
                element.setIconId(elementJSONObject.getInt("iconId"));
                if (elementJSONObject.has("range")) element.setRange(ControlElement.Range.valueOf(elementJSONObject.getString("range")));
                if (elementJSONObject.has("orientation")) element.setOrientation((byte)elementJSONObject.getInt("orientation"));
                if (elementJSONObject.has("scrollLocked")) element.setScrollLocked(elementJSONObject.getBoolean("scrollLocked"));

                if (elementJSONObject.has("shooterMovementType")) element.setShooterMovementType(elementJSONObject.getString("shooterMovementType"));
                if (elementJSONObject.has("shooterLookType")) element.setShooterLookType(elementJSONObject.getString("shooterLookType"));
                if (elementJSONObject.has("shooterLookSensitivity")) element.setShooterLookSensitivity((float)elementJSONObject.getDouble("shooterLookSensitivity"));
                if (elementJSONObject.has("shooterJoystickSize")) element.setShooterJoystickSize((float)elementJSONObject.getDouble("shooterJoystickSize"));

                if (elementJSONObject.has("touchpadSensitivity")) element.setTouchpadSensitivity((float)elementJSONObject.getDouble("touchpadSensitivity"));
                if (elementJSONObject.has("touchpadScreenWide")) element.setTouchpadScreenWide(elementJSONObject.getBoolean("touchpadScreenWide"));
                if (elementJSONObject.has("touchpadMomentum")) element.setTouchpadMomentum((float)elementJSONObject.getDouble("touchpadMomentum"));
                if (elementJSONObject.has("touchpadAcceleration")) element.setTouchpadAcceleration((float)elementJSONObject.getDouble("touchpadAcceleration"));
                if (elementJSONObject.has("touchpadMinOutput")) element.setTouchpadMinOutput((float)elementJSONObject.getDouble("touchpadMinOutput"));
                // Gyro trigger (touchpad or stick). Accept the legacy touchpad-only keys too.
                if (elementJSONObject.has("gyroTrigger")) element.setGyroTrigger(elementJSONObject.getBoolean("gyroTrigger"));
                else if (elementJSONObject.has("touchpadGyroTrigger")) element.setGyroTrigger(elementJSONObject.getBoolean("touchpadGyroTrigger"));
                if (elementJSONObject.has("gyroTriggerToggle")) element.setGyroTriggerToggle(elementJSONObject.getBoolean("gyroTriggerToggle"));
                else if (elementJSONObject.has("touchpadGyroTriggerToggle")) element.setGyroTriggerToggle(elementJSONObject.getBoolean("touchpadGyroTriggerToggle"));

                boolean hasGamepadBinding = true;
                JSONArray bindingsJSONArray = elementJSONObject.getJSONArray("bindings");
                element.setBindingCount(Math.max(bindingsJSONArray.length(), 4));
                for (int j = 0; j < bindingsJSONArray.length(); j++) {
                    Binding binding = Binding.fromString(bindingsJSONArray.getString(j));
                    element.setBindingAt(j, binding);
                    if (!binding.isGamepad()) hasGamepadBinding = false;
                }

                if (!virtualGamepad && hasGamepadBinding) virtualGamepad = true;
                elements.add(element);
            }
            elementsLoaded = true;
            Log.d("ControlsProfile", "Loaded " + elements.size() + " elements for profile: " + name + " (virtualGamepad: " + virtualGamepad + ")");
        }
        catch (JSONException e) {
            Log.e("ControlsProfile", "Failed to load elements for profile: " + name + " (ID: " + id + ")", e);
            e.printStackTrace();
        }
    }

    /**
     * Load just the gyro settings from disk if they haven't been loaded yet. Lets the gyro
     * runtime and settings screen work without the touch controls ever being shown (element
     * loading is the other path that populates these).
     */
    public void ensureGyroSettingsLoaded() {
        if (gyroSettingsLoaded) return;
        File file = getProfileFile(context, id);
        if (!file.isFile()) {
            gyroSettingsLoaded = true; // new profile: in-memory defaults are authoritative
            return;
        }
        try {
            loadGyroSettings(new JSONObject(FileUtils.readString(file)));
        }
        catch (JSONException e) {
            Log.e("ControlsProfile", "Failed to load gyro settings for profile: " + name, e);
        }
    }

    private void loadGyroSettings(JSONObject profileJSONObject) {
        // Reset to defaults first so a reload of a profile without a gyro block clears stale state
        gyroEnabled = false;
        gyroOutputLeftStick = false;
        gyroSensitivity = 3.0f;
        gyroAxisX = GyroInput.Axis.YAW;
        gyroAxisY = GyroInput.Axis.PITCH;
        gyroInvertX = false;
        gyroInvertY = false;
        gyroAlwaysOn = false;
        gyroTriggers.clear();
        gyroSettingsLoaded = true;
        if (!profileJSONObject.has("gyro")) return;
        try {
            JSONObject gyro = profileJSONObject.getJSONObject("gyro");
            gyroEnabled = gyro.optBoolean("enabled", false);
            gyroOutputLeftStick = gyro.optBoolean("outputLeftStick", false);
            gyroSensitivity = (float) gyro.optDouble("sensitivity", 3.0);
            gyroInvertX = gyro.optBoolean("invertX", false);
            gyroInvertY = gyro.optBoolean("invertY", false);
            gyroAlwaysOn = gyro.optBoolean("alwaysOn", false);
            try { gyroAxisX = GyroInput.Axis.valueOf(gyro.optString("axisX", "YAW")); }
            catch (IllegalArgumentException e) { gyroAxisX = GyroInput.Axis.YAW; }
            try { gyroAxisY = GyroInput.Axis.valueOf(gyro.optString("axisY", "PITCH")); }
            catch (IllegalArgumentException e) { gyroAxisY = GyroInput.Axis.PITCH; }
            JSONArray triggersArray = gyro.optJSONArray("triggers");
            if (triggersArray != null) {
                for (int i = 0; i < triggersArray.length(); i++) {
                    JSONObject t = triggersArray.getJSONObject(i);
                    Binding button = Binding.fromString(t.optString("button", "NONE"));
                    if (button == Binding.NONE) continue;
                    gyroTriggers.add(new GyroTrigger(button, t.optBoolean("toggle", false)));
                }
            }
        }
        catch (JSONException e) {
            Log.w("ControlsProfile", "Failed to parse gyro settings for profile: " + name, e);
        }
    }
}
