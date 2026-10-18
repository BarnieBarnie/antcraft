package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.StorageArea;
import io.github.barniebarnie.antcraft.work.StorageCategory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

public class GuardHaulerGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		// Bedrock walls keep patrolling guards inside the test area.
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean border = x == 0 || x == 7 || z == 0 || z == 7;
				for (int y = 0; y < 4; y++) {
					if (y == 0 || border) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					}
				}
			}
		}
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow();
		colony.setRadius(6);
		// No default stock targets, so only the test's own orders give the colony work.
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Container core(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(CORE, BlockEntity.class);
	}

	private static AntBot member(GameTestHelper helper, Colony colony, BotRole role) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(role);
		return bot;
	}

	@GameTest(maxTicks = 300)
	public void guardTakesWeaponFromCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.IRON_SWORD));
		AntBot guard = member(helper, colony, BotRole.GUARD);

		helper.succeedWhen(() -> {
			boolean armed = guard.getMainHandItem().is(Items.IRON_SWORD) || guard.getInventory().countItem(Items.IRON_SWORD) == 1;
			helper.assertTrue(armed, "guard has no sword");
			helper.assertTrue(core(helper).countItem(Items.IRON_SWORD) == 0, "sword still in core");
		});
	}

	@GameTest(maxTicks = 600)
	public void guardPatrolsAroundCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		AntBot guard = member(helper, colony, BotRole.GUARD);
		Set<BlockPos> visited = new HashSet<>();

		helper.onEachTick(() -> visited.add(guard.blockPosition()));
		helper.succeedWhen(() -> {
			helper.assertTrue(visited.size() >= 6, "guard visited only " + visited.size() + " spots");
			helper.assertTrue(guard.blockPosition().closerThan(helper.absolutePos(CORE), colony.radius() + 1), "guard wandered off");
		});
	}

	/** Chests stand on the rows z=4 and z=6, with an aisle between them. */
	private static final BlockPos STORAGE = new BlockPos(2, 1, 4);

	private static List<Container> storageChests(GameTestHelper helper, Colony colony) {
		List<Container> chests = new ArrayList<>();
		for (BlockPos spot : colony.storage().orElseThrow().chestSpots()) {
			Container chest = Storage.containerAt(helper.getLevel(), spot);
			if (chest != null) {
				chests.add(chest);
			}
		}
		return chests;
	}

	@GameTest(maxTicks = 600)
	public void haulerMovesSurplusFromAFullCoreIntoWoodChests(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStorage(new StorageArea(helper.absolutePos(STORAGE)));
		Container core = core(helper);
		// Two chests to place, and logs in every other slot.
		core.setItem(0, new ItemStack(Items.CHEST, 2));
		for (int i = 1; i < core.getContainerSize(); i++) {
			core.setItem(i, new ItemStack(Items.OAK_LOG, 64));
		}
		int logs = core.countItem(Items.OAK_LOG);
		member(helper, colony, BotRole.HAULER);

		helper.succeedWhen(() -> {
			// Without stock targets, the core keeps one stack.
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 64, "core holds " + core.countItem(Items.OAK_LOG) + " logs");
			List<Container> chests = storageChests(helper, colony);
			int stored = chests.stream().mapToInt(chest -> chest.countItem(Items.OAK_LOG)).sum();
			helper.assertTrue(stored == logs - 64, "storage holds " + stored + " logs");
			for (Container chest : chests) {
				for (int i = 0; i < chest.getContainerSize(); i++) {
					ItemStack stack = chest.getItem(i);
					helper.assertTrue(stack.isEmpty() || StorageCategory.of(stack) == StorageCategory.WOOD, "not a wood chest: " + stack);
				}
			}
		});
	}

	/** A core full of logs it keeps, and a hauler carrying cobblestone that does not fit in it. */
	private static AntBot haulerWithLeftovers(GameTestHelper helper, Colony colony) {
		Container core = core(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.LOGS, core.getContainerSize() * 64, Items.OAK_LOG)));
		for (int i = 0; i < core.getContainerSize(); i++) {
			core.setItem(i, new ItemStack(Items.OAK_LOG, 64));
		}
		AntBot hauler = member(helper, colony, BotRole.HAULER);
		hauler.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
		hauler.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 32));
		return hauler;
	}

	@GameTest(maxTicks = 400)
	public void haulerStoresWhatTheFullCoreCannotTake(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStorage(new StorageArea(helper.absolutePos(STORAGE)));
		helper.setBlock(STORAGE, Blocks.CHEST);
		Container chest = (Container) helper.getBlockEntity(STORAGE, BlockEntity.class);
		AntBot hauler = haulerWithLeftovers(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 96, "storage holds " + chest.countItem(Items.COBBLESTONE) + " cobblestone");
			helper.assertTrue(hauler.getInventory().isEmpty(), "hauler still carries " + hauler.getInventory());
		});
	}

	@GameTest(maxTicks = 300)
	public void storageOrdersAChestForWhatAHaulerCannotStore(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStorage(new StorageArea(helper.absolutePos(STORAGE)));
		haulerWithLeftovers(helper, colony);

		helper.succeedWhen(() -> helper.assertTrue(colony.orders().containsKey(Items.CHEST), "no chest ordered: " + colony.orders()));
	}

	@GameTest(maxTicks = 500)
	public void storageChestOrderIsWithdrawnWhenAPlayerBringsAChest(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStorage(new StorageArea(helper.absolutePos(STORAGE)));
		Container core = core(helper);
		// More logs than the core keeps, and no chest to store them in: storage needs one. No crafter makes it.
		for (int i = 0; i < 3; i++) {
			core.setItem(i, new ItemStack(Items.OAK_LOG, 64));
		}
		member(helper, colony, BotRole.HAULER);
		boolean[] brought = {false};

		helper.onEachTick(() -> {
			if (!brought[0] && colony.orders().containsKey(Items.CHEST)) {
				core.setItem(10, new ItemStack(Items.CHEST));
				brought[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(brought[0], "storage never ordered a chest");
			helper.assertTrue(!colony.orders().containsKey(Items.CHEST), "chest order left: " + colony.orders());
		});
	}

	/**
	 * A colony whose core is full and that has no storage area: it needs a hauler, a site and a chest,
	 * and the chest has no slot in the core to be crafted into. Inside the walls the only flat site
	 * clear of the core is the rows z=4 and z=6.
	 */
	@GameTest(maxTicks = 1500)
	public void aFullCoreGetsAHaulerAndAStorageChest(GameTestHelper helper) {
		BlockPos corePos = new BlockPos(2, 1, 2);
		helper.setBlock(corePos, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(corePos)).orElseThrow();
		colony.setRadius(5);
		colony.setStockTargets(List.of());
		Container core = (Container) helper.getBlockEntity(corePos, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
		for (int i = 1; i < core.getContainerSize(); i++) {
			core.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
		}
		member(helper, colony, BotRole.CRAFTER);
		AntBot newcomer = member(helper, colony, BotRole.NONE);

		helper.succeedWhen(() -> {
			helper.assertTrue(newcomer.getRole() == BotRole.HAULER, "newcomer is a " + newcomer.getRole());
			helper.assertTrue(colony.storage().isPresent(), "no storage area");
			List<Container> chests = storageChests(helper, colony);
			helper.assertTrue(!chests.isEmpty(), "no storage chest placed");
			int free = 0;
			for (int i = 0; i < core.getContainerSize(); i++) {
				free += core.getItem(i).isEmpty() ? 1 : 0;
			}
			helper.assertTrue(free > 0, "core still full");
			List<ItemEntity> loose = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
				AABB.encapsulatingFullBlocks(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(7, 7, 7))));
			helper.assertTrue(loose.isEmpty(), "items dropped: " + loose);
		});
	}

	/** Stacks of {@code count} different plain items, none of them planks, sticks or chests. */
	private static List<ItemStack> distinctStacks(int count) {
		return BuiltInRegistries.ITEM.stream()
			.filter(item -> item != Items.AIR && item.getDefaultMaxStackSize() == 64)
			.filter(item -> BuiltInRegistries.ITEM.getKey(item).getNamespace().equals("minecraft"))
			.filter(item -> item != Items.OAK_PLANKS && item != Items.STICK && item != Items.CHEST)
			.limit(count)
			.map(item -> new ItemStack(item, 64))
			.toList();
	}

	/**
	 * A core full of different items, a stack of each and none above a stock target, so nothing in
	 * it is surplus. The colony still makes room: storage takes what nothing needs, and the order is
	 * crafted.
	 */
	private static final BlockPos FULL_CORE = new BlockPos(2, 1, 2);

	/**
	 * A colony whose core at {@link #FULL_CORE} is full of different items, a stack of each and none
	 * above a stock target, so nothing in it is surplus; planks in the first slot to craft a chest from.
	 * It has a crafter and a newcomer, who becomes its hauler.
	 */
	private static Colony coreFullOfDifferentItems(GameTestHelper helper) {
		helper.setBlock(FULL_CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(FULL_CORE)).orElseThrow();
		colony.setRadius(5);
		colony.setStockTargets(List.of());
		Container core = fullCore(helper);
		core.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
		List<ItemStack> others = distinctStacks(core.getContainerSize());
		for (int i = 1; i < core.getContainerSize(); i++) {
			core.setItem(i, others.get(i - 1));
		}
		member(helper, colony, BotRole.CRAFTER);
		member(helper, colony, BotRole.NONE);
		return colony;
	}

	private static Container fullCore(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(FULL_CORE, BlockEntity.class);
	}

	/** That the colony made room: a storage chest stands, the core has a free slot and nothing was dropped. */
	private static void assertMadeRoom(GameTestHelper helper, Colony colony) {
		helper.assertTrue(!storageChests(helper, colony).isEmpty(), "no storage chest placed");
		Container core = fullCore(helper);
		int free = 0;
		for (int i = 0; i < core.getContainerSize(); i++) {
			free += core.getItem(i).isEmpty() ? 1 : 0;
		}
		helper.assertTrue(free > 0, "core still full");
		List<ItemEntity> loose = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
			AABB.encapsulatingFullBlocks(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(7, 7, 7))));
		helper.assertTrue(loose.isEmpty(), "items dropped: " + loose);
	}

	/** Storage takes what nothing needs, and the order is crafted. */
	@GameTest(maxTicks = 1500)
	public void aCoreFullOfDifferentItemsMakesRoom(GameTestHelper helper) {
		Colony colony = coreFullOfDifferentItems(helper);
		colony.addOrder(Items.STICK, 4);

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.orders().getOrDefault(Items.STICK, 0) == 0, "orders: " + colony.orders());
			assertMadeRoom(helper, colony);
		});
	}

	/** A depositor fills the slot the hauler freed before the chest is crafted; the hauler frees another. */
	@GameTest(maxTicks = 1500)
	public void aFreedSlotThatIsRefilledIsFreedAgain(GameTestHelper helper) {
		Colony colony = coreFullOfDifferentItems(helper);
		ItemStack deposit = distinctStacks(fullCore(helper).getContainerSize() + 1).getLast();
		boolean[] refilled = {false};
		helper.onEachTick(() -> {
			Container core = fullCore(helper);
			for (int i = 0; i < core.getContainerSize() && !refilled[0]; i++) {
				if (core.getItem(i).isEmpty() && core.countItem(Items.CHEST) == 0) {
					core.setItem(i, deposit.copy());
					refilled[0] = true;
				}
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(refilled[0], "the freed slot was never refilled");
			assertMadeRoom(helper, colony);
		});
	}

	private static int freeSlots(Container container) {
		int free = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			free += container.getItem(i).isEmpty() ? 1 : 0;
		}
		return free;
	}

	/**
	 * A core full of different items, each exactly at its stock target, so none is surplus. The core
	 * still frees a slot, and the hauler does not fetch what it stored straight back.
	 */
	@GameTest(maxTicks = 600)
	public void aCoreFullOfWantedItemsMakesRoomWithoutFetchingItBack(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStorage(new StorageArea(helper.absolutePos(STORAGE)));
		helper.setBlock(STORAGE, Blocks.CHEST);
		Container chest = (Container) helper.getBlockEntity(STORAGE, BlockEntity.class);
		Container core = core(helper);
		List<ItemStack> stacks = distinctStacks(core.getContainerSize());
		List<StockTarget> targets = new ArrayList<>();
		for (int i = 0; i < core.getContainerSize(); i++) {
			core.setItem(i, stacks.get(i).copy());
			targets.add(StockTarget.of(stacks.get(i).getItem(), stacks.get(i).getCount()));
		}
		colony.setStockTargets(targets);
		member(helper, colony, BotRole.HAULER);
		int[] stored = {0};
		boolean[] fetchedBack = {false};

		helper.onEachTick(() -> {
			int now = 0;
			for (int i = 0; i < chest.getContainerSize(); i++) {
				now += chest.getItem(i).getCount();
			}
			fetchedBack[0] |= now < stored[0];
			stored[0] = now;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(!fetchedBack[0], "the hauler fetched stored items back");
			helper.assertTrue(stored[0] > 0, "nothing stored");
			helper.assertTrue(freeSlots(core) > 0, "core still full");
			// Long enough for a hauler that fetches the items back to do so.
			helper.assertTrue(helper.getTick() >= 400, "still watching");
		});
	}

	/** A hauler holds surplus out of a full core to make room for a chest; once the core has room, it puts it back. */
	@GameTest(maxTicks = 900)
	public void haulerPutsHeldItemsIntoTheCoreOnceItHasRoom(GameTestHelper helper) {
		Colony colony = colony(helper);
		// A storage area waiting for a chest, and no crafter to make one.
		colony.setStorage(new StorageArea(helper.absolutePos(STORAGE)));
		Container core = core(helper);
		List<ItemStack> stacks = distinctStacks(core.getContainerSize());
		for (int i = 0; i < core.getContainerSize(); i++) {
			core.setItem(i, stacks.get(i).copy());
		}
		AntBot hauler = member(helper, colony, BotRole.HAULER);
		ItemStack[] held = {ItemStack.EMPTY};

		helper.onEachTick(() -> {
			if (held[0].isEmpty() && !hauler.getInventory().isEmpty()) {
				for (int i = 0; i < hauler.getInventory().getContainerSize(); i++) {
					if (!hauler.getInventory().getItem(i).isEmpty()) {
						held[0] = hauler.getInventory().getItem(i).copy();
					}
				}
				// The colony uses up ten stacks: the core has room again.
				for (int i = 0; i < core.getContainerSize() && freeSlots(core) < 10; i++) {
					if (!core.getItem(i).isEmpty()) {
						core.setItem(i, ItemStack.EMPTY);
					}
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(!held[0].isEmpty(), "the hauler never held anything");
			helper.assertTrue(hauler.getInventory().isEmpty(), "hauler still holds " + hauler.getInventory());
			helper.assertTrue(core.countItem(held[0].getItem()) >= held[0].getCount(), "core lacks the held " + held[0]);
		});
	}

	@GameTest(maxTicks = 400)
	public void haulerBringsAMissingPickaxeBackFromStorage(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.PICKAXES, 2, Items.STONE_PICKAXE)));
		colony.setStorage(new StorageArea(helper.absolutePos(STORAGE)));
		helper.setBlock(STORAGE, Blocks.CHEST);
		Container chest = (Container) helper.getBlockEntity(STORAGE, BlockEntity.class);
		chest.setItem(0, new ItemStack(Items.STONE_PICKAXE));
		member(helper, colony, BotRole.HAULER);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.STONE_PICKAXE) == 1, "pickaxe not in the core");
			helper.assertTrue(chest.countItem(Items.STONE_PICKAXE) == 0, "pickaxe still in storage");
		});
	}

	@GameTest(maxTicks = 600)
	public void haulerStoresLooseItemsInCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.spawnItem(Items.OAK_SAPLING, 5, 1, 5);
		helper.spawnItem(Items.STICK, 2, 1, 5);
		helper.spawnItem(Items.APPLE, 5, 1, 2);
		member(helper, colony, BotRole.HAULER);

		helper.succeedWhen(() -> {
			Container core = core(helper);
			helper.assertTrue(core.countItem(Items.OAK_SAPLING) == 1 && core.countItem(Items.STICK) == 1 && core.countItem(Items.APPLE) == 1,
				"core does not hold all three items yet");
		});
	}
}
