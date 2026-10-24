package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The bundled farm blueprint, and a built farm's plots as the colony's field. */
class FarmTest {
	private static final Identifier FARM = Identifier.fromNamespaceAndPath("antcraft", "farm");
	private static final BlockPos AT = new BlockPos(6, 64, 6);
	/** How far farmland reaches for water, sideways. */
	private static final int HYDRATION = 4;
	private static Blueprint blueprint;
	private static StructureTemplate structure;

	@BeforeAll
	static void bootstrap() throws IOException {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		blueprint = Blueprint.parse(JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/antcraft/blueprint/farm.json"))))
			.getOrThrow();
		structure = VanillaData.structure(blueprint.structure().getPath());
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	private static BuildPlan plan(BlockPos at, Mirror mirror, Rotation rotation) {
		return RegionPlan.load(structure, at, mirror, rotation, 0).orElseThrow();
	}

	private static List<BlockPos> built(BlockPos at, Mirror mirror, Rotation rotation, Block block) {
		return plan(at, mirror, rotation).steps().stream().filter(step -> step.state().is(block)).map(BuildPlan.Step::pos).toList();
	}

	/** Places the farm for the colony to build, without levelling. */
	private static PlacedBlueprint place(Colony colony, BlockPos at, Mirror mirror, Rotation rotation) {
		PlacedBlueprint site = BlueprintPlacement.site(colony, FARM, Optional.of(blueprint), Optional.of(structure.getSize()), at, rotation, mirror)
			.right().orElseThrow();
		return colony.placeBlueprint(site, Optional.empty(), plan(at, mirror, rotation));
	}

	/** Finishes every open build batch until none is left. */
	private static void finishAll(Colony colony) {
		for (int round = 0; round < 50 && !colony.builds().isEmpty(); round++) {
			colony.postBuildJobs();
			colony.board().jobs().stream().filter(job -> job.isOpen() && colony.build(job.id()).isPresent()).toList().forEach(job -> {
				colony.finishBatch(job.id());
				colony.board().complete(job.id());
			});
		}
	}

	@Test
	void theBundledFarmIsAFarmWhosePlotsAreItsSoilAndAllReachTheWater() {
		assertEquals(Optional.of(Blueprint.Role.FARM), blueprint.role());
		assertTrue(blueprint.workSpots().size() >= 20, "too few plots: " + blueprint.workSpots());
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				List<BlockPos> plots = blueprint.workSpotsAt(structure.getSize(), AT, mirror, rotation);
				assertEquals(new HashSet<>(built(AT, mirror, rotation, Blocks.DIRT)), new HashSet<>(plots), mirror + " " + rotation);
				assertEquals(plots.size(), new HashSet<>(plots).size(), "a plot twice");
				List<BlockPos> water = built(AT, mirror, rotation, Blocks.WATER);
				assertTrue(!water.isEmpty(), "no water");
				for (BlockPos plot : plots) {
					// Clear above, and water on its level within reach, as farmland needs to stay wet.
					assertTrue(plan(AT, mirror, rotation).steps().stream().anyMatch(step -> step.pos().equals(plot.above()) && step.state().isAir()),
						"nothing clears above " + plot);
					assertTrue(water.stream().anyMatch(w -> w.getY() == plot.getY() && Math.abs(w.getX() - plot.getX()) <= HYDRATION
						&& Math.abs(w.getZ() - plot.getZ()) <= HYDRATION), "dry plot " + plot);
				}
			}
		}
	}

