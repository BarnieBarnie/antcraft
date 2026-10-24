package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
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
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
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
}
