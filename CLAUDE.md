# Create: Gravity Batteries — repo guide

Create addon for **Minecraft 1.21.1 / NeoForge 21.1.219+ / Create 6.0+**. One block: a Gravity
Battery hangs a glued weight from a cable the way a Rope Pulley does, but decides its own direction —
winding up on the network's surplus and letting down to drive the shaft when the network runs short.

## Commands

```bash
./gradlew build              # compile + jar
./gradlew runClient          # dev client
./gradlew runServer          # dev dedicated server (needs run/eula.txt)
./gradlew runGameTestServer  # automated in-world tests -- the real check
./gradlew publishMods        # upload to CurseForge and GitHub Releases
./gradlew publishMods -PdryRun=true   # ...or rehearse it without uploading anything
python3 tools/generate_textures.py     # redraw every texture and the badge
python3 tools/generate_structures.py   # the Ponder + GameTest structures and the scene's lang keys
python3 tools/check_lang.py            # every translation key this mod asks for actually exists
python3 tools/generate_logo.py         # the in-jar badge at 256
python3 tools/generate_logo.py branding/icon-512.png --size 512   # ...and the 512 CurseForge wants
```

JDK 21 required. `gradle/gradle-daemon-jvm.properties` pins the daemon to it, so the commands work
without setting `JAVA_HOME` even when the default `java` is newer — don't delete that file, or
`./gradlew build` dies with "Could not create task ':test' ... Type T not present" on a newer JVM.

There is no unit-test suite; correctness is covered by GameTests in
`com.creategravitybatteries.test.GBGameTests`. Run them after any change to mode selection, the
stress arithmetic, or the drop probe.

## Build quirk worth knowing

Create declares Registrate / Ponder / Flywheel as Maven dependencies, but **no 1.21.1 build of any of
them is published to a public Maven** — Create ships them jar-in-jar. So `build.gradle`:

1. resolves Create with `transitive = false`,
2. unpacks `META-INF/jarjar/*.jar` out of Create's jar (`unpackCreateJij` task),
3. puts those on the compile classpath as **`compileOnly`**.

`compileOnly` is deliberate: at runtime FML loads them from Create's own jar, and a second copy on
the runtime classpath makes each mod load twice. Catnip is not a separate artifact — it lives inside
the Ponder jar, which is where `SuperByteBuffer`, `LangBuilder` and `CachedBuffers` come from.

**Reading Create's source.** There is no sources jar. Decompiling the production jar works and is how
this mod was written against it: `curl -sLO https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar`
then `java -jar cfr-0.152.jar <create jar> --outputdir dec`. NeoForge 1.21 runs on Mojang's official
mappings, so the output uses the same names the code here compiles against.

## Architecture landmarks

| Path | Role |
|---|---|
| `battery/GravityBatteryBlockEntity` | The whole mod. Mode selection, the stress arithmetic, the drop probe, and a transcription of Create's generator half |
| `battery/GravityBatteryBlock` | `HorizontalAxisKineticBlock`; empty-handed use toggles whether a weight is held, and a comparator reads the charge |
| `battery/GravityBatteryContraption` | Structurally Create's `PulleyContraption`, on its own `ContraptionType` |
| `battery/BatteryMode` | IDLE / CHARGING / DISCHARGING, persisted by ordinal and synced |
| `battery/IdleReason` | Why an idle battery is idle. Diagnostic only — nothing branches on it |
| `registry/GBContraptionTypes` | The contraption type, registered into Create's registry via `DeferredRegister` |
| `client/GravityBatteryRenderer` | Shaft, cable and clamp. The casing and spool are the static block model |
| `client/GBTooltips` | Registers into Create's own tooltip registry, so the item gets the native Hold-Shift treatment |
| `client/ponder/GravityBatteryScenes` | The Ponder scene. Its structure is generated, not authored in-game |
| `client/ponder/AnimateGravityBatteryInstruction` | Subclasses Create's animation instruction so the scene can wind the cable |
| `GBConfig` | The whole balance, in two numbers that matter |
| `tools/generate_textures.py` | Every block texture |
| `tools/generate_logo.py` | The Create-family badge: the blueprint disc and the subject on it |
| `tools/generate_structures.py` | The Ponder structure, the GameTest template, and the scene's lang keys |
| `tools/check_lang.py` | Resolves every key the code asks for and fails if it is missing |
| `registry/GBTags` | The `c:kinetic_energy_storage` convention, and the reasoning for it being a tag |
| `battery/CableGeometry` | Where the cable's pieces go. Out of the client package so a GameTest can reach it |
| `battery/display/*DisplaySource` | What a Display Board shows: the mode on one, the charge on the other |
| `registry/GBDisplaySources` | Registers those, and — separately — tells a Display Link the block offers them |

## Things that will bite you

- **The battery measures the network *excluding itself*, and that is load-bearing.** A battery that
  read total capacity minus total stress would wind up, see the deficit its own draw created, flip to
  letting down, see the surplus its own capacity created, and flip back — once per tick, for ever.
  `networkCapacityWithoutSelf`/`networkStressWithoutSelf` subtract `lastStressApplied` and
  `lastCapacityProvided` — *the values the network actually has recorded*, not fresh calculations — so
  the subtraction is exact. `theModeSettlesRatherThanFlipping` is the lock.
- **Direction comes from the mode, not the shaft, and that is the one real departure from
  `LinearActuatorBlockEntity`.** `getMovementSpeed()` takes the magnitude from
  `convertToLinear(getSpeed())` and the sign from `mode`. It has to: when the battery discharges it
  *is* the source, so there is no external rotation direction to read. Everything else about the block
  follows from this — including why there is no Movement Mode scroll option and why `alignDirectionWith`
  exists.
- **`getSpeed()`, not `getTheoreticalSpeed()`, drives the movement.** `getSpeed()` returns 0 on an
  overstressed network, which is correct: a stalled network's weight holds still. The capacity the
  battery advertises does not vanish while stalled, but nothing is turning and nothing is being
  powered, so nobody gains. Don't "fix" it to keep the weight moving.
- **`tryDisassemble()` is overridden to do nothing but honour removal, and the block would be a
  different block without it.** Create's actuators put their load back as blocks whenever they stop; a
  battery that did that would empty itself every time the factory came back online. A battery's charge
  *is* where the weight is, so it has to keep hold of it.
- **The drop is probed, not assumed.** `probeDrop` walks `ContraptionCollider.isCollidingWithWorld`
  down the shaft at assembly and that figure is the battery's capacity — its charge scale, not its
  permission to move. Without it the charge readout is `offset / maxCableLength`, which calls a weight
  resting on the floor 90% charged.
  `theDropIsMeasuredRatherThanAssumed` fails on a build that returns the config value instead.
- **A weight may not contain machinery, and that is a rule, not a limitation.** Create's actors —
  drills, saws, harvesters, deployers, portable interfaces — *stall* a contraption while they work,
  which freezes the offset. Every number this mod quotes comes from one coupling, that time spent
  equals height lost, and a stall breaks it: a stalled weight supplies its full rating while descending
  nothing, so a drill parked on obsidian is a power station whose output is set by block hardness.
  `GravityBatteryContraption.assemble` refuses one, before anything has started moving, with an
  `AssemblyException` the block shows on its own face.
