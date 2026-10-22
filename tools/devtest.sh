#!/usr/bin/env bash
# devtest.sh -- the fast loop. An incremental build+test runner that stays
# under 5 seconds for the edit -> test cycle it is built for.
#
# Why this exists instead of plain Maven: `mvn test` re-runs javac over all
# ~450 sources plus every plugin check on each invocation (20 s+ even when
# nothing changed), and `useIncrementalCompilation` never takes effect in
# this tree. This script compiles only stale files with plain javac and runs
# JUnit directly, so a no-change run is file-scan + JVM startup only.
#
# Maven stays the canonical build (`./mvnw -o test` must stay green; CI and
# packaging use it). This is the iteration loop, with identical flags
# (-source/-target 1.8, UTF-8, same dependency set).
#
# Usage:
#   tools/devtest.sh villager.voice.SpectralAuditTest   # one test class
#   tools/devtest.sh SpectralAuditTest ClipIndexTest     # simple names work
#   tools/devtest.sh --all                              # every *Test class
#   tools/devtest.sh --full SpectralAuditTest           # force full recompile
#
# Resources resolve from src/main/resources (the code's dev fallback checks
# there first) plus the classloader, so no resource copying step is needed.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

DEVDIR="$ROOT/target/devloop"
CPFILE="$DEVDIR/cp.txt"
MAIN_SRC="$ROOT/src/main/java"
TEST_SRC="$ROOT/src/test/java"
# Relative output/resource dirs: javac.exe/java.exe do not understand the
# absolute /c/... form Git Bash uses, but relative paths work everywhere.
MAIN_OUT="target/classes"
TEST_OUT="target/test-classes"
RES_DIR="src/main/resources"

FULL=0
RUN_ALL=0
TESTS=()
for arg in "$@"; do
    case "$arg" in
        --full) FULL=1 ;;
        --all) RUN_ALL=1 ;;
        -h|--help)
            echo "usage: tools/devtest.sh [--full] [--all | TestClass ...]"
            exit 0 ;;
        *) TESTS+=("$arg") ;;
    esac
done
if [ "$RUN_ALL" -eq 0 ] && [ "${#TESTS[@]}" -eq 0 ]; then
    echo "usage: tools/devtest.sh [--full] [--all | TestClass ...]" >&2
    exit 2
fi

START_S=$(date +%s); START_NS=$(date +%N 2>/dev/null || echo 0)

# --- dependency classpath (refreshed only when pom.xml changes) -------------
if [ ! -f "$CPFILE" ] || [ "$ROOT/pom.xml" -nt "$CPFILE" ]; then
    mkdir -p "$DEVDIR"
    echo "[devtest] refreshing dependency classpath (pom.xml changed)..."
    if ! ./mvnw -o -q dependency:build-classpath \
            -Dmdep.outputFile="$CPFILE" -Dmdep.includeScope=test 2>/dev/null; then
        echo "[devtest] FAILED: could not resolve dependencies offline." >&2
        echo "  Run './mvnw -o test' once to populate the local repository." >&2
        exit 1
    fi
fi
DEPCP="$(cat "$CPFILE")"

# --- incremental compile -----------------------------------------------------
# Staleness is tracked with a per-source stamp file recording the mtime javac
# last saw. Mapping a .java to "the" .class does not work here: several
# sources emit differently-named top-level classes (BetaGenLayers.java emits
# BetaLayerIsland, BetaWorldGen.java emits more), so a same-name .class check
# would recompile them on every run and a sweep would delete their output.
compile_tree() {
    local src_dir="$1" out_dir="$2" cp="$3" stamps="$4" stale
    mkdir -p "$out_dir" "$stamps"
    stale="$DEVDIR/stale.$$.txt"
    : > "$stale"
    local rel stamp
    while IFS= read -r src; do
        rel="${src#$src_dir/}"
        stamp="$stamps/${rel%.java}.stamp"
        if [ ! -f "$stamp" ] || [ "$src" -nt "$stamp" ]; then
            # Relative paths: javac.exe mangles absolute /c/... paths.
            echo "${src#$ROOT/}" >> "$stale"
        fi
    done < <(find "$src_dir" -name '*.java')
    local count
    count=$(wc -l < "$stale" | tr -d ' ')
    if [ "$count" -gt 0 ]; then
        echo "[devtest] compiling $count stale file(s) -> $out_dir"
        # C1-only JIT + shared archive: javac is a short-lived process here, so
        # C2 warmup is pure overhead (Main.java: 2.6 s -> 1.3 s).
        javac -J-XX:TieredStopAtLevel=1 -J-Xshare:auto \
            -encoding UTF-8 -nowarn -source 1.8 -target 1.8 -g \
            -cp "$cp" -d "$out_dir" @"$stale" > "$DEVDIR/javac.log" 2>&1
        local javac_status=$?
        grep -v "bootstrap class path\|deprecat" "$DEVDIR/javac.log" || true
        if [ "$javac_status" -ne 0 ]; then
            rm -f "$stale"
            echo "[devtest] COMPILE FAILED" >&2
            exit 1
        fi
        # Stamp only what this successful javac actually compiled. The stale
        # list holds repo-relative paths ("src/main/java/..."); strip that
        # prefix so the stamp mirrors the source tree beneath the stamps dir.
        local src_prefix="${src_dir#$ROOT/}"
        while IFS= read -r src; do
            rel="${src#$src_prefix/}"
            stamp="$stamps/${rel%.java}.stamp"
            mkdir -p "$(dirname "$stamp")"
            touch -r "$src" "$stamp"
        done < "$stale"
    fi
    rm -f "$stale"
}

