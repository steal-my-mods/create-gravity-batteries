package com.creategravitybatteries.client.ponder;

import java.util.Optional;

import com.creategravitybatteries.battery.GravityBatteryBlockEntity;
import com.simibubi.create.foundation.ponder.instruction.AnimateBlockEntityInstruction;

import net.createmod.ponder.api.level.PonderLevel;
import net.minecraft.core.BlockPos;

/**
 * Winds a battery's cable in or out over a number of ticks, so the Ponder scene can show the weight
 * moving.
 *
 * <p>Create ships static factories on {@code AnimateBlockEntityInstruction} for its own bearings,
 * pulleys and deployers, and none of them fit a foreign block entity. The constructor underneath them
 * is {@code protected} rather than private, though, which makes a subclass the intended way in — the
 * alternative would be stepping the offset by hand between {@code idle} calls, which reads as an
 * animation only at whole-tick resolution.
 */
public class AnimateGravityBatteryInstruction extends AnimateBlockEntityInstruction {

	/** A positive distance lets the weight down; a negative one winds it up. */
	public static AnimateGravityBatteryInstruction move(BlockPos location, float distance, int ticks) {
		return new AnimateGravityBatteryInstruction(location, distance, ticks);
	}

	private AnimateGravityBatteryInstruction(BlockPos location, float distance, int ticks) {
		super(location, distance, ticks,
			(level, value) -> battery(level, location).ifPresent(be -> be.animateOffset(value)),
			level -> battery(level, location).map(be -> be.offset)
				.orElse(0F));
	}

	private static Optional<GravityBatteryBlockEntity> battery(PonderLevel level, BlockPos pos) {
		return level.getBlockEntity(pos) instanceof GravityBatteryBlockEntity be ? Optional.of(be)
			: Optional.empty();
	}
}
