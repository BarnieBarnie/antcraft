package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.StorageArea;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.network.chat.Component;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColonyStatusTest {
	private static final UUID LUMBERJACK = UUID.randomUUID();
	private static final UUID MINER = UUID.randomUUID();
	private static final UUID IDLE_MINER = UUID.randomUUID();
	private static final UUID UNLOADED = UUID.randomUUID();
	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID FRIEND = UUID.randomUUID();

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ColonyStatus status() {
		Colony colony = new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(LUMBERJACK, MINER, IDLE_MINER, UNLOADED), new JobBoard(), 20,
			Map.of(), Optional.empty(), List.of(StockTarget.of(Items.OAK_LOG, 32), StockTarget.of(Items.TORCH, 8), StockTarget.of(Items.COBBLESTONE, 64)),
			Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
		colony.reportBlocked(UUID.randomUUID(), List.of(new BlockPos(3, 64, 3)));
		colony.setOwner(OWNER);
		colony.trust(FRIEND);
		colony.board().post(JobType.CHOP_TREE, new BlockPos(5, 64, 5));
		colony.board().post(JobType.CHOP_TREE, new BlockPos(6, 64, 6));
		colony.board().post(JobType.MINE_BRANCH, new BlockPos(7, 64, 7));
		colony.board().claim(LUMBERJACK, Set.of(JobType.CHOP_TREE), new BlockPos(5, 64, 5), 0L);
		// A tree given up after its last try, out of reach for good.
		colony.board().post(JobType.CHOP_TREE, new BlockPos(9, 64, 9));
		colony.board().claim(MINER, Set.of(JobType.CHOP_TREE), new BlockPos(9, 64, 9), 0L);
		colony.board().retryLater(MINER, 600L, 1);
		// A branch given up the same way, its cells out of reach.
		colony.board().post(JobType.MINE_BRANCH, new BlockPos(8, 60, 8));
		colony.board().claim(IDLE_MINER, Set.of(JobType.MINE_BRANCH), new BlockPos(8, 60, 8), 0L);
		colony.board().retryLater(IDLE_MINER, 600L, 1);
		colony.addOrder(Items.DIAMOND, 2);
		colony.addOrder(Items.OAK_LOG, 1);
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.OAK_LOG, 3));
		colony.addBuild(new BuildPlan(List.of(
			new BuildPlan.Step(new BlockPos(10, 64, 10), Blocks.STONE.defaultBlockState()),
			new BuildPlan.Step(new BlockPos(10, 65, 10), Blocks.STONE.defaultBlockState()))));
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(new BlockPos(12, 64, 12))), List.of(), BuildPlan.Kind.TERRAFORM));

		Map<UUID, ColonyStatus.Bot> bots = Map.of(
			LUMBERJACK, new ColonyStatus.Bot("Lumberjack", Optional.of(JobType.CHOP_TREE), false),
			MINER, new ColonyStatus.Bot("Miner", Optional.empty(), false),
			IDLE_MINER, new ColonyStatus.Bot("Idle miner", Optional.empty(), true));
		Map<Item, Integer> stock = Map.of(Items.OAK_LOG, 40, Items.TORCH, 2, Items.COBBLESTONE, 10);
		// The first step is placed already.
		// The core is full, and the colony has no storage area.
		return ColonyStatus.of(colony, bots, stock, item -> List.of(), Items.OAK_LOG::equals,
			pos -> pos.getY() == 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 0, false,
			player -> player.equals(OWNER) ? "Alice" : "Bob");
	}

	@Test
	void namesTheOwnerAndTrustedPlayers() {
		assertEquals(new ColonyStatus.Access(Optional.of("Alice"), List.of("Bob")), status().access());
	}

	@Test
	void aColonyWithoutAnOwnerNamesNone() {
		Colony colony = new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), 20,
			Map.of(), Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
		ColonyStatus status = ColonyStatus.of(colony, Map.of(), Map.of(), item -> List.of(), item -> false, pos -> Blocks.AIR.defaultBlockState(), 1, false);
		assertEquals(new ColonyStatus.Access(Optional.empty(), List.of()), status.access());
	}

	/** A colony with a storage area if {@code site}. */
	private static ColonyStatus.Room room(boolean site, int freeSlots, boolean chestWithRoom) {
		Colony colony = new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), 20,
			Map.of(), Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
		if (site) {
			colony.setStorage(new StorageArea(new BlockPos(5, 64, 5)));
		}
		return ColonyStatus.of(colony, Map.of(), Map.of(), item -> List.of(), item -> false, pos -> Blocks.AIR.defaultBlockState(), freeSlots, chestWithRoom).room();
	}

	@Test
	void reportsAFullCoreAndWhyThereIsNoStorage() {
		ColonyStatus.Room room = status().room();
		assertTrue(room.coreFull());
		assertEquals(ColonyStatus.StorageState.NO_WAREHOUSE, room.storage());
		assertEquals(new ColonyStatus.Room(0, ColonyStatus.StorageState.NO_WAREHOUSE), room(false, 0, false));
		assertEquals(new ColonyStatus.Room(0, ColonyStatus.StorageState.NO_CHEST), room(true, 0, false));
		assertEquals(new ColonyStatus.Room(3, ColonyStatus.StorageState.READY), room(true, 3, true));
		assertFalse(room(true, 3, true).coreFull());
	}

	@Test
	void aFullCoreWithNoStorageSaysToPlaceAWarehouse() {
		assertEquals("commands.antcraft.colony.storage.no_warehouse", ColonyStatus.StorageState.NO_WAREHOUSE.key());
	}

	/** A colony that wants wheat and holds none: a stock target of 10 and an order of 5. With {@code field}, it has a field. */
	private static ColonyStatus wantsWheat(boolean field) {
		Colony colony = new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), 20,
			Map.of(), Optional.empty(), List.of(StockTarget.of(Items.WHEAT, 10), StockTarget.of(Items.OAK_LOG, 10)), Map.of(), List.of(), Optional.empty(),
			Optional.empty(), Map.of(), List.of(), Map.of());
		colony.addOrder(Items.WHEAT, 5);
		colony.addOrder(Items.COBBLESTONE, 5);
		if (field) {
			colony.setField(new Field(new BlockPos(5, 63, 5)));
		}
		return ColonyStatus.of(colony, Map.of(), Map.of(), item -> List.of(), item -> item == Items.WHEAT || item == Items.OAK_LOG || item == Items.COBBLESTONE,
			pos -> Blocks.AIR.defaultBlockState(), 1, false);
	}

	@Test
	void aStockTargetOrOrderForWheatSaysItNeedsAFarmWhileTheColonyHasNoField() {
		ColonyStatus status = wantsWheat(false);
		assertEquals(List.of(new ColonyStatus.Stock("minecraft:wheat", 0, 10, true), new ColonyStatus.Stock("minecraft:oak_log", 0, 10, false)), status.stock());
		assertEquals(List.of(new ColonyStatus.Order(Items.WHEAT, 5, Map.of(), Map.of(Items.WHEAT, 5), Map.of(), true),
			new ColonyStatus.Order(Items.COBBLESTONE, 5, Map.of(), Map.of(Items.COBBLESTONE, 5), Map.of(), false)), status.orders());

		ColonyStatus farmed = wantsWheat(true);
		assertTrue(farmed.stock().stream().noneMatch(ColonyStatus.Stock::needsFarm), "needs a farm with a field: " + farmed.stock());
		assertTrue(farmed.orders().stream().noneMatch(ColonyStatus.Order::needsFarm), "needs a farm with a field: " + farmed.orders());
	}

	@Test
	void countsMembersAndIdleOnes() {
		ColonyStatus status = status();
		assertEquals(4, status.members());
		assertEquals(1, status.idle());
		assertEquals(20, status.radius());
		assertEquals(Map.of(JobType.CHOP_TREE, 1, JobType.MINE_BRANCH, 1), status.openJobs());
		assertEquals(Map.of(JobType.CHOP_TREE, 1), status.claimedJobs());
	}

	@Test
	void listsLoadedMembersInOrder() {
		assertEquals(List.of(LUMBERJACK, MINER, IDLE_MINER), List.copyOf(status().bots().keySet()));
		assertEquals(new ColonyStatus.Bot("Lumberjack", Optional.of(JobType.CHOP_TREE), false), status().bots().get(LUMBERJACK));
	}

	@Test
	void listsShortStockFirst() {
		assertEquals(List.of(
			new ColonyStatus.Stock("minecraft:torch", 2, 8),
			new ColonyStatus.Stock("minecraft:cobblestone", 10, 64),
			new ColonyStatus.Stock("minecraft:oak_log", 40, 32)), status().stock());
	}

	@Test
	void listsOrdersWithWhatTheyStillNeedAndWhoPlacedThem() {
		List<ColonyStatus.Order> orders = status().orders();
		assertEquals(List.of(
			new ColonyStatus.Order(Items.DIAMOND, 2, Map.of(), Map.of(), Map.of(Items.DIAMOND, 2)),
			new ColonyStatus.Order(Items.OAK_LOG, 4, Map.of(Colony.OrderPurpose.BUILD, 3), Map.of(Items.OAK_LOG, 4), Map.of())), orders);
		assertEquals(2, orders.get(0).byPlayer());
		assertEquals(1, orders.get(1).byPlayer());
	}

	@Test
	void summarisesBuildsAndBlockedSteps() {
		ColonyStatus status = status();
		assertEquals(List.of(
			new ColonyStatus.Build(new BlockPos(10, 64, 10), 1, 2, false, false, List.of(new ColonyStatus.Batch(1, 2, false)), Component.literal("build"), 0, Map.of()),
			new ColonyStatus.Build(new BlockPos(12, 64, 12), 1, 1, false, true, List.of(new ColonyStatus.Batch(1, 1, false)), Component.literal("terraform"), 0,
				Map.of())), status.builds());
		assertEquals(List.of(new BlockPos(3, 64, 3)), status.blocked());
	}

	@Test
	void reportsProgressPerBatchAndWhichWaitOnBlockedSteps() {
		Colony colony = new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), 20, Map.of(), Optional.empty(), List.of(),
			Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
		// Batch 0 has its first block placed; batch 2 needs batch 1, which a builder found blocked.
		BuildPlan plan = new BuildPlan(List.of(
			new BuildPlan.Step(new BlockPos(10, 64, 10), Blocks.STONE.defaultBlockState()),
			new BuildPlan.Step(new BlockPos(10, 65, 10), Blocks.STONE.defaultBlockState()),
			new BuildPlan.Step(new BlockPos(11, 65, 10), Blocks.STONE.defaultBlockState()),
			new BuildPlan.Step(new BlockPos(12, 65, 10), Blocks.STONE.defaultBlockState())),
			List.of(new BuildPlan.Batch(0, 2, List.of()), new BuildPlan.Batch(2, 3, List.of()), new BuildPlan.Batch(3, 4, List.of(1))));
		colony.addBuild(plan);
		colony.postBuildJobs();
		Job second = colony.board().jobs().stream().filter(job -> colony.build(job.id()).equals(Optional.of(plan.batch(1)))).findFirst().orElseThrow();
		colony.board().complete(second.id());
		colony.reportBlocked(second.id(), List.of(new BlockPos(11, 65, 10)));

		ColonyStatus status = ColonyStatus.of(colony, Map.of(), Map.of(), item -> List.of(), item -> false,
			pos -> pos.getY() == 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 1, false);
		assertEquals(List.of(new ColonyStatus.Build(new BlockPos(10, 64, 10), 3, 4, false, false, List.of(
			new ColonyStatus.Batch(1, 2, false), new ColonyStatus.Batch(1, 1, true), new ColonyStatus.Batch(1, 1, true)), Component.literal("build"), 0, Map.of())),
			status.builds());
	}

	@Test
	void showsEachBuildsProjectWithItsPriorityAndWhatItStillWaitsFor() {
		Colony colony = new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), 20, Map.of(), Optional.empty(), List.of(),
			Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
		Project hut = colony.addBuild("hut", new BuildPlan(List.of(new BuildPlan.Step(new BlockPos(10, 64, 10), Blocks.OAK_PLANKS.defaultBlockState()))));
		colony.setPriority(hut.id(), 2);
		colony.planProjects(pos -> Blocks.AIR.defaultBlockState(), step -> true, Map.of(), tool -> true);

		ColonyStatus status = ColonyStatus.of(colony, Map.of(), Map.of(), item -> List.of(), item -> false, pos -> Blocks.AIR.defaultBlockState(), 1, false);
		ColonyStatus.Build build = status.builds().getFirst();
		assertEquals(Component.literal("hut"), build.project());
		assertEquals(2, build.priority());
		assertEquals(Map.of(Items.OAK_PLANKS, 1), build.waitsFor());
	}

	@Test
	void showsAPlacedBlueprintsBuildUnderTheBlueprintsName() {
		Colony colony = new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), 20, Map.of(), Optional.empty(), List.of(),
			Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
		Blueprint blueprint = new Blueprint(Identifier.fromNamespaceAndPath("antcraft", "hut"), "blueprint.antcraft.hut", Optional.empty(), List.of(), List.of());
		PlacedBlueprint site = BlueprintPlacement.site(colony, Identifier.fromNamespaceAndPath("antcraft", "hut"), Optional.of(blueprint),
			Optional.of(new Vec3i(1, 1, 1)), new BlockPos(10, 64, 10), Rotation.NONE, Mirror.NONE).right().orElseThrow();
		colony.placeBlueprint(site, Optional.empty(), new BuildPlan(List.of(new BuildPlan.Step(new BlockPos(10, 64, 10), Blocks.OAK_PLANKS.defaultBlockState()))));

		ColonyStatus status = ColonyStatus.of(colony, Map.of(), Map.of(), item -> List.of(), item -> false, pos -> Blocks.AIR.defaultBlockState(), 1, false);
		assertEquals(Component.translatable("blueprint.antcraft.hut"), status.builds().getFirst().project());
	}

	/** Given-up jobs are listed, not counted as open work. */
	@Test
	void givenUpTypesShownIncludeLogsAndVeins() {
		assertEquals(List.of(JobType.CHOP_TREE, JobType.CHOP_LOG, JobType.MINE_BRANCH, JobType.MINE_VEIN), ColonyStatus.GIVEN_UP_TYPES);
	}

	@Test
	void givenUpTreesAreNotOpenJobs() {
		ColonyStatus status = status();
		assertEquals(1, status.openJobs().get(JobType.CHOP_TREE));
		assertEquals(3, status.jobs());
	}

	@Test
	void listsTreesAndBranchesGivenUpOn() {
		ColonyStatus status = status();
		assertEquals(List.of(new ColonyStatus.GivenUp(JobType.CHOP_TREE, new BlockPos(9, 64, 9)),
			new ColonyStatus.GivenUp(JobType.MINE_BRANCH, new BlockPos(8, 60, 8))), status.givenUp());
		assertEquals(List.of(new BlockPos(9, 64, 9)), status.givenUp(JobType.CHOP_TREE));
		assertEquals(List.of(new BlockPos(8, 60, 8)), status.givenUp(JobType.MINE_BRANCH));
	}

	@Test
	void listsAGivenUpVeinUnderItsOwnKey() {
		ColonyStatus status = new ColonyStatus(BlockPos.ZERO, 0, Map.of(), 0, 0, Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of(),
			List.of(new ColonyStatus.GivenUp(JobType.MINE_VEIN, new BlockPos(4, 30, 4))), new ColonyStatus.Room(1, ColonyStatus.StorageState.READY),
			new ColonyStatus.Access(Optional.empty(), List.of()));
		assertEquals(List.of(new BlockPos(4, 30, 4)), status.givenUp(JobType.MINE_VEIN));
		assertEquals("commands.antcraft.colony.given_up_veins", ColonyStatus.givenUpKey(JobType.MINE_VEIN));
		assertEquals("commands.antcraft.colony.given_up_branches", ColonyStatus.givenUpKey(JobType.MINE_BRANCH));
	}

	@Test
	void roundTripsThroughItsStreamCodec() {
		ColonyStatus status = status();
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		ColonyStatus.STREAM_CODEC.encode(buf, status);
		assertEquals(status, ColonyStatus.STREAM_CODEC.decode(buf));
		ColonyStatus needsFarm = wantsWheat(false);
		ColonyStatus.STREAM_CODEC.encode(buf, needsFarm);
		assertEquals(needsFarm, ColonyStatus.STREAM_CODEC.decode(buf));
	}
}
