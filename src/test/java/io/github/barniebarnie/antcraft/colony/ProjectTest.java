package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProjectTest {
	private static GameRecipeBook book;
	private static Predicate<Item> gatherable;

	@BeforeAll
	static void bootstrap() {
		book = VanillaData.recipeBook();
		gatherable = VanillaData.gatherable();
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	private static final BlockPos ORE = new BlockPos(5, 64, 5);
	/** Iron ore where the plan puts planks: it needs digging out with a stone pickaxe first. */
	private static final Function<BlockPos, BlockState> WORLD = pos -> pos.equals(ORE) ? Blocks.IRON_ORE.defaultBlockState()
		: Blocks.AIR.defaultBlockState();

	private static BuildPlan plan(BlockPos pos, Block block) {
		return new BuildPlan(List.of(new BuildPlan.Step(pos, block.defaultBlockState())));
	}

	/** What the crafter makes at the core for the colony's orders, as {@code CrafterGoal.coreCrafts} finds it. */
	private static Set<Item> coreCrafts(Colony colony, Map<Item, Integer> stock) {
		Set<Item> crafts = new HashSet<>();
		colony.orders().forEach((item, count) -> TaskPlanner.plan(item, count, stock, book, gatherable).crafts()
			.forEach(craft -> crafts.add(craft.recipe().output())));
		return crafts;
	}

	private static Map<Item, Integer> craftJobs(Colony colony) {
		return colony.board().jobs().stream().filter(job -> job.type() == JobType.CRAFT)
			.collect(Collectors.toMap(job -> job.item().orElseThrow(), Job::priority));
	}

	@Test
	void aBuildNeedingAPickaxeTheColonyLacksYieldsACraftJobForItAndGatherWorkForItsMaterials() {
		Colony colony = colony();
		Project project = colony.addBuild("hut", plan(ORE, Blocks.OAK_PLANKS));
		colony.setPriority(project.id(), 2);
		colony.postBuildJobs();

		colony.planProjects(WORLD, step -> true, Map.of(), tool -> false);

		assertEquals(Map.of(Items.STONE_PICKAXE, 1, Items.OAK_PLANKS, 1), colony.waitingFor(project.id()));
		assertEquals(1, colony.orders().get(Items.STONE_PICKAXE));
		colony.postCraftJobs(coreCrafts(colony, Map.of()), Map.of(), book, gatherable);
		Map<Item, Integer> crafts = craftJobs(colony);
		assertEquals(2, crafts.get(Items.STONE_PICKAXE), "no craft job at the project's priority for the pickaxe: " + crafts);
		assertEquals(2, crafts.get(Items.STICK), "no craft job for the pickaxe's sticks: " + crafts);
		Set<Item> gather = Stockkeeping.wantedForOrders(colony.orders(), Map.of(), book, gatherable);
		assertTrue(gather.contains(Items.COBBLESTONE), "cobblestone not gathered: " + gather);
		assertTrue(gather.stream().anyMatch(item -> item.builtInRegistryHolder().is(net.minecraft.tags.ItemTags.LOGS)),
			"no logs gathered for the sticks: " + gather);
	}

	@Test
	void aPickaxeTheColonyHasIsNotOrdered() {
		Colony colony = colony();
		Project project = colony.addBuild("hut", plan(ORE, Blocks.OAK_PLANKS));
		colony.planProjects(WORLD, step -> true, Map.of(Items.OAK_PLANKS, 1), tool -> true);
		assertEquals(Map.of(), colony.waitingFor(project.id()));
		assertEquals(Map.of(), colony.orders());
	}

	@Test
	void twoProjectsJobsCarryTheirOwnPriorities() {
		Colony colony = colony();
		Project low = colony.addBuild("shed", plan(new BlockPos(5, 64, 5), Blocks.OAK_PLANKS));
		Project high = colony.addBuild("hut", plan(new BlockPos(9, 64, 9), Blocks.CRAFTING_TABLE));
		colony.setPriority(low.id(), 1);
		colony.setPriority(high.id(), 3);
		colony.postBuildJobs();
		assertEquals(Map.of(new BlockPos(5, 64, 5), 1, new BlockPos(9, 64, 9), 3), buildPriorities(colony));

		// A new priority reaches the jobs posted already.
		colony.setPriority(low.id(), 5);
		colony.postBuildJobs();
		assertEquals(Map.of(new BlockPos(5, 64, 5), 5, new BlockPos(9, 64, 9), 3), buildPriorities(colony));
		assertEquals(List.of("shed", "hut"), colony.projects().stream().map(Project::name).toList(), "not listed highest priority first");

		// Each project's crafts carry its priority; the higher one wins a craft both need.
		colony.planProjects(pos -> Blocks.AIR.defaultBlockState(), step -> true, Map.of(), tool -> false);
		colony.postCraftJobs(coreCrafts(colony, Map.of()), Map.of(), book, gatherable);
		Map<Item, Integer> crafts = craftJobs(colony);
		assertEquals(3, crafts.get(Items.CRAFTING_TABLE), crafts.toString());
		assertEquals(5, crafts.get(Items.OAK_PLANKS), crafts.toString());
	}

	private static Map<BlockPos, Integer> buildPriorities(Colony colony) {
		return colony.board().jobs().stream().collect(Collectors.toMap(Job::target, Job::priority));
	}

	@Test
	void aLaterBatchRanksBelowTheFirstOfItsProject() {
		Colony colony = colony();
		BuildPlan plan = new BuildPlan(List.of(new BuildPlan.Step(new BlockPos(5, 64, 5), Blocks.COBBLESTONE.defaultBlockState()),
			new BuildPlan.Step(new BlockPos(5, 65, 5), Blocks.COBBLESTONE.defaultBlockState())),
			List.of(new BuildPlan.Batch(0, 1, List.of(), false), new BuildPlan.Batch(1, 2, List.of(), false)), BuildPlan.Kind.BUILD);
		Project project = colony.addBuild("tower", plan);
		colony.setPriority(project.id(), 4);
		colony.postBuildJobs();
		colony.batchWaiting(colony.board().jobs().getFirst().id(), Colony.Wait.ITEMS);
		assertEquals(Set.of(4, 3), colony.board().jobs().stream().map(Job::priority).collect(Collectors.toSet()));
	}

	@Test
	void cancellingAProjectDropsItsBuildsAndJobs() {
		Colony colony = colony();
		Project kept = colony.addBuild("hut", plan(new BlockPos(9, 64, 9), Blocks.OAK_PLANKS));
		Project cancelled = colony.addBuild("shed", plan(new BlockPos(5, 64, 5), Blocks.COBBLESTONE));
		colony.postBuildJobs();
		colony.cancelProject(cancelled.id());
		assertEquals(List.of(kept), colony.projects());
		assertEquals(List.of(plan(new BlockPos(9, 64, 9), Blocks.OAK_PLANKS)), colony.builds());
		assertEquals(Set.of(new BlockPos(9, 64, 9)), colony.board().jobs().stream().map(Job::target).collect(Collectors.toSet()));
	}

	@Test
	void aProjectIsDoneOnceItsLastBuildIs() {
		Colony colony = colony();
		colony.addBuild("hut", plan(new BlockPos(9, 64, 9), Blocks.OAK_PLANKS));
		colony.postBuildJobs();
		colony.finishBatch(colony.board().jobs().getFirst().id());
		assertEquals(List.of(), colony.projects());
	}

	@Test
	void aColonyWithProjectsSurvivesSaveAndLoad() {
		Colony colony = colony();
		Project hut = colony.addBuild("hut", plan(new BlockPos(9, 64, 9), Blocks.OAK_PLANKS));
		Project shed = colony.addBuild("shed", plan(new BlockPos(5, 64, 5), Blocks.COBBLESTONE));
		colony.setPriority(shed.id(), 2);
		Project tools = colony.addProject("tools", DefaultStock.TOOLS);
		colony.postBuildJobs();
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(colony.projects(), loaded.projects());
		assertEquals(List.of("shed", "hut", "tools"), loaded.projects().stream().map(Project::name).toList());
		assertEquals(DefaultStock.TOOLS, loaded.projects().getLast().stock());
		loaded.postBuildJobs();
		assertEquals(buildPriorities(colony), buildPriorities(loaded));
		loaded.cancelProject(hut.id());
		assertEquals(List.of(plan(new BlockPos(5, 64, 5), Blocks.COBBLESTONE)), loaded.builds());
		// A project of stock alone goes too.
		loaded.cancelProject(tools.id());
		assertEquals(List.of("shed"), loaded.projects().stream().map(Project::name).toList());
	}

	private static final Function<BlockPos, BlockState> STONE = pos -> Blocks.STONE.defaultBlockState();

	/** A new spiral shaft with its first batch posted, the stairs it needs ordered and their crafts posted. */
	private static Mineshaft shaftNeedingStairs(Colony colony, Map<Item, Integer> has, boolean onSpot) {
		Mineshaft shaft = new Mineshaft(new BlockPos(2, 64, 2), List.of(60));
		colony.setMineshaft(shaft);
		colony.postBuildJobs();
		colony.planProjects(STONE, step -> true, has, tool -> true);
		colony.postStairCrafts(STONE, has, onSpot);
		colony.postCraftJobs(coreCrafts(colony, has), has, book, gatherable);
		return shaft;
	}

	private static List<Job> stairCrafts(Colony colony) {
		return colony.board().jobs().stream().filter(job -> job.type() == JobType.CRAFT && job.item().equals(Optional.of(Items.COBBLESTONE_STAIRS))).toList();
	}

	@Test
	void aShaftBatchWhoseStairsTheCoreLacksYieldsACraftJobAtTheBatch() {
		Colony colony = colony();
		Mineshaft shaft = shaftNeedingStairs(colony, Map.of(), true);
		assertTrue(colony.orders().containsKey(Items.COBBLESTONE_STAIRS), "stairs not ordered: " + colony.orders());
		// Crafted on the spot with a carried crafting table, and not at the core as well.
		List<Job> crafts = stairCrafts(colony);
		assertEquals(1, crafts.size(), crafts.toString());
		assertEquals(shaft.plan().batch(0).target(), crafts.getFirst().target());
		assertEquals(Optional.of(Items.CRAFTING_TABLE), crafts.getFirst().tool());
	}

	@Test
	void withNoBotToCraftThemOnTheSpotShaftStairsAreCraftedAtTheCore() {
		Colony colony = colony();
		shaftNeedingStairs(colony, Map.of(), true);
		colony.postStairCrafts(STONE, Map.of(), false);
		colony.postCraftJobs(coreCrafts(colony, Map.of()), Map.of(), book, gatherable);
		List<Job> crafts = stairCrafts(colony);
		assertEquals(1, crafts.size(), crafts.toString());
		assertEquals(colony.core(), crafts.getFirst().target());
	}

	@Test
	void stoneStairsTheColonyHasLeaveNothingToCraftOnTheSpot() {
		Colony colony = colony();
		shaftNeedingStairs(colony, Map.of(Items.STONE_BRICK_STAIRS, 64), true);
		assertEquals(List.of(), stairCrafts(colony));
	}

	@Test
	void anOnTheSpotCraftGoesWithItsShaft() {
		Colony colony = colony();
		Mineshaft shaft = shaftNeedingStairs(colony, Map.of(), true);
		colony.removeBuild(shaft.plan());
		assertEquals(List.of(), stairCrafts(colony).stream().filter(job -> !job.target().equals(colony.core())).toList());
	}

	@Test
	void buildsSavedBeforeProjectsBecomeAProjectEach() {
		String json = """
			{"id": [0, 0, 0, 1], "core": [0, 64, 0],
			"builds": {"00000000-0000-0000-0000-000000000007": {"steps": [{"pos": [5, 64, 5], "state": "minecraft:cobblestone"}], "batches": [{"from": 0, "to": 1}]},
				"00000000-0000-0000-0000-000000000008": {"steps": [{"pos": [6, 64, 5], "state": "minecraft:oak_planks"}], "batches": [{"from": 0, "to": 1}]}}}
			""";
		Colony colony = Colony.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
		assertEquals(2, colony.projects().size());
		assertTrue(colony.projects().stream().allMatch(project -> project.priority() == 0 && project.plans().size() == 1), colony.projects().toString());
		colony.cancelProject(colony.projects().getFirst().id());
		assertEquals(List.of(plan(new BlockPos(6, 64, 5), Blocks.OAK_PLANKS)), colony.builds());
	}
}
