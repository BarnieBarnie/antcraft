#!/usr/bin/env python3
"""Monte Carlo of what lib.dig.ore's branches find of an ore at its lib.dig.best_y (#984, #985).

The vanilla 26.3 placed ore features (count per chunk, vein size, height) through solid stone, OreFeature ported from
the game's code: caves and the air discards left out, the bedrock floor gradient in. A branch is a tunnel two blocks
tall along +x whose walls and ceiling are mined as lib.mine.veins does. Counts ore blocks, not items.

    scripts/ore_sim.py [ore ...] [--runs N] [--branches R] [--seed S]

For each ore it prints the mean blocks a branch of 32 finds, how often it finds none, and for some counts wanted the
mean branches to find them, what lib.dig.BRANCHES models of that, and how often ore's limit
(lib.dig.rounds) and the old limit of 4 fall short. A count past --branches counts as --branches.
"""
import argparse
import math
import random


def trapezoid(lo, hi):
    """TrapezoidHeight with no plateau: lo plus two uniform halves."""
    half = (hi - lo) // 2
    return lambda: lo + random.randint(0, hi - lo - half) + random.randint(0, half)


def uniform(lo, hi):
    return lambda: random.randint(lo, hi)


# (vein size, mean count per chunk, height) of each placed feature near best_y; the feet of the branch.
ORES = {
    "diamond": (-59, [(4, 7, trapezoid(-144, 16)), (8, 4, trapezoid(-144, 16)), (12, 1 / 9, trapezoid(-144, 16)),
                      (8, 2, uniform(-64, -4))]),
    "raw_iron": (16, [(9, 10, trapezoid(-24, 56)), (4, 10, uniform(-64, 72))]),  # ore_iron_upper is 80 and up
    "raw_gold": (-16, [(9, 4, trapezoid(-64, 32)), (9, 0.5, uniform(-64, -48))]),  # not the badlands' extra gold
    "redstone": (-59, [(8, 4, uniform(-64, 15)), (8, 8, trapezoid(-96, -32))]),
    "lapis_lazuli": (0, [(7, 2, trapezoid(-32, 32)), (7, 4, uniform(-64, 64))]),
}
LENGTH = 32

BED = {}


def bedrock(x, y, z):
    if y < -64:
        return True
    if y >= -59:
        return False
    k = (x, y, z)
    if k not in BED:
        BED[k] = random.random() < (-59 - y) / 5
    return BED[k]


def place(ox, oy, oz, size, ores):
    a = random.random() * math.pi
    spread = size / 8
    x0 = ox + math.sin(a) * spread; x1 = ox - math.sin(a) * spread
    z0 = oz + math.cos(a) * spread; z1 = oz - math.cos(a) * spread
    y0 = oy + random.randint(0, 2) - 2; y1 = oy + random.randint(0, 2) - 2
    s = []
    for i in range(size):
        t = i / size
        r = random.random() * size / 16
        d = ((math.sin(math.pi * t) + 1) * r + 1) / 2
        s.append([x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t, d])
    for i in range(size - 1):
        if s[i][3] <= 0: continue
        for j in range(i + 1, size):
            if s[j][3] <= 0: continue
            dx, dy, dz = s[i][0] - s[j][0], s[i][1] - s[j][1], s[i][2] - s[j][2]
            dr = s[i][3] - s[j][3]
            if dr * dr > dx * dx + dy * dy + dz * dz:
                if dr > 0: s[j][3] = -1
                else: s[i][3] = -1
    for x, y, z, d in s:
        if d < 0: continue
        for xx in range(math.floor(x - d), math.floor(x + d) + 1):
            ex = (xx + 0.5 - x) / d
            if ex * ex >= 1: continue
            for yy in range(math.floor(y - d), math.floor(y + d) + 1):
                ey = (yy + 0.5 - y) / d
                if ex * ex + ey * ey >= 1: continue
                for zz in range(math.floor(z - d), math.floor(z + d) + 1):
                    ez = (zz + 0.5 - z) / d
                    if ex * ex + ey * ey + ez * ez < 1 and not bedrock(xx, yy, zz):
                        ores.add((xx, yy, zz))


def poisson(lam):
    if lam > 50:
        return poisson(50) + poisson(lam - 50)  # exp(-lam) underflows past some 700
    l, k, p = math.exp(-lam), 0, 1
    while True:
        p *= random.random()
        if p <= l: return k
        k += 1


def vein(p, ores, cap=32):
    seen, todo = set(), [p]
    while todo and len(seen) < cap:
        q = todo.pop(0)
        if q in seen: continue
        seen.add(q)
        for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            n = (q[0] + d[0], q[1] + d[1], q[2] + d[2])
            if n in ores and n not in seen: todo.append(n)
    return seen


def run(feet, features, branches):
    """The ore blocks found after each of branches branches of LENGTH."""
    ores = set()
    BED.clear()
    xs, zs = (-16, branches * LENGTH + 16), (-16, 16)
    area = (xs[1] - xs[0]) * (zs[1] - zs[0])
    for size, count, height in features:
        for _ in range(poisson(count * area / 256)):
            y = height()
            if abs(y - feet) > 12: continue
            place(random.uniform(*xs), y, random.uniform(*zs), size, ores)
    found, got = [], set()
    for i in range(1, branches * LENGTH + 1):
        for c in ((i, feet, 0), (i, feet + 1, 0)):
            for p in (c, (c[0], c[1], c[2] - 1), (c[0], c[1], c[2] + 1), (c[0] + 1, c[1], c[2]), (c[0], c[1] + 1, c[2])):
                if p in ores and p not in got:
                    got |= vein(p, ores)
        if i % LENGTH == 0:
            found.append(len(got))
    return found


COUNTS = (1, 2, 3, 6, 12)


def main():
    a = argparse.ArgumentParser()
    a.add_argument("ores", nargs="*", default=list(ORES))
    a.add_argument("--runs", type=int, default=2000)
    a.add_argument("--branches", type=int, default=64)
    a.add_argument("--seed", type=int, default=1)
    args = a.parse_args()
    random.seed(args.seed)
    for name in args.ores:
        feet, features = ORES[name]
        runs = [run(feet, features, args.branches) for _ in range(args.runs)]
        need = {n: [next((k + 1 for k, v in enumerate(f) if v >= n), args.branches) for f in runs] for n in COUNTS}
        mean = {n: sum(b) / len(b) for n, b in need.items()}
        # lib.dig.BRANCHES: the branches to the first block and those for each block more.
        first, more = mean[1], (mean[12] - mean[1]) / 11
        print(f"{name} at {feet}: {sum(f[0] for f in runs) / len(runs):.2f} blocks a branch, none in"
              f" {sum(f[0] == 0 for f in runs) / len(runs):.0%}; BRANCHES ({first:.1f}, {more:.2f})")
        for n in COUNTS:
            limit = max(4, int(2 * (first + (n - 1) * more)) + 1)  # lib.dig.rounds
            print(f"  {n:2} blocks: {mean[n]:.2f} branches (model {first + (n - 1) * more:.2f}), ore's limit {limit}"
                  f" falls short {sum(b > limit for b in need[n]) / len(runs):.1%}, the old 4 {sum(b > 4 for b in need[n]) / len(runs):.0%};"
                  f" {sum(b >= args.branches for b in need[n])} of {len(runs)} past {args.branches}")


if __name__ == "__main__":
    main()
