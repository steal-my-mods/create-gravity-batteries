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
