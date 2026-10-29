# Writing a reference page

Every builtin a script can call has its own page, listed on the [index](index.md). A unit test, `ReferenceDocsTest`, keeps them in step with the code: adding a native without a page fails the build, and so does a page named after no native.

## Where a page lives

`docs/reference/<category>/<name>.md`, where `<name>` is the builtin's name and `<category>` the folder of its group on the index:

| Folder | Group on the index | Builtins |
|---|---|---|
| `movement/` | Movement | `goto`, `travel`, `reachable`, `pos`, `home`, `set_home`, `home_dimension`, `dimension`, `portals` |
| `world/` | The world around | `block`, `find_nearest`, `scan`, `entities`, `items_near`, `time`, `is_day` |
| `inventory/` | Inventory | `inventory`, `held`, `count`, `has`, `free_slots`, `matches`, `equip`, `drop`, `pick_up` |
| `blocks/` | Working blocks | `mine`, `place`, `use` |
| `crafting/` | Containers and crafting | `container`, `put`, `take`, `craft`, `smelt` |
| `building/` | Building | `build`, `blueprint` |
| `mining/` | Mining | `mineshaft`, `mineshaft_at`, `ore_vein` |
| `combat/` | Fighting | `attack` |
| `colony/` | Colony and jobs | `colony`, `post_job`, `take_job`, `jobs`, `finish_job`, `drop_job` |
| `bot/` | The bot and its output | `deaths`, `last_death`, `health`, `max_health`, `say`, `print`, `debug`, `info`, `warn`, `error`, `wait`, `stop` |
| `python/` | Python builtins | `abs`, `all`, `any`, `bool`, `dict`, `enumerate`, `float`, `int`, `isinstance`, `len`, `list`, `max`, `min`, `range`, `reversed`, `round`, `sorted`, `str`, `sum`, `tuple`, `zip` |

The types (`Pos`, `Result`, `Stack`, `Block`, `Entity`, selectors, `memory`) have their pages in `types/`, which the test leaves out. A type's constructor, `Pos(x, y, z)`, is a native whose page is its type's page, `types/pos.md`: its index row links there. The language's methods (`str.split`, `list.append`, ...) are no natives of their own: each is on its type's page, `types/str.md`, `types/list.md`, `types/dict.md` and `types/pos.md`, which `ReferenceDocsTest` checks names every registered method. The library modules have a page each under `modules/`, by their path: `modules/lib/home.md`, `modules/colony/common.md`.

## Adding a page

1. Write `docs/reference/<category>/<name>.md` from the skeleton below. The page starts with the heading ``# `name` ``.
2. On the [index](index.md), make the builtin's name in its row a link to the page: `` | [`name`](category/name.md) | ... | ``, and keep the row's description to one line.
3. Take the name out of `src/test/resources/reference-pages-to-write.txt`, the list of pages still to write. It is grouped by issue, one name a line, so each issue takes out its own block.
4. Run `scripts/check.sh test` and `zensical build --strict`.

Until its page exists, a builtin's index row names it without a link: `` | `name` | ... | ``, as a link to a missing page fails the strict build. The test checks both ways: a written page must be linked, an unwritten one must not. Pages are not added to the nav in `zensical.toml`; the index is their table of contents.

The text for a page comes from the code and from the tables the reference had before (`git show 030d06f:docs/reference/index.md`, Senses and Actions, and the reasons each action fails with).

Every ` ```python ` block under `docs/` must compile with the mod's compiler (`DocExampleTest`). An example that is wrong on purpose goes in a ` ```text ` block.

## Skeleton

Leave out a section that does not apply (a sense has no "Fails with"; a builtin without parameters has no table).

````markdown
# `name`

```text
name(param, other=default)
```

One or two sentences: what it does.

## Parameters

| Name | Type | Default | Meaning |
|---|---|---|---|
| `param` | `Pos` or tuple | | ... |
| `other` | int | `0` | ... |

## Returns

What it returns; for an action, a [`Result`](../types/result.md) and what its `value` is.

## Pauses

Whether the script waits for it (an action that takes ticks, or a search that goes on over the next ticks), or it is done at once.

## Cost

The instructions a call counts against the script's budget, besides the call itself, and any shared budget it draws on.

## Description

The details: edge cases, limits, what it does in another dimension.

## Examples

```python
pass  # a short script that uses it; it must compile
```

## Fails with

| Reason | When |
|---|---|
| `no_item` | ... |

## See also

Links to related builtins and types.
````

[`time`](world/time.md) and [`equip`](inventory/equip.md) are written this way.
