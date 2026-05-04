#!/usr/bin/env bash
# ============================================================
#  Android Build Booster — CLI Tool
#  Run from ANY Android project root:
#    bash /path/to/abb.sh [command]
#
#  Or install globally:
#    chmod +x /path/to/abb.sh
#    sudo ln -s /path/to/abb.sh /usr/local/bin/abb
#  Then just run:
#    abb             (full report)
#    abb health      (gradle.properties + build files check)
#    abb impact      (which modules your changes affect)
#    abb timeline    (parse last build output for slow tasks)
# ============================================================

set -euo pipefail

# ---- Colors ----
RED='\033[0;31m'
ORANGE='\033[0;33m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
RESET='\033[0m'

# ---- Config ----
PROJECT_ROOT="${ABB_PROJECT_ROOT:-$(pwd)}"
COMMAND="${1:-all}"

# ---- Helpers ----
hr() { printf "${DIM}%0.s─${RESET}" $(seq 1 70); echo; }
header() { echo; echo -e "${BOLD}${CYAN}$1${RESET}"; hr; }
ok()       { echo -e "  ${GREEN}✅  $1${RESET}"; }
critical() { echo -e "  ${RED}❌  [CRITICAL] $1${RESET}"; ISSUES_FOUND=$((ISSUES_FOUND+1)); }
high()     { echo -e "  ${RED}⚠️   [HIGH]     $1${RESET}"; ISSUES_FOUND=$((ISSUES_FOUND+1)); }
medium()   { echo -e "  ${YELLOW}ℹ️   [MEDIUM]   $1${RESET}"; ISSUES_FOUND=$((ISSUES_FOUND+1)); }
info()     { echo -e "  ${DIM}$1${RESET}"; }
suggest()  { echo -e "  ${CYAN}   ↳ Fix: $1${RESET}"; }

ISSUES_FOUND=0

# ============================================================
# SECTION 1: gradle.properties health check
# ============================================================
check_gradle_properties() {
    header "🩺  HEALTH — gradle.properties"

    local PROPS="$PROJECT_ROOT/gradle.properties"

    if [ ! -f "$PROPS" ]; then
        high "gradle.properties not found at $PROPS"
        suggest "Create $PROPS and add the optimizations below"
        return
    fi

    # --- parallel ---
    if grep -q "org.gradle.parallel=true" "$PROPS"; then
        ok "org.gradle.parallel=true"
    else
        critical "org.gradle.parallel is not enabled"
        suggest "Add: org.gradle.parallel=true"
    fi

    # --- daemon ---
    if grep -q "org.gradle.daemon=true" "$PROPS"; then
        ok "org.gradle.daemon=true"
    else
        high "org.gradle.daemon is not enabled"
        suggest "Add: org.gradle.daemon=true"
    fi

    # --- caching ---
    if grep -q "org.gradle.caching=true" "$PROPS"; then
        ok "org.gradle.caching=true"
    else
        high "org.gradle.caching is not enabled"
        suggest "Add: org.gradle.caching=true"
    fi

    # --- configure on demand ---
    if grep -q "org.gradle.configureondemand=true" "$PROPS"; then
        ok "org.gradle.configureondemand=true"
    else
        medium "org.gradle.configureondemand is not enabled"
        suggest "Add: org.gradle.configureondemand=true"
    fi

    # --- kotlin incremental ---
    if grep -q "kotlin.incremental=true" "$PROPS"; then
        ok "kotlin.incremental=true"
    else
        high "kotlin.incremental compilation is not enabled"
        suggest "Add: kotlin.incremental=true"
    fi

    # --- non-transitive R class ---
    if grep -q "android.nonTransitiveRClass=true" "$PROPS"; then
        ok "android.nonTransitiveRClass=true"
    else
        high "android.nonTransitiveRClass is not enabled (slower resource compilation)"
        suggest "Add: android.nonTransitiveRClass=true"
    fi

    # --- R8 full mode ---
    if grep -q "android.enableR8.fullMode=true" "$PROPS"; then
        ok "android.enableR8.fullMode=true"
    else
        medium "android.enableR8.fullMode is not enabled"
        suggest "Add: android.enableR8.fullMode=true"
    fi

    # --- JVM heap ---
    local HEAP_LINE
    HEAP_LINE=$(grep "org.gradle.jvmargs" "$PROPS" 2>/dev/null || echo "")
    if [ -z "$HEAP_LINE" ]; then
        critical "org.gradle.jvmargs not set — Gradle daemon using default heap (512m)"
        suggest "Add: org.gradle.jvmargs=-Xmx4g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8"
    else
        local XMX_VAL
        XMX_VAL=$(echo "$HEAP_LINE" | grep -oE '\-Xmx[0-9]+[gGmM]' | grep -oE '[0-9]+[gGmM]' || echo "")
        if [ -z "$XMX_VAL" ]; then
            medium "org.gradle.jvmargs set but no -Xmx value found"
            suggest "Add -Xmx4g to org.gradle.jvmargs"
        else
            local UNIT="${XMX_VAL: -1}"
            local NUM="${XMX_VAL%?}"
            local MB=$NUM
            [ "$UNIT" = "g" ] || [ "$UNIT" = "G" ] && MB=$((NUM * 1024))
            if [ "$MB" -lt 2048 ]; then
                critical "JVM heap is ${XMX_VAL} — too low (min recommended: 2g)"
                suggest "Update org.gradle.jvmargs to include -Xmx4g"
            else
                ok "JVM heap: ${XMX_VAL}"
            fi
        fi
    fi
}

