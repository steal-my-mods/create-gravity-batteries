package com.creategravitybatteries.client.ponder;

import com.creategravitybatteries.CreateGravityBatteries;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
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
}
