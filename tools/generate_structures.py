#!/usr/bin/env python3
"""
Builds the two structure files this mod ships -- the one the Ponder scene plays inside and the
empty template the GameTests build their rig into -- and the lang keys the scene's text needs.

Create authors these in-game with a schematic tool and checks the .nbt in. There is no such tool
here, so the layout is described in code and written straight out. That has one real advantage
worth keeping: the rig the player is shown and the rig the GameTests assert against are the same
arrangement, written down twice in the same repo rather than drawn once and hoped about.

Conventions copied from Create's own ponder files (assets/create/ponder/*.nbt): y=0 is a
checkerboard base plate of white concrete and snow, the build sits at y>=1, and the whole thing is
one block larger than the base plate the scene declares.

    python3 tools/generate_structures.py
"""

import gzip
import json
import os
import re
import struct
import sys

DATA_VERSION = 3955  # 1.21.1


# --- a very small NBT writer --------------------------------------------------------------------


def _str(value):
    raw = value.encode('utf8')
    return struct.pack('>H', len(raw)) + raw


def _compound(pairs):
    out = b''
    for name, (tag, payload) in pairs:
        out += bytes([tag]) + _str(name) + payload
    return out + b'\x00'


def _list(tag, items):
    return bytes([tag]) + struct.pack('>i', len(items)) + b''.join(items)


def _int_list(values):
    return _list(3, [struct.pack('>i', v) for v in values])


def write_structure(path, size, palette, blocks):
    """
    palette: list of (name, {property: value}).
    blocks: list of (state_index, (x, y, z)).
    """
    palette_entries = []
    for name, properties in palette:
        pairs = [('Name', (8, _str(name)))]
        if properties:
            pairs.append(('Properties', (10, _compound(
                [(k, (8, _str(v))) for k, v in sorted(properties.items())]))))
        palette_entries.append(_compound(pairs))

    block_entries = []
    for state, pos in blocks:
        block_entries.append(_compound([
            ('state', (3, struct.pack('>i', state))),
            ('pos', (9, _int_list(list(pos)))),
        ]))

    root = _compound([
        ('DataVersion', (3, struct.pack('>i', DATA_VERSION))),
        ('size', (9, _int_list(list(size)))),
        ('palette', (9, _list(10, palette_entries))),
        ('blocks', (9, _list(10, block_entries))),
        ('entities', (9, _list(0, []))),
    ])
    payload = b'\x0a' + _str('') + root

    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with gzip.GzipFile(path, 'wb', mtime=0) as handle:
        handle.write(payload)


# --- the scene ---------------------------------------------------------------------------------

PLATE = 5  # matches scene.configureBasePlate(0, 0, PLATE)


def gravity_battery_scene():
    """
    A creative motor driving a Gravity Battery, a pair of cogs for it to drive, and an eight block
    iron weight resting on the base plate two blocks below the end of its cable.

    Laid out along z so the camera looks along the battery's axis and sees the cable come out of
    the bottom. The scene destroys the motor partway through to show the battery taking over, so
    the cogs on the far side are load bearing: without them the battery would have nothing left to
    drive and the failover would be a claim rather than a picture.

    No block entity data, unlike Create's own pulley structures. A ponder level cannot assemble a
    contraption -- that is server-side work -- so the scene tells the battery it is holding
    something with modifyBlockEntity at the moment the player is shown right-clicking it, which is
    also where a player would expect the cable to appear.
    """
    palette = [
        ('minecraft:white_concrete', None),
        ('minecraft:snow_block', None),
        ('create:creative_motor', {'facing': 'north'}),
        ('create:shaft', {'axis': 'z', 'waterlogged': 'false'}),
        ('creategravitybatteries:gravity_battery', {'axis': 'z'}),
        ('create:cogwheel', {'axis': 'z', 'waterlogged': 'false'}),
        ('minecraft:iron_block', None),
    ]
    blocks = []

    for x in range(PLATE):
        for z in range(PLATE):
            blocks.append(((x + z) % 2, (x, 0, z)))

    blocks.append((2, (2, 5, 4)))  # creative motor, shaft facing north
    blocks.append((3, (2, 5, 3)))  # shaft
    blocks.append((4, (2, 5, 2)))  # the battery
    blocks.append((5, (2, 5, 1)))  # cog on the far side...
    blocks.append((5, (1, 5, 1)))  # ...and one meshing with it, so there is visibly a load

    # The weight. Its top block sits two below the end of a fully wound cable, which is the two
    # blocks of travel the scene animates.
    for x in (2, 3):
        for z in (1, 2):
            for y in (1, 2):
                blocks.append((6, (x, y, z)))

    return (PLATE + 1, 7, PLATE + 1), palette, blocks


