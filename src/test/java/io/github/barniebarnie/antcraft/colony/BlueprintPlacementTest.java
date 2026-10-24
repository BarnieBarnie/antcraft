package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.BlueprintBlocks;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Terraform;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlueprintPlacementTest {
	private static final Identifier HUT = Identifier.fromNamespaceAndPath("antcraft", "hut");
	/** A 3 by 2 by 4 storage hut with a chest in one corner and a work spot in the other. */
	private static final Blueprint BLUEPRINT = new Blueprint(Identifier.fromNamespaceAndPath("antcraft", "hut"), "blueprint.antcraft.hut",
		Optional.of(Blueprint.Role.STORAGE), List.of(new BlockPos(0, 0, 0)), List.of(new BlockPos(2, 0, 3)));
	private static final Vec3i SIZE = new Vec3i(3, 2, 4);
	private static final BlockPos AT = new BlockPos(8, 64, 8);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	private static Either<BlueprintPlacement.Refusal, PlacedBlueprint> site(Colony colony, BlockPos at) {
		return BlueprintPlacement.site(colony, HUT, Optional.of(BLUEPRINT), Optional.of(SIZE), at, Rotation.NONE, Mirror.NONE);
	}

	private static void assertRefused(BlueprintPlacement.Refusal expected, Either<BlueprintPlacement.Refusal, PlacedBlueprint> site) {
		assertEquals(Optional.of(expected), site.left(), "placement " + site.right());
	}

	@Test
	void anUnknownBlueprintIsRefused() {
		assertRefused(BlueprintPlacement.Refusal.UNKNOWN_BLUEPRINT,
			BlueprintPlacement.site(colony(), HUT, Optional.empty(), Optional.empty(), AT, Rotation.NONE, Mirror.NONE));
	}

	@Test
	void aBlueprintWhoseStructureIsMissingIsRefused() {
		assertRefused(BlueprintPlacement.Refusal.NO_STRUCTURE,
			BlueprintPlacement.site(colony(), HUT, Optional.of(BLUEPRINT), Optional.empty(), AT, Rotation.NONE, Mirror.NONE));
	}

	@Test
	void aSiteOutsideTheRadiusIsRefused() {
		assertRefused(BlueprintPlacement.Refusal.OUTSIDE, site(colony(), new BlockPos(Colony.DEFAULT_RADIUS + 5, 64, 0)));
		// Only its far corner sticks out.
		assertRefused(BlueprintPlacement.Refusal.OUTSIDE, site(colony(), new BlockPos(Colony.DEFAULT_RADIUS - 1, 64, 0)));
	}

	@Test
	void aSiteOverABuildIsRefused() {
		Colony colony = colony();
		colony.addBuild(new BuildPlan(List.of(new BuildPlan.Step(AT.offset(2, 5, 3), Blocks.STONE.defaultBlockState()))));
		assertRefused(BlueprintPlacement.Refusal.OVERLAPS_BUILD, site(colony, AT));
	}

	@Test
	void aSiteOverBlocksBuildersPlacedIsRefused() {
		Colony colony = colony();
		colony.protect(AT.offset(1, 0, 1), Blocks.OAK_PLANKS);
		assertRefused(BlueprintPlacement.Refusal.OVERLAPS_BUILD, site(colony, AT));
	}

	@Test
	void aSiteOverAnotherPlacedBlueprintIsRefused() {
		Colony colony = colony();
		place(colony, AT, Optional.empty());
		assertRefused(BlueprintPlacement.Refusal.OVERLAPS_BUILD, site(colony, AT.offset(2, 0, 3)));
	}

	@Test
	void aSiteOverTheMineshaftIsRefused() {
		Colony colony = colony();
		Mineshaft shaft = new Mineshaft(new BlockPos(10, 64, 10), List.of(60), 1, 3, 4, Mineshaft.DEFAULT_ROUNDS, Mineshaft.Layout.STAIRCASE);
		colony.setMineshaft(shaft);
		assertRefused(BlueprintPlacement.Refusal.OVERLAPS_MINESHAFT, site(colony, AT));
	}

	@Test
	void aSiteOverTheCoreIsRefused() {
		assertRefused(BlueprintPlacement.Refusal.OVERLAPS_CORE, site(colony(), new BlockPos(-1, 60, -1)));
	}

	@Test
	void anAcceptedSiteHoldsTheTurnedSpotsAndArea() {
		PlacedBlueprint site = BlueprintPlacement.site(colony(), HUT, Optional.of(BLUEPRINT), Optional.of(SIZE), AT, Rotation.CLOCKWISE_90, Mirror.FRONT_BACK)
			.right().orElseThrow();
		assertEquals(HUT, site.blueprint());
		assertEquals(Optional.of(Blueprint.Role.STORAGE), site.role());
		assertEquals(BLUEPRINT.chestsAt(SIZE, AT, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90), site.chests());
		assertEquals(BLUEPRINT.workSpotsAt(SIZE, AT, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90), site.workSpots());
		// Turned a quarter, the hut is 4 wide and 3 deep.
		assertEquals(new BoundingBox(8, 64, 8, 11, 65, 10), site.area());
	}

	/** Places the hut at {@code at} with {@code terraform} to do first: a plan of one stone block at its origin. */
	private static PlacedBlueprint place(Colony colony, BlockPos at, Optional<BuildPlan> terraform) {
		PlacedBlueprint site = site(colony, at).right().orElseThrow();
		return colony.placeBlueprint(site, terraform, new BuildPlan(List.of(new BuildPlan.Step(at, Blocks.STONE.defaultBlockState()))));
	}

	private static Optional<BuildPlan> levelling(BlockPos at) {
		return Terraform.plan(pos -> pos.getY() >= at.getY() ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState(),
			at, at.offset(2, 1, 3), at.getY() - 1);
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
	void theColonyLevelsTheSiteFirstThenBuildsAndKeepsTheFinishedBuildByRole() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, levelling(AT));
		assertEquals(List.of(BuildPlan.Kind.TERRAFORM), colony.builds().stream().map(BuildPlan::kind).toList(), "only the levelling to do at first");
		assertEquals(Optional.of(placed), colony.blueprintOf(colony.builds().getFirst()));
		Project project = colony.projects().stream().filter(p -> p.id().equals(placed.project())).findFirst().orElseThrow();
		assertEquals(Component.translatable("blueprint.antcraft.hut"), colony.displayName(project));

		// Each finished batch posts the next; the levelling's last one hands over to the structure.
		for (int round = 0; round < 50 && colony.builds().stream().allMatch(plan -> plan.kind() == BuildPlan.Kind.TERRAFORM); round++) {
			colony.postBuildJobs();
			colony.board().jobs().stream().filter(job -> job.isOpen() && colony.build(job.id()).isPresent()).findFirst().ifPresent(job -> {
				colony.finishBatch(job.id());
				colony.board().complete(job.id());
			});
		}
		assertEquals(List.of(BuildPlan.Kind.BUILD), colony.builds().stream().map(BuildPlan::kind).toList(), "the structure after the levelling");
		assertEquals(List.of(), colony.finishedBlueprints(Blueprint.Role.STORAGE));

		finishAll(colony);
		assertTrue(colony.builds().isEmpty(), "builds left: " + colony.builds());
		assertEquals(List.of(AT), colony.finishedBlueprints(Blueprint.Role.STORAGE).stream().map(PlacedBlueprint::at).toList());
		assertEquals(List.of(), colony.finishedBlueprints(Blueprint.Role.FARM));
	}

	@Test
	void aPlacedBlueprintIsSavedWithTheColony() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, levelling(AT));
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow()).getOrThrow();
		assertEquals(List.of(placed), loaded.blueprints());

		finishAll(loaded);
		Colony finished = Colony.CODEC.parse(JsonOps.INSTANCE, Colony.CODEC.encodeStart(JsonOps.INSTANCE, loaded).getOrThrow()).getOrThrow();
		assertEquals(List.of(AT), finished.finishedBlueprints(Blueprint.Role.STORAGE).stream().map(PlacedBlueprint::at).toList());
	}

	@Test
	void cancellingThePlacedBuildForgetsIt() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, levelling(AT));
		colony.cancelProject(placed.project());
		assertEquals(List.of(), colony.blueprints());
		// The site is free again.
		assertTrue(site(colony, AT).right().isPresent());
	}

	/** A 2x1x1 template with two palettes: cobblestone and oak planks in palette 0, stone and spruce planks in palette 1. */
	private static StructureTemplate twoPalettes() {
		ListTag palettes = new ListTag();
		for (List<BlockState> palette : List.of(List.of(Blocks.COBBLESTONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()),
				List.of(Blocks.STONE.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState()))) {
			ListTag states = new ListTag();
			palette.forEach(state -> states.add(NbtUtils.writeBlockState(state)));
			palettes.add(states);
		}
		ListTag blocks = new ListTag();
		for (int x = 0; x < 2; x++) {
			CompoundTag block = new CompoundTag();
			block.put("pos", ints(x, 0, 0));
			block.putInt("state", x);
			blocks.add(block);
		}
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(2, 1, 1));
		tag.put("palettes", palettes);
		tag.put("blocks", blocks);
		tag.put("entities", new ListTag());
		StructureTemplate template = new StructureTemplate();
		template.load(BuiltInRegistries.BLOCK, tag);
		return template;
	}

	private static ListTag ints(int... values) {
		ListTag list = new ListTag();
		for (int value : values) {
			list.add(IntTag.valueOf(value));
		}
		return list;
	}

	private static Blueprint withPalette(int palette) {
		return new Blueprint(HUT, "blueprint.antcraft.hut", Optional.empty(), List.of(), List.of(), palette);
	}

	@Test
	void aBlueprintWithAPaletteOtherThanZeroPlacesThatPalette() {
		BuildPlan plan = BlueprintPlacement.structure(twoPalettes(), withPalette(1), AT, Rotation.NONE, Mirror.NONE).right().orElseThrow();
		assertEquals(List.of(Blocks.STONE.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState()),
			plan.steps().stream().map(BuildPlan.Step::state).toList());
		assertEquals(List.of(AT, AT.east()), plan.steps().stream().map(BuildPlan.Step::pos).toList());
		BuildPlan first = BlueprintPlacement.structure(twoPalettes(), withPalette(0), AT, Rotation.NONE, Mirror.NONE).right().orElseThrow();
		assertEquals(List.of(Blocks.COBBLESTONE.defaultBlockState(), Blocks.OAK_PLANKS.defaultBlockState()),
			first.steps().stream().map(BuildPlan.Step::state).toList());
	}

	@Test
	void aBlueprintWithAPaletteItsStructureLacksIsRefused() {
		assertEquals(Optional.of(BlueprintPlacement.Refusal.NO_PALETTE),
			BlueprintPlacement.structure(twoPalettes(), withPalette(2), AT, Rotation.NONE, Mirror.NONE).left());
	}

	@Test
	void theGhostShowsTheBlueprintsPalette() {
		assertEquals(List.of(new BlueprintSummary.Block(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
				new BlueprintSummary.Block(new BlockPos(1, 0, 0), Blocks.SPRUCE_PLANKS.defaultBlockState())),
			BlueprintBlocks.of(HUT, Optional.of(twoPalettes()), 1).blocks());
		assertEquals(List.of(), BlueprintBlocks.of(HUT, Optional.of(twoPalettes()), 2).blocks());
	}

	/** Stone at {@code ground}, but at {@code holes}; air everywhere else, above and below, as over a cave or the game test floor. */
	private static Function<BlockPos, BlockState> floor(int ground, List<BlockPos> holes) {
		return pos -> pos.getY() == ground && !holes.contains(pos) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
	}

	@Test
	void aSiteOnAFlatFloorWithAirBelowIsNotLevelled() {
		BoundingBox area = new BoundingBox(8, 64, 8, 10, 65, 11);
		assertEquals(Optional.empty(), BlueprintPlacement.levelling(floor(63, List.of()), area));
	}

	@Test
	void theLevellingFillsOnlyTheGroundTheStructureStandsOn() {
		BoundingBox area = new BoundingBox(8, 64, 8, 10, 65, 11);
		BlockPos hole = new BlockPos(9, 63, 9);
		BuildPlan plan = BlueprintPlacement.levelling(floor(63, List.of(hole)), area).orElseThrow();
		// Only the hole in the layer under the structure, not the air below it.
		assertEquals(List.of(Terraform.fill(hole)), plan.steps());
		assertEquals(0, BlueprintPlacement.FILL_DEPTH);
	}

	@Test
	void givingUpTheLevellingDropsThePlacementRatherThanBuildOnAnUnlevelledSite() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, levelling(AT));
		colony.postBuildJobs();
		Job job = colony.board().jobs().stream().filter(j -> j.isOpen() && colony.build(j.id()).isPresent()).findFirst().orElseThrow();
		BuildPlan.Step step = colony.build(job.id()).orElseThrow().steps().getFirst();
		colony.reportBlocked(job.id(), List.of(step.pos()));

		Colony.Cleared cleared = colony.clearBlocked();
		assertEquals(1, cleared.steps());
		assertEquals(List.of(placed), cleared.unlevelled());
		finishAll(colony);
		assertFalse(colony.builds().stream().anyMatch(plan -> plan.kind() == BuildPlan.Kind.BUILD), "structure started: " + colony.builds());
		assertEquals(List.of(), colony.blueprints());
		assertTrue(colony.projects().stream().noneMatch(project -> project.id().equals(placed.project())), "project left: " + colony.projects());
	}

	@Test
	void aPlacementDroppedForALevellingGivenUpInAnOldSaveIsReported() {
		Colony colony = colony();
		// A levelling of two batches, neither needing the other: the second is still to do once the first is given up.
		BuildPlan levelling = new BuildPlan(List.of(Terraform.fill(AT.below()), Terraform.fill(AT.offset(1, -1, 0))),
			List.of(new BuildPlan.Batch(0, 1, List.of(), false), new BuildPlan.Batch(1, 2, List.of(), false)), BuildPlan.Kind.TERRAFORM);
		PlacedBlueprint placed = place(colony, AT, Optional.of(levelling));
		UUID plan = colony.projects().stream().filter(project -> project.id().equals(placed.project())).findFirst().orElseThrow().plans().getFirst();
		// A save from before #267: the levelling's first batch, whose job id is the plan's, given up with colony blocked clear, the placement kept.
		JsonObject json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow().getAsJsonObject();
		JsonObject batches = json.has("batches") ? json.getAsJsonObject("batches") : new JsonObject();
		batches.addProperty(plan.toString(), "given_up");
		json.add("batches", batches);
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(List.of(placed), loaded.blueprints());

		finishAll(loaded);
		assertEquals(List.of(), loaded.blueprints());
		assertEquals(List.of(placed), loaded.droppedUnlevelled());
	}

	@Test
	void givingUpABlockedStepOfTheStructureKeepsThePlacement() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Optional.empty());
		colony.postBuildJobs();
		Job job = colony.board().jobs().stream().filter(j -> j.isOpen() && colony.build(j.id()).isPresent()).findFirst().orElseThrow();
		colony.reportBlocked(job.id(), List.of(AT));
		assertEquals(List.of(), colony.clearBlocked().unlevelled());
		assertEquals(List.of(placed.project()), colony.blueprints().stream().map(PlacedBlueprint::project).toList());
	}
}
