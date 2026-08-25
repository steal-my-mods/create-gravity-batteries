package com.creategravitybatteries.battery;

import java.util.List;

import com.creategravitybatteries.GBConfig;
import com.creategravitybatteries.GBLang;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchObservable;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.utility.CreateLang;
import com.simibubi.create.foundation.utility.ServerSpeedProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The whole mod: a linear actuator that is not steered by its shaft.
 *
 * <h2>How it decides</h2>
 * Every tick it works out the network's balance <em>excluding itself</em> — subtracting the very
 * numbers it last handed the network rather than recomputing them, so the subtraction is exact — and
 * then:
 *
 * <ul>
 * <li>if the rest of the network cannot carry its own load, it lets the weight down and generates;
 * <li>if the rest of the network has spare capacity for its draw <em>plus a margin</em>, it winds up;
 * <li>otherwise it holds station.
 * </ul>
 *
 * <p>Excluding itself is what makes this stable. A battery that read total capacity minus total
 * stress would start winding, see the deficit its own draw created, flip to discharging, see the
 * surplus its own capacity created, and flip back — once per tick, for ever. Measuring what
 * everything <em>else</em> is doing means the quantity it tests does not move when it acts on it, and
 * {@link GBConfig#chargeMarginStress} leaves a band in the middle where neither test fires.
 *
 * <h2>Why the direction is not the shaft's</h2>
 * A Rope Pulley goes up or down according to which way you turn it. This block cannot work that way:
 * when it discharges it <em>is</em> the source, so there is no external direction to read, and the
 * direction it needs is decided by the network's balance rather than by the player. So
 * {@link #getMovementSpeed()} takes the magnitude of the shaft speed and the sign from
 * {@link #getMode() the mode}. Turn a battery either way and it winds up; it lets down on its own.
 *
 * <h2>The energy model</h2>
 * Power is {@code weight × stressPerBlock × rpm}; the descent rate is {@code rpm ÷ (512 ×
 * gearReduction)} blocks per tick. Multiply power by the time it takes to fall its drop and the RPM
 * cancels — total energy is proportional to {@code weight × drop} and nothing else. So <b>the weight
 * is the power rating and the drop is the runtime</b>, which is both what a real gravity battery does
 * and the shortest true description of this block.
 *
 * <h2>What is borrowed</h2>
 * {@link LinearActuatorBlockEntity} is Create's, and so is the generator half of this class:
 * {@link #updateGeneratedRotation()} and {@link #applyNewSpeed(float, float)} are
 * {@code GeneratingKineticBlockEntity}'s, transcribed rather than inherited because Java has one
 * superclass and the actuator had already claimed it. See NOTICE.md.
 */
public class GravityBatteryBlockEntity extends LinearActuatorBlockEntity
	implements ThresholdSwitchObservable {

	/**
	 * Ticks to wait before deciding anything. A block that has only just been placed has not been
	 * found by the rotation propagator yet, so it reads as having no source and no network capacity —
	 * which looks exactly like being the only thing that could turn the shaft. Without this, a battery
	 * placed next to a running motor spends its first tick discharging.
	 */
	private static final int WARMUP_TICKS = 5;

	private BatteryMode mode = BatteryMode.IDLE;
	private IdleReason idleReason = IdleReason.NO_WEIGHT;
	private int warmup = WARMUP_TICKS;

	/**
	 * Blocks in the hanging contraption, capped by {@link GBConfig#maxWeightBlocks}. This is the
	 * battery's power rating and it has to be synced: the goggle overlay quotes it, and the client has
	 * no contraption to count until the entity has arrived.
	 */
	private int weightBlocks;

	/**
	 * How far the weight can drop before it is resting on something — measured once, at assembly, by
	 * walking Create's own collision test down the shaft. This is the battery's capacity: the charge
	 * reading is {@code (restingOffset - offset) / restingOffset}, and a battery whose weight is
	 * already on the floor reads empty rather than reading full because the cable happens to be short.
	 */
	private float restingOffset;

	/** Set when the battery has flipped its declared direction to agree with a turning shaft. */
	private boolean reversed;

	/**
	 * The last speed this battery saw the network running at under someone else's power. Discharging
	 * is capped to it so a failover does not change how fast the factory runs — see
	 * {@link #getGeneratedSpeed()}.
	 */
	private float rememberedSpeed;

	/** Set by the block on use; consumed next tick. Attaching a weight is not a tick-loop decision. */
	public boolean toggleNextTick;

	/** Copy of {@link #offset} from the previous tick, for Ponder's interpolation. */
	private float prevAnimatedOffset;

	/** Create's own generator re-activation flag, from {@code GeneratingKineticBlockEntity}. */
	private boolean reActivateSource;

	/**
	 * What a comparator was last told. Comparators do not poll — a block with an analog output has to
	 * nudge its neighbours when the value moves, or the reading latches once and then goes stale.
	 */
	private int lastComparatorOutput;

	public GravityBatteryBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);
		// A Gravity Battery never lets go of its weight, so the actuator's Movement Mode option has
		// nothing to choose between and showing the scroll slot would only promise a setting that does
		// nothing. The field itself stays -- the base class reads it through getMovementMode().
		behaviours.remove(movementMode);
	}

	public BatteryMode getMode() {
		return mode;
	}

	public IdleReason getIdleReason() {
		return idleReason;
	}

	public int getWeightBlocks() {
		return weightBlocks;
	}

	public float getDropRange() {
		return restingOffset;
	}

	/** 0 when the weight is resting, 1 when it is as high as this battery has room to lift it. */
	public float getChargeFraction() {
		if (restingOffset <= 0)
			return 0;
		return Mth.clamp((restingOffset - offset) / restingOffset, 0, 1);
	}

	// --- kinetics ---------------------------------------------------------------------------------

	/** Stress Units per RPM this battery is worth in either direction, before losses. */
	public float ratingPerRpm() {
		return weightBlocks * GBConfig.stressPerBlock();
	}

	/**
	 * The tier is a ceiling, not a target.
	 *
	 * <p>A battery that always declared {@link GBConfig#maxRpm} would yank the whole network up to it
	 * the moment it took over — every belt and every machine changing pace at once because a water
	 * wheel iced over. Holding the speed the network was already running at costs nothing: whether the
	 * battery can carry the load is decided by {@link #ratingPerRpm()}, and the load scales with speed
	 * in exactly the same way, so the coverage at 8 RPM and at 64 RPM is identical. The cap is a
	 * {@code min}, so gearing the network up raises the remembered speed but never the ceiling.
	 */
	@Override
	public float getGeneratedSpeed() {
		if (mode != BatteryMode.DISCHARGING || weightBlocks <= 0)
			return 0;
		float ceiling = GBConfig.maxRpm();
		float target = rememberedSpeed > 0 ? Math.min(ceiling, rememberedSpeed) : ceiling;
		return (reversed ? -1 : 1) * target;
	}

	/**
	 * Winding up costs what letting down pays, divided by the round-trip efficiency. Charging the loss
	 * to this side rather than to the descent is what makes a self-charging loop impossible: a
	 * discharging battery supplies strictly less than a charging battery of the same weight wants, so
	 * two of them on one shaft can never close the circuit however they are geared.
	 *
	 * <p>One method, read by both the network and {@link #decideMode()} through
	 * {@link #getChargeDraw()}. Written twice they can disagree, and a battery that decided on one
	 * figure and was billed another would refuse loops it could actually fund -- which is the failure
	 * mode a mutation test caught, because the decision was still using the honest number.
	 */
	public float chargeImpactPerRpm() {
		return ratingPerRpm() / GBConfig.roundTripEfficiency();
	}

	@Override
	public float calculateStressApplied() {
		float impact = mode == BatteryMode.CHARGING ? chargeImpactPerRpm() : 0;
		this.lastStressApplied = impact;
		return impact;
	}

	@Override
	public float calculateAddedStressCapacity() {
		float added = mode == BatteryMode.DISCHARGING ? ratingPerRpm() : 0;
		this.lastCapacityProvided = added;
		return added;
	}

	/**
	 * What this battery is currently contributing to the network's totals — the values the network
	 * actually has recorded for it, not fresh calculations, so subtracting them leaves exactly what
	 * everything else is doing.
	 */
	public float networkCapacityWithoutSelf() {
		return capacity - lastCapacityProvided * Math.abs(getGeneratedSpeed());
	}

	public float networkStressWithoutSelf() {
		return stress - lastStressApplied * Math.abs(getTheoreticalSpeed());
	}

	/** Stress this battery would draw if it started winding at the shaft's current speed. */
	public float getChargeDraw() {
		return chargeImpactPerRpm() * Math.abs(getTheoreticalSpeed());
	}

	/**
	 * Records how fast the network runs when something else is driving it. Only when something else
	 * is: while this battery is the source, the speed it reads is its own output, and remembering that
	 * would pin the ceiling to wherever it happened to settle instead of to what the network is for.
	 */
	private void rememberNetworkSpeed() {
		if (!hasSource())
			return;
		float observed = Math.abs(getTheoreticalSpeed());
		if (observed > 0 && observed != rememberedSpeed) {
			rememberedSpeed = observed;
			setChanged();
		}
	}

	/**
	 * Never fight a shaft that is already turning. This is the Steam Engine's own answer to the
	 * problem — flip the declared direction rather than assert one that would have
	 * {@link #applyNewSpeed} destroy the block.
	 */
	private void alignDirectionWith(float shaftSpeed) {
		if (shaftSpeed == 0)
			return;
		if (shaftSpeed > 0 != !reversed) {
			reversed = !reversed;
			setChanged();
		}
	}

	// --- the loop ---------------------------------------------------------------------------------

	@Override
	public void tick() {
		if (level != null && !level.isClientSide && toggleNextTick) {
			toggleNextTick = false;
			if (running || movedContraption != null)
				disassemble();
			else
				tryAssemble();
		}

		float offsetBefore = offset;
		super.tick();

		// GeneratingKineticBlockEntity's half of tick(). A source that lost its rotation to a stronger
		// one has to re-assert itself when that one goes away.
		if (reActivateSource) {
			updateGeneratedRotation();
			reActivateSource = false;
		}

		if (isVirtual())
			prevAnimatedOffset = offsetBefore;
		invalidateRenderBoundingBox();

		if (level == null || level.isClientSide)
			return;

		if (warmup > 0) {
			warmup--;
			return;
		}

		alignDirectionWith(getTheoreticalSpeed());
		rememberNetworkSpeed();

		// A full wind-up re-measures the drop. Nothing else notices a floor that was dug out or built
		// up after the weight was picked up, and one probe per charge cycle is a price worth paying to
		// keep the charge reading honest. On the tick it *arrives* at the top, not every tick it spends
		// there: the probe walks the whole shaft, and a battery sitting fully wound would walk it for
		// ever.
		if (running && movedContraption != null && offset <= 0 && offsetBefore > 0)
			reprobeDrop();

		BatteryMode desired = decideMode();
		if (desired != mode)
			setMode(desired);

		refreshComparator();
	}

	/**
	 * Tells any comparator that the charge has moved.
	 *
	 * <p>Declaring {@code hasAnalogOutputSignal} is only half of a comparator output: nothing polls it.
	 * Without this the reading a comparator latched when it was placed never changed again, which made
	 * the whole feature look like it worked and do nothing. Gated on the value so this costs at most
	 * sixteen block updates over a battery's entire travel.
	 */
	private void refreshComparator() {
		int output = getComparatorOutput();
		if (output == lastComparatorOutput)
			return;
		lastComparatorOutput = output;
		level.updateNeighbourForOutputSignal(worldPosition, getBlockState().getBlock());
	}

	private BatteryMode decideMode() {
		if (!IRotate.StressImpact.isEnabled())
			return idle(IdleReason.NONE);
		if (!running || movedContraption == null || weightBlocks <= 0)
			return idle(IdleReason.NO_WEIGHT);

		// A stalled contraption is one whose actors are busy -- a drill on the weight's underside
		// grinding through a block, most often. Create freezes the offset while that lasts, so a
		// battery that kept its mode would supply its full rating with the weight motionless, for as
		// long as the drill took. Which is for ever, if it is on something it cannot get through.
		// Capacity is only ever paid for by descent; no movement, no trade, in either direction.
		if (movedContraption.isStalled())
			return idle(IdleReason.JAMMED);

		float headroom = networkCapacityWithoutSelf() - networkStressWithoutSelf();

		// Nothing else on this network can turn it, and there is something attached worth turning. The
		// second half matters: without it a charged battery in an empty room would spin against
		// nothing and quietly lower its own weight to the floor.
		boolean soleSource = !hasSource() && networkCapacityWithoutSelf() <= 0 && hasSomethingToDrive();

		if (headroom < 0 || soleSource) {
			if (!canDescend())
				return idle(IdleReason.DISCHARGED);
			idleReason = IdleReason.NONE;
			return BatteryMode.DISCHARGING;
		}

		if (Math.abs(getTheoreticalSpeed()) == 0)
			return idle(IdleReason.NOT_TURNING);
		if (!canAscend())
			return idle(IdleReason.FULLY_CHARGED);

		float draw = getChargeDraw();
		if (draw > 0 && headroom >= draw + GBConfig.chargeMarginStress()) {
			idleReason = IdleReason.NONE;
			return BatteryMode.CHARGING;
		}
		return idle(IdleReason.NO_SURPLUS);
	}

	private BatteryMode idle(IdleReason reason) {
		if (idleReason != reason) {
			idleReason = reason;
			sendData();
		}
		return BatteryMode.IDLE;
	}

	/** There is somewhere for the weight to go, and it is not already resting on something. */
	public boolean canDescend() {
		return offset < restingOffset - 1.0E-3F && offset < getExtensionRange();
	}

	public boolean canAscend() {
		return offset > 1.0E-3F;
	}

	/** Whether either shaft face has a kinetic block on it that would take the rotation. */
	private boolean hasSomethingToDrive() {
		Direction.Axis axis = getRotationAxisOfThis();
		for (Direction.AxisDirection sign : Direction.AxisDirection.values()) {
			Direction side = Direction.get(sign, axis);
			BlockPos neighbour = worldPosition.relative(side);
			BlockState state = level.getBlockState(neighbour);
			if (state.getBlock() instanceof IRotate rotate
				&& rotate.hasShaftTowards(level, neighbour, state, side.getOpposite()))
				return true;
		}
		return false;
	}

	private Direction.Axis getRotationAxisOfThis() {
		BlockState state = getBlockState();
		return state.getBlock() instanceof IRotate rotate ? rotate.getRotationAxis(state)
			: Direction.Axis.X;
	}

	private void setMode(BatteryMode next) {
		mode = next;
		refreshKineticContribution();
		setChanged();
		sendData();
	}

	/**
	 * Re-tells the network what this battery is worth. Also what starts or stops the shaft when this
	 * battery is the network's root, since {@code updateGeneratedRotation} is where that happens.
	 */
	private void refreshKineticContribution() {
		updateGeneratedRotation();
		// updateGeneratedRotation only refreshes stress while its own speed is non-zero, so releasing
		// a charger's load on a network this battery does not drive has to be done here.
		if (hasNetwork()) {
			getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
			getOrCreateNetwork().updateStress();
		}
	}

	// --- assembly ---------------------------------------------------------------------------------

	/** The player's path: an explicit activation may reach down the shaft for a weight. */
	private void tryAssemble() {
		try {
			assemble(true);
			lastException = null;
		} catch (AssemblyException e) {
			lastException = e;
		}
		sendData();
	}

	/**
	 * The rotation path. Rotation arriving at a battery is not permission to go looking for something
	 * to pick up — see {@link #findWeightOffset(boolean)}.
	 */
	@Override
	protected void assemble() throws AssemblyException {
		assemble(false);
	}

	/**
	 * Picks up the weight hanging below.
	 *
	 * <p>Unlike a Rope Pulley this does not walk down a column of rope blocks, because there are none
	 * to walk: a battery's cable is drawn, not placed, so the only record of where a weight hangs is
	 * the block that is actually there.
	 */
	private void assemble(boolean mayReach) throws AssemblyException {
		if (level == null || level.isClientSide)
			return;
		if (movedContraption != null)
			return;

		int found = findWeightOffset(mayReach);
		if (found < 0)
			return;

		BlockPos anchorPos = worldPosition.below(found + 1);
		GravityBatteryContraption contraption = new GravityBatteryContraption(found);
		if (!contraption.assemble(level, anchorPos))
			return;
		if (contraption.getBlocks()
			.isEmpty())
			return;

		offset = found;
		contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
		weightBlocks = Math.min(contraption.getBlocks()
			.size(), GBConfig.maxWeightBlocks());
		restingOffset = probeDrop(contraption, anchorPos, found);

		movedContraption = ControlledContraptionEntity.create(level, this, contraption);
		movedContraption.setPos(anchorPos.getX(), anchorPos.getY(), anchorPos.getZ());
		level.addFreshEntity((Entity) movedContraption);
		forceMove = true;
		clientOffsetDiff = 0;
		running = true;
		sendData();
	}

	/**
	 * Where the weight is, if there is one.
	 *
	 * <p>Two rules, and the second is only offered to a player: flush against the battery, which is
	 * where a Rope Pulley picks its load up from; or anywhere down the shaft, <em>only</em> when a
	 * player has right-clicked the block.
	 *
	 * <p>That restriction is not fussiness. Rotation reaching a battery is not a request: an unattended
	 * battery that got power would walk up to {@link GBConfig#maxCableLength} blocks down, find the
	 * first solid thing, and tear it out of the world — someone's floor, or the terrain — and then
	 * never let go, because that is what a battery does. A player right-clicking is looking at the
	 * block, has asked for it, and can undo it with a second click; rotation arriving on a shaft is
	 * none of those things.
	 *
	 * <p>There used to be a third rule ahead of both: the offset this battery last held a weight at, so
	 * a released weight could be picked back up without rebuilding it. It was a hole straight through
	 * the restriction — {@link #disassemble()} left the offset in place and it persists, so a battery
	 * that had once held something 20 blocks down would let rotation alone take whatever later stood at
	 * 20. The player path's scan finds a released weight at the same offset anyway, so the rule bought
	 * nothing that is not still there.
	 */
	private int findWeightOffset(boolean mayReach) {
		if (isWeightAt(0))
			return 0;
		if (!mayReach)
			return -1;

		int range = getExtensionRange();
		for (int candidate = 1; candidate <= range; candidate++)
			if (isWeightAt(candidate))
				return candidate;
		return -1;
	}

	private boolean isWeightAt(int candidate) {
		BlockState state = level.getBlockState(worldPosition.below(candidate + 1));
		return !state.isAir() && !state.canBeReplaced();
	}

	/**
	 * How far this weight can fall, measured with Create's own collision test rather than guessed.
	 *
	 * <p>One walk down the shaft at assembly time, which is cheap and is what makes the charge reading
	 * mean something: a battery is empty when its weight is on the floor, not when its cable is paid
	 * out to some configured maximum that has nothing to do with the room it is standing in.
	 */
	private float probeDrop(GravityBatteryContraption contraption, BlockPos anchorPos, int startOffset) {
		int range = getExtensionRange();
		for (int drop = 1; startOffset + drop <= range; drop++)
			if (ContraptionCollider.isCollidingWithWorld(level, contraption, anchorPos.below(drop),
				Direction.DOWN))
				return startOffset + drop - 1;
		return range;
	}

	/** Re-runs the probe against the contraption already hanging, from wherever it is now. */
	private void reprobeDrop() {
		if (movedContraption == null
			|| !(movedContraption.getContraption() instanceof GravityBatteryContraption contraption))
			return;
		restingOffset = probeDrop(contraption, worldPosition.below((int) offset + 1), (int) offset);
		sendData();
	}

	/**
	 * Never on its own. A Rope Pulley drops its load whenever it stops; a battery that did that would
	 * put its weight back as blocks every time the factory came back online, and there would be
	 * nothing left to store anything in. It lets go when the block is broken, or when a player asks it
	 * to.
	 */
	@Override
	protected void tryDisassemble() {
		if (remove)
			disassemble();
	}

	@Override
	public void disassemble() {
		if (!running && movedContraption == null)
			return;

		offset = getGridOffset(offset);
		if (movedContraption != null) {
			resetContraptionToOffset();
			if (!level.isClientSide)
				movedContraption.disassemble();
			movedContraption.discard();
		}
		movedContraption = null;
		running = false;
		weightBlocks = 0;
		restingOffset = 0;
		// Nothing on the end of the cable means no offset either. Leaving it set was what let rotation
		// alone reach for a block at the offset a weight used to hang at -- see findWeightOffset.
		offset = 0;
		mode = BatteryMode.IDLE;
		idleReason = IdleReason.NO_WEIGHT;
		// Not through setMode: on the removal path the network is already being torn down by
		// KineticBlockEntity#remove, and re-declaring a speed into it there is how you get a ghost
		// source left behind on the network.
		if (!remove && !level.isClientSide)
			refreshKineticContribution();
		sendData();
	}

	/**
	 * Keeps the offset exactly where it was across a speed change.
	 *
	 * <p>{@code LinearActuatorBlockEntity#onSpeedChanged} re-grids the offset whenever the rotation's
	 * sign changes with a contraption attached, and the arithmetic is
	 * {@code Math.round(offset * 16f) / 16} — an <em>integer</em> division. Confirmed in Create's
	 * bytecode: {@code Math.round:(F)I}, {@code bipush 16}, {@code idiv}, {@code i2f}. So it does not
	 * snap to a sixteenth of a block as it clearly means to, it truncates to a whole one.
	 *
	 * <p>A Rope Pulley barely notices, because it re-grids on stop anyway. For a battery the offset
	 * <em>is</em> the charge, which made this free energy rather than a cosmetic jump:
	 * {@code Math.signum(0) == 0}, so every transition from turning to stopped counts as a sign change,
	 * and each one lifted the weight by up to a whole block without paying a single Stress Unit for it.
	 * Flicking the drive on and off was a charging strategy. {@code losingTheDriveDoesNotMoveTheWeight}
	 * is the lock.
	 */
	@Override
	public void onSpeedChanged(float prevSpeed) {
		float keep = offset;
		super.onSpeedChanged(prevSpeed);
		if (offset == keep)
			return;
		offset = keep;
		resetContraptionToOffset();
	}

	/**
	 * The weight has run into something. Recording where is what stops a blocked battery generating for
	 * nothing: capacity is only ever supplied while the weight can actually descend, and
	 * {@link #canDescend()} is what enforces that.
	 *
	 * <p>Only a collision on the way <em>down</em> tells us anything about the drop. Hitting something
	 * while winding up says only that the way up is blocked, and clamping the drop to that would leave
	 * the battery reading empty — no charge, nothing to spend, {@link IdleReason#DISCHARGED} — with a
	 * full weight hanging over a clear shaft.
	 */
	@Override
	protected void collided() {
		boolean descending = mode == BatteryMode.DISCHARGING;
		super.collided();
		if (level == null || level.isClientSide)
			return;
		if (descending) {
			restingOffset = Math.min(restingOffset, offset);
			setMode(BatteryMode.IDLE);
		}
		sendData();
	}

	// --- actuator geometry ------------------------------------------------------------------------

	/**
	 * Magnitude from the shaft, sign from the mode. This is the one place where a Gravity Battery is
	 * not a Rope Pulley, and everything else about the block follows from it.
	 */
	@Override
	public float getMovementSpeed() {
		float rate = Math.abs(convertToLinear(getSpeed())) / GBConfig.gearReduction();
		float directed = switch (mode) {
			case DISCHARGING -> rate;
			case CHARGING -> -rate;
			case IDLE -> 0F;
		};
		float movementSpeed = Mth.clamp(directed, -0.49F, 0.49F) + clientOffsetDiff / 2F;
		if (level != null && level.isClientSide)
			movementSpeed *= ServerSpeedProvider.get();
		return movementSpeed;
	}

	@Override
	protected int getExtensionRange() {
		return Math.max(0, Math.min(GBConfig.maxCableLength(),
			worldPosition.getY() - 1 - level.getMinBuildHeight()));
	}

	/** Only consulted by the movement modes this block does not offer. */
	@Override
	protected int getInitialOffset() {
		return 0;
	}

	@Override
	protected Vec3 toMotionVector(float speed) {
		return new Vec3(0, -speed, 0);
	}

	@Override
	protected Vec3 toPosition(float offset) {
		if (movedContraption == null)
			return Vec3.ZERO;
		Contraption contraption = movedContraption.getContraption();
		if (contraption instanceof GravityBatteryContraption gravity)
			return Vec3.atLowerCornerOf((Vec3i) gravity.anchor)
				.add(0, gravity.getInitialOffset() - offset, 0);
		return Vec3.ZERO;
	}

	@Override
	protected ValueBoxTransform getMovementModeSlot() {
		return new CenteredSideValueBoxTransform((state, d) -> d == Direction.UP);
	}

	@Override
	protected AABB createRenderBoundingBox() {
		// The cable and the weight hang below the block, and the client has to be told to keep drawing
		// them when only the weight is on screen.
		return super.createRenderBoundingBox()
			.expandTowards(0, -offset - 1, 0);
	}

	@Override
	public float getInterpolatedOffset(float partialTicks) {
		if (isVirtual())
			return Mth.lerp(partialTicks, prevAnimatedOffset, offset);
		boolean moving = running && mode != BatteryMode.IDLE
			&& (movedContraption == null || !movedContraption.isStalled());
		return super.getInterpolatedOffset(moving ? partialTicks : 0.5F);
	}

	/** Ponder's hook. A ponder level has no contraption, so the cable is drawn from this alone. */
	public void animateOffset(float forcedOffset) {
		offset = forcedOffset;
	}

	// --- the generator half, transcribed from Create's GeneratingKineticBlockEntity ---------------

	private void notifyStressCapacityChange(float capacity) {
		getOrCreateNetwork().updateCapacityFor(this, capacity);
	}

	@Override
	public void removeSource() {
		if (hasSource() && isSource())
			reActivateSource = true;
		super.removeSource();
	}

	@Override
	public void setSource(BlockPos source) {
		super.setSource(source);
		if (level == null)
			return;
		if (!(level.getBlockEntity(source) instanceof KineticBlockEntity sourceBE))
			return;
		if (reActivateSource && Math.abs(sourceBE.getSpeed()) >= Math.abs(getGeneratedSpeed()))
			reActivateSource = false;
	}

	public void updateGeneratedRotation() {
		float speed = getGeneratedSpeed();
		float prevSpeed = this.speed;
		if (level == null || level.isClientSide)
			return;

		if (prevSpeed != speed) {
			if (!hasSource() && IRotate.SpeedLevel.of(this.speed) != IRotate.SpeedLevel.of(speed))
				effects.queueRotationIndicators();
			applyNewSpeed(prevSpeed, speed);
		}

		if (hasNetwork() && speed != 0) {
			KineticNetwork network = getOrCreateNetwork();
			notifyStressCapacityChange(calculateAddedStressCapacity());
			network.updateStressFor(this, calculateStressApplied());
			network.updateStress();
		}

		onSpeedChanged(prevSpeed);
		sendData();
	}

	public void applyNewSpeed(float prevSpeed, float speed) {
		if (speed == 0) {
			if (hasSource()) {
				notifyStressCapacityChange(0);
				getOrCreateNetwork().updateStressFor(this, calculateStressApplied());
				return;
			}
			detachKinetics();
			setSpeed(0);
			setNetwork(null);
			return;
		}

		if (prevSpeed == 0) {
			setSpeed(speed);
			setNetwork(createNetworkId());
			attachKinetics();
			return;
		}

		if (hasSource()) {
			if (Math.abs(prevSpeed) >= Math.abs(speed)) {
				// A generator that insists on opposing a stronger network is torn off it. That is
				// Create's rule and it is why alignDirectionWith exists -- reaching this branch means
				// the alignment failed to keep up, not that opposing is intended.
				if (Math.signum(prevSpeed) != Math.signum(speed))
					level.destroyBlock(worldPosition, true);
				return;
			}
			detachKinetics();
			setSpeed(speed);
			source = null;
			setNetwork(createNetworkId());
			attachKinetics();
			return;
		}

		detachKinetics();
		setSpeed(speed);
		attachKinetics();
	}

	public Long createNetworkId() {
		return worldPosition.asLong();
	}

	// --- persistence ------------------------------------------------------------------------------

	@Override
	protected void write(CompoundTag compound, Provider registries, boolean clientPacket) {
		compound.putInt("Mode", mode.ordinal());
		compound.putInt("IdleReason", idleReason.ordinal());
		compound.putInt("WeightBlocks", weightBlocks);
		compound.putFloat("RestingOffset", restingOffset);
		compound.putBoolean("Reversed", reversed);
		compound.putFloat("RememberedSpeed", rememberedSpeed);
		super.write(compound, registries, clientPacket);
	}

	@Override
	protected void read(CompoundTag compound, Provider registries, boolean clientPacket) {
		BatteryMode before = mode;
		super.read(compound, registries, clientPacket);
		mode = BatteryMode.byOrdinal(compound.getInt("Mode"));
		idleReason = IdleReason.byOrdinal(compound.getInt("IdleReason"));
		weightBlocks = compound.getInt("WeightBlocks");
		restingOffset = compound.getFloat("RestingOffset");
		reversed = compound.getBoolean("Reversed");
		rememberedSpeed = compound.getFloat("RememberedSpeed");
		// The actuator parks a client-side contraption until the next speed change when it collides,
		// and a battery's speed need never change again -- the mode changes instead. Treat a mode
		// change as the release.
		if (clientPacket && before != mode)
			waitingForSpeedChange = false;
	}

	// --- goggles ----------------------------------------------------------------------------------

	/**
	 * Modelled on Create's Boiler, which is the densest overlay Create ships and still only manages a
	 * status line and three bars. Two rules taken from it: a level that changes every tick is a
	 * <em>bar</em>, not a number, and a rate that changes every tick is left to the Stress line Create
	 * already draws.
	 *
	 * <p>An earlier version had a charge percentage, a paid-out-of-total, and a seconds-remaining
	 * countdown. All three are the same fact — where the weight is — and all three flickered. The bar
	 * moves in ten steps, and the only other number here is the weight, which does not move at all
	 * while the battery is holding one. Create's own Steam Engine, for comparison, adds nothing to its
	 * overlay beyond the two lines every generator gets.
	 */
	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		GBLang
			.translate("tooltip.gravity_battery.status", GBLang.translate(mode.translationKey())
				.style(modeColour())
				.component())
			.forGoggles(tooltip);

		if (mode == BatteryMode.IDLE && idleReason != IdleReason.NONE) {
			GBLang.translate(idleReason.translationKey())
				.style(ChatFormatting.DARK_GRAY)
				.forGoggles(tooltip, 1);
			// Only for this one reason, and only because it is the number that tells a player what to
			// change. It is two moving figures, so it stays behind a state nothing else shows.
			if (idleReason == IdleReason.NO_SURPLUS)
				GBLang
					.translate("tooltip.gravity_battery.needs",
						CreateLang.number(getChargeDraw())
							.translate("generic.unit.stress"),
						CreateLang
							.number(Math.max(0, networkCapacityWithoutSelf() - networkStressWithoutSelf()))
							.translate("generic.unit.stress"))
					.style(ChatFormatting.DARK_GRAY)
					.forGoggles(tooltip, 1);
		}

		if (weightBlocks > 0) {
			GBLang.translate("tooltip.gravity_battery.charge")
				.style(ChatFormatting.GRAY)
				.space()
				.add(chargeBar())
				.forGoggles(tooltip, 1);

			GBLang.translate("tooltip.gravity_battery.weight")
				.style(ChatFormatting.GRAY)
				.space()
				// Create ships no "generic.unit.blocks" -- its generic.unit.* set is buckets, degrees,
				// millibuckets, minutes, rpm, seconds, stress and ticks. Borrowing a key that does not
				// exist puts the key itself on the goggles, so this unit is ours.
				.add(CreateLang.number(weightBlocks)
					.space()
					.add(GBLang.translate("tooltip.gravity_battery.unit.blocks"))
					.style(ChatFormatting.GOLD))
				.forGoggles(tooltip, 1);
		}

		// Create's own lines go underneath: the impact when winding up, the capacity when letting
		// down. The return says this block filled the overlay, which it has whether or not there was
		// any stress worth quoting.
		super.addToGoggleTooltip(tooltip, isPlayerSneaking);
		// Not covered by a test, and it cannot be: forGoggles indents with
		// Minecraft.getInstance().font, so building a tooltip on a dedicated server throws. A GameTest
		// can only assert the two figures these lines read, which is what
		// bothDirectionsHaveAStressFigureToReport does. Deleting this call would be silent.
		addGeneratedStressStats(tooltip);
		return true;
	}

	/**
	 * {@code GeneratingKineticBlockEntity}'s half of the overlay, transcribed for the same reason the
	 * rest of that class is: {@link LinearActuatorBlockEntity} is not a
	 * {@code GeneratingKineticBlockEntity}, so {@code super.addToGoggleTooltip} lands on
	 * {@code KineticBlockEntity} — which reports {@link #calculateStressApplied()} and bails when it is
	 * zero. That is zero for every battery that is letting down, so the overlay quoted a figure while
	 * winding up and nothing at all while generating. Missing this was an omission in the
	 * transcription, not a decision.
	 *
	 * <p>Kept faithful to Create's version, including the correction for a network running at a speed
	 * other than the one this block declares: a generator contributes at the speed it <em>declares</em>,
	 * so the two lines quote {@code capacity × generatedSpeed} rather than {@code capacity × networkSpeed}.
	 */
	private void addGeneratedStressStats(List<Component> tooltip) {
		if (!IRotate.StressImpact.isEnabled())
			return;
		float stressBase = calculateAddedStressCapacity();
		if (Mth.equal(stressBase, 0))
			return;

		CreateLang.translate("gui.goggles.generator_stats")
			.forGoggles(tooltip);
		CreateLang.translate("tooltip.capacityProvided")
			.style(ChatFormatting.GRAY)
			.forGoggles(tooltip);

		float speed = getTheoreticalSpeed();
		if (speed != getGeneratedSpeed() && speed != 0)
			stressBase *= getGeneratedSpeed() / speed;
		CreateLang.number(Math.abs(stressBase * speed))
			.translate("generic.unit.stress")
			.style(ChatFormatting.AQUA)
			.space()
			.add(CreateLang.translate("gui.goggles.at_current_speed")
				.style(ChatFormatting.DARK_GRAY))
			.forGoggles(tooltip, 1);
	}

	/** Segments in the charge bar. Ten, so a tick of movement is usually not a visible change. */
	private static final int BAR_SEGMENTS = 10;

	/**
	 * A charge bar in Create's own idiom — {@code |} repeated and coloured, the same glyph the Boiler
	 * draws its size, water and heat with.
	 */
	private LangBuilder chargeBar() {
		int filled = Mth.clamp(Mth.ceil(getChargeFraction() * BAR_SEGMENTS), 0, BAR_SEGMENTS);
		return GBLang.builder()
			.add(Component.literal("|".repeat(filled))
				.withStyle(ChatFormatting.GREEN))
			.add(Component.literal("|".repeat(BAR_SEGMENTS - filled))
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	private ChatFormatting modeColour() {
		return switch (mode) {
			case CHARGING -> ChatFormatting.AQUA;
			case DISCHARGING -> ChatFormatting.GREEN;
			case IDLE -> ChatFormatting.DARK_GRAY;
		};
	}

	/** Comparator output: 0 empty, 15 fully wound. */
	public int getComparatorOutput() {
		return chargeOnScale(15);
	}

	/**
	 * The charge on a 0..{@code max} scale, with both ends meaning exactly what they say: 0 only when
	 * the weight is resting and {@code max} only when it is as high as it goes. In between it rounds up,
	 * so a battery with anything left in it never reads empty.
	 *
	 * <p>One method because the comparator and the Threshold Switch both need it and used to work it out
	 * separately — one rounding, one ceiling. That left them disagreeing at the ends: a battery at 0.3%
	 * gave a comparator a strength of 1 while telling a Threshold Switch it was at 0, so "fire when it
	 * hits empty" fired while a comparator still said there was charge in it.
	 */
	private int chargeOnScale(int max) {
		if (weightBlocks <= 0)
			return 0;
		float fraction = getChargeFraction();
		if (fraction <= 0)
			return 0;
		if (fraction >= 1)
			return max;
		return Mth.clamp(Mth.ceil(fraction * max), 1, max - 1);
	}

	// --- Threshold Switch -------------------------------------------------------------------------

	/**
	 * A Threshold Switch reads a battery's charge as a percentage, so "start the backup boiler when it
	 * drops below 20%" is a redstone line rather than a mod feature.
	 *
	 * <p>Percent rather than the offset in blocks, which is what Create's Rope Pulley reports. A drop is
	 * measured per installation, so a threshold in blocks would mean something different for every
	 * battery in the world; a percentage is the same promise everywhere. Create finds this by
	 * {@code instanceof} — implementing the interface is the whole registration.
	 */
	@Override
	public int getCurrentValue() {
		return chargeOnScale(100);
	}

	@Override
	public int getMinValue() {
		return 0;
	}

	@Override
	public int getMaxValue() {
		return 100;
	}

	@Override
	public MutableComponent format(int value) {
		return GBLang.translate("threshold_switch.charge", value)
			.component();
	}

	@Nullable
	public ControlledContraptionEntity getAttachedContraption() {
		return movedContraption instanceof ControlledContraptionEntity controlled ? controlled : null;
	}
}
