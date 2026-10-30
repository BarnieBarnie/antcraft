"""Writes the .litematic fixtures of LitematicTest (python3 make_fixtures.py, in this folder).

An encoder of its own, independent of the mod's decoder: gzipped NBT laid out as Litematica saves it, block states
packed into longs at max(2, ceil(log2(palette size))) bits each, an entry spanning two longs where it falls over the edge.
"""
import gzip
import struct

DATA_VERSION = 4556  # 1.21.10: palette entries in the old {Name, Properties} form, so loading them runs the data fixer


def text(s):
    b = s.encode()
    return struct.pack('>H', len(b)) + b


def tag(type_id, key, payload):
    return bytes([type_id]) + text(key) + payload


def compound(entries):
    """entries: (type id, key, payload) each."""
    return b''.join(tag(*e) for e in entries) + b'\x00'


def int_(v):
    return struct.pack('>i', v)


def long_array(values):
    return int_(len(values)) + b''.join(struct.pack('>q', v - (1 << 64) if v >= 1 << 63 else v) for v in values)


def compounds(items):
    return bytes([10]) + int_(len(items)) + b''.join(compound(i) for i in items)


def vec(x, y, z):
    return compound([(3, 'x', int_(x)), (3, 'y', int_(y)), (3, 'z', int_(z))])


def state(block, **properties):
    entries = [(8, 'Name', text(block))]
    if properties:
        entries.append((10, 'Properties', compound([(8, k, text(v)) for k, v in properties.items()])))
    return entries


def pack(indices, palette_size):
    bits = max(2, (palette_size - 1).bit_length())
    longs = [0] * ((len(indices) * bits + 63) // 64)
    for i, value in enumerate(indices):
        start = i * bits
        longs[start // 64] |= (value << (start % 64)) & ((1 << 64) - 1)
        if start % 64 + bits > 64:
            longs[start // 64 + 1] |= value >> (64 - start % 64)
    return longs


def strings(items):
    return bytes([8]) + int_(len(items)) + b''.join(text(i) for i in items)


def tile_entity(block_entity, x, y, z, entries):
    """A TileEntities entry as Litematica saves it: the block entity's NBT, at x, y, z from the region's lowest corner."""
    return [(8, 'id', text(block_entity)), (3, 'x', int_(x)), (3, 'y', int_(y)), (3, 'z', int_(z))] + entries


def sign(x, y, z, line):
    return tile_entity('minecraft:sign', x, y, z, [(10, 'front_text', compound([(9, 'messages', strings([line, '', '', '']))]))])


def chest(x, y, z, name, item):
    """A chest named name holding one item."""
    return tile_entity('minecraft:chest', x, y, z, [(8, 'CustomName', text(name)), (9, 'Items', compounds([
        [(1, 'Slot', bytes([0])), (8, 'id', text(item)), (3, 'count', int_(1))]]))])


def region(position, size, palette, indices, tile_entities=()):
    return compound([
        (10, 'Position', vec(*position)),
        (10, 'Size', vec(*size)),
        (9, 'BlockStatePalette', compounds(palette)),
        (12, 'BlockStates', long_array(pack(indices, len(palette)))),
        (9, 'TileEntities', compounds(tile_entities) if tile_entities else bytes([0]) + int_(0)),
        (9, 'Entities', bytes([0]) + int_(0)),
    ])


def write(path, name, regions):
    root = compound([
        (3, 'MinecraftDataVersion', int_(DATA_VERSION)),
        (3, 'Version', int_(6)),
        (10, 'Metadata', compound([(8, 'Name', text(name)), (8, 'Author', text('AntCraft'))])),
        (10, 'Regions', compound([(10, key, payload) for key, payload in regions])),
    ])
    with gzip.open(path, 'wb') as out:
        out.write(tag(10, '', root))


# packed: one 5x1x5 region, five states at 3 bits each, so entry 21 spans the first and second long.
# Entry i (x + z * 5) is palette[i * 3 % 5], so entry 21 is 3 (0b011), whose second bit lies in the second long.
write('packed.litematic', 'Packed', [('Main', region((0, 0, 0), (5, 1, 5), [
    state('minecraft:air'), state('minecraft:stone'), state('minecraft:dirt'), state('minecraft:oak_planks'),
    state('minecraft:oak_stairs', facing='east', half='bottom', shape='straight', waterlogged='false'),
], [i * 3 % 5 for i in range(25)]))])

# regions: 'Floor' at (0,0,0), size (2,1,1): stone, dirt. 'Top' at (3,2,1), size (-2,-1,-1), so it spans x 2..3, y 2,
# z 1: glass at x 2, cobblestone at x 3. Together 4x3x2 from (0,0,0).
write('regions.litematic', 'Two regions', [
    ('Floor', region((0, 0, 0), (2, 1, 1), [state('minecraft:air'), state('minecraft:stone'), state('minecraft:dirt')], [1, 2])),
    ('Top', region((3, 2, 1), (-2, -1, -1), [state('minecraft:air'), state('minecraft:glass'), state('minecraft:cobblestone')], [1, 2])),
])

# The game tests' imported_step: two cobblestones on the floor and oak planks on the east one, as two regions.
write('../../../gametest/resources/data/antcraft-gametest/blueprint/imported_step.litematic', 'Imported step', [
    ('Floor', region((0, 0, 0), (2, 1, 1), [state('minecraft:air'), state('minecraft:cobblestone')], [1, 1])),
    ('Top', region((1, 1, 0), (-1, -1, -1), [state('minecraft:air'), state('minecraft:oak_planks')], [1])),
])

# entities: 'Floor' at (0,0,0), size (2,1,1): stone, then a chest named Loot holding a diamond. 'Top' at (2,1,0), size
# (-2,-1,-1), so it spans x 1..2, y 1: air, then a sign reading Hello at x 2, its block entity at x 1 from the region's
# lowest corner (x 3 if read from its position).
CHEST = state('minecraft:chest', facing='north', type='single', waterlogged='false')
SIGN = state('minecraft:oak_sign', rotation='0', waterlogged='false')
write('entities.litematic', 'Block entities', [
    ('Floor', region((0, 0, 0), (2, 1, 1), [state('minecraft:air'), state('minecraft:stone'), CHEST], [1, 2],
        [chest(1, 0, 0, 'Loot', 'minecraft:diamond')])),
    ('Top', region((2, 1, 0), (-2, -1, -1), [state('minecraft:air'), SIGN], [0, 1], [sign(1, 0, 0, 'Hello')])),
])

# The game tests' imported_sign: a cobblestone with a sign reading Hello on it, and a chest holding a diamond beside it.
write('../../../gametest/resources/data/antcraft-gametest/blueprint/imported_sign.litematic', 'Imported sign', [
    ('Main', region((0, 0, 0), (2, 2, 1), [state('minecraft:air'), state('minecraft:cobblestone'), CHEST, SIGN], [1, 2, 3, 0],
        [chest(1, 0, 0, 'Loot', 'minecraft:diamond'), sign(0, 1, 0, 'Hello')])),
])
