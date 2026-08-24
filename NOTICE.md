# Third-party notices

Create: Gravity Batteries is MIT licensed; see [LICENSE](LICENSE). This file records the third-party
code it is built from, and the notices that code's licence requires be carried along with it.

## Create

Create is split-licensed: its **code is MIT**, and everything under its `assets/` is **All Rights
Reserved**. Only the MIT half is used here, and it is used heavily — this mod is written against
Create's own extension points rather than around them:

- `GravityBatteryBlockEntity` extends Create's `LinearActuatorBlockEntity`, which is where contraption
  assembly, movement, collision and the client-side offset sync come from. The mode-driven direction
  in `getMovementSpeed()` and the refusal to disassemble in `tryDisassemble()` are the only two places
  it departs from the contract.
- The same class carries a transcription of `GeneratingKineticBlockEntity` — `updateGeneratedRotation`,
  `applyNewSpeed`, `notifyStressCapacityChange` and the `reActivateSource` handling. Transcribed
  rather than inherited because Java has one superclass and the actuator had already claimed it; the
  code is Create's, not a reimplementation of it.
- `GravityBatteryContraption` is structurally Create's `PulleyContraption`, including the
  `isAnchoringBlockAt` rule that stops the search climbing the cable.
- The stress arithmetic is written against the contract in `KineticNetwork`: capacity and impact are
  per-RPM ratings multiplied by speed, and a block may be both a source and a member at once.
- `GravityBatteryRenderer` follows the shape of `AbstractPulleyRenderer`, and calls Create's
  `KineticBlockEntityRenderer` statics to draw and spin the shaft.
- `AnimateGravityBatteryInstruction` subclasses Create's `AnimateBlockEntityInstruction`.

That is enough of Create's MIT code that its notice travels with this mod:

> MIT License
>
> Copyright (c) The Create Team / The Creators of Create
>
> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all
> copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

## Art

None of Create's art is used or derived from. Every texture in this mod is drawn by
`tools/generate_textures.py`, which is checked in and re-run in CI; what it borrows from Create is
the convention — 16x16, a flat base with two shade steps, hard 1px highlights, rivets at the corners
— which is how Minecraft block art has looked since 2011.

The one exception is at render time, not in the jar: the rotating shaft drawn through the middle of
the block is Create's own `create:shaft` model, referenced rather than copied, exactly as Create's
own pulleys reference it.
