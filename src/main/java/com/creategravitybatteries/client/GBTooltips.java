package com.creategravitybatteries.client;

import com.creategravitybatteries.registry.GBItems;
import com.simibubi.create.foundation.item.ItemDescription;
import com.simibubi.create.foundation.item.KineticStats;
import com.simibubi.create.foundation.item.TooltipModifier;

import net.createmod.catnip.lang.FontHelper.Palette;
import net.minecraft.world.item.Item;

/**
 * Gives this mod's block the tooltip a Create block has: a summary line, the behaviour list behind
 * Hold Shift, and the stress bar with its su/RPM.
 *
 * <p>None of that is Create-only machinery. {@code TooltipModifier.REGISTRY} is a public registry
 * keyed by item, Create's client reads it for every item regardless of namespace, and
 * {@code ItemDescription} derives its keys from the item's own description id. Registrate wires this
 * up automatically for Create's own blocks; doing it by hand is the whole difference.
 */
public class GBTooltips {

	public static void register() {
		add(GBItems.GRAVITY_BATTERY.get());
	}

	private static void add(Item item) {
		TooltipModifier modifier = new ItemDescription.Modifier(item, Palette.STANDARD_CREATE)
			.andThen(TooltipModifier.mapNull(KineticStats.create(item)));
		TooltipModifier.REGISTRY.register(item, modifier);
	}
}