# ============================================================
# SECTION 2: Per-module build.gradle(.kts) scan
# ============================================================
check_build_files() {
    header "📦  HEALTH — Per-Module Build File Scan"

    # Find all build.gradle / build.gradle.kts files
    local BUILD_FILES
    BUILD_FILES=$(find "$PROJECT_ROOT" \
        -name "build.gradle" -o -name "build.gradle.kts" \
        2>/dev/null | grep -v "/.gradle/" | grep -v "/build/" | sort)

    if [ -z "$BUILD_FILES" ]; then
        info "No build.gradle(.kts) files found."
        return
    fi

    local MODULE_COUNT=0
    local FILE_ISSUE_COUNT=0

    while IFS= read -r FILE; do
        local MODULE_NAME
        MODULE_NAME=$(dirname "$FILE" | sed "s|$PROJECT_ROOT||" | sed 's|/|:|g')
        [ -z "$MODULE_NAME" ] && MODULE_NAME=":root"

        local FILE_ISSUES=""

        local CONTENT
        CONTENT=$(cat "$FILE")

        # A) kapt instead of KSP
        if echo "$CONTENT" | grep -qE 'kotlin-kapt|id\("kotlin-kapt"\)|kapt\('; then
            FILE_ISSUES="${FILE_ISSUES}\n  ${RED}⚠️  [HIGH]   kapt used instead of KSP — 2x slower annotation processing${RESET}"
            FILE_ISSUES="${FILE_ISSUES}\n  ${CYAN}     ↳ Replace id(\"kotlin-kapt\") with id(\"com.google.devtools.ksp\") and kapt(...) with ksp(...)${RESET}"
            ISSUES_FOUND=$((ISSUES_FOUND+1)); FILE_ISSUE_COUNT=$((FILE_ISSUE_COUNT+1))
        fi

        # B) minSdk < 21
        local MIN_SDK
        MIN_SDK=$(echo "$CONTENT" | grep -oE 'minSdk[Version]*\s*[=\s]+[0-9]+' | grep -oE '[0-9]+$' | head -1 || echo "")
        if [ -n "$MIN_SDK" ] && [ "$MIN_SDK" -lt 21 ]; then
            FILE_ISSUES="${FILE_ISSUES}\n  ${RED}⚠️  [HIGH]   minSdk=$MIN_SDK disables MultiDex optimization (set to 21+)${RESET}"
            FILE_ISSUES="${FILE_ISSUES}\n  ${CYAN}     ↳ Set minSdk = 21 to enable native multidex and faster builds${RESET}"
            ISSUES_FOUND=$((ISSUES_FOUND+1)); FILE_ISSUE_COUNT=$((FILE_ISSUE_COUNT+1))
        fi

        # C) includeCompileClasspath
        if echo "$CONTENT" | grep -q "includeCompileClasspath\s*=\s*true"; then
            FILE_ISSUES="${FILE_ISSUES}\n  ${RED}❌  [CRITICAL] includeCompileClasspath=true scans entire classpath for annotation processors${RESET}"
            FILE_ISSUES="${FILE_ISSUES}\n  ${CYAN}     ↳ Remove this — it dramatically slows builds${RESET}"
            ISSUES_FOUND=$((ISSUES_FOUND+1)); FILE_ISSUE_COUNT=$((FILE_ISSUE_COUNT+1))
        fi

        # D) Both ViewBinding + DataBinding
        if echo "$CONTENT" | grep -q "viewBinding\s*=\s*true" && \
           echo "$CONTENT" | grep -q "dataBinding\s*=\s*true"; then
            FILE_ISSUES="${FILE_ISSUES}\n  ${YELLOW}ℹ️  [MEDIUM]  Both viewBinding and dataBinding enabled (redundant)${RESET}"
            FILE_ISSUES="${FILE_ISSUES}\n  ${CYAN}     ↳ Remove viewBinding = true — dataBinding includes it${RESET}"
            ISSUES_FOUND=$((ISSUES_FOUND+1)); FILE_ISSUE_COUNT=$((FILE_ISSUE_COUNT+1))
        fi

        # E) Google services on non-app module
        if echo "$CONTENT" | grep -qE 'com\.google\.gms\.google-services|com\.google\.firebase\.crashlytics'; then
            if [ "$MODULE_NAME" != ":" ] && [ "$MODULE_NAME" != ":root" ] && \
               ! echo "$MODULE_NAME" | grep -q ":app$"; then
                FILE_ISSUES="${FILE_ISSUES}\n  ${RED}⚠️  [HIGH]   Google Services/Firebase plugin applied to non-app module ($MODULE_NAME)${RESET}"
                FILE_ISSUES="${FILE_ISSUES}\n  ${CYAN}     ↳ Move these plugins to :app module only${RESET}"
                ISSUES_FOUND=$((ISSUES_FOUND+1)); FILE_ISSUE_COUNT=$((FILE_ISSUE_COUNT+1))
            fi
        fi

        # F) redundant multiDexEnabled with minSdk >= 21
        if echo "$CONTENT" | grep -q "multiDexEnabled\s*=\s*true"; then
            if [ -n "$MIN_SDK" ] && [ "$MIN_SDK" -ge 21 ]; then
                FILE_ISSUES="${FILE_ISSUES}\n  ${YELLOW}ℹ️  [MEDIUM]  multiDexEnabled=true is redundant when minSdk >= 21${RESET}"
                FILE_ISSUES="${FILE_ISSUES}\n  ${CYAN}     ↳ Remove multiDexEnabled = true${RESET}"
                ISSUES_FOUND=$((ISSUES_FOUND+1)); FILE_ISSUE_COUNT=$((FILE_ISSUE_COUNT+1))
            fi
        fi

        # G) Missing namespace
        if echo "$CONTENT" | grep -q "android\s*{" && \
           ! echo "$CONTENT" | grep -q "namespace"; then
            FILE_ISSUES="${FILE_ISSUES}\n  ${YELLOW}ℹ️  [MEDIUM]  Missing namespace declaration in android {} block${RESET}"
            FILE_ISSUES="${FILE_ISSUES}\n  ${CYAN}     ↳ Add: namespace = \"com.yourpackage.name\"${RESET}"
            ISSUES_FOUND=$((ISSUES_FOUND+1)); FILE_ISSUE_COUNT=$((FILE_ISSUE_COUNT+1))
        fi

        # H) allprojects repositories (deprecated)
        if echo "$CONTENT" | grep -q "allprojects\s*{" && \
           echo "$CONTENT" | grep -q "repositories\s*{"; then
            FILE_ISSUES="${FILE_ISSUES}\n  ${YELLOW}ℹ️  [MEDIUM]  Deprecated allprojects { repositories {} } block${RESET}"
            FILE_ISSUES="${FILE_ISSUES}\n  ${CYAN}     ↳ Migrate to dependencyResolutionManagement in settings.gradle.kts${RESET}"
            ISSUES_FOUND=$((ISSUES_FOUND+1)); FILE_ISSUE_COUNT=$((FILE_ISSUE_COUNT+1))
        fi

        # Print module header + issues if any
        if [ -n "$FILE_ISSUES" ]; then
            echo -e "\n  ${BOLD}${MODULE_NAME}${RESET}  ${DIM}($(basename "$FILE"))${RESET}"
            echo -e "$FILE_ISSUES"
            MODULE_COUNT=$((MODULE_COUNT+1))
        fi

    done <<< "$BUILD_FILES"

    if [ "$FILE_ISSUE_COUNT" -eq 0 ]; then
        ok "No build file issues found across all modules"
    else
        echo
        info "Found $FILE_ISSUE_COUNT issue(s) across $MODULE_COUNT module(s)"
    fi
}