SCENES = {'gravity_battery': gravity_battery_scene}

PONDER_ROOT = 'src/main/resources/assets/creategravitybatteries/ponder'
STRUCTURE_ROOT = 'src/main/resources/data/creategravitybatteries/structure'

# The GameTest rig. Deliberately empty: every test builds the arrangement it needs with setBlock, so
# the one thing the template has to get right is being big enough. Two batteries side by side on one
# shaft at y=9, with room for a weight to hang under each and floor to land on.
TEST_RIG_SIZE = (11, 12, 11)

# Java sources the scene text is read back out of, so the lang file cannot drift from the scene.
SCENE_SOURCES = {
    'gravity_battery':
        'src/main/java/com/creategravitybatteries/client/ponder/GravityBatteryScenes.java',
}

LANG = 'src/main/resources/assets/creategravitybatteries/lang/en_us.json'
NAMESPACE = 'creategravitybatteries'


def sync_scene_lang():
    """
    Writes the ponder lang keys from the strings in the scene source.

    Ponder resolves every line of scene text through I18n against a key it derives itself --
    `<namespace>.ponder.<sceneId>.header` and `.text_N`, numbered from one in call order. The
    English passed to `.text(...)` is only the datagen default; if the key is missing the player is
    shown the key. Create generates these in datagen. There is no datagen here, so they are
    generated from the one place they already exist: the scene.
    """
    with open(LANG) as handle:
        lang = json.load(handle)

    for scene_id, source_path in SCENE_SOURCES.items():
        source = open(source_path).read()
        title = re.search(r'scene\.title\("([^"]+)",\s*"([^"]+)"\)', source)
        if not title:
            raise SystemExit('%s has no scene.title(...) call' % source_path)
        if title.group(1) != scene_id:
            raise SystemExit('%s titles itself %r, expected %r'
                             % (source_path, title.group(1), scene_id))

        texts = re.findall(r'\n\t+\.text\("((?:[^"\\]|\\.)*)"\)', source)
        # A .text( the pattern above failed to match would silently lose a line at runtime and
        # nowhere else, so count them a second way and insist the two agree.
        expected = len(re.findall(r'\.text\(', source))
        if len(texts) != expected:
            raise SystemExit('%s: matched %d of %d .text( calls -- the pattern needs updating'
                             % (source_path, len(texts), expected))

        prefix = '%s.ponder.%s.' % (NAMESPACE, scene_id)
        for key in [k for k in lang if k.startswith(prefix)]:
            del lang[key]
        lang[prefix + 'header'] = title.group(2)
        for index, text in enumerate(texts, start=1):
            lang[prefix + 'text_%d' % index] = text
        print('synced %d lang entries for %s' % (len(texts) + 1, scene_id))

    with open(LANG, 'w') as handle:
        json.dump(lang, handle, indent=2, ensure_ascii=False)
        handle.write('\n')


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else PONDER_ROOT
    for name, build in SCENES.items():
        size, palette, blocks = build()
        path = os.path.join(root, name + '.nbt')
        write_structure(path, size, palette, blocks)
        print('wrote %s (%s blocks)' % (path, len(blocks)))

    rig = os.path.join(STRUCTURE_ROOT, 'test_rig.nbt')
    write_structure(rig, TEST_RIG_SIZE, [('minecraft:air', None)], [])
    print('wrote %s (empty %sx%sx%s)' % ((rig,) + TEST_RIG_SIZE))

    sync_scene_lang()


if __name__ == '__main__':
    main()