- **Two attempts to withhold the payment instead both ended up worse than the exploit, and the reason
  generalises.** Withholding moves the mode — directly, or through the network balance, since capacity
  is an input to `decideMode` as well as an output of it. A mode change moves `getGeneratedSpeed`, and
  `LinearActuatorBlockEntity#onSpeedChanged` calls `Contraption.stop` on a sign change, which resets
  the actors and clears the stall. The stall was the only thing holding the weight against the block,
  because `ContraptionCollider` exempts whatever a block-breaking actor *could* break on the
  understanding that the actor will deal with it. Measured on the second attempt: a weight ten blocks
  down with all six blocks it "cut" still standing. **Do not reach for this again** without a way to
  keep the declared speed constant.
- **Allowing actors aboard but making them inert is the worst of the three, and it is ruled out rather
  than merely costly.** `ContraptionCollider.isCollidingWithWorld` grants its exemption because the
  *block* has a block-breaking behaviour, not because that behaviour is active — the exemption is a
  promise that the actor will clear the way. Strip the actors and you keep the benefit and drop the
  promise. Measured: a weight with a stripped-actor drill fell its full ten blocks with all six
  deepslate still standing.
- **A drill also made the drop probe read straight through breakable blocks**, so a drilling weight
  advertised a drop it could only cover at drilling speed — far slower than the descent rate the
  duration arithmetic assumes. Moot now that actors are refused, and one more reason they are.
- **350 ticks of a motionless weight is not necessarily a jam.** It was a drill part-way through
  obsidian, which takes about 2,900 ticks a block. An earlier version of this analysis called it a
  permanent hang on the strength of a 500-tick sample and built a guard on top of that reading. If a
  weight is not moving, measure for longer before concluding it is stuck.
- **`canDescend()` is what refuses free energy, and `collided()` is its backstop.** Capacity is only
  supplied while the weight can actually fall. Removing either guard leaves a battery whose weight is
  on the floor supplying stress for ever — the one failure mode a gravity battery has.
  `aRestingWeightSuppliesNothing` catches a mutated `canDescend`; it also passes with the probe
  removed, because `collided()` catches it a few ticks later. Two guards, deliberately.
- **`canDescend()` asks the world, and `restingOffset` is only the charge scale.** They were the same
  thing once — `offset < restingOffset` — and that made the cached measurement load-bearing for
  movement. Clearing the blocks under a descending weight left it stopped dead at the old limit,
  reporting itself spent, over an open shaft; reported from play, and `restingOnSomething()` is the
  answer: a one-step `isCollidingWithWorld` against the block actually below. Cheap enough per tick,
  because the *walk* still only runs when the cached figure has been shown to be wrong.
  `clearingTheFloorLetsTheWeightCarryOnDown` fails on the old comparison.
- **`restingOnSomething()` is measured once a tick, not once a caller.** Two call sites reach
  `canDescend()` in the same tick and neither can see the other: the reprobe condition in `tick()` and
  the discharge branch of `decideMode()`. Both are live in the state a discharged battery sits in for
  as long as the base is dark, so a weight resting on the floor walked the collider set twice every
  tick — measured at 2.00 probes a tick there, against 1.00 while descending and 0.00 either side of
  charging. The walk is Create's `isCollidingWithWorld` over the weight's *bottom footprint*, so it is
  16 block-state and collision-shape lookups for an ordinary 4×4×2 weight and 64 for an 8×8 one. The
  cache is keyed on the **tick as well as the offset**, and the tick half is what keeps it honest: a
  weight whose floor is dug out from under it has an unchanged offset, so a cache that outlived the
  tick would report it still resting for ever. `clearingTheFloorLetsTheWeightCarryOnDown` is what fails
  on that mutation — verified, not assumed.
- **`hasSomethingToDrive()` asks whether anything wants power, and asking whether a shaft is *present*
  was a bug reported from play.** Attaching a bare shaft to a charged battery span it up and it lowered
  its weight to the floor driving nothing; a cogwheel did it too, and so did a *disengaged* clutch —
  which is the clearest case, because a disengaged clutch splits the network, so the battery's whole
  world was itself and a clutch passing nothing through. The guard's own comment always said it existed
  so that "a charged battery in an empty room would not spin against nothing"; an empty room had simply
  been implemented as *no kinetic neighbour* rather than *no demand*.
  `aChargedBatteryOnABareShaftStaysPut` is the lock, and it is caught by three tests on the mutation.
- **This is not the rejected "throttle the descent to the load", and that stays rejected.** See
  *Balance*. Throttling is about modulating the *rate* to match a partial load, and it is refused
  because the speed a generator declares *is* the network's speed, so regulating would slow every belt
  in the base. None of that applies at zero load: there is no rate to modulate, the battery just does
  not start. Do not collapse the two.
- **A stopped Create network does not exist, and that is what makes the demand test hard.**
  `KineticBlockEntity.network` is only ever assigned from `setSource` — which copies it off a block that
  already has one — or by a generator asserting itself, and `clearKineticInformation` nulls it. So every
  block on a shaft run with nothing driving it has `network == null` and there is **no member map to
  walk**. Reading `KineticNetwork.members` was the first attempt at this fix and it could never have
  worked; confirmed by probe, an idle battery with a shaft and an Encased Fan bolted to it reported
  `hasNetwork=false`. `walkForSomethingThatWantsPower` walks the topology itself for that reason, and
  only the branch taken while the battery is already carrying the base reads `members`.
- **The topology walk is Create's own, reassembled from its public parts.**
  `RotationPropagator.getConnectedNeighbours` is private, but the two things it is built from are not:
  `KineticBlockEntity#addPropagationLocations` for the candidate positions — which is what carries a
  block's own idea of what it reaches, including large-cog diagonals and any addon's custom
  connections — and `RotationPropagator.isConnected` for the edge test. Composing those two gives a
  walk that agrees with the propagator by construction. Do not hand-roll the connection rules instead;
  cogwheel meshing and large-to-small gearing are not guessable.
- **Latent impact, never `networkStressWithoutSelf()`.** Create scales stress by speed —
  `getActualStressOf` multiplies the recorded impact by `|getTheoreticalSpeed()|` — so on a network
  nothing is turning, every member reports zero stress however much machinery is bolted to it. Testing
  the stress total would have read "no load" at exactly the moment a battery is deciding whether to
  take over, and failover would never have happened again. `calculateStressApplied()` is a flat lookup
  of the block's impact and does not depend on speed, which is why the walk asks each block that.
- **The demand walk is measured every tick, and a cache was tried and taken out.** It is gated by the
  `&&` chain in `decideMode` — it only runs when nothing else is a source and nothing else supplies
  capacity — and it stops at the first block that draws anything. A 20-tick cache looked like an
  obvious win and was actively wrong: the first call lands the tick after the warm-up, before the
  rotation propagator has finished, so it cached "nothing to drive" from an incomplete picture and held
  it for a second. Seven GameTests failed on it. `DEMAND_WALK_LIMIT` is the cost control instead, and
  it fails *towards the old behaviour* — a component bigger than the cap with no load found in it is
  treated as a load, rather than risking a battery that refuses to carry a large base.
- **`load()` in the GameTests is not scaffolding, it is the other half of the guard.** Six tests need a
  real consumer on the shaft before a battery will discharge at all, and the rig used to get its
  discharges for free from a bare shaft. Every one of those six fails on a build with the guard removed,
  which is why the Encased Fan (impact 2) is there rather than a longer shaft.
- **`reprobeDrop` runs at the two moments the measurement is known to be stale, and never per tick.**
  The weight arriving at the top, where a charge reading is about to be quoted from a possibly ancient
  figure; and the cached drop claiming the weight is at the bottom while `canDescend()` says otherwise,
  which is what digging out the floor looks like. Re-measuring makes that second condition stop
  holding, so it is one probe per disagreement rather than one per tick — and the probe walks the whole
  shaft, so that distinction matters. The first version fired on `offset <= 0` alone and walked it every
  tick, and crashed on the first GameTest run, because `running` can be true for a tick with
  `movedContraption` still null.
