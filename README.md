# Create: Gravity Batteries

**Gravity storage for [Create](https://github.com/Creators-of-Create/Create).**
Minecraft 1.21.1 · NeoForge 21.1.219+ · Create 6.0+

A kinetic network in Create has no memory. A water wheel that turns all night while the factory
sleeps is turning for nothing, and the moment every machine starts at once the whole network trips.
This mod gives it a battery made of the oldest idea in engineering: pick something heavy up while
you can, and let it back down when you can't.

## Building one

```
[ shaft ] — [ Gravity Battery ] — [ shaft ]
                    |
                  cable
                    |
             [ glued weight ]
```

Put a **Gravity Battery** on a shaft the way you would a Rope Pulley, build something heavy under it,
glue it together, and right-click the battery. It takes hold of the weight and does not let go — a
battery is not a lift, and its charge *is* the position of that weight.

From then on it decides for itself. While the rest of the network has stress capacity going spare it
draws that surplus and winds the weight up. When the network can no longer carry its own load, it
lets the weight back down and the descent drives the shaft. Right-click again to let go; a comparator
reads the charge.

The weight falls until it runs into something and stops there. That is not a limitation to work
around, it is the design — the battery measures the drop when it picks the weight up, and that
measurement is its capacity.

## Power is the weight, runtime is the drop

| Quantity | What sets it |
|---|---|
| **Power** (Stress Units supplied) | the number of blocks in the weight |
| **Runtime** (how long it supplies them) | how far the weight can fall |
| **Total energy** | the two multiplied |

That is not a balance decision, it is what mechanical work is, and it falls out of the arithmetic
rather than being imposed on it. A battery supplies `blocks × stressPerBlock × rpm` Stress Units and
pays cable out at `rpm ÷ (512 × gearReduction)` blocks per tick. Multiply the first by the time the
second takes to cover the drop and the RPM cancels: total energy depends on `blocks × drop` and on
nothing else.

The consequences are worth knowing before you build one:

- **Doubling the weight doubles the power and leaves the runtime alone.** A bigger weight is a bigger
  generator, not a longer-lasting one.
- **Doubling the drop doubles the runtime and leaves the power alone.** Height is the only thing that
  makes a battery last.
- **Speed changes neither.** A battery on a fast network drains faster and supplies more, in exact
  proportion. Gearing it up buys nothing.

So a battery sized for a load it does not have is wasteful: it supplies its full rating whether or
not anything is drawing on it, the way a water wheel spins whether or not anything is attached. The
difference is that a battery is spending something. Size the weight to the shortfall you actually
need to cover.

## How it decides

Every tick the battery works out the network's balance **excluding its own contribution**, and:

| It sees | It does |
|---|---|
| The rest of the network cannot carry its own load | **Let down** — spend height, supply capacity |
| Spare capacity for its draw, plus a margin | **Wind up** — draw stress, store height |
| Anything in between | **Hold** |

Excluding itself is the whole trick. A battery that measured the total would wind up, see the deficit
its own draw created, flip to letting down, see the surplus its own capacity created, and flip
straight back — once a tick, for ever. Measuring what everything *else* is doing means the number it
tests does not move when it acts on it, and the margin leaves a band in the middle where neither test
fires.

**Rotation direction does not steer it.** This is the one place a Gravity Battery is not a Rope
Pulley, and everything else about the block follows from it. A pulley reads the shaft to decide which
way to go; a battery cannot, because when it lets down it *is* the shaft's source and there is no
external direction to read. So the shaft supplies the speed and the mode supplies the direction: turn
a battery either way and it charges.

**A battery taking over holds the speed the network was already running at.** It never speeds a
factory up. An 8 RPM network whose water wheel freezes keeps running at 8 RPM, instead of jumping to
the battery's own ceiling and changing every belt speed in the base at once.

## Why two of them are not a perpetual motion machine

The round-trip loss is charged entirely on the way *up*: winding costs
`weight ÷ roundTripEfficiency`, letting down pays `weight`. So a battery that is letting down
supplies strictly less than a battery of the same weight needs to wind, and the loop never closes —
whatever they are geared through, and however many you chain. `twoBatteriesOnOneShaftCannotChargeEachOther`
is the regression lock, and it asserts the right thing: not that the pair loses height, but that one
never winds up on the other's output. The weaker version of that test passes a build where charging
is deliberately made cheap, because two equal weights swapping height leave the total unchanged.

## Configuration

`config/creategravitybatteries-server.toml`, all under `[battery]`:

| Key | Default | What it moves |
|---|---|---|
| `stressPerBlock` | 4.0 | su/RPM per block of weight — the power dial |
| `gearReduction` | 8 | how much slower the drum turns than the shaft — the duration dial |
| `roundTripEfficiency` | 0.75 | fraction of the winding work you get back |
| `chargeMarginStress` | 64.0 | spare capacity required before winding starts |
| `maxRpm` | 64 | speed a battery drives a network that has none of its own |
| `maxWeightBlocks` | 512 | ceiling on the weight counted towards the rating |
| `maxCableLength` | 64 | longest cable paid out |

`gearReduction` is the one to reach for. It multiplies both how long a battery lasts and how much
energy it holds, and changes nothing about how much power it supplies — so it moves the mod from
"covers a hiccup" to "covers the night" without touching the balance of anything else.

## Building the mod

```bash
./gradlew build              # compile + jar
./gradlew runClient          # dev client
./gradlew runGameTestServer  # the automated in-world tests -- the real check
python3 tools/generate_textures.py     # redraw every texture and the badge
python3 tools/generate_structures.py   # the Ponder + GameTest structures and the scene's lang keys
python3 tools/check_lang.py            # every translation key this mod asks for actually exists
```

JDK 21. See [CLAUDE.md](CLAUDE.md) for the build quirks, the architecture, and the things that will
bite you.

## Licence

MIT, see [LICENSE](LICENSE). This mod leans on Create's MIT-licensed code heavily; its notice travels
with the jar, see [NOTICE.md](NOTICE.md).
