package com.creategravitybatteries.registry;

import com.creategravitybatteries.CreateGravityBatteries;
import com.creategravitybatteries.battery.display.BatteryChargeDisplaySource;
import com.creategravitybatteries.battery.display.BatteryStatusDisplaySource;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;

import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Display Link sources, so a battery's state can be put on a Display Board.
 *
 * <p>Registering the source is only half of it — {@link DisplaySource#BY_BLOCK} is what tells a
 * Display Link that <em>this block</em> offers them, and Create's own blocks get that entry from a
 * Registrate transform this mod does not use. Hence {@link #attach}.
 */
public class GBDisplaySources {

	public static final DeferredRegister<DisplaySource> DISPLAY_SOURCES =
		DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, CreateGravityBatteries.ID);

	/**
	 * The names a player picks between in the Display Link's UI come from the registry path, as
	 * {@code <namespace>.display_source.<path>} — so these ids are user-visible and
	 * {@code tools/check_lang.py} checks each one has a translation.
	 */
	public static final DeferredHolder<DisplaySource, BatteryStatusDisplaySource> BATTERY_STATUS =
		DISPLAY_SOURCES.register("gravity_battery_status", BatteryStatusDisplaySource::new);

	public static final DeferredHolder<DisplaySource, BatteryChargeDisplaySource> BATTERY_CHARGE =
		DISPLAY_SOURCES.register("gravity_battery_charge", BatteryChargeDisplaySource::new);

	public static void attach(FMLCommonSetupEvent event) {
		event.enqueueWork(() -> {
			DisplaySource.BY_BLOCK.add(GBBlocks.GRAVITY_BATTERY.get(), BATTERY_STATUS.get());
			DisplaySource.BY_BLOCK.add(GBBlocks.GRAVITY_BATTERY.get(), BATTERY_CHARGE.get());
		});
	}
}