- **`chargeImpactPerRpm()` is one method because it was two.** `decideMode` reads it through
  `getChargeDraw()` and the network reads it through `calculateStressApplied()`. Written twice they can
  disagree, and a mutation test proved it: making charging cheap in `calculateStressApplied` alone left
  `twoBatteriesOnOneShaftCannotChargeEachOther` passing, because the *decision* was still using the
  honest number.
- **The round-trip loss is not what refuses perpetual motion, and believing it was cost this mod a
  bug.** It reads like it should: winding costs `weight / roundTripEfficiency` and letting down pays
  `weight`, so a battery can never fund an equal or heavier one — which is exactly what
  `twoBatteriesOnOneShaftCannotChargeEachOther` asserts with two equal weights, and that test passing
  was mistaken for the whole guarantee. A *lighter* battery costs less than a heavier one supplies, and
  was funded. Measured at the old 0.75 default: a six block weight filled two three block ones, then
  spent its remaining five blocks into a bare shaft. The threshold was
  `weightB <= roundTripEfficiency * weightA`. The tag does the refusing now, at any efficiency — see
  *The kinetic storage convention* — and `aBatteryWillNotWindUpOnBorrowedCapacity` is the lock that
  covers the unequal case the old reasoning missed.
- **`roundTripEfficiency` defaults to 1.0, and that is a considered default rather than a missing
  loss.** A battery is a buffer; sizing generation to average load and absorbing the peaks is the
  reason to install one, so taxing it charges the player for the feature. Create itself models no
  losses anywhere — water wheels, belts and gearboxes are all free, and the Steam Engine's only
  "efficiency" is a boiler *allocation* ratio, not waste — so a storage tax would have been the single
  lossy conversion in the whole ecosystem. FE mods reach the same answer: Thermal, Mekanism, AE2,
  Ender IO and Immersive Engineering all store losslessly and put their losses in transmission, where
  the player is making a decision. The knob stays for packs that want a cost, and the loss still lands
  entirely on the winding side so that a lower setting can only cost, never pay.
- **`twoBatteriesOnOneShaftCannotChargeEachOther` asserts the mode *pair*, not the total height.** The
  weaker version — "the sum of the two offsets never falls" — passes a build where charging is
  deliberately made cheap, because two equal weights swapping height leave the sum flat. What the cheap
  version actually buys is network surplus funded by nothing. Both assertions are in the test now; the
  height one catches the opposite mistake (capacity supplied without descending).
- **A generator contributes at the speed it *declares*, not the speed it is spun at.** So
  `getGeneratedSpeed()` caps at the speed the network was already running at (`rememberedSpeed`) rather
  than always asserting `maxRpm`. Without the cap, an 8 RPM network whose source dies jumps to 64 and
  every belt in the base changes pace. Capping costs no coverage, because the load scales with speed
  identically.
- **`rememberNetworkSpeed` only records while something else is driving.** While the battery is the
  source, the speed it reads is its own output; remembering that would pin the ceiling to wherever it
  settled instead of to what the network is for.
- **The generator half is transcribed, not inherited, and it has to be.**
  `LinearActuatorBlockEntity` and `GeneratingKineticBlockEntity` are both direct subclasses of
  `KineticBlockEntity`, and Java has one superclass. `updateGeneratedRotation`, `applyNewSpeed`,
  `notifyStressCapacityChange` and the `reActivateSource` handling are Create's code; see NOTICE.md.
  Keep them faithful — `applyNewSpeed` destroying a generator that opposes a stronger network is why
  `alignDirectionWith` is not optional.
- **A Create block entity can be a source *and* a member at the same time.** `KineticNetwork#add` puts
  every block into `members` and additionally into `sources` when `isSource()`. That is what makes one
  dual-mode block possible at all, rather than needing two.
- **The renderer must not extend `KineticBlockEntityRenderer`.** That class returns immediately when
  Flywheel is active, because every Create kinetic block also ships a Flywheel visual to take over.
  This mod has no visual, so inheriting the early return draws nothing under the default backend — no
  shaft and no cable, just a casing floating above a weight. Extend `SafeBlockEntityRenderer` and call
  the `KineticBlockEntityRenderer` statics.
- **The cable needs no half-block model.** Create's pulley draws whole rope segments plus a half
  segment for the fractional offset. This one anchors the cable at the *bottom*, where a gap would be
  obvious, and lets the topmost segment overshoot into the casing, which hides it. A new segment
  appears inside the spool rather than popping onto the end of the cable. If you move the cable
  element in the model, note the texture is a full-tile braid for exactly this reason — the auto-derived
  UVs follow the element.
- **The contraption needs its own `ContraptionType`.** The type decides which class a saved contraption
  deserializes into, so borrowing Create's `pulley` would have a battery's weight come back from disk
  as a Rope Pulley's. It is also the handle pack authors use, since Create's block-movement rules are
  keyed on contraption-type tags.
- **The Movement Mode behaviour is removed from the list, not from the field.**
  `behaviours.remove(movementMode)` in `addBehaviours`. The field has to stay because the base class
  reads it through `getMovementMode()`; the scroll slot has to go because a battery has nothing to
  choose between and an inert setting is worse than none.
- **Catnip resolves a LangBuilder key as `<namespace>.<key>`, and forgetting the prefix in the lang
  file is invisible until someone looks at the block.** `GBLang.translate("tooltip.gravity_battery.title")`
  looks up `creategravitybatteries.tooltip.gravity_battery.title`. The first release wrote those keys
  unprefixed, so every line of the goggle overlay rendered as its own key and nothing logged.
  `tools/check_lang.py` is the guard, and it runs in CI.
- **Create ships no `generic.unit.blocks`.** Its whole `generic.unit.*` set is buckets, degrees,
  millibuckets, minutes, rpm, seconds, stress and ticks. Borrowing a key from another mod's namespace
  that does not exist puts the key on screen, so `check_lang.py` holds an allowlist of the Create keys
  this mod leans on and refuses a new one until someone has looked it up.
- **`LinearActuatorBlockEntity#onSpeedChanged` truncates the offset to a whole block, and for a
  battery that is free energy.** It re-grids on a rotation sign change, and the arithmetic is
  `Math.round(offset * 16f) / 16` — an *integer* division. Confirmed in Create's bytecode:
  `Math.round:(F)I`, `bipush 16`, `idiv`, `i2f`. It means to snap to a sixteenth and truncates to a
  whole one. A Rope Pulley barely notices because it re-grids on stop anyway; here the offset *is* the
  charge, and `Math.signum(0) == 0` makes every transition from turning to stopped a sign change — so
  each one lifted the weight by up to a block without paying a Stress Unit. Flicking the drive on and
  off was a charging strategy. `onSpeedChanged` is overridden to put the offset back, and
  `losingTheDriveDoesNotMoveTheWeight` is the lock. Do not "simplify" the override away.
- **Rotation arriving may only take a weight that is flush; only a right-click may reach.**
  `findWeightOffset(mayReach)`. The two halves were each reasonable and together were a block-eater:
  assembling on a speed change is inherited Rope Pulley behaviour, and scanning down the shaft for the
  weight is this mod's, so an unattended battery that got power walked up to `maxCableLength` blocks
  down, tore the first solid thing it found out of the world, and never let go — because never letting
  go is the whole point of the block. A player right-clicking is looking at it, asked for it, and can
  undo it with a second click. `aPoweredBatteryDoesNotEatTheFloor` is the lock, and
  `activatingReachesForAWeightFurtherDown` keeps the other half honest.
