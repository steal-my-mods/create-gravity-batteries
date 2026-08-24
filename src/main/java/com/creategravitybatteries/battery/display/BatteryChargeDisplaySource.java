package com.creategravitybatteries.battery.display;

import com.creategravitybatteries.GBLang;
import com.creategravitybatteries.battery.GravityBatteryBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.PercentOrProgressBarDisplaySource;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * How charged a battery is, for a Display Board — as a percentage or as a progress bar, the player's
 * choice.
 *
 * <p>Extends Create's own {@code PercentOrProgressBarDisplaySource} rather than formatting a number by
 * hand, which is how this started out and was wrong twice over. It rendered
 * {@code CreateLang.number(fraction * 100)}, and Catnip's {@code LangNumberFormat} only drops to two
 * decimal places in a client-side {@code update()} that never runs on a dedicated server — so a board
 * on a multiplayer world read "66.667%" for a value that moves every tick. And it inherited the plain
 * single-line base, so on a Flap Display the charge fell back to an alphabet layout instead of the
 * numeric one every other numeric source in Create uses.
 *
 * <p>The base class gives an integer percentage, the progress bar, the "Number"/"Progress" flap
 * layouts and the pixel flap sections. The bar is the default, because a level that moves every tick is
 * a bar and not a number — the same rule the goggle overlay follows.
 */
public class BatteryChargeDisplaySource extends PercentOrProgressBarDisplaySource {

	@Nullable
	@Override
	protected Float getProgress(DisplayLinkContext context) {
		if (!(context.getSourceBlockEntity() instanceof GravityBatteryBlockEntity battery))
			return null;
		return battery.getChargeFraction();
	}

	@Override
	protected boolean allowsLabeling(DisplayLinkContext context) {
		return true;
	}

	@Override
	protected boolean progressBarActive(DisplayLinkContext context) {
		return context.sourceConfig()
			.getInt("Mode") == 0;
	}

	@Override
	@OnlyIn(Dist.CLIENT)
	public void initConfigurationWidgets(DisplayLinkContext context, ModularGuiLineBuilder builder,
		boolean isFirstLine) {
		super.initConfigurationWidgets(context, builder, isFirstLine);
		if (isFirstLine)
			return;
		builder.addSelectionScrollInput(0, 120,
			(input, label) -> input
				.forOptions(GBLang.translatedOptions("display_source.gravity_battery_charge",
					"progress_bar", "number"))
				.titled(GBLang.translate("display_source.gravity_battery_charge.display")
					.component()),
			"Mode");
	}
}
