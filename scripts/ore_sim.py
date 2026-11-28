#!/usr/bin/env python3
"""Monte Carlo of what lib.dig.ore's branches find of an ore at its lib.dig.best_y (#984, #985, #989).

The vanilla 26.3 placed ore features (count per chunk, vein size, height, air discard) through solid stone, OreFeature
ported from the game's code, the bedrock floor gradient in. With --air, that share of the stone around the branch is
caves, as balls of radius 2 to 4 (more surface than the game's caves, so an upper bound of the discard): no ore in
them, and a feature's air discard next to them. A branch is a tunnel two blocks tall along +x whose walls and ceiling
are mined as lib.mine.veins does. Counts ore blocks, not items.

    scripts/ore_sim.py [ore ...] [--runs N] [--branches R] [--seed S] [--air A]

For each ore it prints the mean blocks a branch of 32 finds, how often it finds none, lib.dig.BRANCHES (the mean
branches for 1, 4, 8, 16 and 32 blocks), and for more counts the mean branches, what BRANCHES interpolates, and how
often ore's limit (lib.dig.rounds), so the second trip of lib.get, and the old limit of 4 fall short. A count past
--branches counts as --branches. lib.dig.BRANCHES is from --runs 1000 --branches 96.
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


# (vein size, mean count per chunk, height, chance a block next to air is left out) of each placed feature near
# best_y; the feet of the branch.
ORES = {
    "diamond": (-59, [(4, 7, trapezoid(-144, 16), 0.5), (8, 4, trapezoid(-144, 16), 1),
                      (12, 1 / 9, trapezoid(-144, 16), 0.7), (8, 2, uniform(-64, -4), 0.5)]),
    "raw_iron": (16, [(9, 10, trapezoid(-24, 56), 0), (4, 10, uniform(-64, 72), 0)]),  # ore_iron_upper is 80 and up
    "raw_gold": (-16, [(9, 4, trapezoid(-64, 32), 0.5), (9, 0.5, uniform(-64, -48), 0.5)]),  # not the badlands' extra
    "redstone": (-59, [(8, 4, uniform(-64, 15), 0), (8, 8, trapezoid(-96, -32), 0)]),
    "lapis_lazuli": (0, [(7, 2, trapezoid(-32, 32), 0), (7, 4, uniform(-64, 64), 1)]),
    "coal": (96, [(17, 30, uniform(136, 320), 0), (17, 20, trapezoid(0, 192), 0.5)]),  # ore_coal_upper and _lower
    "raw_copper": (48, [(10, 16, trapezoid(-16, 112), 0)]),  # not ore_copper_large, dripstone caves only
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


AIR = set()
SIDES = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))


def caves(xs, ys, zs, share):
    """Fills about share of the box with air, as balls of radius 2 to 4: more surface than the game's caves for the
    same air, so an upper bound of what the air discard takes."""
    AIR.clear()
    volume = (xs[1] - xs[0]) * (ys[1] - ys[0]) * (zs[1] - zs[0])
    for _ in range(poisson(share * volume / (4 / 3 * math.pi * 27))):
        cx, cy, cz, r = random.uniform(*xs), random.uniform(*ys), random.uniform(*zs), random.uniform(2, 4)
        for x in range(math.floor(cx - r), math.floor(cx + r) + 1):
            for y in range(math.floor(cy - r), math.floor(cy + r) + 1):
                for z in range(math.floor(cz - r), math.floor(cz + r) + 1):
                    if (x - cx) ** 2 + (y - cy) ** 2 + (z - cz) ** 2 < r * r:
                        AIR.add((x, y, z))


def stone(p, discard):
    """Whether OreFeature puts ore at p: not in air, nor next to air for the discard chance."""
    if p in AIR:
        return False
    if discard > 0 and any((p[0] + d[0], p[1] + d[1], p[2] + d[2]) in AIR for d in SIDES):
        return discard < 1 and random.random() >= discard
    return True


def place(ox, oy, oz, size, ores, discard=0):
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
                    if ex * ex + ey * ey + ez * ez < 1 and not bedrock(xx, yy, zz) and stone((xx, yy, zz), discard):
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
        for d in SIDES:
            n = (q[0] + d[0], q[1] + d[1], q[2] + d[2])
            if n in ores and n not in seen: todo.append(n)
    return seen


def run(feet, features, branches, air=0):
    """The ore blocks found after each of branches branches of LENGTH, air the share of caves around them."""
    ores = set()
    BED.clear()
    xs, zs = (-16, branches * LENGTH + 16), (-16, 16)
    caves(xs, (feet - 16, feet + 18), zs, air)
    area = (xs[1] - xs[0]) * (zs[1] - zs[0])
    for size, count, height, discard in features:
        for _ in range(poisson(count * area / 256)):
            y = height()
            if abs(y - feet) > 12: continue
            place(random.uniform(*xs), y, random.uniform(*zs), size, ores, discard)
    found, got = [], set()
    for i in range(1, branches * LENGTH + 1):
        for c in ((i, feet, 0), (i, feet + 1, 0)):
            for p in (c, (c[0], c[1], c[2] - 1), (c[0], c[1], c[2] + 1), (c[0] + 1, c[1], c[2]), (c[0], c[1] + 1, c[2])):
                if p in ores and p not in got:
                    got |= vein(p, ores)
        if i % LENGTH == 0:
            found.append(len(got))
    return found


COUNTS = (1, 2, 3, 4, 6, 8, 12, 16, 24, 32)
# The block counts lib.dig.BRANCHES lists; those in between are interpolated.
POINTS = (1, 4, 8, 16, 32)


def model(table, n):
    """lib.dig.branches: table interpolated at n, past its last point along its last stretch."""
    for (n0, b0), (n1, b1) in zip(table, table[1:]):
        if n <= n1 or n1 == table[-1][0]:
            return b0 + (b1 - b0) * (max(n, n0) - n0) / (n1 - n0)


def short(need, limit):
    """How often limit branches fall short, so lib.get sends the bot down a second time."""
    return sum(b > limit for b in need) / len(need)


def main():
    a = argparse.ArgumentParser()
    a.add_argument("ores", nargs="*", default=list(ORES))
    a.add_argument("--runs", type=int, default=2000)
    a.add_argument("--branches", type=int, default=64)
    a.add_argument("--seed", type=int, default=1)
    a.add_argument("--air", type=float, default=0, help="the share of caves, as balls of radius 2 to 4")
    args = a.parse_args()
    random.seed(args.seed)
    for name in args.ores:
        feet, features = ORES[name]
        runs = [run(feet, features, args.branches, args.air) for _ in range(args.runs)]
        need = {n: [next((k + 1 for k, v in enumerate(f) if v >= n), args.branches) for f in runs] for n in COUNTS}
        mean = {n: sum(b) / len(b) for n, b in need.items()}
        table = tuple((n, round(mean[n], 1)) for n in POINTS)  # lib.dig.BRANCHES
        print(f"{name} at {feet}: {sum(f[0] for f in runs) / len(runs):.2f} blocks a branch, none in"
              f" {sum(f[0] == 0 for f in runs) / len(runs):.0%}; BRANCHES {table}")
        for n in COUNTS:
            m = model(table, n)
            limit = max(4, int(2 * m) + 1)  # lib.dig.rounds
            print(f"  {n:2} blocks: {mean[n]:.2f} branches (model {m:.2f}, {m / mean[n] - 1:+.0%}), ore's limit {limit}"
                  f" falls short {short(need[n], limit):.1%}, the old 4 {short(need[n], 4):.0%};"
                  f" {sum(b >= args.branches for b in need[n])} of {len(runs)} past {args.branches}")


if __name__ == "__main__":
    main()