- **A battery installed with its weight already up is installed charged, and that is deliberate.**
  Nothing stops a player building the weight flush under a battery at the top of a 60-block shaft and
  activating it, which is a full charge for the cost of carrying the blocks up. Minecraft does not
  charge for carrying blocks, and re-winding costs full price, so it is a one-off per installation
  rather than a loop. Do not "fix" it by refusing to assemble above the resting point — that would also
  forbid the ordinary case of re-attaching a weight that is part-way up.
- **The badge is one object, not a scene, and nine drafts proved it.** Both sibling addons' badges
  are a single bold subject; at seven screen pixels per sprite pixel behind a thick white stroke,
  there is room for about four shapes and no more. Every attempt at a whole winding tower — splayed
  legs, cross-bracing, a headframe wheel, a base slab — resolved into something already familiar: a
  bell, a padlock, a bookshelf, a lamp, a picture frame. The rules that came out of it are in
  `subject_sprite`'s docstring, and the two worth repeating are that splayed legs over a block make a
  padlock, and a closed rectangle around anything makes a picture frame.
- **The stroke must know a hole from the outside.** `outside_cells` flood-fills the sprite grid from
  its border, and only those cells may take white. Without it the stroke fills every gap in the frame
  and an open gantry comes out a solid plinth — which is what the first draft did. Create's own badges
  show graph paper through their subjects' gaps, so this is not optional to match them.
- **Three redstone surfaces, each carrying one thing.** A comparator reads the charge 0–15; a
  Threshold Switch reads it 0–100 and fires at a configured level; a Display Link reads either the mode
  or the charge as text. Deliberately not one channel carrying two facts — encoding the mode into the
  comparator was considered and rejected, because it would cost the level reading that every other
  block in the game uses that channel for.
- **A Threshold Switch needs no registration: Create finds it by `instanceof ThresholdSwitchObservable`.**
  It reports percent, not the offset in blocks that Create's Rope Pulley reports, because a drop is
  measured per installation — a threshold in blocks would mean a different thing for every battery in
  the world. `theThresholdScaleRunsFromRestingToFullyWound` asserts both ends, and it has to: a test
  that only asserts the reading *rises* passes just as happily on the offset in blocks.
- **A Display Link source needs registration *and* attachment, and only the second one is easy to
  forget.** `DeferredRegister` into `CreateRegistries.DISPLAY_SOURCE` creates the source;
  `DisplaySource.BY_BLOCK.add(block, source)` in `GBDisplaySources.attach` is what makes a Display Link
  offer it on this block. Create's own blocks get that second half from a Registrate transform this mod
  does not use, so it is a hand-written call at common setup. Without it the sources exist and are
  unreachable — and a test that calls `provideLine` directly would never notice, which is why
  `theDisplaySourcesReportModeAndCharge` asserts through `DisplaySource.getAll` as well.
- **A Display Link source's name comes from its registry id**, as
  `<namespace>.display_source.<path>`, and appears in nothing that looks like a translation call. It is
  in `check_lang.py`'s coverage for that reason.
- **A Display Link polls its source every 100 ticks, and for the status line that was five seconds of
  lying.** `DisplaySource.getPassiveRefreshTicks()` defaults to 100 and neither source overrides it,
  which is right for the charge — 100 ticks is about 2.6% of a full travel at the defaults, and a bar
  cell on a Display Board is several percent wide, so the bar cannot show a finer poll — and wrong for
  the mode, which is a discrete event. `DisplayLinkBlock.notifyGatherers` is Create's push, used by its
  own Threshold Switch, Nixie Tube, Station and Track Observer for exactly this. `refreshDisplayLinks()`
  calls it from the three places that already gate on the displayed value having moved: `setMode`,
  `idle` (the reason is the other half of what the status source shows, and it moves without the mode
  moving) and `disassemble` (which sets both by direct assignment, so `setMode` does not cover it).
  **Keep it to sites that already gate**, because a push resets the link's own timer — pushing on charge
  movement would turn one poll every five seconds into one every tick, per link, for a bar that cannot
  show the difference. `aChangeOfStateReachesADisplayLinkAtOnce` and
  `anIdleReasonChangeReachesADisplayLinkToo` lock all three sites, each verified to fail on its own
  mutation, and they go through a real link onto a real sign because calling the source directly reports
  the new state whether or not anything told the link to ask.
- **A battery ignores a Sequenced Gearshift, and half-obeying one was worse than either.**
  `LinearActuatorBlockEntity` accepts a `TURN_DISTANCE` instruction as a travel limit, counts it down
  against every tick of movement, and once it is spent sets `locked` — which forces a re-sync and a hard
  client-side snap *every tick* for as long as the sequence holds. `getMovementSpeed()` never honoured
  the limit, because a battery's direction comes from its mode rather than from the shaft, so there was
  nothing for "turn this far" to steer: the block paid the cost and got no control.
  `ignoreSequencedDistance()` clears it after `super.onSpeedChanged` (which is where the base class
  imposes it) and again on load. `aBatteryIgnoresASequencedDistance` has to stage a real
  `SequenceContext` to mean anything — `super.onSpeedChanged` clears the limit on the way in and only
  re-imposes it when a context is present, so the first version of that test passed with the fix removed.
- **The block is in Create's `non_movable` tag, and that is data safety rather than taste.** Create's
  own Rope Pulley can be carried by a contraption, but it is *handled*: `Contraption.moveBlock`
  special-cases `PulleyBlock` to bring the rope along, and Create's own ponder scene says pulleys are
  only movable while stopped. A battery is never stopped in that sense and gets none of that handling.
  Sweep one into a piston and its block leaves the world without going through `remove()`, so the
  weight's entity finds no controller and `ControlledContraptionEntity.tickContraption` calls
  `discard()` — which deletes the blocks instead of putting them back. Breaking a battery is fine;
  being carried off is what loses the weight. `aBatteryCannotBeCarriedOffByAnotherContraption` asserts
  it through `BlockMovementChecks`, which is what a contraption actually asks.
- **`hasAnalogOutputSignal` is only half of a comparator output.** Nothing polls it: a block has to
  call `level.updateNeighbourForOutputSignal` when the value moves, which `refreshComparator` does once
  per changed step. Without it the reading a comparator latched when it was placed never changed again
  — the feature looked present, the README said it worked, and it reported a stale number for ever.
  `aComparatorFollowsTheCharge` asserts the reading *rises*, not merely that it is non-zero, because
  non-zero passes on the broken version. Note `ComparatorBlock#getInputSignal` reads
  `pos.relative(FACING)`, so FACING points *at* what is being measured — the first version of that test
  had the comparator the wrong way round and failed for a reason that had nothing to do with the bug.
- **Letting go settles the weight *downwards*, and "down" is `ceil` because offset is measured
  downward.** `getGridOffset` rounds to nearest, which for this block is free charge: rounding the
  offset down lifts the weight, so charging to just under a half and toggling banks height nobody paid
  a Stress Unit for. Rounding the offset *up* settles it down, which can only lose a fraction. Same
  asymmetry as the round-trip loss going on the way up — losses are fine, gains are not. Note `floor`
  is the wrong answer despite sounding like the safe one; it is `floor` of the *height* that is wanted,
  and that is `ceil` of the offset. `lettingGoSettlesTheWeightDownwards` fails on round *and* on floor.
  It cannot settle a weight into the floor: `restingOffset` is always whole and `canDescend()` keeps the
  offset under it.
