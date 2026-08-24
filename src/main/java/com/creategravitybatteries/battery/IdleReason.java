package com.creategravitybatteries.battery;

/**
 * Why an idle battery is idle. Diagnostic only — nothing branches on it. It exists because "nothing
 * is happening" is the single most confusing state a self-deciding machine can be in, and the goggles
 * are the only place a player can be told which of six different nothings this one is.
 */
public enum IdleReason {

	NONE,
	/** No contraption attached. Glue a weight underneath and activate the battery. */
	NO_WEIGHT,
	/** The weight is already as high as the cable goes; there is nothing left to charge. */
	FULLY_CHARGED,
	/** The weight is sitting on something; there is nothing left to spend. */
	DISCHARGED,
	/** Nothing is turning the shaft, so there is no surplus to draw on. */
	NOT_TURNING,
	/** The network is turning but has less capacity spare than winding would cost. */
	NO_SURPLUS;

	public static IdleReason byOrdinal(int ordinal) {
		IdleReason[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : NONE;
	}

	public String translationKey() {
		return "tooltip.gravity_battery.idle." + name().toLowerCase();
	}
}
