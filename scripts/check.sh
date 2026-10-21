#!/usr/bin/env bash
# Runs Gradle quietly and prints only the result and what failed.
#
#   scripts/check.sh                       # ./gradlew build
#   scripts/check.sh test                  # unit tests only
#   scripts/check.sh testIsolated          # unit tests, one JVM per class (CI runs this)
#   scripts/check.sh runGameTest -PgameTest='antcraft-gametest:miner_game_test*'
#
# Gradle runs with --continue so one run reports every failing suite. The full
# output goes to build/check.log; the exit code is Gradle's.

cd "$(dirname "$0")/.." || exit 1
log=build/check.log
mkdir -p build
start=build/.check-start
touch "$start"

has_task=false
for arg in "$@"; do
	case $arg in -*) ;; *) has_task=true ;; esac
done
$has_task || set -- build "$@"

./gradlew --continue "$@" >"$log" 2>&1
status=$?

# Server log lines look like "[12:00:00] [Server thread/INFO] (Minecraft) text".
game=$(sed -n 's/^\[[0-9:]*\] \[[^]]*\] ([^)]*) //p' "$log" |
	sed -n '/GAME TESTS COMPLETE/,/^=====/p' | grep -v '^=*$')

reports=$(find build/test-results/test build/test-results/testIsolated -name '*.xml' -newer "$start" 2>/dev/null)
unit=""
if [ -n "$reports" ]; then
	unit=$(cat $reports | sed -n 's/^<testsuite .* tests="\([0-9]*\)" skipped="[0-9]*" failures="\([0-9]*\)" errors="\([0-9]*\)".*/\1 \2 \3/p' |
		awk '{ t += $1; f += $2 + $3 } END { if (f) print t " unit tests, " f " failed"; else print t " unit tests passed" }')
elif grep -q '^> Task :test UP-TO-DATE' "$log"; then
	unit="unit tests up to date"
fi

games=$(echo "$game" | sed -n 's/^=* *\([0-9]*\) GAME TESTS COMPLETE.*/\1 game tests/p')
if [ -n "$games" ]; then
	gamefailed=$(echo "$game" | sed -n 's/^\([0-9]*\) required tests failed.*/, \1 failed/p')
	games="$games${gamefailed:- passed}"
fi
summary="$unit${unit:+${games:+; }}$games"
if [ $status -eq 0 ]; then result=PASS; else result=FAIL; fi
echo "$result $*${summary:+: $summary}"

found=false

# Compile errors, as file:line: message.
errors=$(grep -E '^/[^ ]+\.java:[0-9]+: error: ' "$log" | sed "s|^$PWD/||; s/: error:/:/")
if [ -n "$errors" ]; then echo "$errors"; found=true; fi

# Failing unit tests: class.method, message, first frame in our package.
if [ -n "$reports" ]; then
	failures=$(awk '
		function unxml(s) {
			gsub(/&lt;/, "<", s); gsub(/&gt;/, ">", s); gsub(/&quot;/, "\"", s)
			gsub(/&#10;/, " ", s); gsub(/&apos;/, "\047", s); gsub(/&amp;/, "\\&", s)
			return s
		}
		/<testcase / {
			match($0, /name="[^"]*"/); name = substr($0, RSTART + 6, RLENGTH - 7)
			match($0, /classname="[^"]*"/); cls = substr($0, RSTART + 11, RLENGTH - 12)
			sub(/.*\./, "", cls)
		}
		/<(failure|error) message=/ {
			match($0, /message="[^"]*"/); msg = unxml(substr($0, RSTART + 9, RLENGTH - 10))
			if (length(msg) > 300) msg = substr(msg, 1, 300) "..."
			print "unit " cls "." name ": " msg; frame = 1; next
		}
		frame && /at .*io\.github\.barniebarnie/ {
			sub(/.*at (knot\/\/)?io\.github\.barniebarnie\.antcraft\./, "  at "); print; frame = 0
		}
		/<\/(failure|error)>/ { frame = 0 }
	' $reports)
	if [ -n "$failures" ]; then echo "$failures"; found=true; fi
fi

# Failing game tests: the "   - id: message" lines under the game test summary.
gamefail=$(echo "$game" | sed -n 's/^ *- \(.*\)/game \1/p')
if [ -n "$gamefail" ]; then echo "$gamefail"; found=true; fi

if [ $status -ne 0 ] && ! $found; then
	tail -n 20 "$log"
fi
[ $status -ne 0 ] && echo "Full log: $log"
rm -f "$start"
exit $status
