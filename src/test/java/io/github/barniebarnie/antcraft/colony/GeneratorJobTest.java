package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The job to mine cobblestone at a built cobblestone generator (see {@link Colony#postGeneratorJobs}). */
class GeneratorJobTest {
	private static final Identifier GENERATOR = Identifier.fromNamespaceAndPath("antcraft", "cobble_generator");
	private static final BlockPos AT = new BlockPos(6, 64, 6);
	private static Blueprint blueprint;
	private static StructureTemplate structure;

	@BeforeAll
	static void bootstrap() throws IOException {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		// Gather job priorities look at item tags, such as logs.
		VanillaData.bindTags();
		blueprint = Blueprint.parse(JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/antcraft/blueprint/cobble_generator.json"))))
			.getOrThrow();
		structure = VanillaData.structure(blueprint.structure().getPath());
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	/** Places the generator for the colony to build, without levelling. */
	private static PlacedBlueprint place(Colony colony, BlockPos at, Rotation rotation) {
		PlacedBlueprint site = BlueprintPlacement.site(colony, GENERATOR, Optional.of(blueprint), Optional.of(structure.getSize()), at, rotation, Mirror.NONE)
			.right().orElseThrow();
		return colony.placeBlueprint(site, Optional.empty(), RegionPlan.load(structure, at, Mirror.NONE, rotation, 0).orElseThrow());
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

	private static List<Job> generatorJobs(Colony colony) {
		return colony.board().jobs().stream().filter(job -> job.type() == JobType.MINE_GENERATOR).toList();
	}

	@Test
	void noJobWithoutAGenerator() {
		Colony colony = colony();
		colony.postGeneratorJobs(true);
		assertEquals(List.of(), generatorJobs(colony));
	}

	@Test
	void noJobWhileTheGeneratorIsBeingBuilt() {
		Colony colony = colony();
		place(colony, AT, Rotation.NONE);
		colony.postGeneratorJobs(true);
		assertEquals(List.of(), generatorJobs(colony));
	}

	@Test
	void aBuiltGeneratorGetsOneJobAtItsMiningBlockOnlyWhileTheColonyIsShortOfCobblestone() {
		Colony colony = colony();
		place(colony, AT, Rotation.CLOCKWISE_90);
		finishAll(colony);
		PlacedBlueprint generator = colony.finishedBlueprints(Blueprint.Role.GENERATOR).getFirst();

		colony.postGeneratorJobs(false);
		assertEquals(List.of(), generatorJobs(colony), "posted while not short of cobblestone");

		colony.postGeneratorJobs(true);
		colony.postGeneratorJobs(true);
		List<Job> jobs = generatorJobs(colony);
		assertEquals(1, jobs.size(), "one job per generator: " + jobs);
		assertEquals(generator.workSpots().getFirst(), jobs.getFirst().target());
		assertEquals(Optional.of(Items.WOODEN_PICKAXE), jobs.getFirst().tool(), "needs a pickaxe");
	}

	@Test
	void theOpenJobComesOffOnceTheColonyHasEnoughCobblestoneButAClaimedOneStays() {
		Colony colony = colony();
		place(colony, AT, Rotation.NONE);
		finishAll(colony);
		colony.postGeneratorJobs(true);
		colony.postGeneratorJobs(false);
		assertEquals(List.of(), generatorJobs(colony), "open job left up");

		colony.postGeneratorJobs(true);
		colony.board().claim(UUID.randomUUID(), Set.of(JobType.MINE_GENERATOR), BlockPos.ZERO, 0L);
		colony.postGeneratorJobs(false);
		assertEquals(1, generatorJobs(colony).size(), "the bot's job was taken from it; it ends the job itself");
	}

	@Test
	void eachBuiltGeneratorGetsAJob() {
		Colony colony = colony();
		place(colony, AT, Rotation.NONE);
		place(colony, AT.offset(0, 0, -10), Rotation.CLOCKWISE_180);
		finishAll(colony);
		colony.postGeneratorJobs(true);
		assertEquals(2, generatorJobs(colony).size());
	}

	@Test
	void withABuiltGeneratorTheColonyMinesForOresButNotForCobblestone() {
		Colony colony = colony();
		java.util.function.Predicate<net.minecraft.world.item.Item> wanted = item -> item == Items.COBBLESTONE || item == Items.RAW_IRON;
		assertTrue(colony.wantedFromMining(wanted).test(Items.COBBLESTONE), "no generator yet");
		place(colony, AT, Rotation.NONE);
		assertTrue(colony.wantedFromMining(wanted).test(Items.COBBLESTONE), "generator not built yet");
		finishAll(colony);
		assertTrue(!colony.wantedFromMining(wanted).test(Items.COBBLESTONE), "mined for cobblestone with a generator");
		assertTrue(colony.wantedFromMining(wanted).test(Items.RAW_IRON), "not mined for iron");
		assertTrue(!colony.wantedFromMining(wanted).test(Items.COAL), "mined for what is not wanted");
	}

	@Test
	void theMiningBlockStaysProtectedWhileMinedAndOnceFormedAgain() {
		Colony colony = colony();
		place(colony, AT, Rotation.NONE);
		finishAll(colony);
		BlockPos mine = colony.finishedBlueprints(Blueprint.Role.GENERATOR).getFirst().workSpots().getFirst();
		assertTrue(colony.isProtected(pos -> Blocks.AIR.defaultBlockState(), mine), "mined");
		assertTrue(colony.isProtected(pos -> Blocks.COBBLESTONE.defaultBlockState(), mine), "formed again, never placed by a builder");
	}

	@Test
	void theMiningSpotIsKeptOpenForPathsOnceTheGeneratorIsBuilt() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Rotation.NONE);
		BlockPos mine = placed.workSpots().getFirst();
		finishAll(colony);
		assertTrue(colony.keepOpen().test(mine), "a path could put scaffold in the empty mining spot");
		assertTrue(!colony.keepOpen().test(placed.workSpots().get(1).above(3)), "kept open off the mining spot");
	}

	@Test
	void theGeneratorJobTakesThePriorityOfWhatWaitsForCobblestone() {
		Colony colony = colony();
		place(colony, AT, Rotation.NONE);
		finishAll(colony);
		colony.postGeneratorJobs(true);
		colony.addOrder(Items.COBBLESTONE, 16);
		colony.prioritizeGatherJobs(Map.of(), item -> List.of(), item -> true);
		assertEquals(Colony.ORDER_PRIORITY, generatorJobs(colony).getFirst().priority());
	}

	private static BuildPlan plan(BlockPos at, Rotation rotation) {
		return RegionPlan.load(structure, at, Mirror.NONE, rotation, 0).orElseThrow();
	}

	private static List<BlockPos> built(BlockPos at, Rotation rotation, Block fluid) {
		return plan(at, rotation).steps().stream().filter(step -> step.state().is(fluid) && step.state().getFluidState().isSource())
			.map(BuildPlan.Step::pos).toList();
	}

	@Test
	void aPlacedGeneratorKeepsWhereItsWaterAndLavaGo() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Rotation.CLOCKWISE_90);
		assertEquals(built(AT, Rotation.CLOCKWISE_90, Blocks.WATER), placed.water());
		assertEquals(built(AT, Rotation.CLOCKWISE_90, Blocks.LAVA), placed.lava());
		assertEquals(1, placed.lava().size(), "one lava source");
	}

	@Test
	void aGeneratorSavedWithoutItsFluidsGetsThemFromItsStructureOnLoad() {
		Colony colony = colony();
		place(colony, AT, Rotation.CLOCKWISE_180);
		finishAll(colony);
		JsonElement saved = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		saved.getAsJsonObject().getAsJsonArray("blueprints").forEach(placed -> {
			placed.getAsJsonObject().remove("water");
			placed.getAsJsonObject().remove("lava");
		});
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
		loaded.lookUpFluids(placed -> Optional.of(plan(placed.at(), placed.rotation())));
		PlacedBlueprint generator = loaded.blueprints().getFirst();
		assertEquals(built(AT, Rotation.CLOCKWISE_180, Blocks.WATER), generator.water());
		assertEquals(built(AT, Rotation.CLOCKWISE_180, Blocks.LAVA), generator.lava());
	}

	@Test
	void aBuiltGeneratorsWaterThatIsGoneIsPouredBackOnce() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Rotation.NONE);
		BlockPos water = placed.water().getFirst();
		BlockPos lava = placed.lava().getFirst();
		colony.restoreFluids(pos -> Blocks.AIR.defaultBlockState());
		assertEquals(1, colony.builds().size(), "only the structure while the generator is being built");
		finishAll(colony);

		colony.restoreFluids(world(Map.of(water, Blocks.WATER.defaultBlockState(), lava, Blocks.LAVA.defaultBlockState())));
		assertEquals(List.of(), colony.builds(), "both stand");

		Function<BlockPos, BlockState> gone = world(Map.of(water, Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 2),
			lava, Blocks.LAVA.defaultBlockState()));
		colony.restoreFluids(gone);
		colony.restoreFluids(gone);
		assertEquals(1, colony.builds().size(), "one plan: " + colony.builds());
		assertEquals(List.of(new BuildPlan.Step(water, Blocks.WATER.defaultBlockState())), colony.builds().getFirst().steps());
	}

	/**
	 * A generator's lava is walled in but for the mining block: its water is scooped up, then the mining block mined once
	 * nothing flows beside it, then both are poured back, water first.
	 */
	@Test
	void aBuiltGeneratorsLavaThatIsGoneIsPouredBackThroughItsMiningBlock() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Rotation.CLOCKWISE_90);
		finishAll(colony);
		BlockPos water = placed.water().getFirst();
		BlockPos lava = placed.lava().getFirst();
		BlockPos mine = placed.workSpots().getFirst();
		BlockPos channel = mine.offset(mine.subtract(lava));
		BlockState flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);

		assertEquals(List.of(BuildPlan.Step.clear(water)),
			restored(colony, Map.of(water, Blocks.WATER.defaultBlockState(), channel, flowing, mine, Blocks.COBBLESTONE.defaultBlockState())),
			"the water scooped up first");
		assertEquals(List.of(), restored(colony, Map.of(channel, flowing, mine, Blocks.COBBLESTONE.defaultBlockState())),
			"mined while water flows beside it");
		assertEquals(List.of(BuildPlan.Step.clear(mine)), restored(colony, Map.of(mine, Blocks.COBBLESTONE.defaultBlockState())),
			"the mining block mined once dry");
		assertEquals(List.of(new BuildPlan.Step(water, Blocks.WATER.defaultBlockState()), new BuildPlan.Step(lava, Blocks.LAVA.defaultBlockState())),
			restored(colony, Map.of()), "both poured back, water first");
		assertEquals(List.of(new BuildPlan.Step(lava, Blocks.LAVA.defaultBlockState())),
			restored(colony, Map.of(water, Blocks.WATER.defaultBlockState(), channel, flowing)), "lava poured through the mined block");
	}

	/**
	 * While the water a builder scooped up still flows beside the mining block, restoreFluids says so, so the colony looks
	 * again soon instead of at its next survey; otherwise it does not.
	 */
	@Test
	void aGeneratorWaitingForItsWaterToDrainAsksForASurveySoon() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Rotation.NONE);
		finishAll(colony);
		BlockPos lava = placed.lava().getFirst();
		BlockPos mine = placed.workSpots().getFirst();
		BlockPos channel = mine.offset(mine.subtract(lava));
		BlockState flowing = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);

		assertTrue(colony.restoreFluids(world(Map.of(channel, flowing, mine, Blocks.COBBLESTONE.defaultBlockState()))), "draining");
		assertEquals(List.of(), colony.builds());
		assertFalse(colony.restoreFluids(world(Map.of(mine, Blocks.COBBLESTONE.defaultBlockState()))), "dry");
		assertEquals(List.of(BuildPlan.Step.clear(mine)), colony.builds().getFirst().steps());
	}

	/** A player who cancels a stage of the restore of a generator's lava keeps it empty. */
	@Test
	void aLavaRestoreThePlayerCancelledIsNotAddedAgain() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Rotation.NONE);
		finishAll(colony);
		BlockPos mine = placed.workSpots().getFirst();
		Function<BlockPos, BlockState> dry = world(Map.of(mine, Blocks.COBBLESTONE.defaultBlockState()));
		colony.restoreFluids(dry);
		assertEquals(List.of(BuildPlan.Step.clear(mine)), colony.builds().getFirst().steps());

		colony.removeBuild(colony.builds().getFirst());
		colony.restoreFluids(dry);
		colony.restoreFluids(world(Map.of()));
		assertEquals(List.of(), colony.builds(), "added again");
	}

	/**
	 * A restore the player turned off by cancelling it is shown as off in the colony's status, and turned on again
	 * ({@code colony build restore}), after which builders pour the lava back again. A position outside the generator
	 * turns on nothing.
	 */
	@Test
	void aRestoreTurnedOffIsShownAsOffAndTurnedOnAgain() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Rotation.NONE);
		finishAll(colony);
		BlockPos mine = placed.workSpots().getFirst();
		Function<BlockPos, BlockState> dry = world(Map.of(mine, Blocks.COBBLESTONE.defaultBlockState()));
		assertEquals(List.of(), status(colony, dry).restoreOff(), "off before it was turned off");
		colony.restoreFluids(dry);
		// The restore plan stays marked as one once saved and loaded.
		colony = Colony.CODEC.parse(JsonOps.INSTANCE, Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow()).getOrThrow();
		colony.removeBuild(colony.builds().getFirst());
		assertEquals(List.of(new ColonyStatus.RestoreOff(placed.displayName(), placed.at())), status(colony, dry).restoreOff());

		assertEquals(List.of(), colony.restoreFluidsAgain(Optional.of(placed.area().getCenter().offset(0, 0, 40))), "outside it");
		assertEquals(1, colony.restoreFluidsAgain(Optional.of(mine)).size(), "turned on");
		assertEquals(List.of(), status(colony, dry).restoreOff(), "still shown off");
		colony.restoreFluids(dry);
		assertEquals(List.of(BuildPlan.Step.clear(mine)), colony.builds().getFirst().steps(), "not restored again");
		assertEquals(List.of(), colony.restoreFluidsAgain(Optional.empty()), "turned on twice");
	}

	/** A player's own plan wholly on a generator's fluid spots, cancelled or given up, is no restore: the restore stays on. */
	@Test
	void cancellingAPlayersOwnPlanOnAFluidSpotLeavesTheRestoreOn() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Rotation.NONE);
		finishAll(colony);
		BlockPos mine = placed.workSpots().getFirst();
		Function<BlockPos, BlockState> dry = world(Map.of(mine, Blocks.COBBLESTONE.defaultBlockState()));
		BuildPlan own = new BuildPlan(List.of(BuildPlan.Step.clear(mine)));
		colony.addBuild("mine it", own);
		colony.removeBuild(own);
		Project project = colony.addBuild("mine it again", own);
		colony.cancelProject(project.id());
		colony.addBuild("and again", own);
		colony.postBuildJobs();
		Job job = colony.board().jobs().stream().filter(j -> colony.build(j.id()).isPresent()).findFirst().orElseThrow();
		colony.reportBlocked(job.id(), List.of(mine));
		colony.clearBlocked();

		assertTrue(colony.blueprints().stream().allMatch(PlacedBlueprint::restoreFluids), "restore turned off");
		colony.restoreFluids(dry);
		assertEquals(List.of(BuildPlan.Step.clear(mine)), colony.builds().getFirst().steps(), "not restored");
	}

	private static ColonyStatus status(Colony colony, Function<BlockPos, BlockState> world) {
		return ColonyStatus.of(colony, Map.of(), Map.of(), item -> List.of(), item -> false, world, 1, false);
	}

	/**
	 * The stage that mines a generator's mining block to pour its lava back wants a pickaxe, as other digging of stone does:
	 * its job declares one and, with none in the colony, its project orders one, so no builder mines it by hand.
	 */
	@Test
	void theGeneratorClearStepAsksForAPickaxe() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, AT, Rotation.NONE);
		finishAll(colony);
		BlockPos mine = placed.workSpots().getFirst();
		Function<BlockPos, BlockState> dry = world(Map.of(mine, Blocks.COBBLESTONE.defaultBlockState()));
		colony.restoreFluids(dry);
		assertEquals(List.of(BuildPlan.Step.clear(mine)), colony.builds().getFirst().steps());

		assertTrue(colony.planProjects(dry, step -> true, Map.of(), tool -> false).containsKey(Items.WOODEN_PICKAXE)
			|| colony.orders().containsKey(Items.WOODEN_PICKAXE), "pickaxe ordered");
		colony.postBuildJobs();
		colony.declareBuildNeeds(dry, step -> true, tool -> true);
		assertTrue(colony.board().jobs().stream().anyMatch(job -> colony.build(job.id()).isPresent() && job.tool().equals(Optional.of(Items.WOODEN_PICKAXE))),
			"pickaxe declared");
	}

	/** The world of {@code blocks}, air elsewhere. */
	private static Function<BlockPos, BlockState> world(Map<BlockPos, BlockState> blocks) {
		return pos -> blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	/** The steps of the plan {@link Colony#restoreFluids} adds for {@code blocks}, which is then finished again. */
	private static List<BuildPlan.Step> restored(Colony colony, Map<BlockPos, BlockState> blocks) {
		colony.restoreFluids(world(blocks));
		List<BuildPlan.Step> steps = colony.builds().stream().flatMap(plan -> plan.steps().stream()).toList();
		finishAll(colony);
		return steps;
	}
}
