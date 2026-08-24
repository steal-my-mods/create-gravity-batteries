package com.creategravitybatteries.battery;

import java.util.Locale;

/**
 * What the battery is doing with its weight. Persisted by ordinal and synced to the client, which
 * needs it to decide whether the drum is turning and which way.
 */
public enum BatteryMode {

	/** Holding station. No load on the network, no capacity supplied, no movement. */
	IDLE,
	/** Drawing the network's surplus to wind the weight up. A load, like any Create machine. */
	CHARGING,
	/** Letting the weight down to drive the shaft. A generator, like a Steam Engine. */
	DISCHARGING;

	public static BatteryMode byOrdinal(int ordinal) {
		BatteryMode[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IDLE;
	}

	/**
	 * {@code Locale.ROOT}, not the default locale. Under a Turkish locale {@code "IDLE".toLowerCase()}
	 * is "\u0131dle" with a dotless i, which would put a key nobody has translated on the goggles and on
	 * a Display Board — the same silent failure as the unprefixed keys this mod shipped once.
	 */
	public String translationKey() {
		return "tooltip.gravity_battery.mode." + name().toLowerCase(Locale.ROOT);
	}
}
