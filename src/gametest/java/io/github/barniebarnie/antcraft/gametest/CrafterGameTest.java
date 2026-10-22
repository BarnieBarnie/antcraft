package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.DefaultStock;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import io.github.barniebarnie.antcraft.work.Storage;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

public class CrafterGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
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

	private static void store(GameTestHelper helper, ItemStack... stacks) {
		Container core = core(helper);
		for (int i = 0; i < stacks.length; i++) {
			core.setItem(i, stacks[i]);
		}
	}

	private static AntBot crafter(GameTestHelper helper, Colony colony) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	@GameTest(maxTicks = 60)
	public void craftsOrderFromLogsInCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper, new ItemStack(Items.OAK_LOG, 2));
		colony.addOrder(Items.STICK, 4);
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.STICK) >= 4, "core has " + core(helper).countItem(Items.STICK) + " sticks");
			helper.assertTrue(colony.orders().isEmpty(), "order not cleared: " + colony.orders());
		});
	}

	/**
	 * Planning a bed once searched every wool and dye recipe and hung the server on the first
	 * survey. Now the colony keeps ticking while the bed waits for wool, and makes it once wool
	 * and planks arrive.
	 */
	@GameTest(maxTicks = 520)
	public void ordersABedAndMakesItOnceWoolAndPlanksArrive(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.addOrder(Items.BED.red(), 1);
		crafter(helper, colony);

		// By now the colony has surveyed, and planned the bed, more than once.
		helper.runAtTickTime(250, () -> store(helper, new ItemStack(Items.WOOL.red(), 3), new ItemStack(Items.OAK_PLANKS, 3)));
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getTick() > 250, "the server kept ticking");
			helper.assertTrue(core(helper).countItem(Items.BED.red()) == 1, "core has " + core(helper).countItem(Items.BED.red()) + " red beds");
			helper.assertTrue(colony.orders().isEmpty(), "order not cleared: " + colony.orders());
		});
	}

	/** The default stock keeps torches for the mineshaft. */
	@GameTest(maxTicks = 40)
	public void keepsTorchesInStock(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStockTargets(DefaultStock.TARGETS.stream().filter(target -> target.matches(Items.TORCH)).toList());
		store(helper, new ItemStack(Items.COAL, 1), new ItemStack(Items.STICK, 1));
		crafter(helper, colony);

		helper.succeedWhen(() -> helper.assertTrue(core(helper).countItem(Items.TORCH) == 4, "core has " + core(helper).countItem(Items.TORCH) + " torches"));
	}

	@GameTest(maxTicks = 40)
	public void activatesBotCoresFromStorage(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper, new ItemStack(ModItems.BOT_CORE));
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.members().size() == 2, "members: " + colony.members().size());
			helper.assertTrue(core(helper).countItem(ModItems.BOT_CORE) == 0, "bot core still in storage");
		});
	}

	@GameTest(maxTicks = 210)
	public void buildsANewBotFromRawMaterials(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper,
			new ItemStack(Items.CARVED_PUMPKIN),
			new ItemStack(Items.IRON_INGOT, 3),
			new ItemStack(Items.REDSTONE, 9));
		colony.addOrder(ModItems.BOT_CORE, 1);
		crafter(helper, colony);

		helper.succeedWhen(() -> helper.assertTrue(colony.members().size() == 2, "members: " + colony.members().size()));
	}

	@GameTest(maxTicks = 40)
	public void craftsUpToStockTarget(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.PLANKS, 4)));
		store(helper, new ItemStack(Items.OAK_LOG, 2));
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.OAK_PLANKS) == 4, "core has " + core(helper).countItem(Items.OAK_PLANKS) + " planks");
			helper.assertTrue(core(helper).countItem(Items.OAK_LOG) == 1, "core has " + core(helper).countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(maxTicks = 220)
	public void reordersWhenStockDropsBelowTarget(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.PLANKS, 4)));
		store(helper, new ItemStack(Items.OAK_LOG, 2));
		crafter(helper, colony);

		boolean[] taken = {false};
		helper.onEachTick(() -> {
			if (!taken[0] && core(helper).countItem(Items.OAK_PLANKS) == 4) {
				// Someone uses the planks up.
				Storage.take(core(helper), Items.OAK_PLANKS, 4);
				taken[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(taken[0], "planks never reached the target");
			helper.assertTrue(core(helper).countItem(Items.OAK_PLANKS) == 4, "core has " + core(helper).countItem(Items.OAK_PLANKS) + " planks");
			helper.assertTrue(core(helper).countItem(Items.OAK_LOG) == 0, "core has " + core(helper).countItem(Items.OAK_LOG) + " logs");
		});
	}

	private static int furnacesNearCore(GameTestHelper helper) {
		int furnaces = 0;
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 3, 7)) {
			if (helper.getBlockState(pos).is(Blocks.FURNACE)) {
				furnaces++;
			}
		}
		return furnaces;
	}

	private static void assertIngots(GameTestHelper helper, Colony colony, int ingots) {
		helper.assertTrue(core(helper).countItem(Items.IRON_INGOT) == ingots, "core has " + core(helper).countItem(Items.IRON_INGOT) + " iron ingots");
		helper.assertTrue(!colony.orders().containsKey(Items.IRON_INGOT), "order not cleared: " + colony.orders());
		// The coal went into a furnace: the ingots were smelted, not crafted.
		helper.assertTrue(core(helper).countItem(Items.COAL) == 0, "coal left in core: " + core(helper).countItem(Items.COAL));
	}

	@GameTest(maxTicks = 840)
	public void smeltsRawIronInAFurnaceNextToTheCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(CORE.east(), Blocks.FURNACE);
		store(helper, new ItemStack(Items.RAW_IRON, 2), new ItemStack(Items.COAL, 1));
		colony.addOrder(Items.IRON_INGOT, 2);
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			assertIngots(helper, colony, 2);
			helper.assertTrue(core(helper).countItem(Items.RAW_IRON) == 0, "raw iron left in core");
			helper.assertTrue(furnacesNearCore(helper) == 1, "furnaces: " + furnacesNearCore(helper));
		});
	}

	@GameTest(maxTicks = 860)
	public void placesAStoredFurnaceToSmelt(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper, new ItemStack(Items.RAW_IRON, 2), new ItemStack(Items.COAL, 1), new ItemStack(Items.FURNACE));
		colony.addOrder(Items.IRON_INGOT, 2);
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			assertIngots(helper, colony, 2);
			helper.assertTrue(furnacesNearCore(helper) == 1, "furnaces: " + furnacesNearCore(helper));
			helper.assertTrue(core(helper).countItem(Items.FURNACE) == 0, "furnace still in core");
		});
	}

	@GameTest(maxTicks = 880)
	public void craftsAFurnaceFromCobblestoneToSmelt(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper, new ItemStack(Items.RAW_IRON, 2), new ItemStack(Items.COAL, 1), new ItemStack(Items.COBBLESTONE, 8));
		colony.addOrder(Items.IRON_INGOT, 2);
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			assertIngots(helper, colony, 2);
			helper.assertTrue(furnacesNearCore(helper) == 1, "furnaces: " + furnacesNearCore(helper));
			helper.assertTrue(core(helper).countItem(Items.COBBLESTONE) == 0, "cobblestone left: " + core(helper).countItem(Items.COBBLESTONE));
		});
	}

	@GameTest(maxTicks = 2100)
	public void makesCharcoalFromLogsToSmelt(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(CORE.east(), Blocks.FURNACE);
		// Burning logs directly takes 3 for 4 ingots; one charcoal (a log, smelted with a log) does it with 2.
		store(helper, new ItemStack(Items.RAW_IRON, 4), new ItemStack(Items.OAK_LOG, 3));
		colony.addOrder(Items.IRON_INGOT, 4);
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.IRON_INGOT) == 4, "core has " + core(helper).countItem(Items.IRON_INGOT) + " iron ingots");
			helper.assertTrue(!colony.orders().containsKey(Items.IRON_INGOT), "order not cleared: " + colony.orders());
			helper.assertTrue(core(helper).countItem(Items.OAK_LOG) == 1, "core has " + core(helper).countItem(Items.OAK_LOG) + " logs");
			helper.assertTrue(core(helper).countItem(Items.CHARCOAL) == 0, "charcoal left in core: " + core(helper).countItem(Items.CHARCOAL));
		});
	}

	/** Fills every core slot not taken by {@code stacks} with full stacks of cobblestone. */
	private static void fill(GameTestHelper helper, ItemStack... stacks) {
		Container core = core(helper);
		store(helper, stacks);
		for (int i = stacks.length; i < core.getContainerSize(); i++) {
			core.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
		}
	}

	private static List<ItemEntity> looseItems(GameTestHelper helper) {
		return helper.getLevel().getEntitiesOfClass(ItemEntity.class, AABB.encapsulatingFullBlocks(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(7, 7, 7))));
	}

	@GameTest(maxTicks = 450)
	public void doesNotCraftIntoAFullCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStockTargets(List.of(StockTarget.of(ItemTags.PICKAXES, 2, Items.STONE_PICKAXE)));
		// Neither ingredient stack is used up, so a pickaxe has no slot to go to.
		fill(helper, new ItemStack(Items.STICK, 64));
		int cobblestone = core(helper).countItem(Items.COBBLESTONE);
		AntBot crafter = crafter(helper, colony);
		// A full core gets a storage area, and a bot with room would take surplus out for a chest, freeing a slot
		// (see GuardHaulerGameTest); this one keeps a pack full of scaffold blocks, so it has no room for that.
		for (int i = 0; i < crafter.getInventory().getContainerSize() - 1; i++) {
			crafter.getInventory().addItem(new ItemStack(Items.DIRT, 64));
		}

		helper.runAtTickTime(400, () -> {
			helper.assertTrue(core(helper).countItem(Items.COBBLESTONE) == cobblestone, "crafter used cobblestone: " + core(helper).countItem(Items.COBBLESTONE));
			helper.assertTrue(core(helper).countItem(Items.STICK) == 64, "crafter used sticks: " + core(helper).countItem(Items.STICK));
			helper.assertTrue(crafter.getInventory().countItem(Items.STONE_PICKAXE) == 0, "crafter carries a pickaxe");
			helper.assertTrue(looseItems(helper).isEmpty(), "items dropped: " + looseItems(helper));
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 40)
	public void furnaceOutputWaitsWhileTheCrafterCraftsWhatFits(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(CORE.east(), Blocks.FURNACE);
		AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(CORE.east(), BlockEntity.class);
		furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 5));
		// The log's slot is free for the planks once the log is used; the ingots have nowhere to go.
		fill(helper, new ItemStack(Items.OAK_LOG, 1));
		colony.addOrder(Items.OAK_PLANKS, 4);
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.OAK_PLANKS) == 4, "core has " + core(helper).countItem(Items.OAK_PLANKS) + " planks");
			helper.assertTrue(furnace.getItem(2).is(Items.IRON_INGOT) && furnace.getItem(2).getCount() == 5, "furnace output: " + furnace.getItem(2));
			helper.assertTrue(looseItems(helper).isEmpty(), "items dropped: " + looseItems(helper));
		});
	}

	@GameTest(maxTicks = 20)
	public void furnaceOutputCountsAsColonyStock(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(CORE.east(), Blocks.FURNACE);
		AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(CORE.east(), BlockEntity.class);
		furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 5));

		helper.succeedWhen(() -> {
			int ingots = Storage.colonyStock(helper.getLevel(), colony).getOrDefault(Items.IRON_INGOT, 0);
			helper.assertTrue(ingots == 5, "colony stock has " + ingots + " iron ingots");
		});
	}

	/** Ingots waiting in the furnace for room in the core are what the order needs: no more are smelted. */
	@GameTest(maxTicks = 350)
	public void doesNotSmeltWhatWaitsInTheFurnace(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(CORE.east(), Blocks.FURNACE);
		AbstractFurnaceBlockEntity furnace = (AbstractFurnaceBlockEntity) helper.getBlockEntity(CORE.east(), BlockEntity.class);
		furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
		fill(helper, new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL, 8), new ItemStack(Items.STICK, 2));
		colony.addOrder(Items.IRON_PICKAXE, 1);
		crafter(helper, colony);

		helper.runAtTickTime(300, () -> {
			helper.assertTrue(core(helper).countItem(Items.RAW_IRON) == 3, "core has " + core(helper).countItem(Items.RAW_IRON) + " raw iron");
			helper.assertTrue(furnace.getItem(0).isEmpty(), "furnace input: " + furnace.getItem(0));
			helper.succeed();
		});
	}

	/** A colony bot with another role, kept by a player so the colony does not make it a crafter. */
	private static AntBot colonyGuard(GameTestHelper helper, Colony colony, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	@GameTest(maxTicks = 60)
	public void colonyBotOfAnotherRoleCraftsAnOrderAtTheCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper, new ItemStack(Items.OAK_LOG, 2));
		colony.addOrder(Items.STICK, 4);
		AntBot bot = colonyGuard(helper, colony, 4, 4);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.STICK) >= 4, "core has " + core(helper).countItem(Items.STICK) + " sticks");
			helper.assertTrue(colony.orders().isEmpty(), "order not cleared: " + colony.orders());
		});
	}

	/**
	 * A core on a ledge too high to reach from the ground: the bot pillars up with dirt to craft the order there,
	 * and takes its pillar down once done, though crafting is no build or tree job.
	 */
	@GameTest(maxTicks = 480)
	public void takesDownThePillarItPlacedToReachTheCore(GameTestHelper helper) {
		// A 5x5 ledge, four high, with the core in the middle: out of reach from the ground around it.
		BlockPos ledge = new BlockPos(5, 5, 5);
		for (BlockPos pos : BlockPos.betweenClosed(3, 1, 3, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(ledge, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(ledge)).orElseThrow();
		colony.setRadius(6);
		colony.setStockTargets(List.of());
		Container core = (Container) helper.getBlockEntity(ledge, BlockEntity.class);
		core.setItem(0, new ItemStack(Items.OAK_LOG, 2));
		colony.addOrder(Items.STICK, 4);
		AntBot bot = colonyGuard(helper, colony, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		boolean[] pillared = watchFor(helper, Blocks.DIRT);

		helper.succeedWhen(() -> {
			helper.assertTrue(core.countItem(Items.STICK) >= 4, "core has " + core.countItem(Items.STICK) + " sticks");
			helper.assertTrue(pillared[0], "never pillared up");
			helper.assertTrue(blocksInArea(helper, Blocks.DIRT) == 0, blocksInArea(helper, Blocks.DIRT) + " dirt left standing");
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold still recorded: " + bot.getScaffold());
		});
	}

	private static int blocksInArea(GameTestHelper helper, Block block) {
		int found = 0;
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 3, 7)) {
			if (helper.getBlockState(pos).is(block)) {
				found++;
			}
		}
		return found;
	}

	/** Remembers whether {@code block} ever stood in the test area. */
	private static boolean[] watchFor(GameTestHelper helper, Block block) {
		boolean[] seen = {false};
		helper.onEachTick(() -> seen[0] |= blocksInArea(helper, block) > 0);
		return seen;
	}

	@GameTest(maxTicks = 40)
	public void botCarryingACraftingTablePlacesItCraftsStairsAndTakesItBack(GameTestHelper helper) {
		Colony colony = colony(helper);
		// Far from the core, which a bot could craft at.
		AntBot bot = colonyGuard(helper, colony, 6, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 6));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
		colony.board().postFor(JobType.CRAFT, bot.blockPosition(), Items.COBBLESTONE_STAIRS, Optional.of(Items.CRAFTING_TABLE));
		boolean[] placed = watchFor(helper, Blocks.CRAFTING_TABLE);

		helper.succeedWhen(() -> {
			helper.assertTrue(placed[0], "crafting table never placed");
			helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE_STAIRS) == 4, "bot has " + bot.getInventory().countItem(Items.COBBLESTONE_STAIRS) + " stairs");
			helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 0, "cobblestone left");
			helper.assertTrue(bot.getInventory().countItem(Items.CRAFTING_TABLE) == 1, "crafting table not taken back");
			helper.assertTrue(blocksInArea(helper, Blocks.CRAFTING_TABLE) == 0, "crafting table left standing");
			helper.assertTrue(looseItems(helper).isEmpty(), "items dropped: " + looseItems(helper));
		});
	}

	/**
	 * A bot with a crafting table keeps the cobblestone for a stair craft posted to be done on the spot when it puts its
	 * pack away between jobs, rather than leaving the core to craft them. Another bot holds the job, so this one only does upkeep.
	 */
	@GameTest(maxTicks = 20)
	public void botWithACraftingTableKeepsTheCobblestoneForAStairCraftOnTheSpot(GameTestHelper helper) {
		Colony colony = colony(helper);
		AntBot bot = colonyGuard(helper, colony, 3, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 6));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		BlockPos batch = helper.absolutePos(new BlockPos(6, 1, 6));
		colony.board().postFor(JobType.CRAFT, batch, Items.COBBLESTONE_STAIRS, Optional.of(Items.CRAFTING_TABLE));
		colony.board().claim(UUID.randomUUID(), Set.of(JobType.CRAFT), batch, helper.getLevel().getGameTime());
		helper.onEachTick(() -> {
			if (core(helper).countItem(Items.COBBLESTONE) > 0) {
				helper.fail("cobblestone put in the core");
			}
		});

		// The logs show the bot did put its pack away.
		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.OAK_LOG) == 2, "pack not put away");
			helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 6, "bot has " + bot.getInventory().countItem(Items.COBBLESTONE) + " cobblestone");
		});
	}

	@GameTest(maxTicks = 800)
	public void botCarryingAFurnacePlacesItSmeltsRawIronAndTakesItBack(GameTestHelper helper) {
		Colony colony = colony(helper);
		AntBot bot = colonyGuard(helper, colony, 6, 6);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 2));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		colony.board().postFor(JobType.CRAFT, bot.blockPosition(), Items.IRON_INGOT, Optional.of(Items.FURNACE));
		boolean[] placed = watchFor(helper, Blocks.FURNACE);

		helper.succeedWhen(() -> {
			helper.assertTrue(placed[0], "furnace never placed");
			helper.assertTrue(bot.getInventory().countItem(Items.IRON_INGOT) == 2, "bot has " + bot.getInventory().countItem(Items.IRON_INGOT) + " iron ingots");
			helper.assertTrue(bot.getInventory().countItem(Items.RAW_IRON) == 0, "raw iron left");
			helper.assertTrue(bot.getInventory().countItem(Items.FURNACE) == 1, "furnace not taken back");
			helper.assertTrue(blocksInArea(helper, Blocks.FURNACE) == 0, "furnace left standing");
			helper.assertTrue(looseItems(helper).isEmpty(), "items dropped: " + looseItems(helper));
		});
	}
}
