package com.creategravitybatteries.registry;

import java.util.function.Supplier;

import com.creategravitybatteries.CreateGravityBatteries;
import com.creategravitybatteries.battery.GravityBatteryBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

public class GBBlockEntities {

	public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
		DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateGravityBatteries.ID);

	public static final Supplier<BlockEntityType<GravityBatteryBlockEntity>> GRAVITY_BATTERY =
		BLOCK_ENTITIES.register("gravity_battery",
			() -> BlockEntityType.Builder
				.of((pos, state) -> new GravityBatteryBlockEntity(GBBlockEntities.GRAVITY_BATTERY.get(),
					pos, state), GBBlocks.GRAVITY_BATTERY.get())
				.build(null));
}