- **The block needs `noOcclusion()`, and not for decoration.** A block entity renderer is handed the
  light level at the block's own position, and inside a full-cube occluder that is zero — which drew
  the spinning shaft through the middle of the battery almost black. The model is a frame with an open
  middle and a slot in the bottom for the cable, so occluding like a solid cube was wrong on three
  counts: the renderer got no light, nothing below the battery got any either, and neighbours culled
  faces you can see straight through to. Create's Rope Pulley sets it for the same reason.
  `theBatteryDoesNotBlockItsOwnLight` asserts both halves a server can see — that the state does not
  occlude, and that light from a glowstone actually arrives.
- **The cable's light comes from the block a segment hangs in, and 0 blocks below the battery is the
  battery.** `CableGeometry.lightSource` clamps to at least one below for that reason. The topmost
  segment always has an offset under 1 — that is the point of anchoring the cable at the weight — so
  truncating gave 0 and the first section of cable rendered almost black while everything under it
  looked right. Create's pulley truncates the same way and gets away with it because it never draws a
  full segment that close to the block. `cableGeometryNeverLightsFromInsideTheBattery` is the lock.
  Independent of `noOcclusion()`: that one is why there is light at the battery's position at all, this
  one is why the cable reads the position it occupies. Either alone leaves the other wrong.
- **The per-frame client budget is `2 + segments(offset)` passes, and it is locked at the
  `segments` end.** Every cable segment is a CPU pass through `DefaultSuperByteBuffer.renderInto`,
  which loops per vertex and allocates three JOML objects for each — there is no Flywheel visual to
  take over. `theCableCostsOnePassPerBlockOfDrop` asserts that one more block of drop costs exactly one
  more pass, which is not a restatement of `ceil`: it is what fails if the cable is ever reworked to
  Create's half-segment scheme or to sixteenths, multiplying the budget by 16. Verified against that
  mutation. A GameTest can only reach the arithmetic, never the renderer — but see
  *Testing the client* below, because "you cannot test the client" is a stronger claim than the truth.
- **A battery is culled by its whole assembly, not by its block, and that is why the radius is 64.**
  The default `shouldRender` measures to the block's own position, which is the wrong question for a
  block whose visible extent hangs as far below it as the cable is long. That used to be answered by
  returning 128 from `getViewDistance()`, which was wrong twice: it still measured to the block, so it
  moved the boundary rather than removing it — a battery 130 blocks up with its weight beside you still
  vanished — and quadrupling the radius multiplied by about eight the volume of batteries drawn every
  frame. That volume is expensive here in a way it is not for Create's pulley, which has a Flywheel
  visual: with none, each battery in range costs `2 + ceil(offset)` CPU vertex passes a frame, each
  allocating three JOML objects per vertex in `DefaultSuperByteBuffer.renderInto`.
  `CableGeometry.withinViewRadius` clamps the viewer's height into the assembly's vertical span to get
  the nearest point, which answers the original question exactly and lets the radius go back to the
  vanilla 64 that Create's own kinetic blocks use. `VIEW_RADIUS` lives in `CableGeometry` rather than
  the renderer **so a test can pin the number** — `cullingFollowsTheWholeAssemblyNotJustTheBlock` fails
  both on measuring to the block and on the radius going back to 128, and the first version of that
  test pinned a local `64` and so locked the rule while leaving the constant free.
- **`CableGeometry` is out of the client package on purpose.** A GameTest runs on a dedicated server,
  which cannot load a class that mentions `PoseStack`, so cable arithmetic that lives in the renderer
  cannot be tested at all. Moving the rules out is what made a rendering bug lockable.
- **Everything about a Ponder scene fails silently.** A missing structure, a bad block state and a
  missing lang key all produce a *completely clean* client log at startup and only go wrong when a
  player opens the scene. Two guards, because one cannot cover both halves:
  `thePonderStructureIsValid` parses the .nbt the way the game will and checks every palette entry
  against the real registry; `GBClient.checkPonderScenes` compiles the scenes headlessly at the first
  client tick in dev and reports any text Ponder wants a key for that I18n has not got. Neither can
  tell you the scene *looks* right — only opening it can.
- **Ponder text is not the string you pass to `.text(...)`.** That string is only the datagen default.
  Ponder resolves every line through I18n against a key it derives itself —
  `<namespace>.ponder.<sceneId>.header` and `.text_N`, numbered from one in call order — and shows the
  raw key when it is missing. Create generates these in datagen; `tools/generate_structures.py` reads
  them back out of the scene source instead, and asserts it matched every `.text(` it can find.
- **The Ponder scene carries no block entity data, unlike Create's pulley structures.** A ponder level
  cannot assemble a contraption — that is server-side work — so the scene calls
  `modifyBlockEntity(battery, ..., be -> { be.running = true; be.animateOffset(2); })` at the moment
  the player is shown right-clicking, which is also where a player expects the cable to appear. The
  cable then renders off the block entity's own offset, wound by
  `AnimateGravityBatteryInstruction`. Give that instruction and `moveSection` the same duration or the
  cable and the weight visibly drift apart.
- **`AnimateBlockEntityInstruction`'s constructor is `protected`, which is the way in.** Create ships
  static factories for its own bearings, pulleys and deployers and none of them fit a foreign block
  entity; subclassing is how a third party gets one.
- **GameTest weights are slime blocks, and not for the joke.** Create's contraption search only spreads
  through blocks that are glued or naturally sticky, so a stack of stone assembles as *one* block and
  every test about weight would silently be testing a one-block weight. Slime sticks to slime, which
  gets a multi-block contraption out of `setBlock` calls and no glue entities. The weights are one
  column wide so the two batteries' weights cannot touch and merge into one contraption.
- **`getExtensionRange()` is absolute-Y based, so it is small in a GameTest.** The test world places
  the rig around y=-51, which leaves a range of about 12. Fine for the rig, but don't write a test that
  assumes 64.

## The goggle overlay

Calibrated against Create rather than invented. Create's Steam Engine — a tiered generator with an
efficiency source, so the closest analogue this mod has — adds **nothing** to its overlay beyond the
two lines every generator gets. Create's densest overlay is the Boiler, and it is a status line plus
three *bars*.

Two rules taken from that:

- **A level that changes every tick is a bar, not a number.** The charge bar is `|` repeated and
  coloured, the same glyph the Boiler draws its size, water and heat with, in ten steps so a single
  tick of movement is usually not a visible change.
- **A rate that changes every tick is left to the Stress line Create already draws.**

The first version had a charge percentage, a paid-out-of-total, and a seconds-remaining countdown.
All three are the same fact — where the weight is — and all three flickered. What is left is the mode,
the reason when it is idle, the bar, and the weight; the weight is the only number, and it does not
move while a battery is holding one. The `needs X, has Y` pair is the exception, and it is behind the
one idle reason where those two numbers are what tells a player what to change.

### The Stress lines are Create's, and both halves have to be asked for

`LinearActuatorBlockEntity` is **not** a `GeneratingKineticBlockEntity`, so `super.addToGoggleTooltip`
lands on `KineticBlockEntity` — which quotes `calculateStressApplied` and bails when it is zero. That
is zero for every battery that is letting down, so the first version quoted a figure while winding up
and nothing at all while generating. `addGeneratedStressStats` is the missing half, transcribed like
the rest of that class.

