package com.creategravitybatteries.client;

import com.creategravitybatteries.CreateGravityBatteries;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;

/**
 * The hanging pieces, kept out of the block model because they are drawn at a position the block
 * model has no way to describe: somewhere between here and the weight, which moves.
 *
 * <p>The drum is deliberately <em>not</em> here. Create's Rope Pulley scrolls its coil texture rather
 * than turning it, and a battery has a better readout than either — the weight itself moves. Leaving
 * the drum in the static block model is one fewer orientation to get wrong for nothing gained.
 */
public class GBPartials {

	/** One block of hanging cable, tiled from the weight upwards. */
	public static final PartialModel CABLE = block("gravity_battery_cable");
	/** The clamp on the end of the cable, drawn on top of the weight. */
	public static final PartialModel HOOK = block("gravity_battery_hook");

	private static PartialModel block(String path) {
		return PartialModel.of(CreateGravityBatteries.asResource("block/" + path));
	}

	/** Touching the class is the registration; this exists to make that deliberate at a call site. */
	public static void init() {
	}
}
