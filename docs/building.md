# Building Piatra

This guide covers building the Android app (debug and release), running its tests, making a
signed release APK, and building the board's firmware. All commands run from the repository
root unless a section says otherwise.

## Android app

### What you need

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | Gradle runs on it. A newer JRE without a compiler won't work. |
| Android SDK | Platform 37, Build-Tools 36 | Install with Android Studio or `sdkmanager`. |
| Android NDK + CMake | NDK 28, CMake 3.22.1 | Builds a small C file in `cactus/` (a CPU feature check). |
| A phone | **arm64-v8a**, Android 13 (API 33) or newer | The app installs from Android 8 (API 26), but pairing with the board needs Android 13. The Cactus engine exists only for 64-bit ARM. It also needs the ASIMDHP and ASIMDDP CPU features, which most phones from 2018 on have. |

Gradle itself (9.6) and every library are downloaded by the wrapper (`./gradlew`) on the
first build.

Point Gradle at the SDK with a `local.properties` file in the repository root (git-ignored;
Android Studio writes it for you):

```properties
sdk.dir=/path/to/Android/Sdk
```

If your default `java` isn't JDK 17, tell Gradle which one to use in
`~/.gradle/gradle.properties`:

```properties
org.gradle.java.home=/path/to/jdk-17
```

### Debug build

```sh
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug    # build and install on the phone connected over USB (adb)
```

The debug build is debuggable: `adb shell run-as dev.piatra …` can read its files and
database, and the Agent lab's adb hooks (`app/src/debug/`) are included. It runs slower than
the release build, which shows as choppy animations.

### Tests

```sh
./gradlew testDebugUnitTest   # unit tests (app/src/test/): plain Kotlin, no phone needed
```

Instrumented tests (`app/src/androidTest/`) run on the phone. Install both APKs, then start
the tests with `am instrument`:

```sh
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w dev.piatra.test/androidx.test.runner.AndroidJUnitRunner
```

**Don't use `./gradlew connectedDebugAndroidTest`.** It uninstalls the app when it finishes,
and that deletes its recordings, downloaded models and board pairing.

### Models

The app ships without models. On first start it offers to download them from Hugging Face
(Settings → Speech model): the speech model is about 400 MB and the agent model about 14 MB.
After that it needs no network. Details and licenses are in `THIRD_PARTY.md`.

## Release build

A release build isn't debuggable and is signed with Piatra's own release key. The key is
**never** stored in the repository.

### Create the release key (once)

```sh
mkdir -p ~/.config/piatra-release && chmod 700 ~/.config/piatra-release
keytool -genkeypair -keystore ~/.config/piatra-release/piatra-release.jks -storetype PKCS12 \
    -alias piatra -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Piatra"
```

`keytool` comes with the JDK. It asks for a password. Then write the key's details to a
properties file next to it, `~/.config/piatra-release/keystore.properties`:

```properties
storeFile=/home/you/.config/piatra-release/piatra-release.jks
storePassword=…
keyAlias=piatra
keyPassword=…
```

Tell Gradle where that file is, in `~/.gradle/gradle.properties`:

```properties
piatra.keystore=/home/you/.config/piatra-release/keystore.properties
```

You can instead put `keystore.properties` in the repository root. It's git-ignored there.

**Back up the `.jks` file and its password** somewhere safe, such as a password manager.
Android only installs an update when it's signed with the same key as the installed app.
Without the key, the next version can only be installed after an uninstall, and that deletes
the app's data.

### Make a release

1. Raise `versionCode` (by one) and `versionName` in `app/build.gradle.kts`, and add the
   changes to `CHANGELOG.md`.
2. Build:

   ```sh
   tools/release.sh
   ```

   It runs the unit tests, builds the release APK, refuses one signed with the debug key, and
   writes `dist/piatra-<version>.apk` and its `.sha256` checksum (`dist/` is git-ignored).
3. Install it:

   ```sh
   adb install -r dist/piatra-<version>.apk
   ```

   Or copy the APK to the phone and open it. Android asks once to allow installs from that
   app.
4. Tag the commit: `git tag v<version>`.

Without a release key (e.g. in a fresh clone), `./gradlew assembleRelease` still works but
signs with the debug key and prints a warning. `tools/release.sh` refuses such a build.

### Moving from a debug install to the release build

Debug and release builds are signed with different keys, so Android won't install one over
the other. To switch without losing anything:

1. In the installed app: Settings → Backup → export, and save the zip somewhere outside the
   app, such as Downloads.
2. Uninstall the app.
3. Install the release APK and open it.
4. Settings → Backup → "Restore from a backup", and pick the zip.
5. Do what the backup doesn't cover, because it belongs to the phone and not to your data:
   download the models again (Settings → Speech model), pair the board again (☰ → Device;
   tap the board 5 times if it refuses), and choose the Obsidian folder again if you use one.

## Firmware

Build and flashing details, dev mode and the laptop tools are in
[`firmware/README.md`](../firmware/README.md). In short:

| Tool | Version |
|---|---|
| ESP-IDF | v6.0.3, target `esp32s3` |
| Python 3 | For the laptop tools in `firmware/tools/` (`bleak`, `pyserial`) |

```sh
. ~/esp/esp-idf-v6.0.3/export.sh       # once per terminal: puts idf.py on PATH
cd firmware
echo 123456 > dev.secret                # your own 6-digit dev-mode code (git-ignored; required)
idf.py build                            # components (libopus, LittleFS) download on the first build
idf.py flash                            # first flash over USB; later updates go over Bluetooth
```

The board reports the firmware version from `FIRMWARE_VERSION` in `firmware/main/sync.c`.
Raise it with each firmware release.
