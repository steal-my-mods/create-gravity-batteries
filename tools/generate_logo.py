#!/usr/bin/env python3
"""
Generates the mod badge: the Create-family circle of blue graph paper with this mod's subject
drawn large in front of it.

The badge *convention* -- a white-ringed azure disc of graph paper, the subject given a white
stroke and a soft shadow -- is what every Create addon uses to say "this plugs into Create", and a
convention is not artwork. Nothing here is copied from Create: the palette and proportions are the
ones the sibling addons in this family already use, so the three sit together on a mods list.

The subject is a winding tower: a splayed frame, a drum across the top, and a heavy block hanging
inside it on a cable. That silhouette is the oldest shape in the subject -- a mine headframe -- and
it is what a gravity battery looks like wherever one is drawn, which is why it reads without a
caption. It is drawn here in pixels on a 22x21 grid and blown up by a whole number, so it is
Minecraft-shaped rather than a smooth vector illustration, matching what the block itself looks
like.

Everything is described once at a 256px reference and scaled by a whole factor, so `--size 512` is
the same badge larger rather than a different one. Sizes must be multiples of 256.

    python3 tools/generate_logo.py [output.png] [--size 256]
"""

import math
import os
import struct
import sys
import zlib

REFERENCE = 256                # the size every measurement below was tuned at
SS = 3                         # supersampling factor per axis

# --- badge palette, shared with the sibling addons so the three match ----------
WHITE       = (255.0, 255.0, 255.0)
FIELD_LIGHT = (104.0, 172.0, 217.0)
FIELD       = ( 75.0, 139.0, 193.0)
FIELD_DEEP  = ( 56.0, 114.0, 168.0)
GRID        = (126.0, 190.0, 228.0)
SHADOW      = ( 30.0,  64.0, 100.0)

# --- subject palette ----------------------------------------------------------
# The frame is gunmetal so it reads as structure rather than as the subject, and the weight is
# copper so the eye lands on it: it is the thing this mod is about, and warm against azure is the
# same contrast the other two badges lean on.
IRON        = ( 92.0,  99.0, 110.0)
IRON_DARK   = ( 56.0,  61.0,  70.0)
IRON_LIGHT  = (132.0, 141.0, 152.0)
BRASS       = (176.0, 134.0,  66.0)
BRASS_DARK  = (126.0,  94.0,  44.0)
BRASS_LIGHT = (212.0, 172.0, 100.0)
CABLE       = (176.0, 184.0, 194.0)
WEIGHT      = (196.0, 123.0,  78.0)
WEIGHT_DARK = (146.0,  88.0,  54.0)
WEIGHT_LIGHT= (224.0, 156.0, 108.0)

# --- weights, which are fractions rather than lengths and so do not scale ------
GRID_ALPHA = 0.28
SHADOW_ALPHA = 0.26

# --- geometry, in reference pixels ---------------------------------------------
# One factor moves all of it, and it has to leave SPRITE_SCALE a whole number -- keeping the
# subject's pixels square is the entire reason it is scaled by an integer -- so the output size
# must be a multiple of REFERENCE. 256 is the in-jar logo; 512 is what CurseForge wants for a
# project icon, since it downscales gracefully and never upscales.
GEOMETRY = {
    'RADIUS': 124.0,           # outer edge of the badge
    'RING': 9.0,               # white ring thickness
    'GRID_SPACING': 46.0,
    'GRID_HALF_WIDTH': 2.5,
    'SPRITE_SCALE': 7,         # whole number, so subject pixels stay square
    'STROKE': 6.0,             # white outline thickness
    'SHADOW_DX': 6.0,
    'SHADOW_DY': 8.0,
    'GLOW_DX': -44.0,          # where the light sits, relative to the centre
    'GLOW_DY': -52.0,
}


def configure(size):
    """Scales the geometry above to the requested output size. Call before rendering."""
    if size <= 0 or size % REFERENCE:
        raise SystemExit('size must be a positive multiple of {}, got {}'.format(REFERENCE, size))
    factor = size // REFERENCE
    globals().update({name: value * factor for name, value in GEOMETRY.items()})
    globals().update(OUT=size, N=size * SS, CX=size / 2.0, CY=size / 2.0)


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


# --- the subject ---------------------------------------------------------------

SPRITE_W, SPRITE_H = 20, 22

