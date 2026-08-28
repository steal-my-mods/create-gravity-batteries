package com.creategravitybatteries.battery;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

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

	/**
	 * How far a battery is drawn from, in blocks. The vanilla default, which is also what Create's own
	 * kinetic blocks use.
	 *
	 * <p>Here rather than in the renderer so it can be pinned by a test: the renderer's
	 * {@code getViewDistance()} returns this, and a dedicated server cannot load that class. This was
	 * 128 while the range test measured to the block alone — see
	 * {@link #withinViewRadius(BlockPos, float, Vec3, int)} for why that was the wrong fix.
	 */
	public static final int VIEW_RADIUS = 64;

	/**
	 * Whether any part of a battery's hanging assembly is within {@code radius} of a viewer — the
	 * block, the cable, or the clamp on the end of it.
	 *
	 * <p>The default {@code shouldRender} asks only about the block's own position, and that is the
	 * wrong question for a block whose visible extent hangs as far below it as the cable is long: a weight can be in plain sight while the battery holding it is out of range.
	 * The renderer used to answer that by inflating its view distance to 128, which is the wrong fix
	 * twice over. It still measured to the block, so it only pushed the boundary out rather than
	 * removing it — a battery 130 blocks up with its weight beside you still vanished — and it
	 * quadrupled the radius, and so multiplied by about eight the volume of batteries drawn every
	 * frame. That volume is expensive here in a way it is not for Create's own pulley: with no Flywheel
	 * visual to take over, each battery in it costs {@code 2 + ceil(offset)} CPU vertex passes a frame.
	 *
	 * <p>Measuring to the nearest point of the assembly instead lets the radius go back to the vanilla
	 * 64 that Create's own kinetic blocks use, and answers the original question exactly rather than
	 * approximately.
	 *
	 * <p>Out here rather than in the renderer for the same reason the rest of this class is: a GameTest
	 * runs on a dedicated server, which cannot load a class that mentions {@code PoseStack}.
	 */
	public static boolean withinViewRadius(BlockPos batteryPos, float cableLength, Vec3 viewer,
		int radius) {
		// The assembly occupies the vertical span from the middle of the battery down to the clamp.
		// Clamping the viewer's own height into that span gives the nearest point on it.
		double top = batteryPos.getY() + 0.5;
		double bottom = top - Math.max(0, cableLength);
		double nearestY = Mth.clamp(viewer.y, bottom, top);

		double dx = batteryPos.getX() + 0.5 - viewer.x;
		double dy = nearestY - viewer.y;
		double dz = batteryPos.getZ() + 0.5 - viewer.z;
		return dx * dx + dy * dy + dz * dz < (double) radius * radius;
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
	 * Truncating an offset under 1 gives 0, which samples the light <em>inside</em> the battery rather
	 * than in the open air the cable hangs in — and since the topmost cable segment always has an offset
	 * under 1 by design, the first section of cable rendered almost black while every section below it
	 * looked right. Create's pulley truncates the same way and gets away with it because it never draws
	 * a full segment that close to the block.
	 *
	 * <p>Independent of the block's {@code noOcclusion()}, which fixed the same symptom for the shaft.
	 * That one is why there is any light at the battery's position at all; this one is why the cable
	 * reads the position it actually occupies. Either alone leaves the other wrong.
	 */
	public static BlockPos lightSource(BlockPos batteryPos, float offset) {
		return batteryPos.below(Math.max(1, (int) offset));
	}
}
