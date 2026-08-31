# Changelog

## 0.1.1

One balance fix and the cross-mod convention that generalises it. An 0.1.0 world loads unchanged, and
a lone battery on a network behaves exactly as it did.

- **A battery no longer winds up on capacity another store is supplying.** The round-trip loss makes
  it impossible for a battery to fund an equal or heavier one -- winding costs `weight / efficiency`
  and letting down pays `weight` -- and that was mistaken for the whole guarantee. A *lighter* battery
  costs less than a heavier one supplies, so it was funded. Measured at the defaults: a six block
  weight filled two three block ones and then spent the rest of itself into a bare shaft. Not
  perpetual motion, since the transfer loses on the way up like any other winding, but a battery's
  charge is the player's to spend and nothing should be able to move it uninvited. Capacity supplied
  by a store is now taken out of the *charging* test only; a battery deciding whether to let its own
  weight down goes on counting it, because a discharging store really is holding the network up.
- **`c:kinetic_energy_storage`, a tag any Create addon can honour.** The rule above is not about
  Gravity Batteries, it is about stores, so it is keyed on a tag rather than on this mod's own class:
  any block in `c:kinetic_energy_storage` is treated as spending a store rather than generating, and
  Create already reports how much and whether it is doing it right now. Create: CAES honours it from
  0.1.3, so the two mods leave each other's charge alone; a pack author can add a third mod's block
  with a datapack and fix an interaction neither author has heard of.
- **`roundTripEfficiency` now defaults to 1.0 — a battery gives back exactly what it took in.** It was
  0.75, on the belief that the loss was what stopped one battery charging another. It never was, and
  the tag above does that properly and at any setting, which frees this number to be a balance dial.
  Create charges nothing for a water wheel, a belt or a gearbox, and buffering peaks instead of
  overbuilding generation is the reason to install a battery at all, so a storage tax was charging for
  the feature. The knob is still there for packs that want storage to cost something. **Existing worlds
  keep 0.75**, because the value is already written to their server config — delete the line, or set it
  to 1.0, to pick up the new default.
- **A battery no longer spends its charge into a network where nothing is drawing.** Reported from
  play: attaching a bare shaft to a charged battery span it up and it lowered its weight to the floor
  driving nothing. A cogwheel did it too, and so did a *disengaged* clutch. The guard that was supposed
  to prevent this asked whether a shaft was attached, which is a much weaker question than whether
  anything wants power. A battery holding for this reason says "Nothing needs power" rather than
  reporting the shaft as unpowered. This is not a change to how a battery behaves under *partial* load
  — it still supplies its full rating, and sizing the weight to the shortfall is still the player's job.
- **`c:kinetic_relay` is how it tells a load from a pipe, and packs can extend it.** The tag lists the
  blocks that do nothing but pass rotation along — shafts, cogwheels, gearboxes, clutches, gearshifts,
  chain drives, the encased variants, the gauges — and everything not in it counts as worth driving, so
  a block from a mod this one has never heard of gets driven rather than ignored. Add your own relay
  with a datapack. Stress is deliberately *not* the test: Create gives belts, Gantry Shafts and
  flywheels an impact of exactly zero, so a battery keyed on stress would have refused to drive a belt
  network — the most ordinary load in the game.
- Goggles distinguish the two shortfalls: a battery that will not wind because the surplus is
  *borrowed* now says so, instead of blaming a network that a Stressometer says has plenty spare.

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
- Goggles show the mode, why an idle battery is idle, a charge bar and the weight.
- An animated Ponder scene.
- Reads out three ways, each carrying one thing: a comparator gives the charge 0-15, a **Threshold
  Switch** gives it as a percentage with a redstone output at a level you set, and a **Display Link**
  puts either the mode or the charge on a Display Board.

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
- Weights may not contain machinery. Create's actors — drills, saws, harvesters, deployers — stall a
  contraption while they work, which freezes it in place, and a battery paid by the tick rather than by
  the block would earn its full rating standing still. A battery now refuses such a weight and says so
  on its own face; cut the shaft with a Rope Pulley first, which is the block for it.
- Letting go of a weight snapped it to the nearest whole block, which lifted it by up to half a block
  for nothing — chargeable by winding to just under a half and toggling. It now settles downward, so
  the snap can only ever cost a fraction rather than pay one.
- Clearing the blocks under a descending weight left it stopped at the old limit, reporting itself
  spent over an open shaft. The measured drop is the charge scale; whether the weight may move is now
  asked of the block actually below it, and the measurement is re-taken when the two disagree.
- A battery carried off by another contraption deleted the weight it was holding: the block left the
  world without going through its own removal, so the weight's entity found no controller and
  discarded itself. Batteries are now in Create's `non_movable` tag — breaking one still returns its
  weight, as it always did.
- A battery that was given rotation with nothing hung under it walked down the shaft, tore the first
  solid block it found out of the world, and kept it. Rotation may now only take a weight that is
  flush against the battery's underside; reaching down the shaft is offered only to a player who
  right-clicks, who is looking at the block and can undo it with a second click.
- The comparator output never updated. Declaring `hasAnalogOutputSignal` is only half of an analog
  output — nothing polls it — so the reading a comparator latched when it was placed stayed there for
  ever. It now follows the charge.
- The overlay quoted a Stress figure while winding up and nothing at all while letting down.
  `LinearActuatorBlockEntity` is not a `GeneratingKineticBlockEntity`, so the inherited overlay only
  ever reported stress impact; the generator half had to be transcribed too.
- The goggle overlay was too busy: a charge percentage, a paid-out-of-total and a seconds-remaining
  countdown are all the same fact, and all three flickered. It is now a status line, a charge bar in
  Create's own idiom, and the weight — calibrated against Create's Boiler, which is the densest
  overlay Create ships.
- The shaft spinning through the middle of the battery rendered almost black. Same symptom, different
  cause: the block was missing `noOcclusion()`, and a block entity renderer is handed the light level
  at its block's own position — zero, inside a full-cube occluder. That also blocked all light from
  reaching anything below the battery and had neighbours cull faces you can see through.