# ============================================================
# SECTION 3: Module impact (VCS changes)
# ============================================================
check_module_impact() {
    header "🌐  MODULE IMPACT — VCS Change Analysis"

    # Check if we're in a git repo
    if ! git -C "$PROJECT_ROOT" rev-parse --git-dir > /dev/null 2>&1; then
        info "Not a git repository — skipping VCS impact analysis"
        return
    fi

    # Get changed files
    local CHANGED_FILES
    CHANGED_FILES=$(git -C "$PROJECT_ROOT" status --porcelain 2>/dev/null | \
        awk '{print $2}' | grep -v "^$" || echo "")

    if [ -z "$CHANGED_FILES" ]; then
        ok "No uncommitted changes — all modules unaffected"
        return
    fi

    # Find settings.gradle or settings.gradle.kts
    local SETTINGS_FILE=""
    [ -f "$PROJECT_ROOT/settings.gradle.kts" ] && SETTINGS_FILE="$PROJECT_ROOT/settings.gradle.kts"
    [ -f "$PROJECT_ROOT/settings.gradle" ]     && SETTINGS_FILE="$PROJECT_ROOT/settings.gradle"

    if [ -z "$SETTINGS_FILE" ]; then
        info "settings.gradle not found — cannot determine module boundaries"
        echo -e "\n  ${BOLD}Changed files:${RESET}"
        echo "$CHANGED_FILES" | while read -r f; do
            info "  • $f"
        done
        return
    fi

    # Parse included modules from settings file
    local MODULES
    MODULES=$(grep -oE 'include[[:space:]]*[\("'"'"']+[:\w/\-]+' "$SETTINGS_FILE" 2>/dev/null | \
        grep -oE '[:\w/\-]+$' | grep "^:" || echo "")

    echo -e "  ${BOLD}Changed files ($(echo "$CHANGED_FILES" | wc -l | tr -d ' ')):${RESET}"
    echo "$CHANGED_FILES" | while read -r f; do
        info "  • $f"
    done
    echo

    if [ -z "$MODULES" ]; then
        info "Could not parse modules from $SETTINGS_FILE"
        return
    fi

    # For each module determine if it's directly changed
    declare -A DIRECTLY_CHANGED
    declare -A MODULE_DIR

    while IFS= read -r MOD; do
        local MOD_DIR
        MOD_DIR="$PROJECT_ROOT/$(echo "$MOD" | sed 's|^:||' | sed 's|:|/|g')"
        MODULE_DIR["$MOD"]="$MOD_DIR"

        while IFS= read -r CF; do
            local ABS_CF="$PROJECT_ROOT/$CF"
            if [[ "$ABS_CF" == "$MOD_DIR"* ]]; then
                DIRECTLY_CHANGED["$MOD"]=1
                break
            fi
        done <<< "$CHANGED_FILES"
    done <<< "$MODULES"

    # Parse dependencies per module
    declare -A DEPS
    while IFS= read -r MOD; do
        local MOD_DIR="${MODULE_DIR[$MOD]}"
        local BUILD_FILE=""
        [ -f "$MOD_DIR/build.gradle.kts" ] && BUILD_FILE="$MOD_DIR/build.gradle.kts"
        [ -f "$MOD_DIR/build.gradle" ]     && BUILD_FILE="$MOD_DIR/build.gradle"
        if [ -n "$BUILD_FILE" ]; then
            local DEP_LIST
            DEP_LIST=$(grep -oE 'project\(["'"'"'](:[[:alnum:]/:_\-]+)["'"'"']\)' "$BUILD_FILE" 2>/dev/null | \
                grep -oE ':[[:alnum:]/:_\-]+' || echo "")
            DEPS["$MOD"]="$DEP_LIST"
        fi
    done <<< "$MODULES"

    # BFS: find transitively affected modules
    declare -A TRANSITIVELY_AFFECTED
    local CHANGED_QUEUE
    CHANGED_QUEUE=$(for K in "${!DIRECTLY_CHANGED[@]}"; do echo "$K"; done)

    while IFS= read -r MOD; do
        while IFS= read -r OTHER; do
            [ "$OTHER" = "$MOD" ] && continue
            if echo "${DEPS[$OTHER]:-}" | grep -qw "$MOD"; then
                if [ -z "${DIRECTLY_CHANGED[$OTHER]:-}" ] && \
                   [ -z "${TRANSITIVELY_AFFECTED[$OTHER]:-}" ]; then
                    TRANSITIVELY_AFFECTED["$OTHER"]=1
                fi
            fi
        done <<< "$MODULES"
    done <<< "$CHANGED_QUEUE"

    # Print results
    echo -e "  ${BOLD}Module rebuild impact:${RESET}\n"
    local AFFECTED_COUNT=0

    while IFS= read -r MOD; do
        if [ -n "${DIRECTLY_CHANGED[$MOD]:-}" ]; then
            echo -e "  ${RED}● CHANGED   ${BOLD}$MOD${RESET}"
            AFFECTED_COUNT=$((AFFECTED_COUNT+1))
        elif [ -n "${TRANSITIVELY_AFFECTED[$MOD]:-}" ]; then
            echo -e "  ${ORANGE}◐ AFFECTED  $MOD${RESET}"
            AFFECTED_COUNT=$((AFFECTED_COUNT+1))
        else
            echo -e "  ${GREEN}✓ clean     ${DIM}$MOD${RESET}"
        fi
    done <<< "$MODULES"

    echo
    if [ "$AFFECTED_COUNT" -eq 0 ]; then
        ok "No modules affected by current changes"
    else
        echo -e "  ${BOLD}$AFFECTED_COUNT module(s) will recompile${RESET}"
    fi
}

