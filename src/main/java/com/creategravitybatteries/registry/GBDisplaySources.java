package com.creategravitybatteries.registry;

import java.util.function.Supplier;

import com.creategravitybatteries.CreateGravityBatteries;
import com.creategravitybatteries.battery.display.BatteryChargeDisplaySource;
import com.creategravitybatteries.battery.display.BatteryStatusDisplaySource;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateRegistries;
import com.simibubi.create.api.registry.SimpleRegistry;

import net.minecraft.world.level.block.Block;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

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
			DisplaySource.BY_BLOCK.addProvider(offer(BATTERY_STATUS));
			DisplaySource.BY_BLOCK.addProvider(offer(BATTERY_CHARGE));
		});
	}

	/**
	 * A provider rather than a plain {@code BY_BLOCK.add(block, source)}, for two reasons that only
	 * show up in the registry's implementation.
	 *
	 * <p>{@code MultiImpl.get} memoises its answer per block and only {@code invalidate()} clears that
	 * cache — which {@code add} never calls and {@code addProvider} does, by handing the provider an
	 * invalidate hook. Create's own blocks are attached during block registration, strictly before
	 * anything can ask, so they never meet the problem; attaching at common setup does, and a single
	 * query from another mod's setup listener would have cached "no sources for this block" for the rest
	 * of the run. With nothing logged, and with a GameTest still green, because in a test the first
	 * query happens long after setup.
	 *
	 * <p>The block is also looked up lazily here, inside {@code get}, so this no longer depends on
	 * blocks being registered before Create's display-source registry is filled.
	 */
	private static SimpleRegistry.Provider<Block, DisplaySource> offer(
		Supplier<? extends DisplaySource> source) {
		return new SimpleRegistry.Provider<>() {

			@Nullable
			@Override
			public DisplaySource get(Block block) {
				return block == GBBlocks.GRAVITY_BATTERY.get() ? source.get() : null;
			}

			@Override
			public void onRegister(Runnable invalidate) {
				invalidate.run();
			}
		};
	}
}
