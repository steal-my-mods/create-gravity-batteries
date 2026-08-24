package com.creategravitybatteries.battery.display;

import com.creategravitybatteries.GBLang;
import com.creategravitybatteries.battery.BatteryMode;
import com.creategravitybatteries.battery.GravityBatteryBlockEntity;
import com.creategravitybatteries.battery.IdleReason;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.network.chat.MutableComponent;

/**
 * What a battery is doing, for a Display Board.
 *
 * <p>This is Create's answer to "surface a machine's state", and it is the reason the comparator was
 * left reading the charge instead: a comparator carries a level, and overloading it to carry a mode as
 * well would mean neither could be read properly.
 *
 * <p>When the battery is idle it reports the <em>reason</em> rather than the word "Holding". On a
 * board a player is glancing at from across the room, "Nothing attached" and "Weight is resting" are
 * the two things worth knowing, and "Holding" is not either of them.
 */
public class BatteryStatusDisplaySource extends SingleLineDisplaySource {

	@Override
	protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
		if (!(context.getSourceBlockEntity() instanceof GravityBatteryBlockEntity battery))
			return EMPTY_LINE;
		if (battery.getMode() == BatteryMode.IDLE && battery.getIdleReason() != IdleReason.NONE)
			return GBLang.translate(battery.getIdleReason()
				.translationKey())
				.component();
		return GBLang.translate(battery.getMode()
			.translationKey())
			.component();
	}

	@Override
	protected boolean allowsLabeling(DisplayLinkContext context) {
		return true;
	}
}