# ============================================================
# SECTION 4: Build timeline (parse last Gradle build scan)
# ============================================================
check_build_timeline() {
    header "⏱  BUILD TIMELINE — Slowest Tasks"

    # Try to find the last Gradle build output from daemon logs
    local DAEMON_LOG_DIR="$HOME/.gradle/daemon"
    local LAST_LOG=""

    if [ -d "$DAEMON_LOG_DIR" ]; then
        LAST_LOG=$(find "$DAEMON_LOG_DIR" -name "*.out.log" 2>/dev/null | \
            xargs ls -t 2>/dev/null | head -1 || echo "")
    fi

    # Also check for a local build-output file (many CI setups write one)
    local LOCAL_LOG=""
    [ -f "$PROJECT_ROOT/build-output.txt" ] && LOCAL_LOG="$PROJECT_ROOT/build-output.txt"
    [ -f "$PROJECT_ROOT/build.log" ]        && LOCAL_LOG="$PROJECT_ROOT/build.log"

    local LOG_FILE="${LOCAL_LOG:-$LAST_LOG}"

    if [ -z "$LOG_FILE" ] || [ ! -f "$LOG_FILE" ]; then
        info "No recent build log found."
        info "To enable: pipe your build output to a file, then re-run abb:"
        echo
        echo -e "  ${CYAN}./gradlew assembleDebug 2>&1 | tee build-output.txt && abb timeline${RESET}"
        echo
        info "Or run a build first, then: abb timeline"
        return
    fi

    info "Parsing: $LOG_FILE"
    echo

    # Parse task lines: "> Task :path:name [STATUS]"
    # Extract task lines and compute approximate ordering
    local TASK_LINES
    TASK_LINES=$(grep -E "^> Task :" "$LOG_FILE" 2>/dev/null || echo "")

    if [ -z "$TASK_LINES" ]; then
        info "No task output found in $LOG_FILE"
        info "Make sure your build is run with default output (not --quiet)"
        return
    fi

    local TASK_COUNT
    TASK_COUNT=$(echo "$TASK_LINES" | wc -l | tr -d ' ')
    echo -e "  ${BOLD}Total tasks: $TASK_COUNT${RESET}\n"

    # Count tasks by status
    local UP_TO_DATE FROM_CACHE SKIPPED EXECUTED
    UP_TO_DATE=$(echo "$TASK_LINES" | grep -c "UP-TO-DATE" || echo 0)
    FROM_CACHE=$(echo "$TASK_LINES" | grep -c "FROM-CACHE" || echo 0)
    SKIPPED=$(echo "$TASK_LINES" | grep -c "SKIPPED" || echo 0)
    EXECUTED=$((TASK_COUNT - UP_TO_DATE - FROM_CACHE - SKIPPED))

    echo -e "  ${GREEN}✅ Executed:    $EXECUTED${RESET}"
    echo -e "  ${DIM}⏭  UP-TO-DATE: $UP_TO_DATE${RESET}"
    echo -e "  ${CYAN}💾 FROM-CACHE: $FROM_CACHE${RESET}"
    echo -e "  ${DIM}⏩ Skipped:    $SKIPPED${RESET}"
    echo

    # Try to find task timings from build scan or --profile output
    local PROFILE_HTML
    PROFILE_HTML=$(find "$PROJECT_ROOT/build/reports/profile" -name "profile-*.html" 2>/dev/null | \
        xargs ls -t 2>/dev/null | head -1 || echo "")

    if [ -n "$PROFILE_HTML" ]; then
        echo -e "  ${BOLD}Slowest tasks (from Gradle profile report):${RESET}"
        # Parse task durations from profile HTML (simplified)
        grep -oE ':[a-zA-Z:_-]+.*[0-9]+\.[0-9]+ s' "$PROFILE_HTML" 2>/dev/null | \
            sort -t' ' -k2 -rn | head -10 | while read -r LINE; do
            echo -e "  ${YELLOW}  $LINE${RESET}"
        done
        echo
        echo -e "  ${CYAN}Full report: $PROFILE_HTML${RESET}"
    else
        # Show executed tasks from the log
        echo -e "  ${BOLD}Executed tasks (run with --profile for timing):${RESET}"
        echo "$TASK_LINES" | grep -v "UP-TO-DATE\|FROM-CACHE\|SKIPPED" | head -15 | \
        while IFS= read -r LINE; do
            local TASK_PATH
            TASK_PATH=$(echo "$LINE" | grep -oE ':[a-zA-Z:._-]+' | head -1)
            echo -e "  ${YELLOW}  $TASK_PATH${RESET}"
        done
        echo
        echo -e "  ${CYAN}💡 Tip: Run with --profile to get exact task timings:${RESET}"
        echo -e "  ${CYAN}   ./gradlew assembleDebug --profile 2>&1 | tee build-output.txt${RESET}"
        echo -e "  ${CYAN}   Then open: build/reports/profile/profile-*.html${RESET}"
    fi
}