It is **not covered by a test and cannot be**: `forGoggles` indents its lines with
`Minecraft.getInstance().font`, so building a tooltip on a dedicated server throws "invalid dist
DEDICATED_SERVER". `bothDirectionsHaveAStressFigureToReport` asserts the two figures those lines read
— impact non-zero and capacity zero while winding, the reverse while letting down — and deleting the
call itself would be silent. The call site says so.

## Balance

Two numbers do all of it. `stressPerBlock` is su/RPM per block of weight; `gearReduction` is how much
slower the drum turns than the shaft.

- **Power** is `blocks × stressPerBlock × rpm` Stress Units.
- **Duration** is `512 × gearReduction × drop ÷ rpm` ticks.

Multiply them and the RPM cancels: total energy is
`512 × gearReduction × stressPerBlock × blocks × drop`. So **power comes from the weight and duration
comes from the drop**, and neither depends on how fast the shaft is turning. That is not an invariant
maintained by hand — it is what mechanical work is, and it holds because the descent rate and the
stress rating are both linear in RPM. If a change breaks it, the change is wrong.

At the defaults a 32 block weight is 128su/rpm (4,096su on a 32 RPM network) and a 30 block drop lasts
about three minutes. `gearReduction` is the dial to reach for: it multiplies duration and stored energy
together and leaves the power rating alone, so it moves the mod from "covers a hiccup" to "covers the
night" without touching the balance of anything else.

**On throttling the descent to the load.** Considered and rejected. A battery supplies its full rating
whether or not anything draws on it, which means a battery sized for a load it does not have wastes
charge — the way a water wheel spins whether or not anything is attached, except that a battery is
spending something. The alternative is to declare a lower speed when the deficit is small, which
preserves total energy and would make a battery last longer under light load. It was rejected because
the speed a generator declares *is* the network's speed when it is the source: regulating to the load
would mean every belt in the base slowing down according to how much stress the base happened to be
drawing. Sizing the weight to the shortfall is the player's job, and the goggles quote both numbers.

## Distribution

Releases go out through `publishMods` (`me.modmuss50.mod-publish-plugin`), driven by
`.github/workflows/release.yml` on a `v*` tag. Things in there that are decisions, not accidents:

- **The CurseForge block is conditional on `curseforge_project_id`, and stays conditional now that the
  id is filled in (1666868).** A declared destination with no id fails at *upload* rather than at
  configuration, which half-publishes a release after GitHub has already accepted the jar. The guard
  costs nothing and is what makes the repo safe to fork or to strip the id out of. The other half,
  `CURSEFORGE_TOKEN`, is a repository secret you create yourself — an absent token fails the same way,
  at upload, so check `publishMods -PdryRun=true` names both destinations before tagging.
- **`projectSlug` is a guess taken from the repo name (`create-gravity-batteries`).** The plugin uploads
  by id, not slug, so a wrong one only spoils the URL it prints — but it is worth correcting against
  the real project page.
- **`minecraft_version_range` is `[1.21.1,1.21.2)`,** not the MDK's default `[1.21.1,1.22)`. This mod is
  written against Create 6 for 1.21.1 and reads Create's kinetic and contraption internals; the wider
  range would let it install on 1.21.4 and break there instead of refusing.
- **The changelog drives the release notes.** `publishMods` reads the `CHANGELOG.md` section whose
  heading names the current `mod_version` and fails if there isn't one — a missing entry should stop a
  release rather than ship the previous version's notes under a new number. It is wired as a lazy
  provider so an ordinary `./gradlew build` never trips over it.
- **The release workflow checks the tag against `mod_version`.** A tag that disagrees would publish the
  jar under the wrong number, and neither site lets you rename a file after upload.
- **The CurseForge token is checked with curl before anything is built.** `publishMods` uploads to two
  sites, and a missing or expired token fails at *upload* — by which point GitHub may already have
  accepted the release, leaving a version published on one site and not the other. Five seconds of
  curl against the upload API's cheapest authenticated GET turns that into a failure before anything
  has shipped anywhere. The status codes were measured against the real API rather than assumed: 200
  valid, **400 malformed**, 401 absent. 401/403 blame the token outright; 400 fails the release too but
  names both possibilities, because a changed API contract or a typo in the URL gets a 400 just as
  readily as a malformed secret does. Anything else fails as "could not reach CurseForge", because a
  502 is not a bad secret.
- **Running the release workflow by hand rehearses by default.** `workflow_dispatch` has a `dry_run`
  input defaulting to true, so a manual trigger runs the whole path — token check, build, GameTests,
  generator diff, changelog lookup — and writes what it *would* have uploaded instead of uploading it.
  A tag push always publishes for real. Without the default, a curious click on "Run workflow" from
  `dev` publishes whatever `mod_version` currently says.
- **Both workflows re-run the generators and fail on a diff.** Every texture, the badge, the Ponder
  structure and the GameTest template are generated, so a stale checked-in file would ship in the jar.
  The check stages first (`git add -A` then `git diff --cached`) because a bare `git diff` says nothing
  about a file the generator newly created.
- **The `github` block sets `tagName` explicitly.** Without it the plugin invents its own tag from
  `mod_version`, so pushing `v0.1.0` produces a release filed under a second, bare `0.1.0` tag on the
  same commit — two tags per release, and the release not at the tag that triggered it.
- **`archivesName` carries the Minecraft version** (`creategravitybatteries-1.21.1-0.1.0.jar`). Neither
  site will let you rename a file after upload.
- **`LICENSE` and `NOTICE.md` ship in the jar under `META-INF/`.** This mod leans on Create's
  MIT-licensed code heavily — `LinearActuatorBlockEntity`, a transcription of
  `GeneratingKineticBlockEntity`, the `PulleyContraption` shape, the `KineticNetwork` contract — and MIT
  wants its notice carried with "copies or substantial portions". A jar handed to a player is a copy.
- **Commits use a repo-local identity** (`Steal-My-Mods`, the account noreply address) set in
  `.git/config`, deliberately not the global one. Don't "fix" it back. The pseudonym is carried
  through `mod_authors`, the LICENSE copyright line and both GitHub URLs, so nothing in the jar or on
  either project page names a person. Note `create-caes` kept a real name in its LICENSE; that is an
  inconsistency there, not the pattern to copy.
- **CurseForge and GitHub only — Modrinth is deliberately not a destination.** Modrinth's Content Rules
  section 6.2 bans project images "created or derived from generative AI output" with no disclosure
  lane, and every pixel of this mod's art is chosen by `tools/generate_textures.py`. CurseForge asks
  only that a *misleading* AI-modified showcase image carry a disclaimer, which a badge of the actual
  block is not. To restore Modrinth: redraw the art by hand, add a `modrinth_project_id`, re-add the
  `modrinth` block to `publishMods` **and** `MODRINTH_TOKEN` to `release.yml`.

## Things a review caught that reading the code did not

- **The flood fill in `generate_logo.py` currently suppresses nothing.** Measured on the sprite as
  drawn: 186 transparent cells, all 186 reachable from the border, zero enclosed holes. What keeps
  graph paper visible between the gantry's legs is that the gaps are wider than twice `STROKE`, not
  `outside_cells`. It stays because the first closed opening anyone draws — a window, a ring, a holed
  counterweight — needs it, and discovering that from a rendered badge is slow. But do not trust it to
  be doing anything today.
- **`disassemble()` used to leave `offset` set, and `findWeightOffset` trusted it.** A battery that had
  once held a weight 20 blocks down would let *rotation alone* take whatever later stood at 20 — the
  hole straight through the flush-only rule that exists to stop exactly that.
  `rotationDoesNotReachForAWeightItOnceHeld` is the lock, and `disassemble()` now zeroes the offset.
