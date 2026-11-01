# `Result` and reason codes

What every action returns. Truthy when the action succeeded.

- `ok`: a bool.
- `reason`: a string code, `None` on success.
- `value`: what the action produced, if anything, for example how many items `craft` made.
- `detail`: why the action failed, in words, where it says more than `reason`, such as which step stopped a [`build`](../building/build.md) and why; `None` otherwise. The bot's log line of the action shows it after the reason: `build('antcraft:farm', Pos(4, 64, 0)) -> blocked: no dirt to pillar up with, to reach Pos(5, 67, 2), 12`.

## Reason codes

`unreachable`, `wrong_dimension` (an action given a `Pos` of another dimension, such as `goto(home())` from another dimension than the home's), `no_path_budget` (only seen if a script asks again in the same tick), `out_of_reach`, `not_loaded`, `no_tool` (needs a better tool), `no_item`, `no_space` (inventory or container full), `no_recipe`, `missing_ingredients`, `no_blueprint`, `no_table`, `no_furnace`, `blocked` (something is in the way), `protected` (the block may not be changed, for example by claim rules, or `attack` may not hit the target), `target_gone`, `interrupted` (a reflex took over; any action that takes ticks, `wait` too, can end with it, see [Reflexes](../../features/bots.md#reflexes)), `stopped`, `timeout` (also `travel` when the portal did not take the bot), `no_portal` (`travel` to a position with no nether portal; a remembered one there, or within 16 blocks of it, is forgotten).

`protected` means the block may not be changed: outside the world's build height or border, under the server's spawn protection, and for `mine` a block that cannot be broken, such as bedrock.

Which reasons each action can return is on its page, under "Fails with".
