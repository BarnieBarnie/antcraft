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
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of());
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
		colony.orderForBuilds(Map.of(Items.OAK_STAIRS, 3, Items.TORCH, 4));
		assertEquals(Map.of(Items.OAK_STAIRS, 5, Items.TORCH, 4), colony.orders());

		// Some stairs turned up and the torches are no longer needed: the player's order stays.
		colony.orderForBuilds(Map.of(Items.OAK_STAIRS, 1));
		assertEquals(Map.of(Items.OAK_STAIRS, 3), colony.orders());
		colony.orderForBuilds(Map.of());
		assertEquals(Map.of(Items.OAK_STAIRS, 2), colony.orders());
	}

	@Test
	void craftedItemsGoToBuildOrdersFirst() {
		Colony colony = colony();
		colony.addOrder(Items.OAK_STAIRS, 2);
		colony.orderForBuilds(Map.of(Items.OAK_STAIRS, 1));
		colony.fulfil(Items.OAK_STAIRS, 1);
		// The crafted stair is in stock now, so the plan needs no more.
		colony.orderForBuilds(Map.of());
		assertEquals(Map.of(Items.OAK_STAIRS, 2), colony.orders());
	}

	@Test
	void cancelledBuildOrderIsNotTakenOutOfOtherOrders() {
		Colony colony = colony();
		colony.orderForBuilds(Map.of(Items.TORCH, 4));
		colony.cancelOrder(Items.TORCH);
		colony.addOrder(Items.TORCH, 2);
		colony.orderForBuilds(Map.of());
		assertEquals(Map.of(Items.TORCH, 2), colony.orders());
	}

	@Test
	void plansOrdersAndBlockedStepsSurviveSaveAndLoad() {
		Colony colony = colony();
		colony.addBuild(plan(Blocks.COBBLESTONE));
		colony.postBuildJobs();
		colony.orderForBuilds(Map.of(Items.COBBLESTONE, 1));
		colony.reportBlocked(List.of(new BlockPos(1, 2, 3)));
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(colony.builds(), loaded.builds());
		assertTrue(loaded.build(colony.board().jobs().getFirst().id()).isPresent(), "plan lost its job");
		assertEquals(List.of(new BlockPos(1, 2, 3)), loaded.blockedSteps());
		loaded.orderForBuilds(Map.of());
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
		assertTrue(colony.isProtected(SHAFT.stairs().getLast().below()));

		Mineshaft moved = new Mineshaft(new BlockPos(10, 64, 10), List.of(60), 1, 3, 4);
		colony.setMineshaft(moved);
		assertEquals(List.of(moved.plan()), colony.builds());
		assertFalse(colony.isProtected(SHAFT.stairs().getLast().below()));
	}

	@Test
	void mineshaftSurvivesSaveAndLoad() {
		Colony colony = colony();
		colony.setMineshaft(SHAFT);
		var json = Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow();
		Colony loaded = Colony.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		assertEquals(Optional.of(SHAFT), loaded.mineshaft());
		assertTrue(loaded.isProtected(SHAFT.stairs().getLast().below()));
	}
}
