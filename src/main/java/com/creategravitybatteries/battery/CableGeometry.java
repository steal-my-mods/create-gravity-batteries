package com.creategravitybatteries.battery;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Where the pieces of a hanging cable go. Pure arithmetic, deliberately not in the renderer.
 *
 * <p>Two reasons for its own class. The rules here are the ones a rendering bug hides in — a cable
 * with a seam in it, or a segment lit from inside a solid block — and a GameTest cannot touch the
 * renderer, because loading a class that mentions {@code PoseStack} on a dedicated server fails. Out
 * here they are testable, and {@code cableGeometryNeverLightsFromInsideTheBattery} is the lock on the
 * one that actually shipped.
 *
 * <h2>The scheme</h2>
 * Segments are one block tall and anchored at the <em>bottom</em> of the cable, at the weight, where a
 * gap would be obvious. That leaves the topmost segment overshooting into the battery's own casing by
 * whatever the fractional part is, which the casing hides — so a new segment appears inside the spool
 * rather than popping into existence on the end of the cable, and one model covers every length.
 * Create's pulley solves the same problem with a second, half-height rope model.
 */
public final class CableGeometry {

	private CableGeometry() {
	}

	/** How many one-block segments to draw for a cable this long. */
	public static int segments(float cableLength) {
		return Mth.ceil(cableLength);
	}

	/**
	 * How far below the battery to draw segment {@code index}, counting up from the weight. Segment 0
	 * starts exactly at the end of the cable; the last one runs into the casing.
	 */
	public static float segmentOffset(float cableLength, int index) {
		return cableLength - index;
	}

	/**
	 * Which block to read the light level from for a piece drawn {@code offset} below the battery.
	 *
	 * <p>Never the battery's own position, and that {@code max} is the whole point of this method.
	 * Truncating an offset under 1 gives 0, which samples the light <em>inside</em> the battery block —
	 * and since the topmost cable segment always has an offset under 1 by design, the first section of
	 * cable rendered almost black while every section below it looked right. Create's pulley truncates
	 * the same way and gets away with it because it never draws a full segment that close to the block.
	 */
	public static BlockPos lightSource(BlockPos batteryPos, float offset) {
		return batteryPos.below(Math.max(1, (int) offset));
	}
}
