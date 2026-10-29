# `lib/home`

Going home, also from the other side of a nether portal. A module of the mod's default library: `from lib.home import go_home`, or `import lib.home` and call `lib.home.go_home()` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/home.py`, written with [`home`](../../movement/home.md), [`travel`](../../movement/travel.md) and [`portals`](../../movement/portals.md); read it to see how.

## `go_home(tries=3)`

Walks the bot home, through a nether portal first when its home is in the other dimension. Tries `tries` times, waiting a second after a try that failed, as a walk can fail and work the next time.

- **Returns** `True` once the bot stands at its home, `False` when it found no way there.
- **Pauses** yes, while it walks, goes through a portal or waits.

```python
from lib.home import go_home

if not go_home():
    say("cannot find my way home")
```

## `at_home()`

Whether the bot stands at its home, in its home's dimension. Done at once.

## `to_home_dimension()`

Takes the bot through a nether portal into its home's dimension, with [`travel`](../../movement/travel.md) to the portal `portal_out()` gives. A remembered portal that is gone (`travel` fails with `no_portal`) is forgotten, and it tries the next.

- **Returns** whether the bot is in its home's dimension: `True` at once when it already is, `False` when the bot or its home is in neither the overworld nor the nether, or no portal took it there.
- **Pauses** yes, while it travels.

## `portal_out()`

The nether portal to leave this dimension by: the nearest within 32 blocks ([`find_nearest`](../../world/find_nearest.md)), else the nearest of those the bot went through in this dimension ([`portals`](../../movement/portals.md)), which may be far; `None` without either. It may pause while it searches.

## `NETHER_PORTAL_WORLDS`

The dimensions a nether portal goes between: `("minecraft:overworld", "minecraft:the_nether")`.

## Example

Go home when it gets dark, wherever the bot is:

```python
import lib.home

if not is_day() and not lib.home.at_home():
    lib.home.go_home(tries=5)
```

## See also

[`home`](../../movement/home.md), [`set_home`](../../movement/set_home.md), [`travel`](../../movement/travel.md), [Nether travel](../../../features/nether.md), [Modules & import](../../../language/modules.md#the-mods-library).
