# Android Build Booster

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%20Studio%20%7C%20IntelliJ%20IDEA-green?style=flat-square" />
  <img src="https://img.shields.io/badge/Version-0.4.3-blue?style=flat-square" />
  <img src="https://img.shields.io/badge/Build-241%2B-orange?style=flat-square" />
  <img src="https://img.shields.io/badge/License-MIT-lightgrey?style=flat-square" />
</p>

> **Stop guessing why your Android builds are slow.** Android Build Booster gives you a full suite of build analysis tools directly inside Android Studio — from Gradle config checks to per-task timelines to module impact analysis.

---

## 📥 Installation

### Option 1 — JetBrains Marketplace (Recommended)
1. Open Android Studio → **Settings → Plugins → Marketplace**
2. Search for **"Android Build Booster"**
3. Click **Install** → Restart IDE

### Option 2 — Download ZIP from GitHub Releases
1. Go to the [Releases page](https://github.com/shubham-0707/android-build-booster/releases)
2. Download `android-build-booster-0.4.3-signed.zip`
3. Android Studio → **Settings → Plugins → ⚙️ → Install Plugin from Disk…**
4. Select the downloaded ZIP → Restart IDE

---

## ✨ Features

### 🩺 1. Gradle Health Analyzer
Scans your root `gradle.properties` for missing performance-critical settings and shows color-coded severity levels.

| Severity | Color | What it checks |
|----------|-------|----------------|
| CRITICAL | 🔴 Red | `org.gradle.parallel`, JVM heap < 2 GB |
| HIGH | 🟠 Orange | `org.gradle.daemon`, `org.gradle.caching`, `kotlin.incremental`, `android.nonTransitiveRClass` |
| MEDIUM | 🟡 Yellow | `org.gradle.configureondemand`, `android.enableR8.fullMode` |
| OK | 🟢 Green | Already-correct settings |

**One-click fix** — click **⚡ Apply All Fixes** to automatically patch `gradle.properties` with all recommended settings.

---

### 📦 2. Per-Module Build File Scanner
Scans every module's `build.gradle` or `build.gradle.kts` for common Android build anti-patterns.

Detects:
- ❌ **kapt instead of KSP** — kapt is slow; migrate to KSP for 2–4× faster annotation processing
- ❌ **minSdk < 21** — raises dex overhead; setting minSdk ≥ 21 enables multidex by default
- ❌ **`includeCompileClasspath = true`** — deprecated and slow
- ❌ **ViewBinding + DataBinding enabled together** — DataBinding alone is sufficient and faster
- ❌ **Google Services plugin on non-app modules** — unnecessary and slows configuration
- ❌ **Redundant `multiDexEnabled true`** on minSdk ≥ 21 — already implied
- ❌ **Missing `namespace`** — required in newer AGP versions
- ❌ **`allprojects { repositories { } }`** — deprecated pattern; use `dependencyResolutionManagement`

Results are grouped by module with severity and actionable suggestions.

---

### ⏱ 3. Build Timeline Dashboard
Visual horizontal bar chart showing per-task durations from your last Gradle build.

- See exactly **which tasks are slowest** at a glance
- **Compare two builds** side-by-side to see regressions
- Click any task bar to see a **detail panel** with task path, duration, status, and module
- Auto-refreshes every time a new Gradle build completes
- Supports status labels: `SUCCESS`, `UP-TO-DATE`, `FROM-CACHE`, `SKIPPED`, `FAILED`

---

### 🌐 4. Module Impact Analyzer
Shows which modules will be recompiled based on your current VCS (Git) changes — **before you even run a build**.

- 🔴 **CHANGED** — module has directly modified files
- 🟠 **AFFECTED** — module depends (transitively) on a changed module
- ✅ **UNAFFECTED** — module is safe and won't recompile

**Estimated rebuild times** — uses your build history to estimate how long the recompile will take.

**Stash suggestions** — tells you *"If you stash file X, you save Y modules from recompiling (~Zs saved)"* — great for targeted builds.

---

### 💻 5. `abb` CLI Tool (Terminal)
A bash CLI tool that's **automatically installed** to `~/.android-build-booster/abb` when you first open a project with the plugin active.

Run from the Android Studio built-in terminal:

```bash
# Run all checks
~/.android-build-booster/abb

# Individual commands
~/.android-build-booster/abb health      # Gradle.properties analysis
~/.android-build-booster/abb impact      # VCS module impact
~/.android-build-booster/abb timeline    # Last build task durations
~/.android-build-booster/abb fix         # Auto-fix gradle.properties
```

**Add to PATH permanently** — add to your `~/.zshrc` or `~/.bashrc`:
```bash
source ~/.abb_profile
```
After that, just type `abb health` anywhere.

---

## 🖥️ How to Use

### Opening the Tool Window
- Bottom bar → click **"Android Build Booster"** tab
- **OR** → **Tools → Android Build Booster → Analyze Build Health**

### Tab-by-tab guide

#### 🩺 Health Tab
1. Click **🔍 Analyze** to scan `gradle.properties`
2. Review the color-coded issue list
3. Click **⚡ Apply All Fixes** to auto-patch all issues
4. Switch to **📦 Build Files** sub-tab → click **Scan** to analyze all module build files

#### ⏱ Timeline Tab
1. Run any Gradle build (`./gradlew assembleDebug` etc.)
2. The timeline auto-populates after the build finishes
3. Use the **Build selector** dropdown to switch between past builds
4. Use **Compare with** dropdown to show two builds side-by-side
5. Click any task bar to see details in the right panel

#### 🌐 Impact Tab
1. Make some code changes in your project (git working tree)
2. Click **🔄 Re-analyze**
3. See which modules are affected (red = changed, orange = transitively affected)
4. Check the **💡 Minimize Rebuild Scope** panel for stash suggestions
5. Click any module in the tree to see its changed files and dependencies

---

## 📁 Project Structure

```
android-build-booster/
├── abb.sh                                          # CLI tool source
├── build.gradle.kts                                # Gradle build script
├── settings.gradle.kts
└── src/main/
    ├── kotlin/com/shubham0707/androidbuildbooster/
    │   ├── model/
    │   │   ├── BuildIssue.kt                       # Health issue data model
    │   │   ├── ModuleBuildFileIssue.kt             # Per-module issue model
    │   │   ├── ModuleNode.kt                       # Module graph node
    │   │   └── TaskMetric.kt                       # Build task metric
    │   ├── services/
    │   │   ├── GradleAnalyzerService.kt            # gradle.properties analysis
    │   │   ├── ModuleBuildFileAnalyzer.kt          # Per-module build file scanner
    │   │   ├── ModuleGraphService.kt               # VCS impact + module graph
    │   │   ├── BuildMetricsStore.kt                # Stores build history
    │   │   └── BuildListenerService.kt             # Gradle build event listener
    │   ├── toolwindow/
    │   │   ├── BuildHealthToolWindowFactory.kt     # Tool window entry point
    │   │   ├── BuildHealthPanel.kt                 # Health + module scanner UI
    │   │   ├── BuildTimelinePanel.kt               # Timeline chart UI
    │   │   └── ModuleImpactPanel.kt                # Impact analyzer UI
    │   ├── startup/
    │   │   └── AbbStartupActivity.kt              # Extracts abb CLI on startup
    │   └── actions/
    │       ├── AnalyzeBuildAction.kt
    │       └── ApplyFixesAction.kt
    └── resources/
        ├── META-INF/plugin.xml                     # Plugin descriptor
        └── scripts/abb.sh                          # Bundled CLI script
```

---

## 🔧 Building from Source

### Prerequisites
- JDK 17+
- Internet connection (Gradle Wrapper auto-downloads Gradle 8.6)

### Build the plugin ZIP
```bash
git clone https://github.com/shubham-0707/android-build-booster.git
cd android-build-booster
./gradlew buildPlugin
# Output: build/distributions/android-build-booster-0.4.3.zip
```

### Run in a sandbox IDE (for development)
```bash
./gradlew runIde
```
This launches a fresh IntelliJ instance with the plugin pre-loaded. Open any Android project inside it to test.

### Publish a new version
```bash
source signing.env   # contains CERTIFICATE_CHAIN, PRIVATE_KEY, PUBLISH_TOKEN
./gradlew signPlugin publishPlugin
```

---

## 🗺️ Roadmap

- [ ] Kotlin compilation avoidance suggestions
- [ ] Dependency version conflict detector
- [ ] Configuration cache compatibility checker
- [ ] Gradle task graph visualizer
- [ ] Build regression alerts (notify when a task gets >20% slower)

---

## 🤝 Contributing

Pull requests are welcome! For major changes, please open an issue first.

1. Fork the repo
2. Create a branch: `git checkout -b feature/my-feature`
3. Commit your changes
4. Push and open a Pull Request

---

## 📄 License

MIT © [Shubham Singh](https://github.com/shubham-0707)
