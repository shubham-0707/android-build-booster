# Android Build Booster

An IntelliJ IDEA plugin that analyses your Android project's `gradle.properties`
and `build.gradle(.kts)` files for missing Gradle/Android build optimizations,
lets you see severity-coloured issues in a dedicated Tool Window, and patches
`gradle.properties` with a single click.

---

## Features

| Severity | Colour | Checks |
|----------|--------|--------|
| CRITICAL | 🔴 Red | `org.gradle.parallel`, JVM heap < 2 GB |
| HIGH | 🟠 Orange | `org.gradle.daemon`, `org.gradle.caching`, `kotlin.incremental`, `android.nonTransitiveRClass` |
| MEDIUM | 🟡 Yellow | `org.gradle.configureondemand`, `android.enableR8.fullMode` |
| OK | 🟢 Green | Already-correct settings |

## Usage

1. Open an Android project in IntelliJ IDEA / Android Studio.
2. Go to **Tools → Android Build Booster → Analyze Build Health**
   _or_ open the **Android Build Booster** tool window at the bottom of the IDE.
3. Click **🔍 Analyze** to scan your build config.
4. Review the colour-coded list of issues.
5. Click **⚡ Apply All Fixes** to automatically append the missing settings to
   `gradle.properties`.
6. The list refreshes automatically after fixes are applied.

---

## Building

### Prerequisites

- JDK 17+
- Internet connection (Gradle Wrapper downloads Gradle 8.6 automatically)

### First-time setup — download the Gradle Wrapper JAR

The wrapper JAR (`gradle/wrapper/gradle-wrapper.jar`) is not stored in this
repository. Bootstrap it with:

```bash
# Using any locally installed Gradle (≥ 7.x):
gradle wrapper --gradle-version 8.6

# — OR — download manually:
mkdir -p gradle/wrapper
curl -Lo gradle/wrapper/gradle-wrapper.jar \
  https://github.com/gradle/gradle/raw/v8.6.0/gradle/wrapper/gradle-wrapper.jar
```

### Build the plugin

```bash
./gradlew buildPlugin
# Output: build/distributions/android-build-booster-0.1.0.zip
```

### Run in a sandbox IDE

```bash
./gradlew runIde
```

### Install manually

1. Build the plugin ZIP (see above).
2. In IntelliJ IDEA: **Settings → Plugins → ⚙️ → Install Plugin from Disk…**
3. Select `build/distributions/android-build-booster-0.1.0.zip`.
4. Restart IDE.

---

## Project Structure

```
android-build-booster/
├── build.gradle.kts                        # Gradle build script
├── settings.gradle.kts
├── gradle/wrapper/
│   ├── gradle-wrapper.jar                  # (bootstrap manually, see above)
│   └── gradle-wrapper.properties
└── src/main/
    ├── kotlin/com/yourname/androidbuildbooster/
    │   ├── model/
    │   │   └── BuildIssue.kt               # Data model + Severity enum
    │   ├── services/
    │   │   └── GradleAnalyzerService.kt    # Analysis + fix logic
    │   ├── toolwindow/
    │   │   ├── BuildHealthToolWindowFactory.kt
    │   │   └── BuildHealthPanel.kt         # Swing UI
    │   └── actions/
    │       ├── AnalyzeBuildAction.kt
    │       └── ApplyFixesAction.kt
    └── resources/META-INF/
        └── plugin.xml                      # Plugin descriptor
```

---

## Extending

To add a new check, open `GradleAnalyzerService.kt` and add a `checkBoolean(…)`
call (or write a custom check) inside `analyzeProject()`.  The UI picks up any
`List<BuildIssue>` automatically — no UI changes needed.

---

## License

MIT
