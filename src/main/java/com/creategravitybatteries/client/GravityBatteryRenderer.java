package com.creategravitybatteries.client;

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

/**
 * Draws the rotating shaft through the block, and the cable and clamp hanging below it.
 *
 * <p>Deliberately extends {@code SafeBlockEntityRenderer} rather than Create's
 * {@code KineticBlockEntityRenderer}: that one returns immediately when Flywheel is active, because
 * every Create kinetic block also ships a Flywheel visual to take over. This mod has no visual, so
 * inheriting that early return would draw nothing at all under the default backend — no shaft and no
 * cable, only a casing floating above a weight.
 *
 * <h2>Why the cable needs no half-block model</h2>
 * Create's pulley draws whole rope segments plus a half segment to cover the fractional offset. This
 * one anchors the cable at the <em>bottom</em> — at the weight, where a gap would be obvious — and
 * lets the topmost segment overshoot into the battery's own casing, which hides it. So a new segment
 * appears inside the drum rather than popping into existence at the end of the cable, and one model
 * covers every offset.
 */
public class GravityBatteryRenderer extends SafeBlockEntityRenderer<GravityBatteryBlockEntity> {

	public GravityBatteryRenderer(Context context) {
	}

	@Override
	public boolean shouldRenderOffScreen(GravityBatteryBlockEntity be) {
		return true;
	}

	@Override
	public int getViewDistance() {
		// A battery's weight can hang far enough below the block that the block's own chunk is out of
		// range while the weight is in plain sight.
		return 128;
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
		for (int i = 0; i < Mth.ceil(offset); i++)
			renderAt(level, CachedBuffers.partial(GBPartials.CABLE, state), offset - i, pos, ms, vb);
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
		BlockPos litFrom = batteryPos.below((int) offset);
		int light = LevelRenderer.getLightColor((BlockAndTintGetter) level, level.getBlockState(litFrom),
			litFrom);
		partial.translate(0, -offset, 0)
			.light(light)
			.renderInto(ms, buffer);
	}
}