# The weight, which is the subject. Everything else is the frame it hangs in.
WEIGHT_LEFT, WEIGHT_RIGHT = 5, 15
WEIGHT_TOP, WEIGHT_BOTTOM = 8, 17

LEG_WIDTH = 2
BEAM_ROWS = 3


def blank_sprite():
    return [[None] * SPRITE_W for _ in range(SPRITE_H)]


def put(cells, x, y, colour):
    if 0 <= x < SPRITE_W and 0 <= y < SPRITE_H:
        cells[y][x] = colour


def rect(cells, x0, y0, x1, y1, colour):
    for y in range(max(0, y0), min(SPRITE_H, y1)):
        for x in range(max(0, x0), min(SPRITE_W, x1)):
            cells[y][x] = colour


def subject_sprite():
    """
    A gantry with a glued weight hanging in it, as (width, height, rows-of-RGBA).

    Eight earlier drafts tried to draw a whole winding tower -- splayed legs, cross-bracing, a
    headframe wheel, a base slab -- and every one of them resolved into something already familiar:
    a bell, a padlock, a bookshelf, a lamp. The reason is that both sibling badges in this family
    are a single bold object rather than a scene, and at seven screen pixels per sprite pixel behind
    a thick white stroke, a scene is all the badge has room for and none of it reads.

    So: four shapes. A beam, two legs, a cable, and a block. The block is drawn as four smaller ones
    with seams between them, because what a player actually hangs under a Gravity Battery is a stack
    they glued together, and the space left open below it is the drop -- power is the weight, runtime
    is the drop, and this is the two of them stacked.
    """
    cells = blank_sprite()

    # The two legs, upright rather than splayed. Splayed legs make an arch, and an arch over a block
    # is a padlock however it is shaded.
    for left in (0, SPRITE_W - LEG_WIDTH):
        rect(cells, left, BEAM_ROWS, left + LEG_WIDTH, SPRITE_H, IRON)
        rect(cells, left, BEAM_ROWS, left + 1, SPRITE_H, IRON_DARK)
        rect(cells, left + LEG_WIDTH - 1, BEAM_ROWS, left + LEG_WIDTH, SPRITE_H, IRON_DARK)

    # No bar across the feet. Closing the bottom turns the beam and legs into a rectangle, and a
    # rectangle with something centred in it is a picture frame -- which is precisely what the draft
    # that had one looked like. Left open, the same three pieces are a gantry.

    # The head beam across the top, and the spool slung under it.
    rect(cells, 0, 0, SPRITE_W, BEAM_ROWS, IRON)
    rect(cells, 0, 0, SPRITE_W, 1, IRON_LIGHT)
    rect(cells, 0, BEAM_ROWS - 1, SPRITE_W, BEAM_ROWS, IRON_DARK)
    rect(cells, 6, BEAM_ROWS, 14, BEAM_ROWS + 3, BRASS)
    rect(cells, 6, BEAM_ROWS, 14, BEAM_ROWS + 1, BRASS_LIGHT)
    rect(cells, 6, BEAM_ROWS + 2, 14, BEAM_ROWS + 3, BRASS_DARK)

    # The cable.
    rect(cells, 9, BEAM_ROWS + 3, 11, WEIGHT_TOP, CABLE)

    # The weight: four glued blocks, with a lit top face and seams between them.
    rect(cells, WEIGHT_LEFT, WEIGHT_TOP, WEIGHT_RIGHT, WEIGHT_BOTTOM, WEIGHT)
    rect(cells, WEIGHT_LEFT, WEIGHT_TOP, WEIGHT_RIGHT, WEIGHT_TOP + 1, WEIGHT_LIGHT)
    rect(cells, WEIGHT_LEFT, WEIGHT_BOTTOM - 1, WEIGHT_RIGHT, WEIGHT_BOTTOM, WEIGHT_DARK)
    rect(cells, WEIGHT_LEFT, WEIGHT_TOP, WEIGHT_LEFT + 1, WEIGHT_BOTTOM, WEIGHT_DARK)
    rect(cells, WEIGHT_RIGHT - 1, WEIGHT_TOP, WEIGHT_RIGHT, WEIGHT_BOTTOM, WEIGHT_DARK)
    seam_x = (WEIGHT_LEFT + WEIGHT_RIGHT) // 2
    seam_y = (WEIGHT_TOP + WEIGHT_BOTTOM) // 2
    rect(cells, seam_x, WEIGHT_TOP + 1, seam_x + 1, WEIGHT_BOTTOM - 1, WEIGHT_DARK)
    rect(cells, WEIGHT_LEFT + 1, seam_y, WEIGHT_RIGHT - 1, seam_y + 1, WEIGHT_DARK)
    rect(cells, WEIGHT_LEFT + 1, seam_y + 1, WEIGHT_RIGHT - 1, seam_y + 2, WEIGHT_LIGHT)

    rows = [[(cell + (255,)) if cell else (0, 0, 0, 0) for cell in row] for row in cells]
    return SPRITE_W, SPRITE_H, [[tuple(int(round(v)) for v in px) for px in row] for row in rows]