# ============================================================
# SECTION 5: Quick-fix applier
# ============================================================
apply_fixes() {
    header "⚡  AUTO-FIX — Patching gradle.properties"

    local PROPS="$PROJECT_ROOT/gradle.properties"

    # Create if doesn't exist
    if [ ! -f "$PROPS" ]; then
        touch "$PROPS"
        echo -e "  ${CYAN}Created: $PROPS${RESET}"
    fi

    local CONTENT
    CONTENT=$(cat "$PROPS")
    local ADDED=0

    add_if_missing() {
        local KEY="$1"
        local VALUE="$2"
        local LABEL="$3"
        if ! grep -q "^$KEY" "$PROPS"; then
            echo "$KEY=$VALUE" >> "$PROPS"
            echo -e "  ${GREEN}+ Added: $KEY=$VALUE${RESET}"
            ADDED=$((ADDED+1))
        else
            echo -e "  ${DIM}  Skip:  $KEY (already set)${RESET}"
        fi
    }

    echo "" >> "$PROPS"
    echo "# ===== Android Build Booster auto-fixes =====" >> "$PROPS"

    add_if_missing "org.gradle.parallel"            "true"  "Parallel builds"
    add_if_missing "org.gradle.daemon"              "true"  "Gradle daemon"
    add_if_missing "org.gradle.caching"             "true"  "Build cache"
    add_if_missing "org.gradle.configureondemand"   "true"  "Configure on demand"
    add_if_missing "kotlin.incremental"             "true"  "Kotlin incremental"
    add_if_missing "android.nonTransitiveRClass"    "true"  "Non-transitive R class"
    add_if_missing "android.enableR8.fullMode"      "true"  "R8 full mode"

    # JVM args — check and set if missing or too low
    if ! grep -q "org.gradle.jvmargs" "$PROPS"; then
        echo 'org.gradle.jvmargs=-Xmx4g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8' >> "$PROPS"
        echo -e "  ${GREEN}+ Added: org.gradle.jvmargs=-Xmx4g${RESET}"
        ADDED=$((ADDED+1))
    else
        echo -e "  ${DIM}  Skip:  org.gradle.jvmargs (already set — verify heap is ≥2g)${RESET}"
    fi

    echo
    if [ "$ADDED" -eq 0 ]; then
        ok "gradle.properties already fully optimized — nothing to add"
    else
        ok "Applied $ADDED fix(es) to $PROPS"
        echo -e "  ${CYAN}  → Sync Gradle in Android Studio to take effect${RESET}"
    fi
}

