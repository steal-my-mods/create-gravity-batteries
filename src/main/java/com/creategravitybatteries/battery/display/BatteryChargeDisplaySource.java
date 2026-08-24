package com.creategravitybatteries.battery.display;

import com.creategravitybatteries.battery.GravityBatteryBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.network.chat.MutableComponent;

/**
 * How charged a battery is, as a percentage, for a Display Board.
 *
 * <p>A separate source from the status rather than one line carrying both, because that is how Create
 * splits them — one source is one fact, and a Display Link reads one source per line. A player who
 * wants both puts both on two lines of the same board.
 */
public class BatteryChargeDisplaySource extends SingleLineDisplaySource {

	@Override
	protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
		if (!(context.getSourceBlockEntity() instanceof GravityBatteryBlockEntity battery))
			return EMPTY_LINE;
		return CreateLang.number(battery.getChargeFraction() * 100)
			.text("%")
			.component();
	}

	@Override
	protected boolean allowsLabeling(DisplayLinkContext context) {
		return true;
	}
}
