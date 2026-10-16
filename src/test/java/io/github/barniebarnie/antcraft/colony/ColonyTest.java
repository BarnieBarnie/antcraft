package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
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
		colony.reportBlocked(List.of(new BlockPos(1, 2, 3)));
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(colony.builds(), loaded.builds());
		assertTrue(loaded.build(colony.board().jobs().getFirst().id()).isPresent(), "plan lost its job");
		assertEquals(List.of(new BlockPos(1, 2, 3)), loaded.blockedSteps());
		loaded.orderFor(Colony.OrderPurpose.BUILD, Map.of());
		loaded.orderFor(Colony.OrderPurpose.STORAGE, Map.of());
		assertTrue(loaded.orders().isEmpty(), "build order not saved as such: " + loaded.orders());
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
}
