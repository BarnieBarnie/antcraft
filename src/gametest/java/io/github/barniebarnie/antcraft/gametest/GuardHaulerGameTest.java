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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

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