if [ "$FULL" -eq 1 ]; then
    echo "[devtest] --full: dropping all compiled classes and stamps"
    find "$MAIN_OUT" "$TEST_OUT" -name '*.class' -delete 2>/dev/null
    rm -rf "$DEVDIR/stamps"
fi

# The output dir itself is on the classpath so a stale file compiles against
# the already-built classes of everything else (sibling sources included).
# ';' is the Windows classpath separator these JVMs use; a ':' would silently
# glue the entry onto the last jar and break symbol resolution.
compile_tree "$MAIN_SRC" "$MAIN_OUT" "$DEPCP;$MAIN_OUT" "$DEVDIR/stamps/main"
compile_tree "$TEST_SRC" "$TEST_OUT" "$DEPCP;$MAIN_OUT" "$DEVDIR/stamps/test"

# --- resolve test classes ----------------------------------------------------
resolve_test() {
    local q="$1" found
    if [[ "$q" == *.* ]]; then
        echo "$q"; return 0
    fi
    found=$(cd "$TEST_OUT" && find . -name "$q.class" ! -name '*\$*' | head -1)
    if [ -z "$found" ]; then
        echo "[devtest] no compiled test matches '$q'" >&2
        return 1
    fi
    found="${found#./}"; found="${found%.class}"; echo "${found//\//.}"
}

RUN=()
if [ "$RUN_ALL" -eq 1 ]; then
    while IFS= read -r cls; do
        cls="${cls#$TEST_OUT/}"; cls="${cls%.class}"
        RUN+=("${cls//\//.}")
    done < <(find "$TEST_OUT" -name '*Test.class' ! -name '*\$*' | sort)
else
    for t in "${TESTS[@]}"; do
        r=$(resolve_test "$t") || exit 1
        RUN+=("$r")
    done
fi
if [ "${#RUN[@]}" -eq 0 ]; then echo "[devtest] no tests to run" >&2; exit 2; fi

# --- run ---------------------------------------------------------------------
END_S=$(date +%s); END_NS=$(date +%N 2>/dev/null || echo 0)
SETUP_MS=$(( (END_S - START_S) * 1000 + (10#$END_NS - 10#$START_NS) / 1000000 ))
echo "[devtest] ${#RUN[@]} test class(es), setup ${SETUP_MS}ms"

ALSOFT_DRIVERS=null java -Djava.awt.headless=true \
    -cp "$TEST_OUT;$MAIN_OUT;$RES_DIR;$DEPCP" \
    org.junit.runner.JUnitCore "${RUN[@]}"
STATUS=$?

END2_S=$(date +%s); END2_NS=$(date +%N 2>/dev/null || echo 0)
TOTAL_MS=$(( (END2_S - START_S) * 1000 + (10#$END2_NS - 10#$START_NS) / 1000000 ))
# The budget is for build + JVM start (the part that used to waste minutes);
# test runtime is whatever the tests themselves need (some sleep on purpose).
if [ "$RUN_ALL" -eq 1 ]; then
    # --all is the whole suite, not the edit->test cycle the budget targets.
    echo "[devtest] TOTAL ${TOTAL_MS}ms for the full suite"
elif [ "$SETUP_MS" -le 5000 ]; then
    echo "[devtest] build+start ${SETUP_MS}ms -- budget OK (<=5s); total ${TOTAL_MS}ms"
else
    echo "[devtest] build+start ${SETUP_MS}ms -- OVER the 5s budget; total ${TOTAL_MS}ms"
fi
exit $STATUS
