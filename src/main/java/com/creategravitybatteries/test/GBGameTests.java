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
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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
		helper.setBlock(BATTERY_A, GBBlocks.GRAVITY_BATTERY.get()
			.defaultBlockState()
			.setValue(HorizontalAxisKineticBlock.HORIZONTAL_AXIS, Direction.Axis.X));
		helper.setBlock(SHAFT, AllBlocks.SHAFT.getDefaultState()
			.setValue(BlockStateProperties.AXIS, Direction.Axis.X));
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
