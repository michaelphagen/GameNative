#!/usr/bin/env bash

# Paths required for building the Android project
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0" "ndk;27.3.13750724"

export JAVA_HOME=$HOME/jdk-17
export PATH=$JAVA_HOME/bin:$PATH

# Set JAVA_HOME if your default java is not compatible with Android Gradle Plugin.
# For example, to use Java 21 from your /usr/lib/jvm:
#export JAVA_HOME="/usr/lib/jvm/java-21-openjdk"

echo "Building GameNative (Debug, Modern)..."

# Run the gradle wrapper to assemble the debug APK
./gradlew assembleDebug --max-workers=1

echo ""
echo "Build complete! If successful, the APKs are located at:"
echo " - Modern: app/build/outputs/apk/modern/debug/app-modern-debug.apk"
echo " - Legacy: app/build/outputs/apk/legacy/debug/app-legacy-debug.apk"
