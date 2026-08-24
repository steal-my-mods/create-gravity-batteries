#!/usr/bin/env python3
"""
Draws every texture this mod ships.

None of Create's art is used or derived from: Create's code is MIT but everything under its
assets/ is All Rights Reserved, so the only safe amount of it to copy is none. What is borrowed is
the *convention* -- 16x16, a flat base with two shade steps, hard 1px highlights, rivets at the
corners -- which is how Minecraft block art has looked since 2011 and is not anyone's to own.

Everything is deterministic: the "noise" is a hash of the coordinate, so re-running this produces
byte-identical files and a diff in the repo means someone changed the drawing.

    python3 tools/generate_textures.py

The mod badge is not here -- it is tools/generate_logo.py, which draws the Create-family disc.
"""

import os
import struct
import sys
import zlib

BLOCKS = 'src/main/resources/assets/creategravitybatteries/textures/block'


# --- PNG ---------------------------------------------------------------------------------------


def write_png(path, width, height, pixels):
    """pixels: flat list of (r, g, b, a) tuples, row-major from the top left."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0 (None) -- these are tiny, compression is not the point
        for x in range(width):
            raw.extend(pixels[y * width + x])

    def chunk(tag, data):
        body = tag + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')

    directory = os.path.dirname(path)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, 'wb') as handle:
        handle.write(png)


# --- helpers -----------------------------------------------------------------------------------


def noise(x, y, salt):
    """A stable -1/0/1 per pixel, so a texture looks worked rather than printed."""
    h = (x * 374761393 + y * 668265263 + salt * 2246822519) & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((h >> 7) % 3) - 1


def shade(colour, amount):
    return tuple(max(0, min(255, c + amount)) for c in colour[:3]) + (colour[3],)


def canvas(size, colour):
    return [colour] * (size * size)


def put(pixels, size, x, y, colour):
    if 0 <= x < size and 0 <= y < size:
        pixels[y * size + x] = colour


def rect(pixels, size, x0, y0, x1, y1, colour):
    for y in range(max(0, y0), min(size, y1)):
        for x in range(max(0, x0), min(size, x1)):
            put(pixels, size, x, y, colour)


def grain(pixels, size, salt, strength=6):
    for y in range(size):
        for x in range(size):
            pixels[y * size + x] = shade(pixels[y * size + x], noise(x, y, salt) * strength)


def rivets(pixels, size, positions, colour, highlight):
    for (x, y) in positions:
        put(pixels, size, x, y, colour)
        put(pixels, size, x, y - 1, highlight)


# --- palette -----------------------------------------------------------------------------------
#
# Gunmetal for the housing, warm brass for the drum so the moving part is the one that catches the
# eye, and pale steel for the cable so it reads against dark rock at the bottom of a shaft.

IRON = (86, 90, 96, 255)
IRON_DARK = (58, 61, 66, 255)
IRON_LIGHT = (122, 127, 134, 255)
BRASS = (150, 116, 62, 255)
BRASS_DARK = (108, 82, 42, 255)
BRASS_LIGHT = (186, 150, 88, 255)
STEEL = (156, 162, 170, 255)
STEEL_DARK = (104, 110, 118, 255)
STEEL_LIGHT = (198, 204, 212, 255)


# --- the textures ------------------------------------------------------------------------------


def casing():
    """The housing: a riveted iron plate with a vertical seam, so tall stacks read as panels."""
    pixels = canvas(16, IRON)
    grain(pixels, 16, 11, 5)
    # Frame the plate so an edge is visible wherever two of these meet.
    rect(pixels, 16, 0, 0, 16, 1, IRON_LIGHT)
    rect(pixels, 16, 0, 15, 16, 16, IRON_DARK)
    rect(pixels, 16, 0, 1, 1, 15, IRON_LIGHT)
    rect(pixels, 16, 15, 1, 16, 15, IRON_DARK)
    # A single seam down the middle rather than a grid: the block is only 2px thick on its cheeks,
    # and a grid at that scale turns into noise.
    rect(pixels, 16, 7, 2, 8, 14, IRON_DARK)
    rect(pixels, 16, 8, 2, 9, 14, IRON_LIGHT)
    rivets(pixels, 16, [(3, 4), (12, 4), (3, 11), (12, 11)], IRON_DARK, IRON_LIGHT)
    return pixels


def drum():
    """The spool: cable wound in bands, brass ends. Read at 2px it just needs to be warm and ribbed."""
    pixels = canvas(16, BRASS)
    grain(pixels, 16, 23, 5)
    for y in range(16):
        # Bands every third row, alternating so the winding reads as a direction.
        if y % 3 == 0:
            rect(pixels, 16, 0, y, 16, y + 1, BRASS_DARK)
        elif y % 3 == 1:
            rect(pixels, 16, 0, y, 16, y + 1, BRASS_LIGHT)
    rect(pixels, 16, 0, 0, 16, 1, BRASS_LIGHT)
    rect(pixels, 16, 0, 15, 16, 16, BRASS_DARK)
    rivets(pixels, 16, [(4, 7), (11, 8)], BRASS_DARK, BRASS_LIGHT)
    return pixels


def cable():
    """
    A braided steel line. Only a 4px-wide window of this is ever drawn -- the cable model's faces
    take x=6..10 -- so rather than draw a cable in that column, the whole tile is the braid. Any
    window of it reads as one, which also means the model's element can move without the texture
    quietly becoming a blank strip.
    """
    pixels = canvas(16, STEEL)
    for y in range(16):
        for x in range(16):
            # A two-strand twist: the highlight walks across by one column every two rows.
            phase = (x + y // 2) % 4
            if phase == 0:
                pixels[y * 16 + x] = STEEL_LIGHT
            elif phase == 2:
                pixels[y * 16 + x] = STEEL_DARK
    grain(pixels, 16, 41, 4)
    return pixels


def hook():
    """The clamp on the end of the cable. Bright, because it is the only part a player looks for."""
    pixels = canvas(16, STEEL)
    grain(pixels, 16, 37, 5)
    rect(pixels, 16, 0, 0, 16, 2, STEEL_LIGHT)
    rect(pixels, 16, 0, 14, 16, 16, STEEL_DARK)
    rect(pixels, 16, 2, 6, 14, 8, IRON_DARK)
    rivets(pixels, 16, [(4, 4), (11, 4), (4, 11), (11, 11)], IRON_DARK, STEEL_LIGHT)
    return pixels


# --- main --------------------------------------------------------------------------------------

TEXTURES = {
    'gravity_battery_casing': casing,
    'gravity_battery_drum': drum,
    'gravity_battery_cable': cable,
    'gravity_battery_hook': hook,
}


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else BLOCKS
    for name, draw in sorted(TEXTURES.items()):
        path = os.path.join(root, name + '.png')
        write_png(path, 16, 16, draw())
        print('wrote %s' % path)


if __name__ == '__main__':
    main()
