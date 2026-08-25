package com.creategravitybatteries.battery;

import com.creategravitybatteries.GBLang;
import com.creategravitybatteries.registry.GBContraptionTypes;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.TranslatingContraption;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;

/**
 * The hanging weight. Structurally this is Create's {@code PulleyContraption} — a translating
 * contraption anchored below a block, with the column between it and that block treated as part of
 * the anchor so the search cannot climb the cable — and it is deliberately not a subclass of it.
 *
 * <p>Two reasons for the copy rather than the subclass. A {@link ContraptionType} decides which class
 * the contraption is deserialized into, so sharing Create's {@code pulley} type would have a Gravity
 * Battery's weight come back from disk as a Rope Pulley's; and Create's block-movement rules key off
 * the contraption type through tags, so a distinct type is what lets a pack author say "batteries may
 * move X" without saying it about rope pulleys too.
 */
public class GravityBatteryContraption extends TranslatingContraption {

	/**
	 * How far below the battery the weight hung when it was picked up. The entity's Y position is
	 * measured from here, so a saved contraption comes back at the height it was at rather than at
	 * the battery.
	 */
	private int initialOffset;

	public GravityBatteryContraption() {
	}

	public GravityBatteryContraption(int initialOffset) {
		this.initialOffset = initialOffset;
	}

	@Override
	public ContraptionType getType() {
		return GBContraptionTypes.GRAVITY_BATTERY.get();
	}

	/**
	 * Refuses a weight with machinery in it, before anything has started moving.
	 *
	 * <p>Create calls these actors — drills, saws, harvesters, deployers, portable interfaces — and they
	 * are a category error on a battery rather than a bug to be fixed. An actor <em>stalls</em> the
	 * contraption while it works, which freezes the offset, and the whole energy model rests on the one
	 * coupling that breaks: time spent equals height lost. A stalled weight supplies its full rating
	 * while descending nothing, so a drill parked on obsidian is a power station whose output is set by
	 * block hardness.
	 *
	 * <p>Two attempts to withhold that payment instead both ended up worse than the disease. Withholding
	 * moves the mode — directly, or through the network balance, since capacity is an input to the
	 * decision as well as an output of it — a mode change moves the generated speed, and Create's
	 * actuator calls {@code Contraption.stop} on a sign change, which resets the actors and clears the
	 * stall. With the stall gone nothing holds the weight against the block, because Create's collision
	 * test exempts whatever a block-breaking actor could break. Measured: a weight ten blocks down with
	 * all six blocks it "cut" still standing.
	 *
	 * <p>So the rule is that a battery hangs a weight, not a machine. Create already has the block for
	 * cutting a shaft with drills on it, and it is the Rope Pulley: cut the shaft first, then install
	 * the battery in it. Two blocks, two jobs, and the reason each one is good at its job.
	 */
	@Override
	public boolean assemble(Level world, BlockPos pos) throws AssemblyException {
		if (!searchMovedStructure(world, pos, null))
			return false;

		for (StructureBlockInfo info : getBlocks().values()) {
			if (MovementBehaviour.REGISTRY.get(info.state()) == null)
				continue;
			throw new AssemblyException(GBLang
				.translate("assembly.no_actors", info.state()
					.getBlock()
					.getName())
				.component());
		}

		startMoving(world);
		return true;
	}

	/**
	 * Everything in the column between the anchor and the battery counts as anchored, so the search
	 * stops there instead of climbing up and trying to take the battery — or the ceiling it is bolted
	 * to — along for the ride.
	 */
	@Override
	protected boolean isAnchoringBlockAt(BlockPos pos) {
		if (pos.getX() != anchor.getX() || pos.getZ() != anchor.getZ())
			return false;
		int y = pos.getY();
		return y > anchor.getY() && y <= anchor.getY() + initialOffset + 1;
	}

	@Override
	public CompoundTag writeNBT(HolderLookup.Provider registries, boolean spawnPacket) {
		CompoundTag tag = super.writeNBT(registries, spawnPacket);
		tag.putInt("InitialOffset", initialOffset);
		return tag;
	}

	@Override
	public void readNBT(Level world, CompoundTag nbt, boolean spawnData) {
		initialOffset = nbt.getInt("InitialOffset");
		super.readNBT(world, nbt, spawnData);
	}

	public int getInitialOffset() {
		return initialOffset;
	}
}
