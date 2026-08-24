package com.creategravitybatteries.battery;

import com.creategravitybatteries.registry.GBBlockEntities;
import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The block half of the battery: a horizontal-axis kinetic block with the drum on the axis, so a
 * shaft goes in either side and the cable pays out of the bottom.
 *
 * <p>An empty-handed use toggles whether the weight below is attached. That is the whole interface —
 * everything else the block does, it decides.
 */
public class GravityBatteryBlock extends HorizontalAxisKineticBlock
	implements IBE<GravityBatteryBlockEntity> {

	public GravityBatteryBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
		BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
		if (!player.mayBuild())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (player.isShiftKeyDown())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
		if (!stack.isEmpty())
			return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

		// Deferred to the next tick rather than done here, the way Create's pulley does it: assembling
		// tears blocks out of the world and spawns an entity, which is not work to be doing inside a
		// use handler that may be running on either side.
		withBlockEntityDo(level, pos, be -> {
			be.toggleNextTick = true;
			be.setChanged();
		});
		return ItemInteractionResult.SUCCESS;
	}

	/**
	 * A comparator on a battery reads its charge, so "start the backup boiler when the weight is
	 * nearly down" is a redstone line rather than a mod feature.
	 */
	@Override
	protected boolean hasAnalogOutputSignal(BlockState state) {
		return true;
	}

	@Override
	protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
		GravityBatteryBlockEntity be = getBlockEntity((BlockGetter) level, pos);
		return be == null ? 0 : be.getComparatorOutput();
	}

	@Override
	public Class<GravityBatteryBlockEntity> getBlockEntityClass() {
		return GravityBatteryBlockEntity.class;
	}

	@Override
	public BlockEntityType<? extends GravityBatteryBlockEntity> getBlockEntityType() {
		return GBBlockEntities.GRAVITY_BATTERY.get();
	}
}
