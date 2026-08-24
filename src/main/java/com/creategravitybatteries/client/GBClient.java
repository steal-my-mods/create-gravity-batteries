package com.creategravitybatteries.client;

import com.creategravitybatteries.CreateGravityBatteries;
import com.creategravitybatteries.client.ponder.GBPonderPlugin;
import com.creategravitybatteries.registry.GBBlockEntities;

import net.createmod.ponder.foundation.PonderIndex;
import net.createmod.ponder.foundation.registration.PonderLocalization;
import net.createmod.ponder.foundation.registration.PonderSceneRegistry;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

public class GBClient {

	public static void init(IEventBus modBus) {
		modBus.addListener(GBClient::registerRenderers);
		modBus.addListener(GBClient::clientSetup);
		GBPartials.init();
	}

	private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(GBBlockEntities.GRAVITY_BATTERY.get(),
			GravityBatteryRenderer::new);
	}

	private static void clientSetup(FMLClientSetupEvent event) {
		event.enqueueWork(() -> {
			GBTooltips.register();
			PonderIndex.addPlugin(new GBPonderPlugin());
			NeoForge.EVENT_BUS.addListener(GBClient::onClientTick);
		});
	}

	private static boolean ponderChecked;

	private static void onClientTick(ClientTickEvent.Post event) {
		if (ponderChecked)
			return;
		ponderChecked = true;
		checkPonderScenes();
	}

	/**
	 * Compiles this mod's Ponder scenes at startup and reports anything wrong with them.
	 *
	 * <p>Everything about a Ponder scene fails silently. A missing structure, a bad block state and a
	 * missing lang key all load a perfectly clean client and only go wrong when a player opens the
	 * scene — at which point they see raw translation keys, or nothing at all. Compiling the scene is
	 * what populates the localization map, so asking Ponder which keys it wants means running the
	 * storyboard.
	 *
	 * <p>Development only. With the generated files present, none of this can fire.
	 */
	private static void checkPonderScenes() {
		if (FMLEnvironment.production)
			return;
		if (!(PonderIndex.getLangAccess() instanceof PonderLocalization localization))
			return;

		// The static compileScene is the headless path -- it takes a null level, which is how Create's
		// own datagen compiles scenes to harvest their lang. Going through SceneRegistryAccess.compile
		// instead builds a PonderLevel, and that needs a world loaded, so it throws at the title screen.
		int compiled = 0;
		try {
			for (var entry : PonderIndex.getSceneAccess()
				.getRegisteredEntries()) {
				if (!entry.getKey()
					.getNamespace()
					.equals(CreateGravityBatteries.ID))
					continue;
				PonderSceneRegistry.compileScene(localization, entry.getValue(), null);
				compiled++;
			}
		} catch (Exception e) {
			CreateGravityBatteries.LOGGER.warn("A Ponder scene failed to compile", e);
			return;
		}

		// A guard that passes because it inspected nothing is the failure mode it was written for.
		if (compiled == 0) {
			CreateGravityBatteries.LOGGER
				.warn("No Ponder scene compiled for {} -- the plugin may not have registered",
					CreateGravityBatteries.ID);
			return;
		}

		int inspected = 0;
		for (var scene : localization.specific.entrySet()) {
			ResourceLocation sceneId = scene.getKey();
			if (!sceneId.getNamespace()
				.equals(CreateGravityBatteries.ID))
				continue;
			for (var text : scene.getValue()
				.entrySet()) {
				String langKey =
					sceneId.getNamespace() + ".ponder." + sceneId.getPath() + "." + text.getKey();
				inspected++;
				if (!I18n.exists(langKey))
					CreateGravityBatteries.LOGGER.warn(
						"Ponder scene text has no translation: {} -- run tools/generate_structures.py (\"{}\")",
						langKey, text.getValue());
			}
		}

		if (inspected == 0)
			CreateGravityBatteries.LOGGER
				.warn("{} Ponder scene(s) compiled but registered no text to check", compiled);
	}
}
