# `lib/gather`

Making an item or gathering a raw item, `n` more than the bot carries. A module of the mod's default library: `import lib.gather`, then `lib.gather.make("stone_axe")` (see [Modules & import](../../../language/modules.md)). It is a script like any other, in `config/antcraft/scripts/lib/gather.py`, a few wrappers of [`lib/get`](get.md).

[`lib.get.get(item, n)`](get.md#getitem-n1-radius32) takes a total: "carry `n`". The functions here take "`n` more", as they did before `lib/get`, and call `get` with what the bot carries plus `n`. A new script calls `get` itself.

## `make(item, n=1, radius=32)`

Makes the bot carry `n` more of `item`: [`get`](get.md#getitem-n1-radius32) from the chests within `radius`, by crafting and by gathering the raw items, a tool made first where a source needs one.

- **Returns** what `get` gives: `ok`, `reason`, `at` and `missing`.
- **Pauses** yes, while it gathers and crafts.

```python
import lib.gather

r = lib.gather.make("stone_axe")
if not r["ok"]:
    say(f"no stone axe: {r['reason']} {r['missing']}")
```

## `gather(item, n=1, radius=32)`

The same as `make`: `n` more of `item` through `get`. For a raw item, that is its source in [`lib.get.SOURCES`](get.md#sources); an item `get` can neither gather nor craft fails at once with `no_recipe`.

- **Returns** what `get` gives.
- **Pauses** yes, while it walks and mines.

## `stone(n, radius=32)`

`n` more cobblestone through `get`: [`lib.dig.stone`](dig.md#stonen-radius32), with a pickaxe made first where the bot carries none.

- **Returns** what `get` gives.
- **Pauses** yes, while it walks and mines.

## `exposed_stone(radius=32, skip=None)`

[`lib.find.exposed`](find.md#exposedwhat-radius32-skipnone) for stone or cobblestone: the nearest with air beside or above it, at or above the bot's feet first, leaving out the positions in the dict `skip`; `None` if there is none.

## `is_log(item)`

Whether the item id or selector `item` means a log. Done at once.

## See also

[`lib/get`](get.md), [`lib/craft`](craft.md), [`lib/trees`](trees.md), [`needs`](../../crafting/needs.md).