- **`collided()` clamped the measured drop on collisions in either direction.** A weight that ran into
  something on the way *up* had its capacity collapsed to nothing: no charge, nothing to spend, idle
  reason DISCHARGED, with a full weight hanging over a clear shaft.
  `hittingSomethingWhileWindingUpKeepsTheDrop` is the lock.
- **Catnip's `LangNumberFormat` keeps three decimal places on a dedicated server.** Its `update()`,
  which sets two, is a client hook. So `CreateLang.number(x)` in anything that runs server-side and
  ships a literal component — a Display Link source, for instance — renders "66.667%". Create's own
  numeric sources go through `PercentOrProgressBarDisplaySource`/`NumericSingleLineDisplaySource`
  instead, which format integers and bring the Flap Display layouts with them. Use those.
- **`SimpleRegistry.Multi#add` does not invalidate the per-key cache that `get` fills; `addProvider`
  does.** Create's blocks attach their display sources during block registration, before anything can
  ask, so they never meet this. Attaching at common setup does, and one early query would cache "no
  sources" for the rest of the run — silently, with the GameTest still green, because in a test the
  first query happens long after setup. `GBDisplaySources` registers providers for that reason, and
  they resolve the block lazily so registry ordering stops mattering too.
- **`name().toLowerCase()` is locale-dependent.** Under a Turkish locale `"IDLE"` lowercases to "ıdle",
  and every mode and idle-reason key would render as itself on the goggles and on a Display Board.
  Both enums pass `Locale.ROOT`, and `check_lang.py` fails a bare `toLowerCase()` so it cannot come
  back — it cannot catch it by comparing keys, because Python's `.lower()` is locale-independent.
- **The comparator and the Threshold Switch share `chargeOnScale`.** They worked the charge out
  separately — one rounding, one ceiling — and disagreed at the ends: a battery at 0.3% gave a
  comparator a strength of 1 while telling a Threshold Switch it was at 0.

## Multiplayer

Audited by reading the sync path rather than by playing on a server, which is still the one test that
has not happened. What the audit established:

- **`GBConfig` is `ModConfig.Type.SERVER`, and that is load-bearing.** `ConfigSync.syncConfigs()` sends
  exactly the SERVER configs to a joining client, and `getMovementSpeed()` reads `gearReduction` on
  *both* sides. Move this to COMMON and client and server move the weight at different rates, which
  desyncs permanently and unrecoverably. Do not.
- **Every field the client needs is written unconditionally**, so it is in the client packet as well as
  the save: mode, idle reason, weight, resting offset, reversed, remembered speed. So is
  `GravityBatteryContraption`'s `InitialOffset`, which the entity spawn packet carries and the renderer
  needs to get the cable's length right.
- **`getMovementSpeed()` keeps Create's convergence machinery.** The `+ clientOffsetDiff / 2` term and
  the `ServerSpeedProvider` multiply are what pull a drifting client back; the override changes where
  the *sign* comes from, not how the client catches up. Don't drop either when editing it.
- **Direction-change latency is real but small at the default gearing.** Between the server changing
  mode and the packet landing, a client keeps moving the old way and then eases back. At 32 RPM and
  `gearReduction` 8 that is 0.0078 blocks/tick — a quarter of a pixel over a 100 ms round trip. At
  `gearReduction` 1 on a fast network the movement clamps at 0.49 blocks/tick and the same latency is
  most of a block, which would read as rubber-banding. The gear reduction is what hides it.
- **The periodic re-sync is gated on the offset having moved, and that gate is not optional now.**
  `LinearActuatorBlockEntity#lazyTick` re-syncs unconditionally while a contraption is attached, at
  `setLazyTickRate(3)`. Create's pulleys and pistons get away with it because they disassemble when they
  stop; a battery never does, so it was a packet every fourth tick for the rest of the world's life,
  per battery, per player tracking the chunk. Measured: 255–293 bytes of NBT a send, each becoming
  *two* packets in `ChunkHolder#broadcastChanges` (a block update and a block entity update) — about
  1.5 KB/s and 10 packets/s per battery per player — and across ten consecutive sends of a battery
  holding station, twelve of the thirteen synced fields were byte-identical, the thirteenth being
  `ForceMovement`, which clears itself. `lazyTick` is overridden to hold that send back when
  `offset == lastSyncedOffset`; safe because `clientOffsetDiff` is the client's correction for an offset
  that moved, and a stationary weight has no drift to correct. Held back through a flag rather than by
  skipping `super`, because today the chain is an empty `SmartBlockEntity#lazyTick` plus that one
  conditional send, so the flag has exactly one thing to catch.
  `anIdleBatteryStopsRepeatingItself` is the lock and **both of its halves are load-bearing**: the
  resting half fails on Create's unconditional version, the moving half fails on a gate clamped shut.
  An earlier version asserted a `hasUnsyncedMovement()` predicate instead and passed on *both*
  mutations, because that predicate is self-healing — any send at all clears it. It watches
  `getLastSyncTick()` for that reason.
- **`reversed` and `rememberedSpeed` have to send for themselves.** They used to call only
  `setChanged()` and reach the client on the next unconditional re-sync. Both are read client-side
  through `getGeneratedSpeed()` for the goggle overlay's capacity line, so once an idle battery stopped
  re-syncing they would have gone stale for ever. They call `sendData()` now, which also makes them
  arrive at once rather than up to four ticks late. Neither changes often enough for that to cost
  anything — a network's speed is discrete, and `reversed` only moves on a gearshift flip.
- **No static mutable state**, so nothing leaks between players or worlds.

## The Create interaction audit

Every surprise so far arrived through a contract this block inherits but is not quite an instance of.
So this is organised by *mechanism*, not by block: check the mechanism once and every block that uses
it is covered. Done by reading Create 6.0.11's own code for each one.

**Rotation and stress.** Shafts, cogwheels, gearboxes, chain drives and belts are inert — a battery is
an ordinary network member and, while discharging, an ordinary source. Other generators (creative
motor, water wheel, windmill, steam engine, hand crank) are fine and are in fact what `rememberedSpeed`
is for. A **Rotation Speed Controller** driving a battery is an ordinary consumer relationship, and the
target speed it imposes is picked up as the remembered speed, which the discharge cap then honours.
Stressometer and Speedometer are read-only.

**Gearshift and Clutch** were the case worth checking, because `applyNewSpeed` really does
`level.destroyBlock` on a generator whose rotation opposes a stronger network — that is how Create
punishes two motors fighting. `alignDirectionWith` is what stops it, and
`reversingTheNetworkDoesNotDestroyTheBattery` flips a Gearshift back and forth four times to prove it.
The **Sequenced Gearshift** is the one that needed code: see the note above.

**The wrench needed code too, and it is the clearest example of the pattern.** `IRotate extends
IWrenchable`, so a battery is wrenchable like every Create kinetic block, and rotating one goes through
`KineticBlockEntity.switchToBlockState` — which detaches the kinetics and then re-arms the source with
`if (be instanceof GeneratingKineticBlockEntity) be.reActivateSource = true`. This block is not one, so
it was never re-armed: a wrenched battery that had been carrying the network sat in DISCHARGING at
speed zero for ever, weight stopped, shaft dead, nothing logged. `reassertRotationIfItWasTakenAway`
asks the question directly instead of mirroring the flag, so it covers the wrench and anything else
Create tests the same way. Sneak-wrenching is a plain `destroyBlock`, which reaches `remove()` and
therefore `disassemble()`, so the weight comes back like it does for a pickaxe.

