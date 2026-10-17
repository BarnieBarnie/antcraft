package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.StorageArea;
import io.github.barniebarnie.antcraft.work.Terraform;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColonyTest {
	private static final Mineshaft SHAFT = new Mineshaft(new BlockPos(2, 64, 2), List.of(60), 1, 3, 4);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	/** A world of air: the mineshaft's floors stay protected even when broken, since the builder rebuilds them. */
	private static final Function<BlockPos, BlockState> AIR = pos -> Blocks.AIR.defaultBlockState();
	private static final BlockPos PLACED = new BlockPos(5, 64, 5);

	private static Function<BlockPos, BlockState> world(Block block) {
		return pos -> block.defaultBlockState();
	}

	@Test
	void protectionFollowsThePlacedBlock() {
		Colony colony = colony();
		colony.protect(PLACED, Blocks.OAK_PLANKS);
		assertTrue(colony.isProtected(world(Blocks.OAK_PLANKS), PLACED));
		assertFalse(colony.isProtected(world(Blocks.OAK_LOG), PLACED));
		// Forgotten once replaced, so planks put back later by a player are not protected either.
		assertFalse(colony.isProtected(world(Blocks.OAK_PLANKS), PLACED));
	}

	@Test
	void surveyForgetsReplacedBlocksButKeepsUnloadedOnes() {
		Colony colony = colony();
		BlockPos unloaded = new BlockPos(9, 64, 9);
		colony.protect(PLACED, Blocks.OAK_PLANKS);
		colony.protect(unloaded, Blocks.OAK_PLANKS);
		colony.forgetReplaced(pos -> pos.equals(unloaded) ? null : Blocks.AIR.defaultBlockState());
		assertEquals(1, colony.protectedCount());
		assertTrue(colony.isProtected(world(Blocks.OAK_PLANKS), unloaded));
	}

	@Test
	void protectedBlocksSurviveSaveAndLoad() {
		Colony colony = colony();
		colony.protect(PLACED, Blocks.OAK_PLANKS);
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow()).getOrThrow();
		assertFalse(loaded.isProtected(world(Blocks.OAK_LOG), PLACED));
	}

	@Test
	void oldSavesProtectWhateverIsThereOnFirstCheck() {
		String json = """
			{"id": [1, 2, 3, 4], "core": [0, 64, 0], "protected": [[5, 64, 5]]}
			""";
		Colony colony = Colony.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
		assertTrue(colony.isProtected(world(Blocks.STONE), PLACED));
		assertFalse(colony.isProtected(world(Blocks.OAK_LOG), PLACED));
	}

	private static BuildPlan plan(Block block) {
		return new BuildPlan(List.of(new BuildPlan.Step(new BlockPos(5, 64, 5), block.defaultBlockState())));
	}

	@Test
	void plansStartingAtTheSameSpotGetTheirOwnJobs() {
		Colony colony = colony();
		BuildPlan cobble = plan(Blocks.COBBLESTONE);
		BuildPlan planks = plan(Blocks.OAK_PLANKS);
		colony.addBuild(cobble);
		colony.addBuild(planks);
		colony.postBuildJobs();

		List<Job> jobs = colony.board().jobs();
		assertEquals(2, jobs.size());
		assertEquals(Set.of(cobble, planks), jobs.stream().map(job -> colony.build(job.id()).orElseThrow()).collect(Collectors.toSet()));
	}

	@Test
	void jobsOfRemovedPlansAreWithdrawn() {
		Colony colony = colony();
		BuildPlan cobble = plan(Blocks.COBBLESTONE);
		colony.addBuild(cobble);
		colony.postBuildJobs();
		colony.removeBuild(cobble);
		colony.postBuildJobs();
		assertTrue(colony.board().jobs().isEmpty());
	}

	@Test
	void buildOrdersAreWithdrawnOnceNoLongerNeeded() {
		Colony colony = colony();
		colony.addOrder(Items.OAK_STAIRS, 2);
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.OAK_STAIRS, 3, Items.TORCH, 4));
		assertEquals(Map.of(Items.OAK_STAIRS, 5, Items.TORCH, 4), colony.orders());

		// Some stairs turned up and the torches are no longer needed: the player's order stays.
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.OAK_STAIRS, 1));
		assertEquals(Map.of(Items.OAK_STAIRS, 3), colony.orders());
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of());
		assertEquals(Map.of(Items.OAK_STAIRS, 2), colony.orders());
	}

	@Test
	void craftedItemsGoToBuildOrdersFirst() {
		Colony colony = colony();
		colony.addOrder(Items.OAK_STAIRS, 2);
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.OAK_STAIRS, 1));
		colony.fulfil(Items.OAK_STAIRS, 1);
		// The crafted stair is in stock now, so the plan needs no more.
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of());
		assertEquals(Map.of(Items.OAK_STAIRS, 2), colony.orders());
	}

	@Test
	void cancelledBuildOrderIsNotTakenOutOfOtherOrders() {
		Colony colony = colony();
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.TORCH, 4));
		colony.cancelOrder(Items.TORCH);
		colony.addOrder(Items.TORCH, 2);
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of());
		assertEquals(Map.of(Items.TORCH, 2), colony.orders());
	}

	@Test
	void storageOrdersAreWithdrawnWithoutTouchingPlayerOrBuildOrders() {
		Colony colony = colony();
		colony.addOrder(Items.CHEST, 2);
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.CHEST, 1));
		colony.orderFor(Colony.OrderPurpose.STORAGE, Map.of(Items.CHEST, 1));
		assertEquals(Map.of(Items.CHEST, 4), colony.orders());

		colony.orderFor(Colony.OrderPurpose.STORAGE, Map.of());
		assertEquals(Map.of(Items.CHEST, 3), colony.orders());
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of());
		assertEquals(Map.of(Items.CHEST, 2), colony.orders());
	}

	@Test
	void craftedItemsGoToSystemOrdersBeforePlayerOrders() {
		Colony colony = colony();
		colony.addOrder(Items.CHEST, 1);
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.CHEST, 1));
		colony.orderFor(Colony.OrderPurpose.STORAGE, Map.of(Items.CHEST, 1));
		colony.fulfil(Items.CHEST, 2);
		// Both crafted chests are in stock now, so neither builds nor storage need more.
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of());
		colony.orderFor(Colony.OrderPurpose.STORAGE, Map.of());
		assertEquals(Map.of(Items.CHEST, 1), colony.orders());
	}

	@Test
	void buildOrdersSavedUnderTheOldKeyStillLoad() {
		String json = """
			{"id": [1, 2, 3, 4], "core": [0, 64, 0], "orders": {"minecraft:torch": 6}, "build_orders": {"minecraft:torch": 4}}
			""";
		Colony colony = Colony.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of());
		assertEquals(Map.of(Items.TORCH, 2), colony.orders());
	}

	@Test
	void coloniesLoadedWithoutJobsGetTheirOwnBoard() {
		String json = """
			{"id": [1, 2, 3, 4], "core": [0, 64, 0]}
			""";
		Colony first = Colony.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
		Colony second = Colony.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
		first.board().post(JobType.CHOP_TREE, new BlockPos(5, 64, 5));
		assertTrue(second.board().jobs().isEmpty(), "colonies share a job board");
	}

	@Test
	void plansOrdersAndBlockedStepsSurviveSaveAndLoad() {
		Colony colony = colony();
		colony.addBuild(plan(Blocks.COBBLESTONE));
		colony.postBuildJobs();
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.COBBLESTONE, 1));
		colony.orderFor(Colony.OrderPurpose.STORAGE, Map.of(Items.CHEST, 1));
		colony.reportBlocked(UUID.randomUUID(), List.of(new BlockPos(1, 2, 3)));
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(colony.builds(), loaded.builds());
		assertTrue(loaded.build(colony.board().jobs().getFirst().id()).isPresent(), "plan lost its job");
		assertEquals(List.of(new BlockPos(1, 2, 3)), loaded.blockedSteps());
		loaded.orderFor(Colony.OrderPurpose.BUILD, Map.of());
		loaded.orderFor(Colony.OrderPurpose.STORAGE, Map.of());
		assertTrue(loaded.orders().isEmpty(), "build order not saved as such: " + loaded.orders());
	}

	/** A plan with one step at (5, 64, 5) that a builder reported blocked, and the id of its job. */
	private static UUID blockedPlan(Colony colony, BuildPlan.Step step) {
		colony.addBuild(new BuildPlan(List.of(step)));
		colony.postBuildJobs();
		UUID id = colony.board().jobs().getFirst().id();
		colony.board().complete(id);
		colony.reportBlocked(id, List.of(step.pos()));
		return id;
	}

	@Test
	void aPlanLeftWithOnlyBlockedStepsWaitsWithoutAJob() {
		Colony colony = colony();
		blockedPlan(colony, BuildPlan.Step.clear(PLACED));
		colony.postBuildJobs();
		assertTrue(colony.board().jobs().isEmpty(), "blocked plan posted again: " + colony.board().jobs());
		assertTrue(colony.builds().isEmpty(), "blocked plan still counted as a build under way");
		assertEquals(List.of(PLACED), colony.blockedSteps());
	}

	/** Four one-step batches: 1 needs 0, 2 needs 1, and 3 needs none. */
	private static BuildPlan fourBatches() {
		return new BuildPlan(
			List.of(BuildPlan.Step.clear(new BlockPos(1, 64, 1)), BuildPlan.Step.clear(new BlockPos(2, 64, 1)),
				BuildPlan.Step.clear(new BlockPos(3, 64, 1)), BuildPlan.Step.clear(new BlockPos(4, 64, 1))),
			List.of(new BuildPlan.Batch(0, 1, List.of()), new BuildPlan.Batch(1, 2, List.of(0)),
				new BuildPlan.Batch(2, 3, List.of(1)), new BuildPlan.Batch(3, 4, List.of())));
	}

	/** The open build job for batch {@code batch} of {@code plan}. */
	private static Job batchJob(Colony colony, BuildPlan plan, int batch) {
		return colony.board().jobs().stream().filter(job -> colony.build(job.id()).equals(Optional.of(plan.batch(batch)))).findFirst().orElseThrow();
	}

	@Test
	void aBlockedBatchHoldsUpOnlyTheBatchesThatNeedIt() {
		Colony colony = colony();
		BuildPlan plan = fourBatches();
		colony.addBuild(plan);
		colony.postBuildJobs();
		// Both worked through as far as they go, so batch 2 may start too.
		colony.batchWaiting(batchJob(colony, plan, 0).id(), Colony.Wait.ITEMS);
		colony.batchWaiting(batchJob(colony, plan, 1).id(), Colony.Wait.ITEMS);
		assertEquals(Set.of(0, 1, 2, 3).stream().map(plan::batch).collect(Collectors.toSet()), openBatches(colony));

		Job first = batchJob(colony, plan, 0);
		colony.board().complete(first.id());
		colony.reportBlocked(first.id(), List.of(new BlockPos(1, 64, 1)));
		colony.postBuildJobs();
		// Batch 1 needs the blocked batch, and batch 2 needs batch 1; batch 3 needs neither.
		assertEquals(Set.of(plan.batch(3)), openBatches(colony));
		Job last = claim(colony, ALICE);
		assertEquals(plan.batch(3), colony.build(last.id()).orElseThrow());
		assertEquals(List.of(plan), colony.builds(), "plan with a batch still to do left out");
		colony.finishBatch(last.id());
		colony.board().complete(last.id());
		assertTrue(colony.builds().isEmpty(), "plan left with only blocked batches still counted as a build under way");

		colony.recheckBlocked(world(Blocks.STONE), (batch, step) -> true);
		colony.postBuildJobs();
		assertEquals(Set.of(0, 1, 2).stream().map(plan::batch).collect(Collectors.toSet()), openBatches(colony));
	}

	@Test
	void recheckKeepsStepsThatAreStillBlocked() {
		Colony colony = colony();
		blockedPlan(colony, BuildPlan.Step.clear(PLACED));
		colony.recheckBlocked(world(Blocks.BEDROCK), (batch, step) -> false);
		assertEquals(List.of(PLACED), colony.blockedSteps());
	}

	@Test
	void recheckDropsStepsWhoseTargetBlockIsInPlace() {
		Colony colony = colony();
		blockedPlan(colony, new BuildPlan.Step(PLACED, Blocks.COBBLESTONE.defaultBlockState()));
		colony.recheckBlocked(world(Blocks.COBBLESTONE), (batch, step) -> false);
		assertTrue(colony.blockedSteps().isEmpty(), "done step still reported: " + colony.blockedSteps());
	}

	@Test
	void recheckHandsAWorkableStepBackToItsPlan() {
		Colony colony = colony();
		UUID id = blockedPlan(colony, BuildPlan.Step.clear(PLACED));
		colony.recheckBlocked(world(Blocks.STONE), (batch, step) -> true);
		colony.postBuildJobs();
		assertTrue(colony.blockedSteps().isEmpty(), "workable step still reported: " + colony.blockedSteps());
		assertEquals(List.of(id), colony.board().jobs().stream().map(Job::id).toList());
	}

	@Test
	void recheckDropsStepsOfPlansThatAreGone() {
		Colony colony = colony();
		UUID id = blockedPlan(colony, BuildPlan.Step.clear(PLACED));
		colony.removeBuild(colony.build(id).orElseThrow());
		colony.reportBlocked(UUID.randomUUID(), List.of(new BlockPos(1, 2, 3)));
		colony.recheckBlocked(world(Blocks.BEDROCK), (batch, step) -> false);
		assertTrue(colony.blockedSteps().isEmpty(), "steps of gone plans still reported: " + colony.blockedSteps());
	}

	@Test
	void recheckLeavesStepsInUnloadedChunks() {
		Colony colony = colony();
		blockedPlan(colony, BuildPlan.Step.clear(PLACED));
		colony.recheckBlocked(pos -> null, (batch, step) -> true);
		assertEquals(List.of(PLACED), colony.blockedSteps());
	}

	@Test
	void clearingBlockedStepsGivesUpTheirPlans() {
		Colony colony = colony();
		blockedPlan(colony, BuildPlan.Step.clear(PLACED));
		assertEquals(1, colony.clearBlocked());
		colony.recheckBlocked(world(Blocks.AIR), (batch, step) -> true);
		colony.postBuildJobs();
		assertTrue(colony.blockedSteps().isEmpty());
		assertTrue(colony.board().jobs().isEmpty(), "given up plan posted again: " + colony.board().jobs());
	}

	@Test
	void blockedStepsSavedAsPositionsStillLoad() {
		var json = JsonParser.parseString("""
			{"id": [1, 2, 3, 4], "core": [0, 64, 0], "blocked_steps": [[1, 2, 3]]}
			""");
		Colony colony = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(List.of(new BlockPos(1, 2, 3)), colony.blockedSteps());
		colony.recheckBlocked(world(Blocks.BEDROCK), (batch, step) -> false);
		assertTrue(colony.blockedSteps().isEmpty(), "step of an unknown plan kept");
	}

	@Test
	void plansSavedAsAListStillLoad() {
		var json = JsonParser.parseString("""
			{"id": [1, 2, 3, 4], "core": [0, 64, 0]}
			""").getAsJsonObject();
		json.add("builds", BuildPlan.CODEC.listOf().encodeStart(JsonOps.INSTANCE, List.of(plan(Blocks.COBBLESTONE))).getOrThrow());
		Colony colony = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(List.of(plan(Blocks.COBBLESTONE)), colony.builds());
	}

	@Test
	void oldSaveWithAQuarryLoads() {
		String json = """
			{"id": [1, 2, 3, 4], "core": [0, 64, 0],
			 "quarry": {"min": [4, 50, 4], "max": [8, 60, 8]},
			 "jobs": [{"id": [5, 6, 7, 8], "type": "mine_layer", "target": [4, 60, 4]}]}
			""";
		Colony colony = Colony.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
		assertEquals(new BlockPos(0, 64, 0), colony.core());
		assertTrue(colony.mineshaft().isEmpty());
		// Jobs are posted again by the next survey, so dropping the old ones loses nothing.
		assertTrue(colony.board().jobs().isEmpty());
	}

	@Test
	void settingAMineshaftQueuesItsPlanAndProtectsItsStairs() {
		Colony colony = colony();
		colony.setMineshaft(SHAFT);
		assertEquals(List.of(SHAFT.plan()), colony.builds());
		assertTrue(colony.isProtected(AIR, SHAFT.stairs().getLast().below()));

		Mineshaft moved = new Mineshaft(new BlockPos(10, 64, 10), List.of(60), 1, 3, 4);
		colony.setMineshaft(moved);
		assertEquals(List.of(moved.plan()), colony.builds());
		assertFalse(colony.isProtected(AIR, SHAFT.stairs().getLast().below()));
	}

	@Test
	void mineshaftSurvivesSaveAndLoad() {
		Colony colony = colony();
		colony.setMineshaft(SHAFT);
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(Optional.of(SHAFT), loaded.mineshaft());
		assertTrue(loaded.isProtected(AIR, SHAFT.stairs().getLast().below()));
	}

	/** Two levels: its stairs to y=62 (batch 0), that hall (1), the stairs on to y=60 (2) and that hall (3). */
	private static final Mineshaft TWO_LEVELS = new Mineshaft(new BlockPos(2, 64, 2), List.of(62, 60), 1, 3, 4);
	private static final UUID ALICE = UUID.randomUUID();
	private static final UUID BOB = UUID.randomUUID();

	/** The plan batches the open build jobs are for. */
	private static Set<BuildPlan> openBatches(Colony colony) {
		return colony.board().jobs().stream().filter(job -> job.type() == JobType.BUILD && job.isOpen())
			.map(job -> colony.build(job.id()).orElseThrow()).collect(Collectors.toSet());
	}

	private static Job claim(Colony colony, UUID bot) {
		return colony.board().claim(bot, Set.of(JobType.BUILD), new BlockPos(2, 64, 2), 0).orElseThrow();
	}

	@Test
	void aMineshaftPostsABatchOnlyOnceTheBatchesItNeedsAreDone() {
		Colony colony = colony();
		colony.setMineshaft(TWO_LEVELS);
		colony.postBuildJobs();
		// The halls wait for the stairs to reach them.
		assertEquals(Set.of(TWO_LEVELS.plan().batch(0)), openBatches(colony));
		Job stairs = claim(colony, ALICE);
		assertTrue(colony.board().claim(BOB, Set.of(JobType.BUILD), BlockPos.ZERO, 0).isEmpty(), "a second builder found work");

		colony.finishBatch(stairs.id());
		colony.board().complete(stairs.id());
		assertEquals(Set.of(TWO_LEVELS.plan().batch(1), TWO_LEVELS.plan().batch(2)), openBatches(colony));
		Job a = claim(colony, ALICE);
		Job b = claim(colony, BOB);
		assertNotEquals(a.id(), b.id());
		assertEquals(Set.of(TWO_LEVELS.plan().batch(1), TWO_LEVELS.plan().batch(2)),
			Set.of(colony.build(a.id()).orElseThrow(), colony.build(b.id()).orElseThrow()));
	}

	@Test
	void stairsWaitingForTheirTorchesLetTheHallAndTheStairsBelowStart() {
		Colony colony = colony();
		colony.setMineshaft(TWO_LEVELS);
		colony.postBuildJobs();
		Job stairs = claim(colony, ALICE);
		// Dug, but the colony has no torches yet.
		colony.batchWaiting(stairs.id(), Colony.Wait.ITEMS);
		colony.board().release(ALICE);
		colony.postBuildJobs();
		assertEquals(Set.of(TWO_LEVELS.plan().batch(0), TWO_LEVELS.plan().batch(1), TWO_LEVELS.plan().batch(2)), openBatches(colony));

		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		loaded.postBuildJobs();
		assertEquals(openBatches(colony), openBatches(loaded), "waiting batch not saved as such");
		// Finished once its torches came, it is not posted again.
		loaded.finishBatch(stairs.id());
		assertEquals(Set.of(TWO_LEVELS.plan().batch(1), TWO_LEVELS.plan().batch(2)), openBatches(loaded));
	}

	@Test
	void stairsWaitingForWaterToSettleDoNotLetTheHallStart() {
		Colony colony = colony();
		colony.setMineshaft(TWO_LEVELS);
		colony.postBuildJobs();
		Job stairs = claim(colony, ALICE);
		// Water flows over a stair block still to dig.
		colony.batchWaiting(stairs.id(), Colony.Wait.FLUID);
		colony.board().release(ALICE);
		colony.postBuildJobs();
		assertEquals(Set.of(TWO_LEVELS.plan().batch(0)), openBatches(colony), "a batch waiting for a fluid opened the batches that need it");
	}

	@Test
	void earlierBatchesRankFirst() {
		Colony colony = halfwayDown();
		Set<Integer> ranks = colony.board().jobs().stream().map(job -> colony.batchIndex(job.id())).collect(Collectors.toSet());
		assertEquals(Set.of(1, 2), ranks);
		assertEquals(0, colony.batchIndex(UUID.randomUUID()));
	}

	@Test
	void thePlanIsFinishedOnlyOnceEveryBatchIsDone() {
		Colony colony = colony();
		colony.setMineshaft(TWO_LEVELS);
		colony.postBuildJobs();
		for (int left = TWO_LEVELS.plan().batches().size(); left > 0; left--) {
			assertEquals(List.of(TWO_LEVELS.plan()), colony.builds(), "plan finished with " + left + " batches left");
			Job job = claim(colony, ALICE);
			colony.finishBatch(job.id());
			colony.board().complete(job.id());
		}
		assertTrue(colony.builds().isEmpty(), "finished plan still listed");
		colony.postBuildJobs();
		assertTrue(colony.board().jobs().isEmpty(), "jobs left: " + colony.board().jobs());
	}

	@Test
	void aDoneBatchWithABrokenBlockIsPostedAgainWhileTheBatchesAfterItStayOpen() {
		Colony colony = halfwayDown();
		BuildPlan stairs = TWO_LEVELS.plan().batch(0);
		Map<BlockPos, BlockState> built = new HashMap<>();
		TWO_LEVELS.plan().steps().forEach(step -> built.put(step.pos(), step.state()));
		colony.recheckDone(pos -> built.getOrDefault(pos, Blocks.AIR.defaultBlockState()));
		assertEquals(Set.of(TWO_LEVELS.plan().batch(1), TWO_LEVELS.plan().batch(2)), openBatches(colony), "intact batch posted again");

		BlockPos torch = stairs.steps().stream().filter(step -> step.item() == Items.TORCH).findFirst().orElseThrow().pos();
		built.remove(torch);
		// Unloaded blocks tell nothing.
		colony.recheckDone(pos -> pos.equals(torch) ? null : built.getOrDefault(pos, Blocks.AIR.defaultBlockState()));
		assertEquals(Set.of(TWO_LEVELS.plan().batch(1), TWO_LEVELS.plan().batch(2)), openBatches(colony), "posted for an unloaded block");
		colony.recheckDone(pos -> built.getOrDefault(pos, Blocks.AIR.defaultBlockState()));
		assertEquals(Set.of(stairs, TWO_LEVELS.plan().batch(1), TWO_LEVELS.plan().batch(2)), openBatches(colony));
	}

	/** A colony digging {@link #TWO_LEVELS}, its stairs to the first level done, so two batches are open. */
	private static Colony halfwayDown() {
		Colony colony = colony();
		colony.setMineshaft(TWO_LEVELS);
		colony.postBuildJobs();
		Job stairs = claim(colony, ALICE);
		colony.finishBatch(stairs.id());
		colony.board().complete(stairs.id());
		return colony;
	}

	/** The open build job for batch {@code batch} of {@link #TWO_LEVELS}. */
	private static Job batchJob(Colony colony, int batch) {
		return colony.board().jobs().stream().filter(job -> colony.build(job.id()).equals(Optional.of(TWO_LEVELS.plan().batch(batch)))).findFirst().orElseThrow();
	}

	@Test
	void aBlockedMineshaftStairDropsTheLevelsItLeadsToInsteadOfStalling() {
		Colony colony = halfwayDown();
		// The stairs from y=62 on down to y=60 run into something no builder can clear.
		colony.reportBlocked(batchJob(colony, 2).id(), List.of(TWO_LEVELS.stairs().get(3)));
		Mineshaft shallower = new Mineshaft(TWO_LEVELS.top(), List.of(62), 1, 3, 4);
		assertEquals(Optional.of(shallower), colony.mineshaft());
		assertEquals(List.of(shallower.plan()), colony.builds());
		assertEquals(List.of(), colony.blockedSteps());
	}

	@Test
	void aBlockedStepOfAnotherPlanLeavesTheMineshaftAlone() {
		Colony colony = halfwayDown();
		BuildPlan other = new BuildPlan(List.of(BuildPlan.Step.clear(TWO_LEVELS.stairs().get(3))));
		colony.addBuild(other);
		colony.postBuildJobs();
		Job job = colony.board().jobs().stream().filter(j -> colony.build(j.id()).equals(Optional.of(other))).findFirst().orElseThrow();
		colony.reportBlocked(job.id(), List.of(TWO_LEVELS.stairs().get(3)));
		assertEquals(Optional.of(TWO_LEVELS), colony.mineshaft());
		assertEquals(List.of(TWO_LEVELS.stairs().get(3)), colony.blockedSteps());
	}

	@Test
	void movingTheMineshaftClearsTheOpenBatchesOfTheOldOne() {
		Colony colony = halfwayDown();
		Mineshaft moved = new Mineshaft(new BlockPos(10, 64, 10), List.of(62, 60), 1, 3, 4);
		colony.setMineshaft(moved);
		assertEquals(Set.of(), openBatches(colony), "old batches left open");
		colony.postBuildJobs();
		assertEquals(Set.of(moved.plan().batch(0)), openBatches(colony));
	}

	@Test
	void cancellingAPlanClearsAllItsOpenBatches() {
		Colony colony = halfwayDown();
		colony.removeBuild(TWO_LEVELS.plan());
		assertTrue(colony.board().jobs().isEmpty(), "batches left: " + colony.board().jobs());
		// Added again, it starts from the first batch.
		colony.addBuild(TWO_LEVELS.plan());
		colony.postBuildJobs();
		assertEquals(Set.of(TWO_LEVELS.plan().batch(0)), openBatches(colony));
	}

	@Test
	void batchProgressAndClaimsSurviveSaveAndLoad() {
		Colony colony = halfwayDown();
		Job claimed = claim(colony, BOB);
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(colony.build(claimed.id()), loaded.build(claimed.id()));
		assertEquals(Optional.of(claimed.id()), loaded.board().jobOf(BOB).map(Job::id));
		loaded.postBuildJobs();
		// The stairs already dug are not posted again.
		assertEquals(colony.board().jobs().stream().map(Job::id).collect(Collectors.toSet()),
			loaded.board().jobs().stream().map(Job::id).collect(Collectors.toSet()));
		Job other = claim(loaded, ALICE);
		loaded.finishBatch(claimed.id());
		loaded.finishBatch(other.id());
		loaded.postBuildJobs();
		assertEquals(Set.of(TWO_LEVELS.plan().batch(3)), openBatches(loaded));
	}

	/** An overhang of stone at y=65 on x 0..1 above a hole at y=64, levelled at y=64: a dig batch and a fill batch under it. */
	private static BuildPlan terraform() {
		Function<BlockPos, BlockState> world = pos -> pos.getY() < 64 || pos.getY() == 65 && pos.getX() < 2 ? Blocks.STONE.defaultBlockState()
			: Blocks.AIR.defaultBlockState();
		return Terraform.plan(world, new BlockPos(0, 62, 0), new BlockPos(3, 66, 1), 64).orElseThrow();
	}

	private static Set<JobType> openTypes(Colony colony) {
		return colony.board().jobs().stream().map(Job::type).collect(Collectors.toSet());
	}

	@Test
	void aTerraformPostsDigJobsBeforeItsFillJobs() {
		Colony colony = colony();
		BuildPlan plan = terraform();
		colony.addBuild(plan);
		colony.postBuildJobs();
		// Its one fill batch waits for the digging above it.
		assertEquals(Set.of(JobType.CLEAR_AREA), openTypes(colony));
		assertTrue(colony.hasTerraform());

		Job dig = colony.board().claim(ALICE, Set.of(JobType.CLEAR_AREA), BlockPos.ZERO, 0).orElseThrow();
		assertTrue(colony.build(dig.id()).orElseThrow().steps().stream().allMatch(BuildPlan.Step::isClear));
		colony.finishBatch(dig.id());
		colony.board().complete(dig.id());
		assertEquals(Set.of(JobType.BUILD), openTypes(colony));
	}

	@Test
	void aTerraformSurvivesSaveAndLoad() {
		Colony colony = colony();
		BuildPlan plan = terraform();
		colony.addBuild(plan);
		colony.postBuildJobs();
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(List.of(plan), loaded.builds());
		assertTrue(loaded.hasTerraform());
		loaded.postBuildJobs();
		assertEquals(Set.of(JobType.CLEAR_AREA), openTypes(loaded));
	}

	private static final UUID FIRST_PLAN = new UUID(0, 7);
	private static final UUID SECOND_PLAN = new UUID(0, 8);

	/** A colony with something in every saved field. */
	private static Colony everyField() {
		JobBoard board = new JobBoard();
		board.post(new UUID(0, 9), JobType.BUILD, new BlockPos(5, 64, 5));
		board.claim(new UUID(0, 2), Set.of(JobType.BUILD), BlockPos.ZERO, 100);
		Map<UUID, BuildPlan> builds = new LinkedHashMap<>();
		builds.put(FIRST_PLAN, plan(Blocks.COBBLESTONE));
		builds.put(SECOND_PLAN, new BuildPlan(List.of(new BuildPlan.Step(new BlockPos(6, 64, 5), Blocks.OAK_PLANKS.defaultBlockState()))));
		return new Colony(new UUID(0, 1), new BlockPos(0, 64, 0), List.of(new UUID(0, 2), new UUID(0, 3)), board, 40,
			Map.of(Items.OAK_STAIRS, 5), Optional.of(SHAFT),
			List.of(StockTarget.of(Items.TORCH, 16), StockTarget.of(ItemTags.PICKAXES, 2, Items.STONE_PICKAXE)), builds,
			List.of(new Colony.Placed(new BlockPos(1, 64, 1), Optional.of(Blocks.OAK_PLANKS))), Optional.of(new Field(new BlockPos(10, 64, 10))),
			Optional.of(new StorageArea(new BlockPos(-5, 64, -5))), Map.of(Items.TORCH, 1),
			List.of(new Colony.Blocked(SECOND_PLAN, new BlockPos(6, 64, 5))),
			Map.of(Colony.OrderPurpose.BUILD, Map.of(Items.OAK_STAIRS, 3), Colony.OrderPurpose.STORAGE, Map.of(Items.CHEST, 2)),
			Map.of(FIRST_PLAN, Colony.BatchState.WAITING));
	}

	/** {@link #everyField} as saved before the codec was split into groups: every field flat at the top level. */
	private static final String FLAT_SAVE = """
		{"id":[0,0,0,1],"core":[0,64,0],"members":[[0,0,0,2],[0,0,0,3]],
		"jobs":[{"id":[0,0,0,9],"type":"build","target":[5,64,5],"claimed_by":[0,0,0,2],"heartbeat":100}],
		"radius":40,"orders":{"minecraft:oak_stairs":5},
		"mineshaft":{"top":[2,64,2],"levels":[60],"branches_per_side":1,"branch_length":3,"torch_spacing":4},
		"stock":[{"match":"minecraft:torch","count":16},{"match":"#minecraft:pickaxes","count":2,"make":["minecraft:stone_pickaxe"]}],
		"builds":{"00000000-0000-0000-0000-000000000007":{"steps":[{"pos":[5,64,5],"state":"minecraft:cobblestone"}],"batches":[{"from":0,"to":1}]},
			"00000000-0000-0000-0000-000000000008":{"steps":[{"pos":[6,64,5],"state":"minecraft:oak_planks"}],"batches":[{"from":0,"to":1}]}},
		"protected":[{"pos":[1,64,1],"block":"minecraft:oak_planks"}],"field":[10,64,10],"storage":[-5,64,-5],
		"blocked_steps":[{"plan":"00000000-0000-0000-0000-000000000008","pos":[6,64,5]}],
		"system_orders":{"build":{"minecraft:oak_stairs":3,"minecraft:torch":1},"storage":{"minecraft:chest":2}},
		"batches":{"00000000-0000-0000-0000-000000000007":"waiting"}}
		""";

	private static void assertSameAsEveryField(Colony loaded) {
		Colony expected = everyField();
		assertEquals(expected.id(), loaded.id());
		assertEquals(expected.core(), loaded.core());
		assertEquals(expected.members(), loaded.members());
		assertEquals(expected.board().jobs(), loaded.board().jobs());
		assertEquals(expected.radius(), loaded.radius());
		assertEquals(expected.orders(), loaded.orders());
		assertEquals(expected.mineshaft(), loaded.mineshaft());
		assertEquals(expected.stockTargets(), loaded.stockTargets());
		assertEquals(expected.builds(), loaded.builds());
		assertEquals(expected.field(), loaded.field());
		assertEquals(expected.storage(), loaded.storage());
		// What is left (protected blocks, blocked steps, system orders, batch states) is compared through the save itself.
		assertEquals(Colony.CODEC.encodeStart(JsonOps.INSTANCE, expected).getOrThrow(), Colony.CODEC.encodeStart(JsonOps.INSTANCE, loaded).getOrThrow());
	}

	@Test
	void aFlatSaveFromBeforeTheCodecWasSplitLoadsTheSameColony() {
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(FLAT_SAVE)).getOrThrow();
		assertSameAsEveryField(loaded);
		// And it saves in the same flat layout, so older versions of the mod still read it.
		assertEquals(JsonParser.parseString(FLAT_SAVE), Colony.CODEC.encodeStart(JsonOps.INSTANCE, loaded).getOrThrow());
	}

	@Test
	void everyFieldSurvivesSaveAndLoad() {
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, everyField()).getOrThrow();
		assertSameAsEveryField(Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}

	@Test
	void cancellingATerraformClearsAllItsJobs() {
		Colony colony = colony();
		BuildPlan plan = terraform();
		colony.addBuild(plan);
		colony.postBuildJobs();
		Job dig = colony.board().claim(ALICE, Set.of(JobType.CLEAR_AREA), BlockPos.ZERO, 0).orElseThrow();
		colony.finishBatch(dig.id());
		colony.board().complete(dig.id());
		assertFalse(colony.board().jobs().isEmpty());

		colony.removeBuild(plan);
		colony.postBuildJobs();
		assertTrue(colony.board().jobs().isEmpty(), "jobs left: " + colony.board().jobs());
		assertFalse(colony.hasTerraform());
	}

	/** The colony puts given-up jobs back up now and then, so a tree or branch a player opened the way to is done after all. */
	@Test
	void givenUpJobsAreRetriedAfterTheRecheck() {
		Colony colony = colony();
		colony.board().post(JobType.MINE_BRANCH, new BlockPos(8, 60, 8));
		Set<JobType> mine = Set.of(JobType.MINE_BRANCH);
		colony.board().claim(ALICE, mine, BlockPos.ZERO, 0);
		colony.board().retryLater(ALICE, 100, 1);

		assertEquals(0, colony.recheckGivenUp(1000));
		assertEquals(0, colony.recheckGivenUp(1000 + Colony.GIVEN_UP_RECHECK_TICKS - 1));
		assertTrue(colony.board().claim(ALICE, mine, BlockPos.ZERO, 1000 + Colony.GIVEN_UP_RECHECK_TICKS - 1).isEmpty());

		assertEquals(1, colony.recheckGivenUp(1000 + Colony.GIVEN_UP_RECHECK_TICKS));
		assertTrue(colony.board().claim(ALICE, mine, BlockPos.ZERO, 1000 + Colony.GIVEN_UP_RECHECK_TICKS).isPresent());
	}
}
