package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.Quarry;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class MinerGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
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

	/** Fills the box with stone and makes it the colony's quarry. */
	private static void quarry(GameTestHelper helper, Colony colony, BlockPos from, BlockPos to) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		colony.setQuarry(new Quarry(helper.absolutePos(from), helper.absolutePos(to)));
	}

	private static void assertQuarryEmpty(GameTestHelper helper, BlockPos from, BlockPos to) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			helper.assertBlockNotPresent(Blocks.STONE, pos);
		}
	}

	private static AntBot member(GameTestHelper helper, Colony colony, BotRole role, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(role);
		return bot;
	}

	@GameTest(maxTicks = 1200)
	public void minesQuarryAndStoresStoneInCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockPos from = new BlockPos(3, 1, 3);
		BlockPos to = new BlockPos(5, 2, 5);
		quarry(helper, colony, from, to);
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 4);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.succeedWhen(() -> {
			assertQuarryEmpty(helper, from, to);
			// The miner keeps some cobblestone as scaffold blocks; the rest goes to the core.
			int stored = core(helper).countItem(Items.COBBLESTONE);
			helper.assertTrue(stored > 0, "no cobblestone stored yet");
		});
	}

	@GameTest(maxTicks = 800)
	public void fetchesPickaxeFromCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.STONE_PICKAXE));
		BlockPos from = new BlockPos(3, 1, 3);
		BlockPos to = new BlockPos(5, 1, 5);
		quarry(helper, colony, from, to);
		member(helper, colony, BotRole.MINER, 1, 4);

		helper.succeedWhen(() -> assertQuarryEmpty(helper, from, to));
	}

	@GameTest(maxTicks = 200)
	public void ordersPickaxeWhenNoneAvailable(GameTestHelper helper) {
		Colony colony = colony(helper);
		quarry(helper, colony, new BlockPos(3, 1, 3), new BlockPos(5, 1, 5));
		member(helper, colony, BotRole.MINER, 1, 4);

		helper.succeedWhen(() -> helper.assertTrue(colony.orders().containsKey(Items.WOODEN_PICKAXE), "orders: " + colony.orders()));
	}

	/** Lumberjack logs become a pickaxe at the crafter, which the miner uses to dig. */
	@GameTest(maxTicks = 3000)
	public void colonyMakesItsOwnPickaxeAndMines(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(5 + dx, 4, 1 + dz + 1, leaves);
			}
		}
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(5, y, 2, Blocks.OAK_LOG);
		}
		BlockPos from = new BlockPos(3, 1, 5);
		BlockPos to = new BlockPos(5, 1, 6);
		quarry(helper, colony, from, to);
		member(helper, colony, BotRole.LUMBERJACK, 2, 3);
		member(helper, colony, BotRole.CRAFTER, 1, 3);
		member(helper, colony, BotRole.MINER, 2, 4);

		helper.succeedWhen(() -> assertQuarryEmpty(helper, from, to));
	}
}
