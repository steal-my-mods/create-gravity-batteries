package com.creategravitybatteries.client.ponder;

import com.creategravitybatteries.CreateGravityBatteries;

import net.createmod.ponder.api.registration.PonderPlugin;
import com.simibubi.create.infrastructure.ponder.AllCreatePonderTags;

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

public class GBPonderPlugin implements PonderPlugin {

	@Override
	public String getModId() {
		return CreateGravityBatteries.ID;
	}

	@Override
	public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
		helper.forComponents(CreateGravityBatteries.asResource("gravity_battery"))
			.addStoryBoard("gravity_battery", GravityBatteryScenes::storingHeight);
	}

	/**
	 * Joins Create's own Ponder index pages, so the battery is where a player would look for it.
	 *
	 * <p>Four of them, and each one is a claim the block actually makes: it generates Rotational Force,
	 * it anchors a moving structure, a Threshold Switch can read it, and a Display Link can read it.
	 * Registering the display sources and implementing {@code ThresholdSwitchObservable} makes those
	 * things <em>work</em>; this is what makes them <em>findable</em>, which is a separate job and one
	 * that fails silently — the block simply is not in the list, and nothing says so.
	 */
	@Override
	public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
		helper
			.addToTag(AllCreatePonderTags.KINETIC_SOURCES, AllCreatePonderTags.MOVEMENT_ANCHOR,
				AllCreatePonderTags.THRESHOLD_SWITCH_TARGETS, AllCreatePonderTags.DISPLAY_SOURCES)
			.add(CreateGravityBatteries.asResource("gravity_battery"));
	}
}
