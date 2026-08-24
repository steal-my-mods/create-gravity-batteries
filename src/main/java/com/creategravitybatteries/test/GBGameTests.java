package com.creategravitybatteries.test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.creategravitybatteries.CreateGravityBatteries;
import com.creategravitybatteries.GBConfig;
import com.creategravitybatteries.battery.BatteryMode;
import com.creategravitybatteries.battery.CableGeometry;
import com.creategravitybatteries.battery.GravityBatteryBlockEntity;
import com.creategravitybatteries.battery.IdleReason;
import com.creategravitybatteries.registry.GBBlocks;
import com.creategravitybatteries.registry.GBDisplaySources;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchObservable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The rig is always the same: two Gravity Batteries on one shaft at y=9, a creative motor that can be
 * put on the west end of it, and a slime-block weight hanging under each battery. What changes
 * between tests is which of those is present and how high the weight starts, because those two are
 * exactly what a battery reads when it picks a mode.
 *
 * <p>Slime, not stone, and not for the joke: Create's contraption search only spreads through blocks
 * that are glued or naturally sticky, so a stack of stone would assemble as one block and every test
 * about weight would be testing a one-block weight. Slime sticks to slime, which gets a multi-block
 * contraption out of {@code setBlock} calls and no glue entities.
 */
@GameTestHolder(CreateGravityBatteries.ID)
@PrefixGameTestTemplate(false)
public class GBGameTests {

	private static final String NAMESPACE = CreateGravityBatteries.ID;

	private static final int SITE = 11;
	private static final int SHAFT_Y = 9;

	/** West to east: motor, battery A, shaft, battery B. */
	private static final BlockPos MOTOR = new BlockPos(2, SHAFT_Y, 5);
	private static final BlockPos BATTERY_A = new BlockPos(3, SHAFT_Y, 5);
	private static final BlockPos SHAFT = new BlockPos(4, SHAFT_Y, 5);
	private static final BlockPos BATTERY_B = new BlockPos(5, SHAFT_Y, 5);

	/** Where a weight's top layer sits when it is hung in mid-air, and when it starts on the floor. */
	private static final int HIGH_TOP = 6;
	private static final int RESTING_TOP = 2;

	/** Blocks in the weight {@link #hangWeight} builds. */
	private static final int WEIGHT_BLOCKS = 4;

	/** Past the warm-up, with room for the rotation propagator to settle. */
	private static final int SETTLE_TICKS = 12;

