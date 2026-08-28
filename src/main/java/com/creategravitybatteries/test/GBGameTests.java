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
import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity.SequenceContext;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencerInstructions;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
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
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
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

	/**
	 * North of battery A, for the Display Link test: a link on the battery's north face, and a sign for
	 * it to write to with a block under the sign to stand it on. Nothing else in the rig reaches z=4 or
	 * z=3, and a battery only ever consults its neighbours along its own rotation axis.
	 */
	private static final BlockPos LINK = new BlockPos(3, SHAFT_Y, 4);
	private static final BlockPos SIGN = new BlockPos(3, SHAFT_Y + 1, 3);

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

	/**
	 * Digging the floor out from under a descending weight must let it carry on down.
	 *
	 * <p>Reported from play, and the cause was that {@code canDescend()} compared against the drop
	 * measured at assembly rather than asking the world. The weight stopped dead at the old limit and
	 * reported itself spent, with an open shaft underneath it.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 600)
	public static void clearingTheFloorLetsTheWeightCarryOnDown(GameTestHelper helper) {
		// A shelf for the weight to land on partway down, with the real floor further below.
		rig(helper);
		for (int x = 2; x <= 4; x++)
			for (int z = 4; z <= 6; z++)
				helper.setBlock(new BlockPos(x, 4, z), Blocks.STONE);
		hangWeight(helper, 3, SHAFT_Y - 1);
		activate(helper, BATTERY_A);

		float[] landed = new float[1];
		helper.runAfterDelay(SETTLE_TICKS, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			// The shelf's top is at y=5, so a two-tall weight comes to rest with its top at y=6.
			helper.assertTrue(battery.getDropRange() == SHAFT_Y - 1 - 6,
				"the shelf should have been measured at " + (SHAFT_Y - 1 - 6) + ", drop reads "
					+ battery.getDropRange());
		});

		// Let it settle onto the shelf, then take the shelf away.
		helper.runAfterDelay(420, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			landed[0] = battery.offset;
			helper.assertTrue(!battery.canDescend(),
				"the weight should be resting on the shelf by now, at offset " + landed[0]);
			for (int x = 2; x <= 4; x++)
				for (int z = 4; z <= 6; z++)
					helper.setBlock(new BlockPos(x, 4, z), Blocks.AIR);
		});

		helper.runAfterDelay(520, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getDropRange() > landed[0],
				"the drop was not re-measured after the floor went: still " + battery.getDropRange());
			helper.assertTrue(battery.offset > landed[0],
				"the weight did not carry on down: " + landed[0] + " -> " + battery.offset);
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

	/**
	 * A second activation gives the weight back as blocks, at the grid position it had settled to.
	 *
	 * <p>Not necessarily where it was picked up from: with no motor on the shaft this battery starts
	 * letting the weight down immediately, and blocks land on whole coordinates, so a weight that has
	 * descended a fraction settles to the next position <em>down</em>. That direction is deliberate —
	 * see {@code lettingGoSettlesTheWeightDownwards} — so the expected position is computed from where
	 * the weight actually was rather than assumed to be the starting one.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 300)
	public static void activatingAgainPutsTheWeightBack(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);

		float[] before = new float[1];
		helper.runAfterDelay(SETTLE_TICKS, () -> {
			before[0] = battery(helper, BATTERY_A).offset;
			activate(helper, BATTERY_A);
		});
		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(!battery.running, "the battery should have let go");
			helper.assertBlockPresent(Blocks.SLIME_BLOCK,
				new BlockPos(3, SHAFT_Y - 1 - Mth.ceil(before[0]), 5));
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

	/**
	 * A battery must not be left holding a Sequenced Gearshift's travel limit.
	 *
	 * <p>Create's actuator takes a {@code TURN_DISTANCE} instruction as a distance to move, counts it
	 * down against every tick of movement, and once it is spent sets {@code locked} — which forces a
	 * re-sync and a hard client-side snap <em>every tick</em> for as long as the sequence holds. A
	 * battery's direction comes from its mode rather than from the shaft, so it has nothing to steer with
	 * the instruction and was honouring only the cost.
	 *
	 * <p>The context is staged directly rather than by building a gearshift, whose instructions live in
	 * GUI state a GameTest cannot reach. It has to be staged at all: {@code super.onSpeedChanged} clears
	 * the limit on the way in and only re-imposes it when a context is present, so a test without one
	 * passes whether or not this mod does anything — which is what the first version of this test did.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aBatteryIgnoresASequencedDistance(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getSpeed() != 0,
				"the rig needs a turning shaft for the limit to come out non-zero");

			battery.sequenceContext =
				new SequenceContext(SequencerInstructions.TURN_DISTANCE, 4.0);
			battery.onSpeedChanged(0);

			helper.assertTrue(!battery.hasSequencedLimit(),
				"a Sequenced Gearshift's travel limit survived onSpeedChanged, so the actuator will "
					+ "force a re-sync every tick once it is spent");
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

	// --- weights with machinery in them -----------------------------------------------------------

	/**
	 * A battery refuses a weight with an actor in it, and leaves the world alone doing so.
	 *
	 * <p>Actors — drills, saws, harvesters, deployers — stall the contraption while they work, and a
	 * stall freezes the offset. The energy model rests on time spent equalling height lost, so a stalled
	 * weight supplies its full rating while descending nothing: a drill parked on obsidian is a power
	 * station whose output is set by block hardness. Two attempts to withhold that payment instead both
	 * broke drilling far worse than the exploit they closed, because withholding moves the mode, a mode
	 * change moves the generated speed, and Create's actuator stops the contraption's actors on a sign
	 * change — clearing the stall that was the only thing holding the weight against the block.
	 * Measured: a weight ten blocks down with all six blocks it "cut" still standing.
	 *
	 * <p>Refusing it outright is the rule that has no such tail. Create's block for cutting a shaft with
	 * drills is the Rope Pulley.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aWeightMayNotContainMachinery(GameTestHelper helper) {
		rig(helper);
		BlockPos slime = new BlockPos(3, SHAFT_Y - 1, 5);
		BlockPos drill = new BlockPos(3, SHAFT_Y - 2, 5);
		helper.setBlock(slime, Blocks.SLIME_BLOCK);
		helper.setBlock(drill, AllBlocks.MECHANICAL_DRILL.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.DOWN));
		activate(helper, BATTERY_A);
		drive(helper);

		helper.runAfterDelay(SETTLE_TICKS + 20, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(!battery.running,
				"the battery took hold of a weight with a drill in it");
			helper.assertTrue(battery.getLastAssemblyException() != null,
				"...and it should say why, on the block; it reported nothing");
			// Refused before anything started moving, so the weight is still standing in the world.
			helper.assertBlockPresent(Blocks.SLIME_BLOCK, slime);
			helper.assertBlockPresent(AllBlocks.MECHANICAL_DRILL.get(), drill);
			helper.succeed();
		});
	}

	/** ...while a weight of plain blocks is still taken, so the rule is not simply refusing everything. */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aWeightOfPlainBlocksIsStillTaken(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, SHAFT_Y - 1);
		activate(helper, BATTERY_A);

		helper.runAfterDelay(SETTLE_TICKS, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.running, "a plain slime weight should still be picked up");
			helper.assertTrue(battery.getLastAssemblyException() == null,
				"...without complaint; it says " + battery.getLastAssemblyException());
			helper.succeed();
		});
	}

	/**
	 * A battery may not be carried by another contraption, and that is data safety rather than taste.
	 *
	 * <p>Create's own Rope Pulley can be moved, but it is handled: {@code Contraption.moveBlock}
	 * special-cases {@code PulleyBlock} to bring the rope along, and Create's ponder scene says plainly
	 * that pulleys are only movable while stopped. A battery is never stopped in that sense — it is
	 * holding a weight — and it gets none of that handling. Sweep one into a piston and its block
	 * vanishes from under the weight; the weight's entity then finds no controller and
	 * {@code ControlledContraptionEntity.tickContraption} calls {@code discard()}, which deletes the
	 * blocks rather than putting them back. Breaking a battery is fine — that goes through
	 * {@code remove()} to {@code disassemble()} — it is being carried off that loses the weight.
	 *
	 * <p>So the block is in Create's {@code non_movable} tag. Asserted through
	 * {@code BlockMovementChecks}, which is the thing every contraption actually asks.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void aBatteryCannotBeCarriedOffByAnotherContraption(GameTestHelper helper) {
		rig(helper);
		BlockPos absolute = helper.absolutePos(BATTERY_A);
		helper.assertTrue(
			!BlockMovementChecks.isMovementAllowed(helper.getBlockState(BATTERY_A), helper.getLevel(),
				absolute),
			"a contraption is allowed to carry the battery off, which deletes the weight it was holding");
		helper.succeed();
	}

	/**
	 * Letting go must never lift the weight.
	 *
	 * <p>Create's {@code getGridOffset} rounds to nearest, and offset is measured downward — so rounding
	 * it down lifts the weight by up to half a block, free. Charge to just under a half, toggle, repeat.
	 * Settling downward instead can only lose a fraction, which is the safe direction.
	 *
	 * <p>The toggle has to happen at a fractional offset whose two roundings differ, or the test proves
	 * nothing; that is asserted before it fires.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void lettingGoSettlesTheWeightDownwards(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);

		// Long enough to have wound up past a whole block, landing on a fraction below a half.
		float[] before = new float[1];
		helper.runAfterDelay(200, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			before[0] = battery.offset;
			float fraction = before[0] - (int) before[0];
			helper.assertTrue(fraction > 0.05F && fraction < 0.45F,
				"this test needs an offset whose ceil and round differ, the weight is at " + before[0]);
			activate(helper, BATTERY_A);
		});

		helper.runAfterDelay(215, () -> {
			// The weight's top block sits at batteryY - 1 - offset.
			int settled = Mth.ceil(before[0]);
			int lifted = Math.round(before[0]);
			helper.assertTrue(settled != lifted, "the roundings agree, so this asserts nothing");
			helper.assertBlockPresent(Blocks.SLIME_BLOCK, new BlockPos(3, SHAFT_Y - 1 - settled, 5));
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(3, SHAFT_Y - 1 - lifted, 5));
			helper.succeed();
		});
	}

	// --- reversal and interruption ------------------------------------------------------------------

	/**
	 * A Gearshift reversing the network must not destroy the battery.
	 *
	 * <p>Create's {@code applyNewSpeed} destroys a generator whose rotation opposes a network stronger
	 * than itself — that is how it punishes two motors fighting each other, and it is a real
	 * {@code level.destroyBlock} on the block. A battery declares a direction of its own, so a
	 * Gearshift between it and the rest of the network is exactly the arrangement that could trip it.
	 * {@code alignDirectionWith} is what prevents it, by flipping to agree with a shaft that is already
	 * turning rather than insisting.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void reversingTheNetworkDoesNotDestroyTheBattery(GameTestHelper helper) {
		floor(helper);
		placeBattery(helper, BATTERY_A);
		// motor -> gearshift -> battery, so flipping the gearshift reverses what reaches the battery.
		BlockPos gearshift = new BlockPos(2, SHAFT_Y, 5);
		helper.setBlock(gearshift, AllBlocks.GEARSHIFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		helper.setBlock(new BlockPos(1, SHAFT_Y, 5), AllBlocks.CREATIVE_MOTOR.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.EAST));
		helper.setBlock(SHAFT, AllBlocks.SHAFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);

		// Flip it back and forth; each flip is a sign change at the battery.
		for (int at : new int[] { 60, 110, 160, 210 }) {
			helper.runAfterDelay(at, () -> helper.setBlock(gearshift, helper.getBlockState(gearshift)
				.cycle(BlockStateProperties.POWERED)));
		}

		helper.runAfterDelay(260, () -> {
			helper.assertBlockPresent(GBBlocks.GRAVITY_BATTERY.get(), BATTERY_A);
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.running, "the battery let go of its weight during the reversals");
			helper.succeed();
		});
	}

	// --- the wrench -------------------------------------------------------------------------------

	/**
	 * A wrench-rotated battery must go back to driving the shaft.
	 *
	 * <p>{@code IRotate extends IWrenchable}, so a battery is wrenchable like every Create kinetic
	 * block, and {@code IWrenchable.onWrenched} routes through
	 * {@code KineticBlockEntity.switchToBlockState} — which detaches the kinetics for a state that is
	 * not kinetically equivalent and then re-arms the source with
	 * {@code if (be instanceof GeneratingKineticBlockEntity) be.reActivateSource = true}. This block is
	 * not one, so it never got re-armed: a battery that was carrying the network sat at speed zero
	 * afterwards, mode still DISCHARGING, weight stopped, nothing logged.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 400)
	public static void aWrenchedBatteryKeepsDrivingTheShaft(GameTestHelper helper) {
		rig(helper);
		// A shaft on the other axis too, so rotating the battery does not simply disconnect it -- that
		// would idle it for a legitimate reason and prove nothing about re-arming the source.
		helper.setBlock(new BlockPos(3, SHAFT_Y, 6), AllBlocks.SHAFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);

		// No motor, so the battery is the network's only source and is letting the weight down.
		helper.runAfterDelay(40, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() == BatteryMode.DISCHARGING,
				"expected the battery to be driving the shaft, it is " + battery.getMode());
			helper.assertTrue(battery.getTheoreticalSpeed() != 0,
				"the battery should have a speed before it is wrenched");

			// Exactly what the wrench does: swap in the rotated state through Create's own helper.
			KineticBlockEntity.switchToBlockState(helper.getLevel(), helper.absolutePos(BATTERY_A),
				helper.getBlockState(BATTERY_A)
					.setValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS, Direction.Axis.Z));
		});

		helper.runAfterDelay(120, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getTheoreticalSpeed() != 0,
				"the battery stopped driving after being wrenched; mode is " + battery.getMode()
					+ " and speed is " + battery.getTheoreticalSpeed());
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

	/**
	 * A change of state has to reach a Display Board at once, not at the next poll.
	 *
	 * <p>A Display Link asks its source for text every {@code getPassiveRefreshTicks()}, and both of
	 * this mod's sources take Create's default of 100. That is right for the charge, which at the
	 * defaults moves about 2.6% of a full travel in that time — finer than a board's bar can draw — and
	 * wrong for the status line, which reports an event: a battery that had flipped to letting down went
	 * on saying "Winding up" for up to five seconds. Create's answer is
	 * {@code DisplayLinkBlock.notifyGatherers}, which its own Threshold Switch, Nixie Tube, Station and
	 * Track Observer all call when their state moves in steps rather than drifting.
	 *
	 * <p>Asserted through a real link onto a real sign, because that is the only place the push is
	 * visible: calling the source directly reports the new state whether or not anything has told the
	 * link to ask. The link's own timer starts when it is given a source, at tick {@code SETTLE_TICKS},
	 * so its first passive poll cannot land before tick 112 — and both halves of this read the sign well
	 * before then. On a build without the push the sign is still reading "winding up".
	 *
	 * <p>Two halves, because the two are different call sites. The mode change is
	 * {@code setMode}; letting go of the weight is {@code disassemble}, which sets the mode by
	 * direct assignment and so is not covered by the first.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void aChangeOfStateReachesADisplayLinkAtOnce(GameTestHelper helper) {
		rig(helper);
		hangWeight(helper, 3, HIGH_TOP);
		activate(helper, BATTERY_A);
		drive(helper);
		displayLink(helper);

		String[] shown = new String[1];
		helper.runAfterDelay(SETTLE_TICKS, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() == BatteryMode.CHARGING,
				"expected to be winding up by now, was " + battery.getMode());
			linkStatusToSign(helper);
			shown[0] = signLine(helper);
			helper.assertTrue(!shown[0].isEmpty(),
				"the link wrote nothing to the sign, so this test is watching nothing");
		});

		// Take the drive away. The battery is then the only thing on the network that can turn the
		// shaft, so it flips out of CHARGING within a tick or two of the motor going.
		helper.runAfterDelay(SETTLE_TICKS + 8, () -> helper.setBlock(MOTOR, Blocks.AIR));

		helper.runAfterDelay(SETTLE_TICKS + 23, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() != BatteryMode.CHARGING,
				"the motor is gone and the battery is still winding up, so there is no change to see");
			shown[0] = assertSignFollowed(helper, battery, shown[0], "the mode changed");
		});

		// And the other call site: a right-click that lets the weight go moves both the mode and the
		// reason, and it does it without going through setMode.
		helper.runAfterDelay(SETTLE_TICKS + 28, () -> activate(helper, BATTERY_A));

		helper.runAfterDelay(SETTLE_TICKS + 43, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(!battery.running && battery.getIdleReason() == IdleReason.NO_WEIGHT,
				"the weight should have been let go, the battery says " + battery.getMode() + "/"
					+ battery.getIdleReason());
			assertSignFollowed(helper, battery, shown[0], "the weight was let go");
			helper.succeed();
		});
	}

	/**
	 * The idle <em>reason</em> is the other half of what the status source reports, and it moves without
	 * the mode moving — so it is a third call site, {@code idle}, and nothing above reaches it.
	 *
	 * <p>The transition is one block: a spent battery over a shaft is idle because it is DISCHARGED,
	 * and with the shaft gone there is nothing worth spending charge into, so the same idle battery is
	 * idle because it is NOT_TURNING. The mode is IDLE on both sides of that.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 200)
	public static void anIdleReasonChangeReachesADisplayLinkToo(GameTestHelper helper) {
		rig(helper);
		// On the floor with no motor: idle, spent, and something on the shaft worth driving.
		hangWeight(helper, 3, RESTING_TOP);
		activate(helper, BATTERY_A);
		displayLink(helper);

		String[] shown = new String[1];
		helper.runAfterDelay(SETTLE_TICKS, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() == BatteryMode.IDLE
				&& battery.getIdleReason() == IdleReason.DISCHARGED,
				"expected to be idle and spent, was " + battery.getMode() + "/"
					+ battery.getIdleReason());
			linkStatusToSign(helper);
			shown[0] = signLine(helper);
		});

		helper.runAfterDelay(SETTLE_TICKS + 8, () -> helper.setBlock(SHAFT, Blocks.AIR));

		helper.runAfterDelay(SETTLE_TICKS + 23, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			helper.assertTrue(battery.getMode() == BatteryMode.IDLE,
				"this half only means something while the mode holds still, it is " + battery.getMode());
			helper.assertTrue(battery.getIdleReason() == IdleReason.NOT_TURNING,
				"with the shaft gone the reason should be NOT_TURNING, it is " + battery.getIdleReason());
			assertSignFollowed(helper, battery, shown[0], "the idle reason changed");
			helper.succeed();
		});
	}

	/**
	 * The sign has to read something new, and it has to read the right something: a push that fired but
	 * carried the wrong line, or a line that changed for some reason other than the push, both pass the
	 * first assertion alone.
	 */
	private static String assertSignFollowed(GameTestHelper helper, GravityBatteryBlockEntity battery,
		String before, String because) {
		String now = signLine(helper);
		helper.assertTrue(!now.equals(before),
			"the sign still reads '" + now + "' fifteen ticks after " + because);
		String expected = lineOf(GBDisplaySources.BATTERY_STATUS.get(), battery, 0).getString();
		helper.assertTrue(now.equals(expected),
			"the sign reads '" + now + "' and the source now says '" + expected + "'");
		return now;
	}

	/**
	 * A Display Link on battery A's north face with a sign for it to write to, and a block under the
	 * sign to stand it on.
	 *
	 * <p>FACING points from the source block to the link, which is what {@code getDirection()} reverses
	 * to find its source — so a link north of the battery faces north.
	 */
	private static void displayLink(GameTestHelper helper) {
		helper.setBlock(LINK, AllBlocks.DISPLAY_LINK.getDefaultState()
			.setValue(BlockStateProperties.FACING, Direction.NORTH));
		helper.setBlock(SIGN.below(), Blocks.STONE);
		helper.setBlock(SIGN, Blocks.OAK_SIGN);
	}

	/** What configuring the link in its own UI would do, and one gather to prime the sign. */
	private static void linkStatusToSign(GameTestHelper helper) {
		DisplayLinkBlockEntity link = helper.getBlockEntity(LINK);
		link.activeSource = GBDisplaySources.BATTERY_STATUS.get();
		// Absolute, because target() stores the offset from the link's own world position.
		link.target(helper.absolutePos(SIGN));
		link.updateGatheredData();
	}

	/** The first line of the front of the sign the Display Link writes to. */
	private static String signLine(GameTestHelper helper) {
		SignBlockEntity sign = helper.getBlockEntity(SIGN);
		return sign.getFrontText()
			.getMessage(0, false)
			.getString();
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

	// --- the periodic resync ----------------------------------------------------------------------

	/**
	 * A battery that is not moving must stop repeating itself to the client; a battery that is moving
	 * must carry on.
	 *
	 * <p>Create's actuator resyncs unconditionally for as long as a contraption is attached, at
	 * {@code setLazyTickRate(3)}. A Rope Pulley only pays that while it is moving, because it lets go
	 * when it stops; a battery never lets go, so it was a block-entity packet every fourth tick for the
	 * rest of the world's life -- per battery, per player tracking the chunk, and measured to be
	 * identical in twelve of its thirteen fields across ten consecutive sends.
	 *
	 * <p>Watched through {@link GravityBatteryBlockEntity#getLastSyncTick()}, because a GameTest runs
	 * on a dedicated server and cannot see the wire. Both halves are load-bearing and each catches the
	 * opposite mistake: the resting half fails on Create's unconditional version, and the moving half
	 * fails on a gate clamped shut, which would leave every client's weight to drift. An earlier
	 * version of this test asserted {@code hasUnsyncedMovement()} instead and passed on both mutations,
	 * because that predicate is self-healing -- any send at all clears it.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 500)
	public static void anIdleBatteryStopsRepeatingItself(GameTestHelper helper) {
		rig(helper);
		// Already on the floor, and no motor: IDLE, spent, and the offset never moves again.
		hangWeight(helper, 3, RESTING_TOP);
		activate(helper, BATTERY_A);

		long[] syncedAtRest = new long[2];
		helper.runAfterDelay(SETTLE_TICKS + 20,
			() -> syncedAtRest[0] = battery(helper, BATTERY_A).getLastSyncTick());
		helper.runAfterDelay(220, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			syncedAtRest[1] = battery.getLastSyncTick();
			helper.assertTrue(battery.getMode() == BatteryMode.IDLE,
				"the weight is on the floor with no drive, it should be idle; it is "
					+ battery.getMode());
			helper.assertTrue(syncedAtRest[0] != Long.MIN_VALUE,
				"the battery never synced at all, so this test is watching nothing");
			helper.assertTrue(syncedAtRest[1] == syncedAtRest[0],
				"a battery holding station resynced anyway: last sync moved " + syncedAtRest[0] + " -> "
					+ syncedAtRest[1] + " over 180 ticks");
		});

		// The other half: once it is moving again the resync must resume, or the gate has been shut on
		// the case it exists to serve and every client's weight drifts.
		long[] syncedWhileMoving = new long[2];
		helper.runAfterDelay(230, () -> drive(helper));
		helper.runAfterDelay(300,
			() -> syncedWhileMoving[0] = battery(helper, BATTERY_A).getLastSyncTick());
		helper.runAfterDelay(400, () -> {
			GravityBatteryBlockEntity battery = battery(helper, BATTERY_A);
			syncedWhileMoving[1] = battery.getLastSyncTick();
			helper.assertTrue(battery.getMode() == BatteryMode.CHARGING,
				"the motor is back, it should be winding up; it is " + battery.getMode());
			helper.assertTrue(battery.offset > 0 && battery.offset < battery.getDropRange(),
				"the weight has to be genuinely mid-travel for this half to mean anything, offset "
					+ battery.offset);
			helper.assertTrue(syncedWhileMoving[1] > syncedWhileMoving[0],
				"a moving weight must keep being resynced; last sync sat at " + syncedWhileMoving[0]
					+ " for 100 ticks");
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
	 * The cable costs one render pass per block of drop, and that is the per-frame budget.
	 *
	 * <p>With no Flywheel visual to take over, every segment is a CPU pass through
	 * {@code DefaultSuperByteBuffer.renderInto}, which loops per vertex and allocates three JOML objects
	 * for each one. So the whole client cost of this block is {@code 2 + segments(offset)} passes a
	 * frame — the shaft, the clamp, and one per segment — and what keeps that bounded is that
	 * {@link CableGeometry#segments} counts <em>blocks</em>.
	 *
	 * <p>The assertion is that one more block of drop costs exactly one more pass. That is not a
	 * restatement of {@code ceil}: it is what fails if the cable is ever reworked to Create's
	 * half-segment scheme or to sixteenths, which would multiply the budget by 16 at every extension
	 * and is a plausible thing for someone to try — Create's own pulley draws a half-rope model, and the
	 * note in CLAUDE.md about the full-tile braid texture is there because people do touch this.
	 *
	 * <p>This is the client cost that a GameTest can reach. That the renderer actually loops over
	 * {@code segments} is not covered and cannot be from here: a dedicated server refuses to load a
	 * class mentioning {@code PoseStack}.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void theCableCostsOnePassPerBlockOfDrop(GameTestHelper helper) {
		// One more block of drop, exactly one more pass -- across the whole range a battery can reach.
		for (int blocks = 1; blocks <= 64; blocks++) {
			int here = CableGeometry.segments(blocks);
			int below = CableGeometry.segments(blocks - 1);
			helper.assertTrue(here - below == 1, "a block of drop should cost one pass; going from "
				+ (blocks - 1) + " to " + blocks + " blocks went from " + below + " to " + here);
		}

		// A fractional drop costs the same as the whole block containing it, so a weight creeping
		// downwards does not add passes 16 times a block.
		helper.assertTrue(CableGeometry.segments(7.01F) == CableGeometry.segments(7.99F),
			"a fraction of a block must not add a pass: 7.01 gave "
				+ CableGeometry.segments(7.01F) + ", 7.99 gave " + CableGeometry.segments(7.99F));

		// And the budget at full extension, stated so a change to it is a visible change.
		int atFullDefaultExtension = 2 + CableGeometry.segments(64F);
		helper.assertTrue(atFullDefaultExtension == 66,
			"the per-frame budget at the default maximum cable length should be 66 passes, it is "
				+ atFullDefaultExtension);
		helper.succeed();
	}

	/**
	 * A battery is drawn while any part of its assembly is in range, not only while the block is.
	 *
	 * <p>The renderer used to cover a weight hanging in sight below an out-of-range battery by
	 * inflating its view distance to 128. That measured to the block, so it moved the boundary instead
	 * of removing it, and it multiplied by about eight the volume of batteries drawn every frame — which
	 * matters here because there is no Flywheel visual to take over, so each one costs
	 * {@code 2 + ceil(offset)} CPU vertex passes a frame.
	 *
	 * <p>Both directions are asserted. A distant battery with nothing hanging must be culled at the
	 * vanilla radius, which is what the old flat 128 got wrong; and a battery whose cable reaches the
	 * viewer must be drawn even when the block itself is far outside it, which is what a naive
	 * measure-to-the-block rule at radius 64 would get wrong.
	 */
	@GameTest(template = "test_rig", timeoutTicks = 100)
	public static void cullingFollowsTheWholeAssemblyNotJustTheBlock(GameTestHelper helper) {
		BlockPos battery = new BlockPos(0, 200, 0);
		int radius = CableGeometry.VIEW_RADIUS;
		helper.assertTrue(radius == 64,
			"the draw radius should be the vanilla 64, not an inflated one; it is " + radius);

		// Right under the battery, nothing hanging: in range.
		helper.assertTrue(
			CableGeometry.withinViewRadius(battery, 0F, new Vec3(0.5, 190, 0.5), radius),
			"a battery 10 blocks overhead must be drawn");

		// Far below it, nothing hanging: out of range, and this is the saving.
		helper.assertTrue(
			!CableGeometry.withinViewRadius(battery, 0F, new Vec3(0.5, 100, 0.5), radius),
			"a battery 100 blocks overhead with no weight must be culled at radius " + radius);

		// Same battery, but the cable now reaches down to the viewer. The block is still 100 blocks
		// away; the clamp on the end of the cable is 10. It must be drawn.
		helper.assertTrue(
			CableGeometry.withinViewRadius(battery, 90F, new Vec3(0.5, 100, 0.5), radius),
			"the weight is 10 blocks overhead and must be drawn even though its battery is 100 away");

		// A cable that stops short of the viewer stays culled -- the test is the nearest point of the
		// assembly, not merely whether a long cable exists.
		helper.assertTrue(
			!CableGeometry.withinViewRadius(battery, 20F, new Vec3(0.5, 100, 0.5), radius),
			"a cable reaching only to y=180 must not pull a battery into view at y=100");

		// Horizontal distance is not softened by cable length: the span is vertical.
		helper.assertTrue(
			!CableGeometry.withinViewRadius(battery, 90F, new Vec3(100.5, 200, 0.5), radius),
			"a battery 100 blocks sideways must be culled however long its cable is");

		// And the near edge: just inside and just outside, so the radius is a real boundary.
		helper.assertTrue(
			CableGeometry.withinViewRadius(battery, 0F, new Vec3(0.5, 200.5 - 63, 0.5), radius),
			"63 blocks below the battery is inside radius " + radius);
		helper.assertTrue(
			!CableGeometry.withinViewRadius(battery, 0F, new Vec3(0.5, 200.5 - 65, 0.5), radius),
			"65 blocks below the battery is outside radius " + radius);

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
