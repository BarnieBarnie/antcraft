#!/usr/bin/env bash
# Runs every game test twice on origin/main and files a GitHub issue for each
# one that fails, so a fix starts from the issue queue with a fresh context
# rather than inside a worker's loop.
#
#   scripts/nightly.sh            # run and file issues
#   scripts/nightly.sh --dry-run  # run and print what it would file
#
# A test failing in both runs is filed as "Game test fails", one failing in a
# single run as "Flaky game test". An open issue with the test id in its title
# gets a comment instead of a new issue. The runs use their own worktree in
# ~/.cache/antcraft-nightly, so they never touch the main checkout or agents'
# worktrees. scripts/nightly.timer and nightly.service run it at 03:00.

set -u
repo=$(cd "$(dirname "$0")/.." && pwd)
dir=${ANTCRAFT_NIGHTLY_DIR:-$HOME/.cache/antcraft-nightly}
dry=false
[ "${1:-}" = --dry-run ] && dry=true

git -C "$repo" fetch -q origin main || exit 1
if [ -d "$dir/.git" ] || [ -f "$dir/.git" ]; then
	git -C "$dir" checkout -q --detach origin/main || exit 1
else
	git -C "$repo" worktree add -q --detach "$dir" origin/main || exit 1
fi
sha=$(git -C "$dir" rev-parse --short HEAD)
mkdir -p "$dir/build/nightly"

# check.sh prints failing game tests as "game <id>: <message>".
fails=""
for run in 1 2; do
	out=$("$dir/scripts/check.sh" runGameTest)
	status=$?
	cp "$dir/build/check.log" "$dir/build/nightly/run$run.log"
	echo "run $run: $(echo "$out" | head -1)"
	runfails=$(echo "$out" | sed -n 's/^game \(antcraft-gametest:[a-z0-9_]*\): \(.*\)/\1\t\2/p')
	if [ $status -ne 0 ] && [ -z "$runfails" ]; then
		# Did not get as far as the tests, such as a compile error.
		runfails="build"$'\t'"$(echo "$out" | head -20)"
	fi
	[ -n "$runfails" ] && fails+="$runfails"$'\n'
done

[ -z "$fails" ] && { echo "nightly $sha: all game tests passed twice"; exit 0; }

echo "$fails" | cut -f1 | grep . | sort | uniq -c | while read -r count id; do
	message=$(echo "$fails" | grep -F "$id"$'\t' | head -1 | cut -f2-)
	if [ "$count" -ge 2 ]; then title="Game test fails: $id"; else title="Flaky game test: $id"; fi
	[ "$id" = build ] && title="Nightly build fails on main"
	excerpt=$(grep -h -F "${id#antcraft-gametest:}" "$dir"/build/nightly/run*.log | cut -c1-300 | head -30)
	body="Failed in $count of 2 nightly full runs of \`runGameTest\` on $sha.

\`\`\`
$message
\`\`\`

Log lines naming the test:

\`\`\`
$excerpt
\`\`\`

Run it alone with \`scripts/check.sh runGameTest -PgameTest='$id'\`. If it passes alone, other tests in the batch are interfering, so run the full suite to confirm a fix."

	existing=$(gh issue list -R BarnieBarnie/antcraft --state open --search "\"$id\" in:title" --json number -q '.[0].number')
	if $dry; then
		echo "would ${existing:+comment on #$existing for}${existing:-file} \"$title\""
	elif [ -n "$existing" ]; then
		gh issue comment -R BarnieBarnie/antcraft "$existing" -b "Failed again in $count of 2 nightly runs on $sha: $message"
	else
		gh issue create -R BarnieBarnie/antcraft -t "$title" -l bug -b "$body"
	fi
done
