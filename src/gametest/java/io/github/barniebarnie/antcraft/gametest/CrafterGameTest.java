package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.DefaultStock;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import io.github.barniebarnie.antcraft.work.Storage;
import java.lang.reflect.Method;
import java.util.List;
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

public class CrafterGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
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
		bot.setRole(BotRole.CRAFTER);
		return bot;
	}

	@GameTest(maxTicks = 400)
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

	/** The default stock keeps torches for the mineshaft. */
	@GameTest(maxTicks = 400)
	public void keepsTorchesInStock(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStockTargets(DefaultStock.TARGETS.stream().filter(target -> target.matches(Items.TORCH)).toList());
		store(helper, new ItemStack(Items.COAL, 1), new ItemStack(Items.STICK, 1));
		crafter(helper, colony);

		helper.succeedWhen(() -> helper.assertTrue(core(helper).countItem(Items.TORCH) == 4, "core has " + core(helper).countItem(Items.TORCH) + " torches"));
	}

	@GameTest(maxTicks = 400)
	public void activatesBotCoresFromStorage(GameTestHelper helper) {
		Colony colony = colony(helper);
		store(helper, new ItemStack(ModItems.BOT_CORE));
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.members().size() == 2, "members: " + colony.members().size());
			helper.assertTrue(core(helper).countItem(ModItems.BOT_CORE) == 0, "bot core still in storage");
		});
	}

	@GameTest(maxTicks = 600)
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

	@GameTest(maxTicks = 600)
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

	@GameTest(maxTicks = 900)
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
		helper.assertTrue(colony.orders().isEmpty(), "order not cleared: " + colony.orders());
		// The coal went into a furnace: the ingots were smelted, not crafted.
		helper.assertTrue(core(helper).countItem(Items.COAL) == 0, "coal left in core: " + core(helper).countItem(Items.COAL));
	}

	@GameTest(maxTicks = 900)
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

	@GameTest(maxTicks = 900)
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

	@GameTest(maxTicks = 1000)
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

	@GameTest(maxTicks = 1400)
	public void makesCharcoalFromLogsToSmelt(GameTestHelper helper) {
		Colony colony = colony(helper);
		helper.setBlock(CORE.east(), Blocks.FURNACE);
		// Burning logs directly takes 3 for 4 ingots; one charcoal (a log, smelted with a log) does it with 2.
		store(helper, new ItemStack(Items.RAW_IRON, 4), new ItemStack(Items.OAK_LOG, 3));
		colony.addOrder(Items.IRON_INGOT, 4);
		crafter(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.IRON_INGOT) == 4, "core has " + core(helper).countItem(Items.IRON_INGOT) + " iron ingots");
			helper.assertTrue(colony.orders().isEmpty(), "order not cleared: " + colony.orders());
			helper.assertTrue(core(helper).countItem(Items.OAK_LOG) == 1, "core has " + core(helper).countItem(Items.OAK_LOG) + " logs");
			helper.assertTrue(core(helper).countItem(Items.CHARCOAL) == 0, "charcoal left in core: " + core(helper).countItem(Items.CHARCOAL));
		});
	}
}
