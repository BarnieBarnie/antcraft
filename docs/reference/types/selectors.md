# Selectors

Where an item or block is asked for, a string selects it: `"oak_log"` (namespace `minecraft:` assumed), `"minecraft:oak_log"`, a tag `"#minecraft:logs"` or `"#c:ores/iron"`, or a list (or tuple) of these. A malformed selector, or a name or tag the game does not know, stops the script with an error at its line (`unknown block 'minecraft:oak_lgo'`).
