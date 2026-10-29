# `Block`

A block, as `block(pos)` gives it. A snapshot of the block when it was read.

- `id`: the block's id, such as `"minecraft:oak_log"`.
- `pos`: its [`Pos`](pos.md).
- `is_air`, `is_fluid`.
- `falls`: a block that falls when nothing holds it up: gravel, sand, concrete powder, an anvil.
- `tags`: a list of [selectors](selectors.md) such as `"#minecraft:logs"`.
- `state`: a dict of the block state's properties: an int, a bool or a name, such as `{'age': 7}` for ripe wheat or `{'axis': 'y'}` for a log standing up.
