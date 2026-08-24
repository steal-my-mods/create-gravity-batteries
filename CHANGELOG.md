# Changelog

## 0.1.0

First release.

- **Gravity Battery.** Goes on a shaft like a Rope Pulley, takes hold of a glued structure hanging
  below it, and winds it up or lets it down on its own according to whether the rest of the kinetic
  network has stress capacity to spare. Rotation direction does not steer it: turn it either way and
  it charges.
- Power is the weight and runtime is the drop, with total energy the two multiplied. Speed changes
  neither — a battery on a fast network drains faster and supplies more in exact proportion.
- The drop is measured when the weight is picked up, by walking Create's own collision test down the
  shaft, so the charge readout means something in the room the battery is actually standing in.
- The weight falls until it runs into something and stops there, and a battery whose weight is
  resting supplies nothing.
- Goggles show the mode, why an idle battery is idle, the charge, the weight and the time left. A
  comparator reads the charge.
- An animated Ponder scene.

Fixed before release, both found by looking at the block in-game and neither catchable by anything
that was running at the time:

- Goggle overlay lines rendered as raw translation keys. Catnip resolves a LangBuilder key as
  `<namespace>.<key>` and the lang file had them unprefixed. `tools/check_lang.py` now resolves every
  key the code asks for, in CI.
- The first section of cable below the battery rendered almost black. Light is read from the block a
  segment hangs in, and the topmost segment hangs less than a block down by design, so truncating its
  offset sampled the light inside the battery itself.
- The weight jumped, usually upward and by up to a whole block, whenever the shaft stopped or
  reversed. Create's actuator re-grids the offset on a sign change using an integer division, so it
  truncates to a whole block instead of snapping to a sixteenth, and `signum(0) == 0` makes "stopped"
  count as a sign change. Since a battery's offset is its charge, flicking the drive on and off was a
  way to charge it for nothing.
- The goggle overlay was too busy: a charge percentage, a paid-out-of-total and a seconds-remaining
  countdown are all the same fact, and all three flickered. It is now a status line, a charge bar in
  Create's own idiom, and the weight — calibrated against Create's Boiler, which is the densest
  overlay Create ships.
- The shaft spinning through the middle of the battery rendered almost black. Same symptom, different
  cause: the block was missing `noOcclusion()`, and a block entity renderer is handed the light level
  at its block's own position — zero, inside a full-cube occluder. That also blocked all light from
  reaching anything below the battery and had neighbours cull faces you can see through.