	@Test
	void aBuiltFarmTurnedAndMirroredIsTheColonyFieldInPlaceOfTheLaidOutOne() {
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				Colony colony = colony();
				Field laidOut = new Field(new BlockPos(-8, 63, -8));
				colony.setField(laidOut);
				PlacedBlueprint placed = place(colony, AT, mirror, rotation);
				assertEquals(Optional.of(laidOut), colony.field(), "while the farm is being built");

				finishAll(colony);
				Field field = colony.field().orElseThrow();
				assertEquals(blueprint.workSpotsAt(structure.getSize(), AT, mirror, rotation), field.plots(), mirror + " " + rotation);
				assertEquals(placed.area(), field.area());
				// The farm is built with its water: the farmer does not pour any.
				assertEquals(Optional.empty(), field.water());
				assertEquals(field.plots().stream().map(BlockPos::above).toList(), field.toPlant(pos -> false));
			}
		}
	}

	@Test
	void theColonyFieldHoldsThePlotsOfEveryBuiltFarmOldestFirst() {
		Colony colony = colony();
		place(colony, AT, Mirror.NONE, Rotation.NONE);
		BlockPos second = AT.offset(0, 0, -8);
		place(colony, second, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90);
		finishAll(colony);

		List<BlockPos> expected = new ArrayList<>(blueprint.workSpotsAt(structure.getSize(), AT, Mirror.NONE, Rotation.NONE));
		expected.addAll(blueprint.workSpotsAt(structure.getSize(), second, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90));
		assertEquals(expected, colony.field().orElseThrow().plots());
	}

	@Test
	void aColonyWithABuiltFarmStillFarmsItOnceLoaded() {
		Colony colony = colony();
		place(colony, AT, Mirror.NONE, Rotation.COUNTERCLOCKWISE_90);
		finishAll(colony);
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow()).getOrThrow();
		assertEquals(colony.field(), loaded.field());
		assertTrue(loaded.field().isPresent());
	}

	@Test
	void aBuiltFarmKeepsItsPlotsTilledOrNotAndItsWaterEvenWhenGone() {
		Colony colony = colony();
		place(colony, AT, Mirror.NONE, Rotation.CLOCKWISE_90);
		List<BlockPos> water = built(AT, Mirror.NONE, Rotation.CLOCKWISE_90, Blocks.WATER);
		BlockPos plot = blueprint.workSpotsAt(structure.getSize(), AT, Mirror.NONE, Rotation.CLOCKWISE_90).getFirst();
		assertFalse(colony.isProtected(pos -> Blocks.FARMLAND.defaultBlockState(), plot), "protected while the farm is being built");

		finishAll(colony);
		for (BlockPos spot : blueprint.workSpotsAt(structure.getSize(), AT, Mirror.NONE, Rotation.CLOCKWISE_90)) {
			// Tilled, the dirt a builder placed is farmland: a path must not break it all the same.
			assertTrue(colony.isProtected(pos -> Blocks.FARMLAND.defaultBlockState(), spot), "tilled plot " + spot);
			assertTrue(colony.isProtected(pos -> Blocks.DIRT.defaultBlockState(), spot), "plot " + spot);
		}
		for (BlockPos pos : water) {
			// Bots fill no bucket there, and once it is gone the farmer pours it back.
			assertTrue(colony.isProtected(p -> Blocks.WATER.defaultBlockState(), pos), "water " + pos);
			assertTrue(colony.isProtected(p -> Blocks.AIR.defaultBlockState(), pos), "water gone " + pos);
		}
		BlockPos beside = new BlockPos(placed(colony).area().maxX() + 1, AT.getY(), AT.getZ());
		assertFalse(colony.isProtected(pos -> Blocks.DIRT.defaultBlockState(), beside), "beside the farm");
	}

	@Test
	void theFieldWaterIsTheWaterOfEveryBuiltFarmOldestFirstElseTheLaidOutFieldsMiddle() {
		Colony colony = colony();
		Field laidOut = new Field(new BlockPos(-8, 63, -8));
		colony.setField(laidOut);
		place(colony, AT, Mirror.NONE, Rotation.NONE);
		BlockPos second = AT.offset(0, 0, -8);
		place(colony, second, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90);
		assertEquals(List.of(laidOut.center()), colony.fieldWater(), "while the farms are being built");

		finishAll(colony);
		List<BlockPos> expected = new ArrayList<>(built(AT, Mirror.NONE, Rotation.NONE, Blocks.WATER));
		expected.addAll(built(second, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90, Blocks.WATER));
		assertEquals(expected, colony.fieldWater());
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow()).getOrThrow();
		assertEquals(expected, loaded.fieldWater(), "once loaded");
	}

	@Test
	void aFarmSavedWithoutWaterGetsItsWaterSpotsFromItsStructureOnLoad() {
		Colony colony = colony();
		place(colony, AT, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90);
		finishAll(colony);
		JsonElement saved = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		// As saved before placed blueprints kept their water.
		saved.getAsJsonObject().getAsJsonArray("blueprints").forEach(placed -> placed.getAsJsonObject().remove("water"));
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
		assertEquals(List.of(), loaded.fieldWater(), "the old save has water");

		loaded.lookUpFluids(placed -> Optional.of(plan(placed.at(), placed.mirror(), placed.rotation())));
		List<BlockPos> water = built(AT, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90, Blocks.WATER);
		assertEquals(water, loaded.fieldWater());
		assertTrue(loaded.isProtected(pos -> Blocks.AIR.defaultBlockState(), water.getFirst()), "water gone");
	}

	@Test
	void aBuiltFarmsWaterThatIsGoneGetsAPlanToPourItBackOnce() {
		Colony colony = colony();
		place(colony, AT, Mirror.NONE, Rotation.NONE);
		finishAll(colony);
		BlockPos water = built(AT, Mirror.NONE, Rotation.NONE, Blocks.WATER).getFirst();
		colony.restoreFluids(pos -> pos.equals(water) ? Blocks.WATER.defaultBlockState() : Blocks.DIRT.defaultBlockState());
		assertEquals(List.of(), colony.builds(), "the water stands");

		colony.restoreFluids(pos -> pos.equals(water) ? Blocks.AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState());
		colony.restoreFluids(pos -> pos.equals(water) ? Blocks.AIR.defaultBlockState() : Blocks.DIRT.defaultBlockState());
		assertEquals(1, colony.builds().size(), "one plan: " + colony.builds());
		assertEquals(List.of(new BuildPlan.Step(water, Blocks.WATER.defaultBlockState())), colony.builds().getFirst().steps());
	}

	private static PlacedBlueprint placed(Colony colony) {
		return colony.blueprints().getFirst();
	}
}
