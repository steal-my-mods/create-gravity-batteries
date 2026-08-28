package com.creategravitybatteries.client;

import com.creategravitybatteries.battery.CableGeometry;
import com.creategravitybatteries.battery.GravityBatteryBlockEntity;
import com.creategravitybatteries.battery.GravityBatteryContraption;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider.Context;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the rotating shaft through the block, and the cable and clamp hanging below it.
 *
 * <p>Deliberately extends {@code SafeBlockEntityRenderer} rather than Create's
 * {@code KineticBlockEntityRenderer}: that one returns immediately when Flywheel is active, because
 * every Create kinetic block also ships a Flywheel visual to take over. This mod has no visual, so
 * inheriting that early return would draw nothing at all under the default backend — no shaft and no
 * cable, only a casing floating above a weight.
 *
 * <p>Where the cable's pieces go is {@link CableGeometry}, not this class: those rules are the ones a
 * rendering bug hides in, and out there a GameTest can reach them.
 */
public class GravityBatteryRenderer extends SafeBlockEntityRenderer<GravityBatteryBlockEntity> {

	public GravityBatteryRenderer(Context context) {
	}

	@Override
	public boolean shouldRenderOffScreen(GravityBatteryBlockEntity be) {
		return true;
	}

	/**
	 * The radius batteries are drawn from. Kept in {@link CableGeometry} so a GameTest can pin it.
	 *
	 * <p>This was 128, to cover a weight hanging in sight below a battery that was not. That is
	 * {@link #shouldRender} 's job now, and it does it by measuring to the nearest point of the
	 * assembly rather than to the block — which answers the original question exactly instead of
	 * approximately, and lets the radius go back to the vanilla default.
	 */
	@Override
	public int getViewDistance() {
		return CableGeometry.VIEW_RADIUS;
	}

	/** Renders while any part of the assembly is in range, not only while the block is. */
	@Override
	public boolean shouldRender(GravityBatteryBlockEntity be, Vec3 cameraPos) {
		return CableGeometry.withinViewRadius(be.getBlockPos(), be.offset, cameraPos,
			getViewDistance());
	}

	@Override
	protected void renderSafe(GravityBatteryBlockEntity be, float partialTicks, PoseStack ms,
		MultiBufferSource buffer, int light, int overlay) {

		BlockState state = be.getBlockState();
		Direction.Axis axis = state.getValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS);
		VertexConsumer vb = buffer.getBuffer(RenderType.solid());

		// Create's own shaft, spun by Create's own transform. The block model leaves a channel through
		// the middle for it.
		SuperByteBuffer shaft = CachedBuffers.block(KineticBlockEntityRenderer.KINETIC_BLOCK,
			KineticBlockEntityRenderer.shaft(axis));
		KineticBlockEntityRenderer
			.kineticRotationTransform(shaft, be, axis,
				KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), axis), light)
			.renderInto(ms, vb);

		if (!isRunning(be))
			return;

		Level level = be.getLevel();
		BlockPos pos = be.getBlockPos();
		float offset = renderedOffset(be, partialTicks);

		renderAt(level, CachedBuffers.partial(GBPartials.HOOK, state), offset, pos, ms, vb);
		for (int i = 0; i < CableGeometry.segments(offset); i++)
			renderAt(level, CachedBuffers.partial(GBPartials.CABLE, state),
				CableGeometry.segmentOffset(offset, i), pos, ms, vb);
	}

	/**
	 * How far below the block to draw the end of the cable.
	 *
	 * <p>Read off the contraption entity when there is one, rather than off the block entity's own
	 * offset. The two are within a tick of each other, and a tick's discrepancy between the cable and
	 * the thing it is holding up is exactly the artefact a player notices.
	 */
	private static float renderedOffset(GravityBatteryBlockEntity be, float partialTicks) {
		AbstractContraptionEntity attached = be.getAttachedContraption();
		if (attached != null
			&& attached.getContraption() instanceof GravityBatteryContraption contraption) {
			double entityY = Mth.lerp((double) partialTicks, attached.yOld, attached.getY());
			return (float) -(entityY - contraption.anchor.getY() - contraption.getInitialOffset());
		}
		return be.getInterpolatedOffset(partialTicks);
	}

	/** True in a Ponder scene too, where there is no contraption and the cable is the whole story. */
	private static boolean isRunning(GravityBatteryBlockEntity be) {
		return be.running || be.isVirtual();
	}

	private static void renderAt(Level level, SuperByteBuffer partial, float offset, BlockPos batteryPos,
		PoseStack ms, VertexConsumer buffer) {
		BlockPos litFrom = CableGeometry.lightSource(batteryPos, offset);
		int light = LevelRenderer.getLightColor((BlockAndTintGetter) level, level.getBlockState(litFrom),
			litFrom);
		partial.translate(0, -offset, 0)
			.light(light)
			.renderInto(ms, buffer);
	}
}
