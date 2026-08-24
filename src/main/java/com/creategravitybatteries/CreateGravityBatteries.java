package com.creategravitybatteries;

import com.creategravitybatteries.registry.GBBlockEntities;
import com.creategravitybatteries.registry.GBBlocks;
import com.creategravitybatteries.registry.GBContraptionTypes;
import com.creategravitybatteries.registry.GBDisplaySources;
import com.creategravitybatteries.registry.GBItems;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create: Gravity Batteries — one block that stores rotational power as height.
 *
 * <p>A Gravity Battery hangs a glued contraption from a cable the way a Rope Pulley does, but it is
 * not steered by the shaft's direction: it watches the kinetic network it is attached to and picks a
 * direction itself. While the network has stress capacity to spare it draws that surplus and winds
 * the weight <em>up</em>. When the rest of the network can no longer carry its own load it lets the
 * weight back <em>down</em>, and the descent drives the shaft.
 *
 * <p>The whole energy model falls out of that: power is the weight, duration is the drop.
 */
@Mod(CreateGravityBatteries.ID)
public class CreateGravityBatteries {

	public static final String ID = "creategravitybatteries";
	public static final Logger LOGGER = LoggerFactory.getLogger("Create: Gravity Batteries");

	public CreateGravityBatteries(IEventBus modBus, ModContainer container) {
		GBBlocks.BLOCKS.register(modBus);
		GBBlockEntities.BLOCK_ENTITIES.register(modBus);
		GBItems.ITEMS.register(modBus);
		GBItems.TABS.register(modBus);
		GBContraptionTypes.CONTRAPTION_TYPES.register(modBus);
		GBDisplaySources.DISPLAY_SOURCES.register(modBus);

		modBus.addListener(GBBlocks::registerStressValues);
		modBus.addListener(GBDisplaySources::attach);

		if (FMLEnvironment.dist == Dist.CLIENT)
			com.creategravitybatteries.client.GBClient.init(modBus);

		container.registerConfig(ModConfig.Type.SERVER, GBConfig.SPEC);
	}

	public static ResourceLocation asResource(String path) {
		return ResourceLocation.fromNamespaceAndPath(ID, path);
	}
}
