# Guard home

A job that guards the bot's home: it fights the hostile mobs that come near, and when it is hurt it walks home, eats and heals before it fights again. The mod bundles it as `jobs/guard.py`; start it with `/antcraft bot run jobs.guard`, or `jobs.guard 24` to fight up to 24 blocks from home (16 by default). It runs until it is stopped. Give it a sword and some food, or keep food in the colony's chests.

```python
import lib.food
from lib.home import go_home
from lib.tools import hold

# The mobs it fights: the game's monsters (monsters(), modded ones too, #722) but the neutral ones (Entity.neutral, the
# bot's own list, #772): endermen, zombified piglins and piglins, which a blow turns on the bot with their kin, the
# warden and the bosses.
MOBS = monsters()
# Health under which it retreats, also from a fight, above the flee reflex's 6, and the health it heals to before it
# fights again.
RETREAT = 12
HEALED = 18
WAIT = 20  # ticks between looks for mobs, and between looks at its health while it heals
SKIP = 600  # ticks it leaves a mob it could not get to, nor shoot, alone (#745)
skipped = {}  # the id of each such mob: the game time it tries it again


def heal():
    """Walks home and eats the best food it carries (lib.food.best; lib.food.upkeep gets some at 14 food or under) while it
    is hurt and not full, until it has HEALED health or can heal no more: under 18 food with nothing left to eat. Gives
    whether it has HEALED health. ponytail: a mob that follows it home is fought back by the bot's own goals once it hurts
    the bot, not by the script."""
    go_home()
    while health() < HEALED:
        f = lib.food.best()
        if food() < 20 and f is not None:
            eat(f[0])
        elif food() < 18:
            return False
        else:
            wait(WAIT)  # 18 food or more heals it, a point every 80 ticks, every 10 with saturation
    return True


def can_shoot():
    """Whether it carries a bow or crossbow and arrows, all shoot needs (#756)."""
    return has(["bow", "crossbow"]) and has("#minecraft:arrows")


def hovers(mob):
    """Whether mob hovers with two blocks of air under it, such as a blaze: shot without planning a way to it first, a
    search that runs through all its nodes to find none, for every guard and every flyer (#786)."""
    below = block(mob.pos.down())
    lower = block(mob.pos.down(2))
    return below is not None and below.is_air and lower is not None and lower.is_air


def mobs(radius):
    """The mobs of MOBS within radius of home, nearest the bot first, but neutral ones and those it skips."""
    now = game_time()
    return [m for m in entities(MOBS, 64)
            if not m.neutral and m.pos.distance(home()) <= radius and skipped.get(m.id, 0) <= now]


def run(radius=16):
    """Guards home until stopped."""
    while True:
        least = RETREAT
        if health() < RETREAT and not heal():  # before upkeep, so it does not eat where it fought
            least = None  # can heal no more: fights on as it is
        lib.food.upkeep()
        near = mobs(radius)
        if near:
            if can_shoot() and (hovers(near[0]) or not reachable(near[0].pos, near=1)):
                r = shoot(near[0], min_health=least)  # from where it stands, not from under a flying mob (#756)
            else:
                hold("sword")
                r = attack(near[0], min_health=least)
                if not r and r.reason == "unreachable":
                    r = shoot(near[0], min_health=least)  # a bow reaches what a sword cannot, such as a flying mob
            if not r and r.reason not in ("interrupted", "hurt"):
                now = game_time()
                for i in [i for i in skipped if skipped[i] <= now]:
                    skipped.pop(i)  # so the ids of mobs long dead do not pile up
                skipped[near[0].id] = now + SKIP  # cannot get to it, say: the next mob first
        else:
            go_home()
            wait(WAIT)


try:
    args = ARGS
except NameError:
    args = None  # imported: the importer calls run
if args is not None:
    run(int(args[0]) if len(args) > 0 else 16)
```

## How it works

- Each round, under `RETREAT` (12) health, `heal` walks home with [`go_home`](../reference/modules/lib/home.md) and eats the best food it carries ([`lib.food.best`](../reference/modules/lib/food.md)) while it is hurt and not full, so it gets saturation and heals quickly, a point every 10 ticks; at 20 food without saturation it waits, healing a point every 80 ticks, until it has `HEALED` (18) health. Under 18 food with nothing left to eat it can heal no more, and goes back to the fight as it is, fighting that round without `min_health`.
- It retreats before [`lib.food.upkeep`](../reference/modules/lib/food.md), which would otherwise eat where it fought; `upkeep` gets food from the chests at 14 food or under where it carries none.
- It then [`attack`](../reference/combat/attack.md)s the mob of `MOBS` nearest it within the radius of [`home()`](../reference/movement/home.md), holding its best sword ([`lib.tools.hold`](../reference/modules/lib/tools.md)), with `min_health=RETREAT`: once its health drops under 12 the attack ends with `hurt`, and it retreats at once, in the middle of the fight. `MOBS` is the game's monsters, [`monsters()`](../reference/world/monsters.md), modded ones too; it leaves out the [`neutral`](../reference/types/entity.md) ones, the mobs the bot's own goals leave alone too (#772): endermen, zombified piglins and piglins, which a blow turns on the bot with their kin, the warden and the bosses. With none near it waits at home.
- A mob it cannot get to, such as a flying one, it shoots with [`shoot`](../reference/combat/shoot.md) where it carries a bow or crossbow and arrows. With them, it shoots a mob that `hovers`, with two blocks of [air](../reference/world/block.md) under it, without asking more: a search for a way to it would run through all its nodes to find none (#786). Otherwise it first asks [`reachable`](../reference/movement/reachable.md) whether it can walk to the mob, and shoots one it cannot from where it stands, rather than walking under a flying mob and shooting it straight up (#756); otherwise it tries [`attack`](../reference/combat/attack.md), and shoots where that ends with `unreachable`. Where that fails too, or the fight ends otherwise than with `hurt` or `interrupted`, it leaves that mob alone for `SKIP` (600) ticks and goes for the next, so it does not try the same mob over and over.
- The flee reflex at `botFleeHealth` (6 by default) stays the last line: it ends the fight with `interrupted` and flees, and once it lets go the bot is under `RETREAT`, so it retreats home and heals as after any fight (see [Reflexes](../features/bots.md#reflexes)).
- While it is hurt and runs a script, the bot's own goals start no fight with a mob in sight, so a mob far off does not draw it out while it retreats or heals. A mob that follows it home and hurts it is still fought back there.

`GuardGameTest` runs the bundled job in a test area with two still zombies, both in sight of home. The bot is hurt to 8 health as it first strikes the near zombie; it walks home before it eats, eats its bread, heals to 18 and only then fights the zombies again and kills both.

## See also

- [`attack`](../reference/combat/attack.md), [`eat`](../reference/inventory/eat.md), [`health`](../reference/bot/health.md), [`food`](../reference/bot/food.md)
- [`lib/food`](../reference/modules/lib/food.md), [`lib/home`](../reference/modules/lib/home.md), [Hunger](../features/bots.md#hunger)