	// --- taking hold ------------------------------------------------------------------------------

	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void activatingTakesHoldOfTheWeight(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.running, "the battery should be holding the weight");
			helper.assertTrue(battery.getWeightBlocks() == WEIGHT_BLOCKS,
				"a four block slime weight should count as four, counted " + battery.getWeightBlocks());
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, HIGH_TOP, 5));
			helper.succeed();
		});
	}

	/**
	 * The probe, and the reason the charge readout means anything. A battery whose drop it had assumed
	 * from the config would call a weight resting on the floor 90% charged.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void theDropIsMeasuredRatherThanAssumed(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			// The weight is two blocks tall with its top at HIGH_TOP, so its bottom lands on the floor
			// at y=1 and its top comes to rest at y=2 -- an offset of SHAFT_Y - 1 - 2.
			float expected = SHAFT_Y - 1 - RESTING_TOP;
			helper.assertTrue(battery.getDropRange() == expected,
				"the drop should measure " + expected + " blocks, measured " + battery.getDropRange());
			helper.succeed();
		});
	}

	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void weightSizeSetsThePowerRating(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			float expected = WEIGHT_BLOCKS * GBConfig.stressPerBlock();
			helper.assertTrue(Math.abs(battery.ratingPerRpm() - expected) < 0.001F,
				WEIGHT_BLOCKS + " blocks should rate " + expected + "su/rpm, rated "
					+ battery.ratingPerRpm());
			helper.succeed();
		});
	}

	// --- charging ---------------------------------------------------------------------------------

	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void surplusPowerWindsTheWeightUp(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		float[] startOffset = new float[1];
		helper.runAfterDelay(SETTLE_TICKS + 5, () -> startOffset[0] = battery(helper, BATTERY_A).offset);
		helper.runAfterDelay(SETTLE_TICKS + 80, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() == BatteryMode.CHARGING,
				"a battery on a creative motor should be winding up, was " + battery.getMode());
			helper.assertTrue(battery.offset < startOffset[0],
				"the weight should have risen; offset went " + startOffset[0] + " -> " + battery.offset);
			helper.assertTrue(battery.calculateStressApplied() > 0,
				"a charging battery must place a load on the network");
			helper.succeed();
		});
	}

	/** Wound to the top there is nothing left to store, and a battery that kept drawing would be a leak. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aFullyWoundBatteryStopsDrawing(GameTestHelper helper) {
		rig(helper);
		// Hung flush under the battery, which is what fully wound means. Winding one up from the floor
		// at a creative motor's 16 RPM through the default gear reduction takes 256 ticks a block, and
		// a test that has to sit through that is testing the clock.
		hangWeight(helper, 3, SHAFT_Y - 1);
		activate(helper, BATTERY_A);
		drive(helper);

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.offset == 0,
				"the weight should be all the way up, offset " + battery.offset);
			helper.assertTrue(battery.getMode() == BatteryMode.IDLE,
				"a fully wound battery should stop, was " + battery.getMode());
			helper.assertTrue(battery.getIdleReason() == IdleReason.FULLY_CHARGED,
				"...and say so; it says " + battery.getIdleReason());
			helper.assertTrue(battery.calculateStressApplied() == 0,
				"a stopped battery must place no load on the network");
			helper.succeed();
		});
	}

	// --- discharging ------------------------------------------------------------------------------

	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void losingTheSourceLetsTheWeightDown(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		float[] offsetAtHandover = new float[1];
		helper.runAfterDelay(120, () -> {
			helper.setBlock(MOTOR, Blocks.AIR);
			offsetAtHandover[0] = battery(helper, BATTERY_A).offset;
		});
		helper.runAfterDelay(220, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() == BatteryMode.DISCHARGING,
				"with nothing else driving the shaft the battery should be letting down, was "
					+ battery.getMode());
			helper.assertTrue(battery.offset > offsetAtHandover[0],
				"the weight should be descending; offset went " + offsetAtHandover[0] + " -> "
					+ battery.offset);
			helper.assertTrue(battery.calculateAddedStressCapacity() > 0,
				"a discharging battery must supply capacity");
			helper.succeed();
		});
	}

	/**
	 * The one test this mod cannot do without. A battery whose weight is on the floor has nothing left
	 * to spend, and supplying capacity anyway would be free power out of a block that is not moving —
	 * which is the whole failure mode a gravity battery has, and the reason the drop is probed at all.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void aRestingWeightSuppliesNothing(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, RESTING_TOP);
		activate(helper, BATTERY_A);

		List<BatteryMode> seen = new ArrayList<>();
		for (int tick = SETTLE_TICKS; tick < 200; tick += 8) {
			int at = tick;
			helper.runAfterDelay(at, () -> seen.add(battery(helper, BATTERY_A).getMode()));
		}

		helper.runAfterDelay(210, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(!seen.contains(BatteryMode.DISCHARGING),
				"a weight already on the floor must never discharge, saw " + seen);
			helper.assertTrue(battery.getIdleReason() == IdleReason.DISCHARGED,
				"...and should say it is spent; it says " + battery.getIdleReason());
			helper.assertTrue(battery.calculateAddedStressCapacity() == 0,
				"a spent battery must supply no capacity");
			helper.succeed();
		});
	}

	/**
	 * Losing the drive must not move the weight. Create's actuator re-grids the offset on a sign
	 * change and truncates it to a whole block doing so, and {@code signum(0) == 0} makes "stopped" a
	 * sign change — so this fired every time the shaft went still, lifting the weight by up to a block
	 * for free. Flicking a lever was a charging strategy.
	 *
	 * <p>The offset has to be genuinely fractional when the drive goes away or truncation is a no-op
	 * and the test proves nothing, so that is asserted first.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void losingTheDriveDoesNotMoveTheWeight(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		float[] before = new float[1];
		helper.runAfterDelay(100, () -> {
			before[0] = battery(helper, BATTERY_A).offset;
			float fraction = before[0] - (int) before[0];
			helper.assertTrue(fraction > 0.2F && fraction < 0.8F,
				"this test needs a fractional offset to mean anything, the weight is at " + before[0]);
			helper.setBlock(MOTOR, Blocks.AIR);
		});

		// Five ticks is enough for the propagator to notice and for any snap to have happened, and at
		// most 0.08 blocks of the battery's own descent at its top speed.
		helper.runAfterDelay(105, () -> {
			float now = battery(helper, BATTERY_A).offset;
			helper.assertTrue(Math.abs(now - before[0]) < 0.15F,
				"the weight jumped when the drive went away: " + before[0] + " -> " + now);
			helper.succeed();
		});
	}

	// --- the two properties a self-deciding block has to have -------------------------------------

	/**
	 * A battery that tested the network's balance <em>including</em> its own contribution would wind
	 * up, see the deficit it just created, let down, see the surplus it just created, and wind up
	 * again — once a tick, for ever. This asserts the mode settles and then stays put.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void theModeSettlesRatherThanFlipping(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		List<BatteryMode> seen = new ArrayList<>();
		for (int tick = 60; tick < 260; tick += 10) {
			int at = tick;
			helper.runAfterDelay(at, () -> seen.add(battery(helper, BATTERY_A).getMode()));
		}

		helper.runAfterDelay(280, () -> {
			helper.assertTrue(!seen.isEmpty(), "nothing was sampled");
			helper.assertTrue(new HashSet<>(seen).size() == 1,
				"the mode should have settled on one thing, saw " + seen);
			helper.succeed();
		});
	}

	/**
	 * Two batteries back to back cannot charge each other.
	 *
	 * <p>The assertion is the mode <em>pair</em>, not the total height, and that is the whole lesson of
	 * this test. A weaker version asserted the sum of the two offsets never falls -- and it passes with
	 * charging deliberately made cheaper than discharging pays, because two equal weights swapping
	 * height leave the sum flat. What the cheap version actually buys is a network surplus funded by
	 * nothing, which other machines can then run on for free. So the thing to refuse is one battery
	 * winding up on another's output at all.
	 *
	 * <p>The sum is asserted too, since it catches the opposite mistake: capacity supplied by a battery
	 * that is not actually descending.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void twoBatteriesOnOneShaftCannotChargeEachOther(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(BATTERY_B, GBBlocks.GRAVITY_BATTERY.get()
			.defaultBlockState()
			.setValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS, Direction.Axis.X));
		hangWeight(helper, 3, HIGH_TOP);
		hangWeight(helper, 5, HIGH_TOP);
		activate(helper, BATTERY_A);
		activate(helper, BATTERY_B);

		float[] startSum = new float[1];
		helper.runAfterDelay(SETTLE_TICKS + 5, () -> startSum[0] =
			battery(helper, BATTERY_A).offset + battery(helper, BATTERY_B).offset);

		List<String> pumping = new ArrayList<>();
		List<Float> sums = new ArrayList<>();
		for (int tick = SETTLE_TICKS + 10; tick < 280; tick += 10) {
			int at = tick;
			helper.runAfterDelay(at, () -> {
				GravityBatteryBlockEntity a = battery(helper, BATTERY_A);
				GravityBatteryBlockEntity b = battery(helper, BATTERY_B);
				sums.add(a.offset + b.offset);
				if (opposed(a.getMode(), b.getMode()))
					pumping.add("t=" + at + " A=" + a.getMode() + " B=" + b.getMode());
			});
		}

		helper.runAfterDelay(300, () -> {
			helper.assertTrue(!sums.isEmpty(), "nothing was sampled");
			helper.assertTrue(pumping.isEmpty(),
				"one battery wound up on the other's output: " + pumping);
			for (float sum : sums)
				helper.assertTrue(sum >= startSum[0] - 0.001F,
					"the pair gained height between them: " + startSum[0] + " -> " + sum);
			helper.succeed();
		});
	}

	/** One winding up while the other lets down -- the shape of a perpetual motion machine. */
	private static boolean opposed(BatteryMode one, BatteryMode other) {
		return (one == BatteryMode.CHARGING && other == BatteryMode.DISCHARGING)
			|| (one == BatteryMode.DISCHARGING && other == BatteryMode.CHARGING);
	}

	// --- letting go -------------------------------------------------------------------------------

	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void activatingAgainPutsTheWeightBack(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);

		helper.runAfterDelay(SETTLE_TICKS, () -> activate(helper, BATTERY_A));
		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(!battery.running, "the battery should have let go");
			helper.assertBlockPresent(Blocks.SLIME_BLOCK, new BlockPos(3, HIGH_TOP, 5));
			helper.succeed();
		});
	}

	// --- what rotation alone is allowed to pick up -------------------------------------------------

	/** Rotation arriving is enough to take hold of a weight hung flush, the way a Rope Pulley does. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void rotationAloneTakesHoldOfAFlushWeight(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, SHAFT_Y - 1);
		drive(helper); // deliberately no activate()

		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.running,
				"a powered battery should take hold of the weight against its underside");
			helper.assertTrue(battery.getWeightBlocks() == WEIGHT_BLOCKS,
				"it took " + battery.getWeightBlocks() + " blocks, expected " + WEIGHT_BLOCKS);
			helper.succeed();
		});
	}

	/**
	 * An unattended battery that gets power must not go looking for something to pick up.
	 *
	 * <p>It used to. The weight search walked down the shaft for the first solid block, which combined
	 * with assembling on rotation meant a battery placed over a base and then powered tore the floor
	 * out of the world from up to {@link GBConfig#maxCableLength} blocks away — and then never let go,
	 * because that is what a battery does. Reaching is now only offered to a player who right-clicks.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aPoweredBatteryDoesNotEatTheFloor(GameTestHelper helper) {
		rig(helper); // a floor at y=0, a battery at y=9, and nothing hung between them
		drive(helper);

		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(!battery.running,
				"a battery with nothing hung under it took hold of something anyway");
			helper.assertBlockPresent(Blocks.STONE, new BlockPos(3, 0, 5));
			helper.assertTrue(battery.getIdleReason() == IdleReason.NO_WEIGHT,
				"...and it should say there is nothing attached; it says " + battery.getIdleReason());
			helper.succeed();
		});
	}

	/** A player's activation still reaches, which is the whole reason the two paths differ. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void activatingReachesForAWeightFurtherDown(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP); // two blocks clear of the battery's underside
		activate(helper, BATTERY_A);

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.running, "right-clicking should reach down for the weight");
			// A tolerance, not an equality: with no motor on the shaft the battery is already letting
			// the weight down by the time this runs, which is the correct behaviour and not the subject.
			float expected = SHAFT_Y - 1 - HIGH_TOP;
			helper.assertTrue(Math.abs(battery.offset - expected) < 0.2F,
				"it should have found the weight at offset " + expected + ", it is at " + battery.offset);
			helper.succeed();
		});
	}

	// --- redstone ----------------------------------------------------------------------------------

	/**
	 * A comparator must both read the charge and keep up with it.
	 *
	 * <p>Declaring {@code hasAnalogOutputSignal} is only half of an analog output: nothing polls it, so
	 * without {@code updateNeighbourForOutputSignal} the value a comparator latched when it was placed
	 * never changed again. That is the worst kind of broken — the feature looks present, the README says
	 * it works, and it silently reports a stale number for ever. So this asserts the reading is both
	 * non-zero and <em>rising</em> while the battery winds up.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void aComparatorFollowsTheCharge(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		// ComparatorBlock#getInputSignal reads pos.relative(FACING), so FACING points *at* what is
		// being measured -- which is why placing one has you face the container. The comparator also
		// needs something solid underneath or the first block update pops it off.
		BlockPos comparator = new BlockPos(3, SHAFT_Y, 4);
		helper.setBlock(comparator.below(), Blocks.STONE);
		helper.setBlock(comparator, Blocks.COMPARATOR.defaultBlockState()
			.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));

		int[] first = new int[1];
		helper.runAfterDelay(40, () -> {
			first[0] = comparatorOutput(helper, comparator);
			helper.assertTrue(first[0] > 0,
				"the comparator reads " + first[0] + " next to a battery holding a part-charged weight");
			helper.assertTrue(first[0] < 15,
				"the weight needs room to rise for this test to mean anything, reading " + first[0]);
		});

		helper.runAfterDelay(300, () -> {
			int now = comparatorOutput(helper, comparator);
			helper.assertTrue(now > first[0],
				"the comparator did not follow the charge up: " + first[0] + " -> " + now);
			helper.succeed();
		});
	}

	private static int comparatorOutput(GameTestHelper helper, BlockPos pos) {
		BlockEntity be = helper.getLevel()
			.getBlockEntity(helper.absolutePos(pos));
		if (!(be instanceof ComparatorBlockEntity comparator))
			throw new GameTestAssertException("no comparator at " + pos + ", found " + be);
		return comparator.getOutputSignal();
	}

	/**
	 * A battery that once held a weight must not let rotation alone reach for that offset again.
	 *
	 * <p>The first version of the flush-only rule had a hole straight through it: an offset it
	 * remembered outranked both other rules, {@code disassemble()} left the offset in place, and
	 * {@code LinearActuatorBlockEntity} persists it — so a battery that had held something 20 blocks
	 * down would take whatever later stood at 20, with no player involved. Exactly the bug the flush
	 * rule was added to close.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void rotationDoesNotReachForAWeightItOnceHeld(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A); // reaches down and takes it, offset 2

		// Let go again, and take the weight away entirely.
		helper.runAfterDelay(SETTLE_TICKS, () -> {
			activate(helper, BATTERY_A);
		});
		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			for (int dz = 0; dz <= 1; dz++)
				for (int dy = 0; dy <= 1; dy++)
					helper.setBlock(new BlockPos(3, HIGH_TOP - dy, 5 + dz), Blocks.AIR);
			// Something innocent now stands where the weight used to hang.
			helper.setBlock(new BlockPos(3, HIGH_TOP, 5), Blocks.CHEST);
			drive(helper);
		});

		helper.runAfterDelay(SETTLE_TICKS + 60, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(!battery.running,
				"rotation reached back to the offset it used to hold a weight at");
			helper.assertBlockPresent(Blocks.CHEST, new BlockPos(3, HIGH_TOP, 5));
			helper.succeed();
		});
	}

	/**
	 * Hitting something on the way up must not cost the battery its capacity.
	 *
	 * <p>{@code collided()} clamped the measured drop on any collision, and a collision while winding up
	 * says only that the way up is blocked. It left a battery reading empty — nothing to spend, idle
	 * reason DISCHARGED — with a full weight hanging over a clear shaft.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void hittingSomethingWhileWindingUpKeepsTheDrop(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		float[] drop = new float[1];
		// A ceiling one block above the weight, so winding up runs straight into it.
		helper.runAfterDelay(SETTLE_TICKS, () -> {
			drop[0] = battery(helper, BATTERY_A).getDropRange();
			helper.assertTrue(drop[0] > 1, "the rig should have measured a real drop, it read " + drop[0]);
			helper.setBlock(new BlockPos(3, HIGH_TOP + 1, 5), Blocks.OBSIDIAN);
		});

		helper.runAfterDelay(320, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getDropRange() == drop[0],
				"the drop was clamped by an upward collision: " + drop[0] + " -> "
					+ battery.getDropRange());
			helper.assertTrue(battery.getIdleReason() != IdleReason.DISCHARGED,
				"a battery with a full weight over a clear shaft says it is spent");
			helper.succeed();
		});
	}

	// --- Threshold Switch and Display Link ---------------------------------------------------------

	/**
	 * A Threshold Switch reads the charge as a percentage. Percent and not the offset in blocks, which
	 * is what Create's Rope Pulley reports: a drop is measured per installation, so a threshold in
	 * blocks would mean a different thing for every battery in the world.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void aThresholdSwitchReadsTheChargeAsAPercentage(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		int[] before = new int[1];
		helper.runAfterDelay(SETTLE_TICKS + 5, () -> {
			ThresholdSwitchObservable observable = battery(helper, BATTERY_A);
			helper.assertTrue(observable.getMinValue() == 0 && observable.getMaxValue() == 100,
				"the scale should be 0..100, it is " + observable.getMinValue() + ".."
					+ observable.getMaxValue());
			before[0] = observable.getCurrentValue();
			helper.assertTrue(before[0] > 0 && before[0] < 100,
				"a part-charged battery should read between the ends, it reads " + before[0]);
		});

		helper.runAfterDelay(250, () -> {
			int now = battery(helper, BATTERY_A).getCurrentValue();
			helper.assertTrue(now > before[0],
				"winding up should raise the reading: " + before[0] + " -> " + now);
			helper.succeed();
		});
	}

	/**
	 * Both ends of the Threshold Switch's scale, which is what a player's threshold setting is measured
	 * against: a resting weight reads 0 and a fully wound one reads 100.
	 *
	 * <p>Asserting only that the reading rises is not enough — it rises just as happily if the value is
	 * the offset in blocks, which is what Create's Rope Pulley reports and what this deliberately does
	 * not. Nothing is placed on the shaft, so neither battery has anything to drive and neither starts
	 * letting its weight down while the ends are being read.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void theThresholdScaleRunsFromRestingToFullyWound(GameTestHelper helper) {
		floor(helper);
		placeBattery(helper, BATTERY_A);
		placeBattery(helper, BATTERY_B);
		hangWeight(helper, 3, SHAFT_Y - 1); // flush under A, so A is fully wound
		hangWeight(helper, 5, RESTING_TOP); // on the floor under B, so B is spent
		activate(helper, BATTERY_A);
		activate(helper, BATTERY_B);

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			int wound = battery(helper, BATTERY_A).getCurrentValue();
			int spent = battery(helper, BATTERY_B).getCurrentValue();
			helper.assertTrue(wound == 100, "a fully wound battery should read 100, it reads " + wound);
			helper.assertTrue(spent == 0, "a resting weight should read 0, it reads " + spent);
			helper.succeed();
		});
	}

	/**
	 * The Display Link sources. Both are server-safe by construction — unlike the goggle overlay, which
	 * cannot be built outside a client at all — so what a Display Board would show is assertable here.
	 *
	 * <p>The status source reports the idle <em>reason</em> rather than the word "Holding", because on a
	 * board read from across the room "Nothing attached" is the useful half.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void theDisplaySourcesReportModeAndCharge(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		helper.runAfterDelay(80, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() == BatteryMode.CHARGING,
				"expected to be winding up by now, was " + battery.getMode());

			// Registering a source is only half of it: DisplaySource.BY_BLOCK is what makes a Display
			// Link offer them on this block, and Create's own blocks get that entry from a Registrate
			// transform this mod does not use. Without GBDisplaySources.attach the sources exist and
			// are unreachable, which no amount of calling them directly would notice.
			List<DisplaySource> offered =
				DisplaySource.getAll(helper.getLevel(), helper.absolutePos(BATTERY_A));
			helper.assertTrue(offered.contains(GBDisplaySources.BATTERY_STATUS.get()),
				"a Display Link is not offered the status source on this block");
			helper.assertTrue(offered.contains(GBDisplaySources.BATTERY_CHARGE.get()),
				"a Display Link is not offered the charge source on this block");

			helper.assertTrue(
				keyOf(GBDisplaySources.BATTERY_STATUS.get(), battery)
					.equals(NAMESPACE + ".tooltip.gravity_battery.mode.charging"),
				"the status source said " + keyOf(GBDisplaySources.BATTERY_STATUS.get(), battery));

			// Mode 0 is the bar, which is the default, and it should be made of Create's own block
			// characters rather than of text.
			String bar = lineOf(GBDisplaySources.BATTERY_CHARGE.get(), battery, 0).getString();
			helper.assertTrue(!bar.isEmpty() && bar.chars()
				.allMatch(c -> c == '\u2588' || c == '\u2592'),
				"the charge source's default should be a progress bar, it rendered '" + bar + "'");

			// Mode 1 is the percentage, and it must be a whole one. Catnip's number formatter keeps
			// three decimal places until a client-side update() that never runs on a server, so
			// formatting this by hand read "66.667%" on a multiplayer board.
			String percent = lineOf(GBDisplaySources.BATTERY_CHARGE.get(), battery, 1).getString();
			helper.assertTrue(percent.endsWith("%") && !percent.contains(".") && !percent.contains(","),
				"the charge percentage should be a whole number, it rendered '" + percent + "'");
			helper.succeed();
		});
	}

	/**
	 * Only the source's own line is under test, so the context is a stub: a real one carries a Display
	 * Link block entity, and the sources read their label, their mode and the target's width off it.
	 *
	 * <p>{@code mode} is the source's own config value — 0 is a progress bar for the charge source and 1
	 * a percentage.
	 */
	private static MutableComponent lineOf(DisplaySource source, GravityBatteryBlockEntity battery,
		int mode) {
		CompoundTag config = new CompoundTag();
		config.putInt("Mode", mode);
		List<MutableComponent> lines =
			source.provideText(new DisplayLinkContext(battery.getLevel(), null) {
				@Override
				public BlockEntity getSourceBlockEntity() {
					return battery;
				}

				@Override
				public BlockEntity getTargetBlockEntity() {
					return null;
				}

				@Override
				public CompoundTag sourceConfig() {
					return config;
				}
			}, STUB_TARGET);
		return lines.isEmpty() ? Component.empty() : lines.get(0);
	}

	/**
	 * A target wide enough to render into. Was {@code null} until the charge source moved onto Create's
	 * progress-bar base, which asks the target how many columns it has — so the stub stopped being
	 * harmless the moment the source it exercises got better.
	 */
	private static final DisplayTargetStats STUB_TARGET = new DisplayTargetStats(4, 16, null);

	private static String keyOf(DisplaySource source, GravityBatteryBlockEntity battery) {
		MutableComponent line = lineOf(source, battery, 0);
		return line.getContents() instanceof TranslatableContents contents ? contents.getKey()
			: "<not a translatable: " + line.getString() + ">";
	}

	// --- what the goggles report ------------------------------------------------------------------

	/**
	 * A battery must have a Stress figure to report in both directions.
	 *
	 * <p>It did not report one while letting down. {@code LinearActuatorBlockEntity} is not a
	 * {@code GeneratingKineticBlockEntity}, so {@code super.addToGoggleTooltip} lands on
	 * {@code KineticBlockEntity}, which quotes {@code calculateStressApplied} and bails when it is zero
	 * — zero for every battery that is generating. The mechanics of Create's generator class had been
	 * transcribed and its half of the overlay had not.
	 *
	 * <p>This asserts the two figures the overlay reads, not the overlay itself: {@code forGoggles}
	 * indents its lines with {@code Minecraft.getInstance().font}, so a tooltip cannot be built on a
	 * dedicated server at all and calling it here dies with "invalid dist DEDICATED_SERVER". That the
	 * generator half is still <em>wired into</em> {@code addToGoggleTooltip} is therefore not covered
	 * by any test, and the call site says so.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void bothDirectionsHaveAStressFigureToReport(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		helper.runAfterDelay(80, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() == BatteryMode.CHARGING,
				"expected to be winding up by now, was " + battery.getMode());
			helper.assertTrue(battery.calculateStressApplied() > 0,
				"a winding battery has no impact figure for the overlay to quote");
			helper.assertTrue(battery.calculateAddedStressCapacity() == 0,
				"a winding battery must not also be advertising capacity");
			helper.setBlock(MOTOR, Blocks.AIR);
		});

		helper.runAfterDelay(180, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() == BatteryMode.DISCHARGING,
				"expected to be letting down by now, was " + battery.getMode());
			helper.assertTrue(battery.calculateAddedStressCapacity() > 0,
				"a battery letting down has no capacity figure for the overlay to quote");
			helper.assertTrue(battery.calculateStressApplied() == 0,
				"a battery letting down must not also be placing a load on the network");
			helper.succeed();
		});
	}

	// --- lighting ----------------------------------------------------------------------------------

	/**
	 * The lock on a bug that shipped: the shaft spinning through the middle of the battery rendered
	 * almost black.
	 *
	 * <p>A block entity renderer is handed the light level at the block's own position, and inside a
	 * full-cube occluder that is zero. Nothing about the renderer was wrong — the block was, for
	 * missing {@code noOcclusion()}. Expressed here in the two terms a dedicated server can see: the
	 * state must not occlude, and light from outside must actually arrive at the block's position.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void theBatteryDoesNotBlockItsOwnLight(GameTestHelper helper) {
		rig(helper);
		helper.setBlock(new BlockPos(3, SHAFT_Y + 1, 5), Blocks.GLOWSTONE);

		helper.runAfterDelay(10, () -> {
			helper.assertTrue(!helper.getBlockState(BATTERY_A)
				.canOcclude(),
				"the battery occludes like a solid cube, so its renderer is handed no light");
			int light = helper.getLevel()
				.getBrightness(LightLayer.BLOCK, helper.absolutePos(BATTERY_A));
			helper.assertTrue(light > 0,
				"glowstone is against the battery and the light at its own position is still " + light);
			helper.succeed();
		});
	}

	// --- the cable's geometry ---------------------------------------------------------------------

	/**
	 * The lock on a bug that shipped: the first section of cable below the battery rendered almost
	 * black while every section under it looked right.
	 *
	 * <p>Light is read from the block a piece hangs in, and the topmost segment always hangs less than
	 * a block below the battery — that is the whole point of anchoring the cable at the weight. So
	 * truncating its offset gives 0, and 0 blocks below the battery <em>is</em> the battery, whose
	 * interior is dark. Nothing about this needs a world, and it is here rather than beside the renderer
	 * because a dedicated server cannot load a class that mentions {@code PoseStack}.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void cableGeometryNeverLightsFromInsideTheBattery(GameTestHelper helper) {
		BlockPos battery = new BlockPos(0, 64, 0);
		for (float length : new float[] { 0F, 0.001F, 0.3F, 0.5F, 0.999F, 1F, 1.2F, 2.3F, 7.75F, 63F }) {
			for (int i = 0; i < CableGeometry.segments(length); i++) {
				float offset = CableGeometry.segmentOffset(length, i);
				BlockPos lit = CableGeometry.lightSource(battery, offset);
				helper.assertTrue(lit.getY() <= battery.getY() - 1,
					"cable at length " + length + " segment " + i + " (offset " + offset
						+ ") would take its light from " + lit + ", the battery is at " + battery);
			}
			// And the clamp on the end, which is drawn at the full length.
			helper.assertTrue(CableGeometry.lightSource(battery, length)
				.getY() <= battery.getY() - 1, "the clamp at length " + length + " lights from inside");
		}
		helper.succeed();
	}

	/**
	 * The invariant that lets one cable model cover every length: the bottom segment starts exactly at
	 * the weight, and the top one runs into the casing rather than stopping short of it. Break either
	 * and the cable has a visible seam, which is the artefact the half-height model exists to avoid in
	 * Create's pulley.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void theCableHasNoSeam(GameTestHelper helper) {
		for (float length : new float[] { 0.001F, 0.3F, 1F, 1.2F, 2.3F, 7.75F, 63F }) {
			int segments = CableGeometry.segments(length);
			helper.assertTrue(segments >= 1, "length " + length + " drew no cable at all");
			helper.assertTrue(CableGeometry.segmentOffset(length, 0) == length,
				"the bottom segment must start at the weight, not at "
					+ CableGeometry.segmentOffset(length, 0));
			float top = CableGeometry.segmentOffset(length, segments - 1);
			helper.assertTrue(top > 0 && top <= 1,
				"the top segment should overshoot into the casing by under a block, it is at " + top);
		}
		helper.succeed();
	}

	// --- the ponder scene -------------------------------------------------------------------------

	/**
	 * Everything about a Ponder scene fails silently — a missing structure, a bad block state and a
	 * missing lang key all load a clean client and only go wrong when a player opens the scene. This
	 * parses the .nbt the way the game will and checks every palette entry against the real registry.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void thePonderStructureIsValid(GameTestHelper helper) {
		CompoundTag root;
		try (InputStream in = GBGameTests.class
			.getResourceAsStream("/assets/creategravitybatteries/ponder/gravity_battery.nbt")) {
			helper.assertTrue(in != null, "the ponder structure is missing from the build");
			root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
		} catch (IOException e) {
			throw new GameTestAssertException("the ponder structure would not parse: " + e);
		}

		ListTag palette = root.getList("palette", Tag.TAG_COMPOUND);
		helper.assertTrue(!palette.isEmpty(), "the structure has an empty palette");

		Set<String> seen = new HashSet<>();
		for (int i = 0; i < palette.size(); i++) {
			CompoundTag entry = palette.getCompound(i);
			String name = entry.getString("Name");
			seen.add(name);
			ResourceLocation id = ResourceLocation.parse(name);
			helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "no such block: " + name);

			Block block = BuiltInRegistries.BLOCK.get(id);
			CompoundTag properties = entry.getCompound("Properties");
			for (String key : properties.getAllKeys()) {
				Property<?> property = block.getStateDefinition()
					.getProperty(key);
				helper.assertTrue(property != null, name + " has no property '" + key + "'");
				helper.assertTrue(property.getValue(properties.getString(key))
					.isPresent(), name + "." + key + " rejects '" + properties.getString(key) + "'");
			}
		}

		// The scene destroys the motor to show the failover and narrates over the rest, so all four
		// have to be there or the story it tells is not the story the structure holds.
		for (String required : new String[] { "creategravitybatteries:gravity_battery", "create:shaft",
			"create:creative_motor", "create:cogwheel" })
			helper.assertTrue(seen.contains(required),
				"the scene needs a " + required + " and the structure has none");

		helper.assertTrue(root.getList("size", Tag.TAG_INT)
			.size() == 3, "the structure has no size");
		helper.succeed();
	}

	// --- the rig ----------------------------------------------------------------------------------

	private static void rig(GameTestHelper helper) {
		floor(helper);
		placeBattery(helper, BATTERY_A);
		helper.setBlock(SHAFT, AllBlocks.SHAFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
	}

	private static void placeBattery(GameTestHelper helper, BlockPos pos) {
		helper.setBlock(pos, GBBlocks.GRAVITY_BATTERY.get()
			.defaultBlockState()
			.setValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS, Direction.Axis.X));
	}

	/** Puts the creative motor on the west end of the shaft, driving east into battery A. */
	private static void drive(GameTestHelper helper) {
		helper.setBlock(MOTOR, AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
	}

	/**
	 * A four block slime weight -- one column wide, two tall, two deep -- hung in the column below
	 * {@code x} with its top layer at {@code top}.
	 *
	 * <p>One column wide on purpose. Both batteries' weights hang from the same shaft two blocks
	 * apart, and slime sticks to slime: a wider weight would touch its neighbour and the two would
	 * assemble as one contraption held by whichever battery got there first.
	 */
	private static void hangWeight(GameTestHelper helper, int x, int top) {
		for (int dz = 0; dz <= 1; dz++)
			for (int dy = 0; dy <= 1; dy++)
				helper.setBlock(new BlockPos(x, top - dy, 5 + dz), Blocks.SLIME_BLOCK);
	}

	private static void floor(GameTestHelper helper) {
		for (int x = 0; x < SITE; x++)
			for (int z = 0; z < SITE; z++)
				helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
	}

	/** What an empty-handed right-click does, without needing a fake player. */
	private static void activate(GameTestHelper helper, BlockPos pos) {
		battery(helper, pos).toggleNextTick = true;
	}

	private static GravityBatteryBlockEntity battery(GameTestHelper helper, BlockPos pos) {
		return helper.getBlockEntity(pos);
	}
}