# ============================================================
# MAIN
# ============================================================
echo
echo -e "${BOLD}${CYAN}╔══════════════════════════════════════════════════════════════════╗${RESET}"
echo -e "${BOLD}${CYAN}║          🚀  Android Build Booster  v0.3.0                      ║${RESET}"
echo -e "${BOLD}${CYAN}║          Analyzing: $(basename "$PROJECT_ROOT")$(printf '%*s' $((40 - ${#PROJECT_ROOT})) '')║${RESET}"
echo -e "${BOLD}${CYAN}╚══════════════════════════════════════════════════════════════════╝${RESET}"

case "$COMMAND" in
    health)
        check_gradle_properties
        check_build_files
        ;;
    impact)
        check_module_impact
        ;;
    timeline)
        check_build_timeline
        ;;
    fix)
        apply_fixes
        ;;
    all|*)
        check_gradle_properties
        check_build_files
        check_module_impact
        check_build_timeline
        ;;
esac

# ---- Summary ----
header "📊  SUMMARY"
if [ "$ISSUES_FOUND" -eq 0 ] && [ "$COMMAND" != "timeline" ] && [ "$COMMAND" != "impact" ]; then
    echo -e "  ${GREEN}${BOLD}✅  All checks passed — build is well optimized!${RESET}"
else
    if [ "$ISSUES_FOUND" -gt 0 ]; then
        echo -e "  ${YELLOW}${BOLD}Found $ISSUES_FOUND issue(s) total${RESET}"
        echo
        echo -e "  ${CYAN}Run with 'fix' to auto-patch gradle.properties:${RESET}"
        echo -e "  ${CYAN}  abb fix${RESET}"
    fi
fi

echo
echo -e "${DIM}Commands: abb health | abb impact | abb timeline | abb fix | abb (all)${RESET}"
echo
