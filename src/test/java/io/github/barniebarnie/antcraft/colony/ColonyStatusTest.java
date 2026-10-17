package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.StorageArea;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
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
		colony.board().post(JobType.CHOP_TREE, new BlockPos(5, 64, 5));
		colony.board().post(JobType.CHOP_TREE, new BlockPos(6, 64, 6));
		colony.board().post(JobType.MINE_BRANCH, new BlockPos(7, 64, 7));
		colony.board().claim(LUMBERJACK, Set.of(JobType.CHOP_TREE), new BlockPos(5, 64, 5), 0L);
		// A tree given up after its last try, out of reach for good.
		colony.board().post(JobType.CHOP_TREE, new BlockPos(9, 64, 9));
		colony.board().claim(MINER, Set.of(JobType.CHOP_TREE), new BlockPos(9, 64, 9), 0L);
		colony.board().retryLater(MINER, 600L, 1);
		colony.addOrder(Items.DIAMOND, 2);
		colony.addOrder(Items.OAK_LOG, 1);
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.OAK_LOG, 3));
		colony.addBuild(new BuildPlan(List.of(
			new BuildPlan.Step(new BlockPos(10, 64, 10), Blocks.STONE.defaultBlockState()),
			new BuildPlan.Step(new BlockPos(10, 65, 10), Blocks.STONE.defaultBlockState()))));
		colony.addBuild(new BuildPlan(List.of(BuildPlan.Step.clear(new BlockPos(12, 64, 12))), List.of(), BuildPlan.Kind.TERRAFORM));

		Map<UUID, ColonyStatus.Bot> bots = Map.of(
			LUMBERJACK, new ColonyStatus.Bot("Lumberjack", BotRole.LUMBERJACK, false, false),
			MINER, new ColonyStatus.Bot("Miner", BotRole.MINER, false, true),
			IDLE_MINER, new ColonyStatus.Bot("Idle miner", BotRole.MINER, true, false));
		Map<Item, Integer> stock = Map.of(Items.OAK_LOG, 40, Items.TORCH, 2, Items.COBBLESTONE, 10);
		// The first step is placed already.
		// The core is full, and there is no hauler to keep storage.
		return ColonyStatus.of(colony, bots, stock, item -> List.of(), Items.OAK_LOG::equals,
			pos -> pos.getY() == 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 0, false);
	}

	/** A colony with a hauler, and a storage area if {@code site}. */
	private static ColonyStatus.Room room(boolean site, int freeSlots, boolean chestWithRoom) {
		UUID hauler = UUID.randomUUID();
		Colony colony = new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(hauler), new JobBoard(), 20,
			Map.of(), Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
		if (site) {
			colony.setStorage(new StorageArea(new BlockPos(5, 64, 5)));
		}
		Map<UUID, ColonyStatus.Bot> bots = Map.of(hauler, new ColonyStatus.Bot("Hauler", BotRole.HAULER, false, false));
		return ColonyStatus.of(colony, bots, Map.of(), item -> List.of(), item -> false, pos -> Blocks.AIR.defaultBlockState(), freeSlots, chestWithRoom).room();
	}

	@Test
	void reportsAFullCoreAndWhyThereIsNoStorage() {
		ColonyStatus.Room room = status().room();
		assertTrue(room.coreFull());
		assertEquals(ColonyStatus.StorageState.NO_HAULER, room.storage());
		assertEquals(new ColonyStatus.Room(0, ColonyStatus.StorageState.NO_SITE), room(false, 0, false));
		assertEquals(new ColonyStatus.Room(0, ColonyStatus.StorageState.NO_CHEST), room(true, 0, false));
		assertEquals(new ColonyStatus.Room(3, ColonyStatus.StorageState.READY), room(true, 3, true));
		assertFalse(room(true, 3, true).coreFull());
	}

	@Test
	void countsMembersByRole() {
		ColonyStatus status = status();
		assertEquals(4, status.members());
		assertEquals(Map.of(BotRole.LUMBERJACK, 1, BotRole.MINER, 2), status.roles());
		assertEquals(1, status.idle());
		assertEquals(20, status.radius());
		assertEquals(Map.of(JobType.CHOP_TREE, 2, JobType.MINE_BRANCH, 1), status.openJobs());
		assertEquals(Map.of(JobType.CHOP_TREE, 1), status.claimedJobs());
	}

	@Test
	void listsLoadedMembersInOrder() {
		assertEquals(List.of(LUMBERJACK, MINER, IDLE_MINER), List.copyOf(status().bots().keySet()));
		assertEquals(new ColonyStatus.Bot("Miner", BotRole.MINER, false, true), status().bots().get(MINER));
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
			new ColonyStatus.Build(new BlockPos(10, 64, 10), 1, 2, false, false),
			new ColonyStatus.Build(new BlockPos(12, 64, 12), 1, 1, false, true)), status.builds());
		assertEquals(List.of(new BlockPos(3, 64, 3)), status.blocked());
	}

	@Test
	void listsTreesGivenUpOn() {
		assertEquals(List.of(new BlockPos(9, 64, 9)), status().givenUp());
	}

	@Test
	void roundTripsThroughItsStreamCodec() {
		ColonyStatus status = status();
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		ColonyStatus.STREAM_CODEC.encode(buf, status);
		assertEquals(status, ColonyStatus.STREAM_CODEC.decode(buf));
	}
}
