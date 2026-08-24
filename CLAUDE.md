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
| `tools/generate_textures.py` | Every texture, plus the mod icon and the branding badge |
| `tools/generate_structures.py` | The Ponder structure, the GameTest template, and the scene's lang keys |
| `tools/check_lang.py` | Resolves every key the code asks for and fails if it is missing |
| `battery/CableGeometry` | Where the cable's pieces go. Out of the client package so a GameTest can reach it |

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
  down the shaft once at assembly and that figure is the battery's capacity. Without it the charge
  readout is `offset / maxCableLength`, which calls a weight resting on the floor 90% charged.
  `theDropIsMeasuredRatherThanAssumed` fails on a build that returns the config value instead.
- **`canDescend()` is what refuses free energy, and `collided()` is its backstop.** Capacity is only
  supplied while the weight can actually fall. Removing either guard leaves a battery whose weight is
  on the floor supplying stress for ever — the one failure mode a gravity battery has.
  `aRestingWeightSuppliesNothing` catches a mutated `canDescend`; it also passes with the probe
  removed, because `collided()` catches it a few ticks later. Two guards, deliberately.
- **`reprobeDrop` runs on the tick the weight *arrives* at the top, not every tick it spends there.**
  The probe walks the whole shaft. The first version fired on `offset <= 0` and walked it every tick —
  and crashed on the first GameTest run, because `running` can be true for a tick with
  `movedContraption` still null.
- **`chargeImpactPerRpm()` is one method because it was two.** `decideMode` reads it through
  `getChargeDraw()` and the network reads it through `calculateStressApplied()`. Written twice they can
  disagree, and a mutation test proved it: making charging cheap in `calculateStressApplied` alone left
  `twoBatteriesOnOneShaftCannotChargeEachOther` passing, because the *decision* was still using the
  honest number.
- **The round-trip loss goes on the way up, and that is what refuses perpetual motion.** Winding costs
  `weight ÷ roundTripEfficiency`; letting down pays `weight`. A discharging battery therefore supplies
  strictly less than a charging battery of the same weight wants, whatever they are geared through.
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
- **The cable's light comes from the block a segment hangs in, and 0 blocks below the battery is the
  battery.** `CableGeometry.lightSource` clamps to at least one below for that reason. The topmost
  segment always has an offset under 1 — that is the point of anchoring the cable at the weight — so
  truncating gave 0 and the first section of cable rendered almost black while everything under it
  looked right. Create's pulley truncates the same way and gets away with it because it never draws a
  full segment that close to the block. `cableGeometryNeverLightsFromInsideTheBattery` is the lock.
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
  has shipped anywhere. A 401/403 fails the release; any other non-200 also fails, but says
  "could not reach" rather than blaming the token, because a 502 from CurseForge is not a bad secret.
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
