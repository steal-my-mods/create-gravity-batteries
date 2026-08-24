#!/usr/bin/env python3
"""
Checks that every translation key this mod asks for at runtime actually exists.

This exists because of a bug that shipped. Catnip's LangBuilder resolves a key as
`<namespace>.<key>`, so `GBLang.translate("tooltip.gravity_battery.title")` looks up
`creategravitybatteries.tooltip.gravity_battery.title` -- and the lang file had the keys written
without the prefix. Every line of the goggle overlay rendered as its own key. Nothing logs when
that happens; the only symptom is a player looking at the block.

The same run also borrowed `create.generic.unit.blocks`, which Create does not ship. So keys in
Create's namespace are checked against an allowlist rather than assumed: adding a new one means
looking it up in Create's own en_us.json first, which is exactly the step that was skipped.

    python3 tools/check_lang.py

Exits non-zero and says what is missing.
"""

import glob
import json
import os
import re
import sys

NAMESPACE = 'creategravitybatteries'
LANG = 'src/main/resources/assets/%s/lang/en_us.json' % NAMESPACE
JAVA = 'src/main/java/com/creategravitybatteries'

# Keys in Create's namespace that this mod leans on. Each one was checked against Create 6.0.11's
# assets/create/lang/en_us.json:
#
#   unzip -p <create jar> assets/create/lang/en_us.json | python3 -m json.tool | grep <key>
#
# Do not add to this list without running that. Create's whole generic.unit.* set is buckets,
# degrees, millibuckets, minutes, rpm, seconds, stress and ticks -- there is no "blocks", which is
# the omission that put a raw key on the goggles.
CREATE_KEYS = {
    'generic.unit.stress',
    'gui.goggles.generator_stats',
    'gui.goggles.at_current_speed',
    'tooltip.capacityProvided',
}

# Enums that derive their key from their own constant names, as `<prefix><lowercase name>`. Parsed
# out of the source rather than listed here, so adding a constant is enough to make the check cover
# it.
KEY_DERIVING_ENUMS = [
    os.path.join(JAVA, 'battery', 'BatteryMode.java'),
    os.path.join(JAVA, 'battery', 'IdleReason.java'),
]

# Create's ItemDescription builds these from the item's own description id. A condition without its
# matching behaviour renders as a heading with nothing under it.
ITEM_DESCRIPTIONS = ['block.%s.gravity_battery' % NAMESPACE]


def load_lang():
    with open(LANG) as handle:
        return json.load(handle)


def java_sources():
    return sorted(glob.glob(os.path.join(JAVA, '**', '*.java'), recursive=True))


def classify_translate_calls():
    """
    Every `.translate("key")` call, paired with the namespace it will resolve in.

    The namespace comes from whichever of GBLang / CreateLang appears nearest before the call inside
    the same statement, which is a heuristic -- so the count of calls found is asserted against the
    count of calls seen. A call this cannot attribute fails the check rather than being quietly
    skipped, which is the only way a heuristic like this stays honest.
    """
    found = []
    unattributed = []
    total = 0
    for path in java_sources():
        source = open(path).read()
        for match in re.finditer(r'\.translate\(\s*"([^"]+)"', source):
            total += 1
            key = match.group(1)
            # Back to the start of the statement, then find which builder opened it.
            statement_start = source.rfind(';', 0, match.start()) + 1
            statement = source[statement_start:match.start()]
            # The *nearest* builder, not the first one in the statement. Nested calls are the norm
            # here -- GBLang.translate("...needs", CreateLang.number(x).translate("...unit.stress"))
            # is one statement containing both -- and taking the first would attribute the inner
            # Create key to this mod's namespace.
            ours = statement.rfind('GBLang')
            theirs = statement.rfind('CreateLang')
            if ours < 0 and theirs < 0:
                unattributed.append((path, key, statement.strip()[-60:]))
            elif ours > theirs:
                found.append((path, NAMESPACE, key))
            else:
                found.append((path, 'create', key))
    return found, unattributed, total


def enum_keys():
    """`<prefix><lowercase constant>` for every constant of the key-deriving enums."""
    keys = []
    for path in KEY_DERIVING_ENUMS:
        source = open(path).read()
        prefix = re.search(r'return\s+"([^"]+)"\s*\+\s*name\(\)\.toLowerCase\(\)', source)
        if not prefix:
            raise SystemExit('%s no longer derives its key from name(); update this check' % path)
        # Constants are the bare SCREAMING_CASE identifiers before the enum's first method.
        body = source[source.index('{') + 1:]
        body = body[:body.index('(')] if '(' in body else body
        constants = re.findall(r'^\s*([A-Z][A-Z0-9_]*)\s*[,;]', body, re.MULTILINE)
        if not constants:
            raise SystemExit('%s: found no enum constants; update this check' % path)
        for constant in constants:
            keys.append((path, prefix.group(1) + constant.lower()))
    return keys


def main():
    lang = load_lang()
    problems = []
    checked = 0

    calls, unattributed, total = classify_translate_calls()
    if len(calls) != total:
        for path, key, context in unattributed:
            problems.append('%s: cannot tell which namespace "%s" resolves in (after: ...%s)'
                            % (path, key, context))

    for path, namespace, key in calls:
        checked += 1
        if namespace == NAMESPACE:
            if namespace + '.' + key not in lang:
                problems.append('%s: %s.%s is not in en_us.json' % (path, namespace, key))
        elif key not in CREATE_KEYS:
            problems.append('%s: create.%s is not in the verified allowlist in this script -- look '
                            'it up in Create\'s en_us.json and add it' % (path, key))

    for path, key in enum_keys():
        checked += 1
        if NAMESPACE + '.' + key not in lang:
            problems.append('%s: %s.%s is not in en_us.json' % (path, NAMESPACE, key))

    for base in ITEM_DESCRIPTIONS:
        checked += 1
        if base + '.tooltip.summary' not in lang:
            problems.append('%s.tooltip.summary is missing, so the item has no description' % base)
        index = 1
        while '%s.tooltip.condition%d' % (base, index) in lang:
            if '%s.tooltip.behaviour%d' % (base, index) not in lang:
                problems.append('%s.tooltip.condition%d has no matching behaviour%d, so it renders '
                                'as a heading with nothing under it' % (base, index, index))
            index += 1
            checked += 1
        if index == 1:
            problems.append('%s has no tooltip.condition1, so Hold Shift shows nothing' % base)

    # A check that passed because it inspected nothing is the failure mode this file was written for.
    if checked == 0:
        raise SystemExit('check_lang inspected no keys at all -- the patterns need updating')

    if problems:
        print('%d translation problem(s):' % len(problems), file=sys.stderr)
        for problem in problems:
            print('  ' + problem, file=sys.stderr)
        raise SystemExit(1)

    print('%d translation keys check out.' % checked)


if __name__ == '__main__':
    main()
