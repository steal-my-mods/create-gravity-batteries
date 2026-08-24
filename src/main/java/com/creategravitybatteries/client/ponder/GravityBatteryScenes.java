package com.creategravitybatteries.client.ponder;

import com.creategravitybatteries.battery.GravityBatteryBlockEntity;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.element.ElementLink;
import net.createmod.ponder.api.element.WorldSectionElement;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The one scene, and it animates because the thing this block does is movement — a still picture of a
 * Gravity Battery is a still picture of a box on a rope.
 *
 * <p>The layout matches {@code tools/generate_structures.py}, which writes the structure it plays
 * in. The weight is shown as an independent section so it can be moved with
 * {@link net.createmod.ponder.api.scene.WorldInstructions#moveSection moveSection}, and the cable is
 * wound to match by {@link AnimateGravityBatteryInstruction}. The two have to be given the same
 * duration or the cable and the weight drift apart on screen.
 */
public class GravityBatteryScenes {

	private static final int TRAVEL = 2;
	private static final int TRAVEL_TICKS = 60;

	public static void storingHeight(SceneBuilder builder, SceneBuildingUtil util) {
		CreateSceneBuilder scene = new CreateSceneBuilder(builder);
		scene.title("gravity_battery", "Storing rotational power as height");
		scene.configureBasePlate(0, 0, 5);
		scene.scaleSceneView(0.9F);
		scene.setSceneOffsetY(-1.0F);

		BlockPos battery = util.grid()
			.at(2, 5, 2);
		BlockPos motor = util.grid()
			.at(2, 5, 4);
		Selection kinetics = util.select()
			.fromTo(1, 5, 1, 2, 5, 4);
		Selection weight = util.select()
			.fromTo(2, 1, 1, 3, 2, 2);

		scene.world()
			.showSection(util.select()
				.layer(0), Direction.UP);
		scene.idle(5);

		ElementLink<WorldSectionElement> weightSection = scene.world()
			.showIndependentSection(weight, Direction.DOWN);
		scene.idle(15);
		scene.overlay()
			.showText(80)
			.text("Glue a heavy structure together beneath a Gravity Battery. Its size is the battery's power rating")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(util.grid()
					.at(3, 2, 1), Direction.EAST));
		scene.idle(90);

		scene.world()
			.showSection(kinetics, Direction.DOWN);
		scene.idle(15);
		scene.overlay()
			.showControls(util.vector()
				.topOf(battery), Pointing.DOWN, 40)
			.rightClick();
		scene.overlay()
			.showText(70)
			.text("Activating the battery takes hold of whatever is hanging below it. It does not let go again")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(battery, Direction.WEST));
		scene.idle(20);

		// The scene has no contraption -- assembling one is server-side work -- so the cable is drawn
		// from the block entity's own offset, and this is what tells it there is something on the end.
		scene.world()
			.modifyBlockEntity(battery, GravityBatteryBlockEntity.class, be -> {
				be.running = true;
				be.animateOffset(TRAVEL);
			});
		scene.idle(60);

		scene.world()
			.setKineticSpeed(kinetics, 32);
		scene.effects()
			.rotationDirectionIndicator(battery.south());
		scene.idle(10);
		scene.overlay()
			.showText(TRAVEL_TICKS + 20)
			.colored(PonderPalette.GREEN)
			.text("While the rest of the network has stress capacity to spare, the battery draws it and winds the weight up")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.topOf(battery));
		scene.addInstruction(AnimateGravityBatteryInstruction.move(battery, -TRAVEL, TRAVEL_TICKS));
		scene.world()
			.moveSection(weightSection, util.vector()
				.of(0, TRAVEL, 0), TRAVEL_TICKS);
		scene.idle(TRAVEL_TICKS + 30);

		// Pull the source out. The shaft and the cogs are left behind on purpose: the battery needs
		// something to drive, and driving it is the whole point of the failover.
		scene.world()
			.destroyBlock(motor);
		scene.idle(15);
		scene.overlay()
			.showText(80)
			.colored(PonderPalette.RED)
			.text("When the rest of the network can no longer carry its own load, the battery lets the weight back down")
			.attachKeyFrame()
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(motor, Direction.UP));
		scene.idle(90);

		scene.effects()
			.rotationDirectionIndicator(battery.south());
		scene.addInstruction(AnimateGravityBatteryInstruction.move(battery, TRAVEL, TRAVEL_TICKS));
		scene.world()
			.moveSection(weightSection, util.vector()
				.of(0, -TRAVEL, 0), TRAVEL_TICKS);
		scene.overlay()
			.showText(TRAVEL_TICKS + 20)
			.text("The descent drives the shaft. Turn a battery either way and it charges; only the network's balance decides when it discharges")
			.placeNearTarget()
			.pointAt(util.vector()
				.blockSurface(util.grid()
					.at(1, 5, 1), Direction.WEST));
		scene.idle(TRAVEL_TICKS + 30);

		scene.world()
			.setKineticSpeed(kinetics, 0);
		scene.overlay()
			.showOutlineWithText(weight, 90)
			.colored(PonderPalette.SLOW)
			.text("It falls until it runs into something, and stops there. The drop is how long the battery lasts; the weight is how much power it gives")
			.attachKeyFrame()
			.placeNearTarget();
		scene.idle(100);
		scene.markAsFinished();
	}
}