def opaque_bounds(width, height, pixels):
    """Bounding box of the visible part, so the badge centres on the art not the canvas."""
    min_x, min_y, max_x, max_y = width, height, -1, -1
    for y in range(height):
        for x in range(width):
            if pixels[y][x][3] > 0:
                min_x = min(min_x, x)
                max_x = max(max_x, x)
                min_y = min(min_y, y)
                max_y = max(max_y, y)
    if max_x < 0:
        raise ValueError('subject is entirely transparent')
    return min_x, min_y, max_x + 1, max_y + 1


def outside_cells(width, height, pixels):
    """
    Which empty cells of the sprite grid are outside the subject rather than holes in it.

    A flood fill from the border, done at sprite resolution because that is where the answer is
    exact and cheap -- 462 cells rather than two million supersamples.

    The stroke needs this. A tower is mostly holes, and a stroke that does not know the difference
    fills every gap between the legs with white and turns an open frame into a solid plinth. That is
    what the first draft of this badge did, and it is the whole reason the subject is a frame at all
    -- Create's badges show graph paper through their subjects' gaps.
    """
    outside = [[False] * width for _ in range(height)]
    stack = []
    for x in range(width):
        stack.append((x, 0))
        stack.append((x, height - 1))
    for y in range(height):
        stack.append((0, y))
        stack.append((width - 1, y))

    while stack:
        x, y = stack.pop()
        if not (0 <= x < width and 0 <= y < height):
            continue
        if outside[y][x] or pixels[y][x][3] > 0:
            continue
        outside[y][x] = True
        stack.extend(((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)))
    return outside


def place_sprite():
    """
    Blows the subject up to badge scale.

    Returns the supersampled colour buffer and, alongside it, a mask of the samples that lie outside
    the subject's silhouette -- everything beyond its edge, but not the holes within it.
    """
    width, height, pixels = subject_sprite()
    min_x, min_y, max_x, max_y = opaque_bounds(width, height, pixels)
    outside = outside_cells(width, height, pixels)

    drawn_width = (max_x - min_x) * SPRITE_SCALE
    drawn_height = (max_y - min_y) * SPRITE_SCALE
    left = CX - drawn_width / 2.0 - min_x * SPRITE_SCALE
    top = CY - drawn_height / 2.0 - min_y * SPRITE_SCALE

    step = SPRITE_SCALE * SS
    buffer = [None] * (N * N)
    # Anything the sprite grid does not cover at all is outside it, by definition.
    strokeable = bytearray(b'\x01') * (N * N)
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[y][x]
            packed = (r, g, b) if a else None
            x0 = int(round((left + x * SPRITE_SCALE) * SS))
            y0 = int(round((top + y * SPRITE_SCALE) * SS))
            for gy in range(max(0, y0), min(N, y0 + step)):
                row = gy * N
                for gx in range(max(0, x0), min(N, x0 + step)):
                    if packed is not None:
                        buffer[row + gx] = packed
                    elif not outside[y][x]:
                        strokeable[row + gx] = 0
    return buffer, strokeable