**Contraptions.** Carrying the battery is refused (`non_movable`, above). Actors inside the weight are
refused at assembly (above). Super glue, chassis and slime/honey are the intended way to hold a weight
together. Another contraption sharing the shaft is Create's collider's problem and it handles it.

**Redstone.** Comparator, Threshold Switch and Display Link each carry one thing and each has a test.
The battery deliberately has no redstone *input*, which is also true of the Rope Pulley.

**Cleared without needing anything.** `SAFE_NBT` has no consumer anywhere in Create 6 — not even as a
literal — so the block's absence from that tag means nothing. A Deployer right-clicking a battery
toggles its weight, which works and is arguably useful. The recipe's `c:plates/iron` resolves to
`create:iron_sheet`.

**Discoverability is a separate job from working, and it fails silently.** Implementing
`ThresholdSwitchObservable` and registering the display sources makes those things work; being in
Create's Ponder index is what makes them findable, and nothing complains if you are not. `registerTags`
joins four of Create's own pages — kinetic sources, movement anchor, threshold switch targets, display
sources — each of which is a claim the block actually makes.

## The kinetic storage convention

`c:kinetic_energy_storage` is a cross-mod block tag meaning **the capacity this block supplies to a
kinetic network is drawn from a store it filled earlier, not generated.** A battery refuses to wind up
on capacity supplied by anything in it. Create: CAES honours the same tag from its 0.1.3.

- **It is a tag because only one bit has to cross the mod boundary.** Create already exposes
  `KineticNetwork.sources` and `getActualCapacityOf`, so any mod can enumerate a network's generators
  and read each one's exact contribution with no cooperation from the mod that owns it — and
  `getActualCapacityOf` multiplies by `getGeneratedSpeed()`, which is zero for a store that is not
  currently spending, so the *runtime* half is answered too. All that is missing is the
  classification. A shared API artifact or a NeoForge capability would both need a common class on the
  classpath and would only re-report a number Create already gives you.
- **The `c` namespace is the point, not a detail.** Nobody owns it, so a second addon adds its block
  without depending on this one, and **a pack author can add a third mod's block with a datapack** and
  fix an interaction neither author has heard of. That last property is the one the alternatives
  cannot match, and it is the reason to prefer the tag even though it is the least expressive option.
- **Subtract it from the charge test only.** `storedCapacityOnNetwork()` comes out of the charging
  branch of `decideMode` and nowhere else. A discharging store genuinely *is* holding the network up,
  so the `headroom < 0` test must go on counting it — take it out there too and a network one battery
  is comfortably covering reads as a deficit to the next battery along, and every battery on it dumps
  at once.
- **Tag the block that is the kinetic source**, which for a multiblock is not the one a player thinks
  of as the storage. CAES tags the Air Engine and not the Pressure Vessel for exactly this reason.
- **The scan walks `sources`, not `members`.** Sources is the network's generators — a handful —
  where members is every shaft and cog in the factory. That is what makes it affordable every tick
  with none of the caching CAES's engine coalition needs. Removed entries are skipped by hand because
  Create only prunes them on its next capacity recalculation, and a stale one would be capacity
  subtracted twice.
- **Both halves are tested, and they are different tests.**
  `aBatteryWillNotWindUpOnBorrowedCapacity` covers the behaviour, and it covers it *for foreign
  blocks* even though it uses a battery as the tagged source, because the check is the tag and not an
  `instanceof` — a foreign block goes down the identical line.
  `theBatteryDeclaresItselfAsKineticStorage` covers the half the behaviour test cannot see: the tag
  being renamed consistently across `GBTags` and the json still passes every behaviour test and
  silently stops the mod composing with every other addon. It spells the name out as a literal for
  that reason.
- **There is no cross-mod GameTest, and that was a deliberate choice rather than an oversight.**
  Standing a battery and an Air Engine on one shaft needs both mods in one dev runtime, which means a
  cross-repo build dependency and a CI job that cannot run until the sibling has published. The split
  above gets the same guarantee out of two single-mod suites: each mod proves it declares itself, and
  each proves it refuses tagged capacity. Two mods passing both compose — and that scales to mods that
  do not exist yet, which an integration test against one named sibling does not.

## Testing the client

There is no client-side GameTest, and that much is settled: the vanilla framework is server-only
(`GameTestServer`, and the sole client-side class is a debug *renderer* for watching tests in-world),
and `RuntimeDistCleaner` is active in dev, which is what throws "invalid dist DEDICATED_SERVER" the
moment a server-dist run touches a client-only member. NeoForge's `junit` run type is **not** a way
round it — `JUnitDevLaunchTarget.getDist()` returns `Dist.DEDICATED_SERVER`, so it is a nicer harness
for pure logic and no help at all for the renderer, despite being handed client assets.

What is *not* settled is the broader claim. Three routes exist, in increasing cost:

1. **Put the arithmetic where a server can reach it, and assert the cost function.** What
   `CableGeometry` is for, and now what `theCableCostsOnePassPerBlockOfDrop` and
   `cullingFollowsTheWholeAssemblyNotJustTheBlock` do. Free, runs in the existing suite, and catches
   the regressions that would actually hurt — an algorithmic change to the per-frame budget, or the
   cull rule going back to measuring the block. Cannot see the renderer.
2. **Plain JUnit against the merged jar, outside FML.** A `src/test/java` suite launched by Gradle's
   own `test` task has no dist cleaner, so client classes load; `Bootstrap.bootStrap()` brings up
   registries with no GL context. Enough for anything that is pure math on client classes —
   `shouldRender` is the example. The catch is the classpath: Create, Catnip and Ponder are
   deliberately `compileOnly` here (see the build quirk above), so they would have to be added to the
   *test* runtime classpath only. Safe to do — a test classpath cannot double-load mods in the game —
   but it is a new moving part in a build that is already unusual. Cannot bake models, so nothing that
   touches a `PartialModel`.
3. **A counting harness under `runClient`.** The real answer for per-frame cost. Boot the client, load
   a world, place batteries, then call the renderer with a `MultiBufferSource` that counts vertices and
   draw calls instead of drawing them, and assert on the counts. **Count the work, do not time it**:
   counts are deterministic and GPU-independent, so they make a usable CI gate, whereas frame times
   under the software rasteriser any headless CI runner will give you (Mesa llvmpipe behind Xvfb) put
   the GPU's work on the CPU and swamp exactly the signal this mod cares about. Costs a client boot in
   CI, a dev-only automation entrypoint, and a Gradle task; Linux-only in practice.

Nothing above is built beyond route 1. Route 3 is what to reach for if the Flywheel visual is ever
attempted, because that is the change whose whole point is a number no current test can see.

## Known gaps

- **The item model is the block model.** It shows the casing and the spool but no shaft, because the
  shaft is drawn by the renderer. Create authors a separate `item.json` with the shaft baked in; doing
  the same here means duplicating the geometry, and it has not been worth it yet.
- **One in-game pass has happened and found two things, both fixed and both now locked** — the
  unprefixed goggle keys and the dark first cable segment. Neither was catchable by anything that was
  running at the time, which is why both guards exist now. Still unverified by eye: the Ponder scene's
  framing at `scaleSceneView(0.9)` / `setSceneOffsetY(-1)`, the spool's proportions, and whether the
  weight and the cable stay visually joined at speed.
- **One scene, one language.** `en_us` only.

## Conventions

Tabs for indentation, matching Create's own style. Registry classes are `GB*` under `registry/`.
Nothing is committed without explicit instruction.
