"""Writes architect.litematic with schematic-architect's own exporter, not this folder's encoder, so LitematicTest catches
a slip on either side (its first export was stamped with 26.3's data version and every block read as air).

Made with schematic-architect 0.1.0 (github.com/BarnieBarnie/schematic-architect, commit 2204881), from its checkout:
    uv run python <this folder>/make_architect_fixture.py <this folder>/architect.litematic
"""
import sys
from pathlib import Path

from schematic_architect import litematic
from schematic_architect.design import Design

design = Design("architect")
design.box(0, 0, 0, 2, 0, 1, "minecraft:oak_planks")
design.put((0, 1, 0), "minecraft:oak_log[axis=x]")
design.put((1, 1, 0), "minecraft:oak_stairs[facing=east]")
litematic.write(Path(sys.argv[1]), "architect", design.resolved())
