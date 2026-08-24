package com.creategravitybatteries;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server config. Every number here changes how much work a network has to do to raise a weight and
 * how much it gets back on the way down, so it has to agree between client and server — hence SERVER
 * rather than COMMON.
 *
 * <h2>The energy model</h2>
 * Two numbers do all of it. {@link #stressPerBlock} is Stress Units per RPM per block of weight, and
 * {@link #gearReduction} is how much slower the drum turns than the shaft.
 *
 * <ul>
 * <li><b>Power</b> is {@code blocks × stressPerBlock × rpm} Stress Units.
 * <li><b>Duration</b> is {@code 512 × gearReduction × drop ÷ rpm} ticks.
 * </ul>
 *
 * Multiply them and the RPM cancels: total energy is
 * {@code 512 × gearReduction × stressPerBlock × blocks × drop}, so <em>power comes from the weight
 * and duration comes from the drop</em>, and neither depends on how fast the shaft happens to be
 * turning. That is not a coincidence to be preserved by hand — it is what mechanical work is, and it
 * holds because the descent rate and the stress rating are both linear in RPM.
 *
 * <p>{@link #roundTripEfficiency} is the only loss, and it applies on the way up: charging costs
 * {@code stressPerBlock ÷ roundTripEfficiency} per block per RPM. Keeping the loss on one side means
 * a battery can never pay for its own charging, whatever it is wired to.
 */
public class GBConfig {

	public static final ModConfigSpec SPEC;
	public static final GBConfig INSTANCE;

	/** Stress Units per RPM supplied by each block of hanging weight. */
	public final ModConfigSpec.DoubleValue stressPerBlock;
	/** How much slower the cable drum turns than the shaft. */
	public final ModConfigSpec.IntValue gearReduction;
	/** Fraction of the work put into winding a weight up that comes back out on the way down. */
	public final ModConfigSpec.DoubleValue roundTripEfficiency;
	/** Spare capacity a network must have beyond the battery's own draw before it starts charging. */
	public final ModConfigSpec.DoubleValue chargeMarginStress;
	/** Speed a discharging battery drives a network that has no speed of its own. */
	public final ModConfigSpec.IntValue maxRpm;
	/** Blocks of weight counted towards a battery's rating, however large the contraption is. */
	public final ModConfigSpec.IntValue maxWeightBlocks;
	/** Longest cable a battery will pay out. */
	public final ModConfigSpec.IntValue maxCableLength;

	private GBConfig(ModConfigSpec.Builder builder) {
		builder.comment("Gravity Battery").push("battery");
		stressPerBlock = builder
			.comment("Stress Units per RPM supplied by each block of hanging weight. A 32 block",
				"weight at this default is worth 128su/rpm -- 4096su on a 32 RPM network. Create's",
				"Steam Engine is 1024su/rpm at its top tier for comparison.")
			.defineInRange("stressPerBlock", 4.0, 0.0, 100000.0);
		gearReduction = builder
			.comment("How much slower the cable drum turns than the shaft. A Rope Pulley is 1 and",
				"pays out roughly 2.3 blocks per revolution; a Gravity Battery is geared down so a",
				"heavy weight creeps rather than plummets. This is the duration dial: doubling it",
				"doubles both how long a battery lasts and how much energy it holds, and changes",
				"nothing about how much power it supplies.")
			.defineInRange("gearReduction", 8, 1, 256);
		roundTripEfficiency = builder
			.comment("Fraction of the work put into winding a weight up that comes back out again.",
				"The loss is charged entirely on the way up, which is what makes it impossible for",
				"one battery to charge another -- a discharging battery cannot cover a charging one",
				"of the same weight, whatever they are geared through.")
			.defineInRange("roundTripEfficiency", 0.75, 0.01, 1.0);
		chargeMarginStress = builder
			.comment("Stress capacity a network must have spare, on top of the battery's own draw,",
				"before it starts winding. This is the deadband that stops a battery flapping",
				"between charging and discharging on the same tick.")
			.defineInRange("chargeMarginStress", 64.0, 0.0, 100000.0);
		maxRpm = builder
			.comment("Speed a discharging battery drives a network that has no speed of its own. A",
				"battery taking over from a failed source holds the speed the network was already",
				"running at instead, capped by this; it never speeds a factory up.")
			.defineInRange("maxRpm", 64, 1, 256);
		maxWeightBlocks = builder
			.comment("Blocks of weight counted towards a battery's rating, however large the",
				"contraption actually is. Without a ceiling here, power scales with Create's",
				"contraption size limit and a single battery can carry any network at all.")
			.defineInRange("maxWeightBlocks", 512, 1, 16384);
		maxCableLength = builder
			.comment("Longest cable a battery will pay out. Create's Rope Pulley uses 256; a shorter",
				"default keeps a battery's stated drop honest about how far it has actually probed.")
			.defineInRange("maxCableLength", 64, 1, 512);
		builder.pop();
	}

	static {
		Pair<GBConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(GBConfig::new);
		INSTANCE = pair.getLeft();
		SPEC = pair.getRight();
	}

	/**
	 * Reading a config value before its file is loaded throws. Most callers here run deep inside a
	 * block entity tick, where it always is loaded — but the stress ratings are also read by Create's
	 * item tooltip and recipe-viewer code, which can run earlier, and a crash there would be a poor
	 * trade for a number that has a perfectly good default sitting right next to it.
	 */
	private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
		return SPEC.isLoaded() ? value.get() : value.getDefault();
	}

	public static float stressPerBlock() {
		return read(INSTANCE.stressPerBlock).floatValue();
	}

	public static int gearReduction() {
		return read(INSTANCE.gearReduction);
	}

	public static float roundTripEfficiency() {
		return read(INSTANCE.roundTripEfficiency).floatValue();
	}

	public static float chargeMarginStress() {
		return read(INSTANCE.chargeMarginStress).floatValue();
	}

	public static int maxRpm() {
		return read(INSTANCE.maxRpm);
	}

	public static int maxWeightBlocks() {
		return read(INSTANCE.maxWeightBlocks);
	}

	public static int maxCableLength() {
		return read(INSTANCE.maxCableLength);
	}
}
