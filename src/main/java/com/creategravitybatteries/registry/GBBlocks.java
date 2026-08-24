package com.creategravitybatteries.registry;

import com.creategravitybatteries.CreateGravityBatteries;
import com.creategravitybatteries.GBConfig;
import com.creategravitybatteries.battery.GravityBatteryBlock;
import com.simibubi.create.api.stress.BlockStressValues;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class GBBlocks {

	public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CreateGravityBatteries.ID);

	public static final DeferredBlock<GravityBatteryBlock> GRAVITY_BATTERY =
		BLOCKS.register("gravity_battery", () -> new GravityBatteryBlock(BlockBehaviour.Properties.of()
			.mapColor(MapColor.PODZOL)
			.strength(3.0F, 6.0F)
			.sound(SoundType.NETHERITE_BLOCK)
			// Not decoration. A block entity renderer is handed the light level at the block's own
			// position, and inside a full-cube occluder that is zero -- which drew the shaft through
			// the middle of the battery almost black. The model is a frame with an open middle and a
			// slot in the bottom for the cable, so occluding like a solid cube was wrong anyway: it
			// also blocked all light from reaching anything below, and had neighbours cull the faces
			// you can see straight through to. Create's own Rope Pulley sets this for the same reason.
			.noOcclusion()
			.requiresCorrectToolForDrops()));

	/**
	 * What Create's item tooltip and stress readouts quote. A battery works its real figures out per
	 * tick from the weight it is holding, so these are one reference weight — a battery with nothing
	 * attached is worth nothing at all, and quoting zero would tell a player less than nothing.
	 *
	 * <p>Impact and capacity are registered on the same block on purpose: a battery genuinely has
	 * both, and which one applies is the mode it is in. {@code mayGenerateLess} is what tells Create's
	 * UI that the quoted speed is a ceiling rather than a promise, which for a battery holding the
	 * network's existing speed it is.
	 */
	public static void registerStressValues(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> {
			BlockStressValues.IMPACTS.register(GRAVITY_BATTERY.get(), GBBlocks::referenceRating);
			BlockStressValues.CAPACITIES.register(GRAVITY_BATTERY.get(), GBBlocks::referenceRating);
			BlockStressValues.setGeneratorSpeed(GBConfig.maxRpm(), true)
				.accept(GRAVITY_BATTERY.get());
		});
	}

	/** A 32 block weight, which is a 2x4x4 stack of stone — about the smallest one worth building. */
	private static final int REFERENCE_WEIGHT_BLOCKS = 32;

	private static double referenceRating() {
		return GBConfig.stressPerBlock() * REFERENCE_WEIGHT_BLOCKS;
	}
}
