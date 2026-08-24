package com.creategravitybatteries.registry;

import com.creategravitybatteries.CreateGravityBatteries;
import com.creategravitybatteries.battery.GravityBatteryContraption;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.api.registry.CreateRegistries;

import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * A contraption type of this mod's own, registered into Create's registry.
 *
 * <p>Not optional and not cosmetic: the type is what a saved contraption is deserialized through, so
 * borrowing Create's {@code pulley} would have a battery's weight come back from disk as a Rope
 * Pulley's contraption. It is also the handle pack authors use — Create's block-movement rules are
 * keyed on contraption-type tags, so having our own is what lets someone allow a block inside a
 * Gravity Battery without allowing it inside every pulley in the game.
 */
public class GBContraptionTypes {

	public static final DeferredRegister<ContraptionType> CONTRAPTION_TYPES =
		DeferredRegister.create(CreateRegistries.CONTRAPTION_TYPE, CreateGravityBatteries.ID);

	public static final DeferredHolder<ContraptionType, ContraptionType> GRAVITY_BATTERY =
		CONTRAPTION_TYPES.register("gravity_battery",
			() -> new ContraptionType(GravityBatteryContraption::new));
}
