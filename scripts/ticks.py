#!/usr/bin/env python3
"""Ticks each game test took, from the logs of one or more full runs.

    scripts/ticks.py build/check.log [more.log ...]

Prints one line per test, slowest pass first: the most ticks it took to pass over
the runs, its maxTicks, the ratio between them, and about twice the slowest pass
(rounded up, at least 20) as a suggested maxTicks. Tests that failed in any run
are listed at the end.
"""
import math
import re
import sys

LINE = re.compile(r"Game test (\S+) (passed|failed) at tick (-?\d+) of (\d+)")


def suggest(worst):
    limit = 2 * worst
    if limit <= 20:
        return 20
    step = max(10, 10 ** (len(str(limit)) - 2))
    return math.ceil(limit / step) * step


def main(logs):
    passed, limits, failed = {}, {}, set()
    for log in logs:
        with open(log, errors="replace") as f:
            for test, result, tick, limit in LINE.findall(f.read()):
                limits[test] = int(limit)
                if result == "passed":
                    passed.setdefault(test, []).append(int(tick))
                else:
                    failed.add(test)
    rows = sorted(passed.items(), key=lambda kv: -max(kv[1]))
    for test, ticks in rows:
        worst = max(ticks)
        print(f"{worst:6} of {limits[test]:5} ({limits[test] / max(worst, 1):5.1f}x) suggest {suggest(worst):5}  runs {len(ticks)}  {test}")
    for test in sorted(failed):
        print(f"failed in a run: {test}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