def outline_distance(buffer, reach):
    """
    Chamfer distance from the subject, in supersampled pixels, so the white stroke can be taken as a
    band around it. Two sweeps, which is plenty for so short a reach.
    """
    far = float(reach + 2)
    distance = [0.0 if cell is not None else far for cell in buffer]
    straight, diagonal = 1.0, 1.41421356

    for y in range(N):
        row = y * N
        previous = row - N
        for x in range(N):
            index = row + x
            best = distance[index]
            if best == 0.0:
                continue
            if x > 0:
                best = min(best, distance[index - 1] + straight)
            if y > 0:
                best = min(best, distance[previous + x] + straight)
                if x > 0:
                    best = min(best, distance[previous + x - 1] + diagonal)
                if x < N - 1:
                    best = min(best, distance[previous + x + 1] + diagonal)
            distance[index] = best

    for y in range(N - 1, -1, -1):
        row = y * N
        following = row + N
        for x in range(N - 1, -1, -1):
            index = row + x
            best = distance[index]
            if best == 0.0:
                continue
            if x < N - 1:
                best = min(best, distance[index + 1] + straight)
            if y < N - 1:
                best = min(best, distance[following + x] + straight)
                if x < N - 1:
                    best = min(best, distance[following + x + 1] + diagonal)
                if x > 0:
                    best = min(best, distance[following + x - 1] + diagonal)
            distance[index] = best

    return distance


def background(x, y):
    """The graph-paper field at one point, before the subject is laid over it."""
    glow = math.hypot(x - (CX + GLOW_DX), y - (CY + GLOW_DY)) / (RADIUS * 1.55)
    colour = lerp(FIELD_LIGHT, FIELD, min(1.0, glow))
    distance = math.hypot(x - CX, y - CY)
    rim = min(1.0, max(0.0, (distance / RADIUS - 0.55) / 0.45)) ** 1.4
    colour = lerp(colour, FIELD_DEEP, rim)
    for coordinate in (x, y):
        offset = abs(((coordinate + GRID_SPACING / 2.0) % GRID_SPACING) - GRID_SPACING / 2.0)
        if offset < GRID_HALF_WIDTH:
            colour = lerp(colour, GRID, GRID_ALPHA)
    return colour


def render():
    buffer, strokeable = place_sprite()
    reach = STROKE * SS
    distance = outline_distance(buffer, reach)

    shadow_dx = int(round(SHADOW_DX * SS))
    shadow_dy = int(round(SHADOW_DY * SS))
    inner = RADIUS - RING

    rows = []
    samples = SS * SS
    for py in range(OUT):
        row = []
        for px in range(OUT):
            r = g = b = a = 0.0
            for sy in range(SS):
                gy = py * SS + sy
                y = (gy + 0.5) / SS
                for sx in range(SS):
                    gx = px * SS + sx
                    x = (gx + 0.5) / SS

                    from_centre = math.hypot(x - CX, y - CY)
                    if from_centre > RADIUS:
                        continue
                    if from_centre > inner:
                        colour = WHITE
                    else:
                        index = gy * N + gx
                        cell = buffer[index]
                        if cell is not None:
                            colour = (float(cell[0]), float(cell[1]), float(cell[2]))
                        elif distance[index] <= reach and strokeable[index]:
                            colour = WHITE
                        else:
                            colour = background(x, y)
                            sx0, sy0 = gx - shadow_dx, gy - shadow_dy
                            if 0 <= sx0 < N and 0 <= sy0 < N:
                                cast = sy0 * N + sx0
                                if buffer[cast] is not None or distance[cast] <= reach:
                                    colour = lerp(colour, SHADOW, SHADOW_ALPHA)

                    r += colour[0]
                    g += colour[1]
                    b += colour[2]
                    a += 1.0

            if a <= 0.0:
                row.append((0, 0, 0, 0))
                continue
            row.append((
                int(round(min(255.0, r / a))),
                int(round(min(255.0, g / a))),
                int(round(min(255.0, b / a))),
                int(round(255.0 * a / samples)),
            ))
        rows.append(row)
    return rows


def write_png(path, rows):
    height, width = len(rows), len(rows[0])
    raw = b''.join(b'\x00' + b''.join(struct.pack('BBBB', *p) for p in row) for row in rows)

    def chunk(kind, data):
        return (struct.pack('>I', len(data)) + kind + data
                + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff))

    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)
    return len(png)


def main():
    arguments = sys.argv[1:]
    size = REFERENCE
    if '--size' in arguments:
        at = arguments.index('--size')
        size = int(arguments[at + 1])
        del arguments[at:at + 2]
    target = arguments[0] if arguments else 'src/main/resources/creategravitybatteries_icon.png'

    configure(size)
    written = write_png(target, render())
    print('wrote {} ({}x{}, {} bytes)'.format(target, OUT, OUT, written))


if __name__ == '__main__':
    main()
