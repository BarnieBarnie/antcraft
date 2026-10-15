package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Bots following planned paths through terrain. Each test builds a room closed in
 * by bedrock so the bot cannot leave the test area.
 */
public class PathingGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Bedrock walls on the border of the 8x8 area (y 0..7) and a floor at {@code floorY}. */
	private static void room(GameTestHelper helper, int floorY) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean border = x == 0 || x == 7 || z == 0 || z == 7;
				for (int y = 0; y < 8; y++) {
					if (border) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					} else if (y <= floorY) {
						helper.setBlock(x, y, z, Blocks.BEDROCK);
					}
				}
			}
		}
	}

	private static void fill(GameTestHelper helper, int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					helper.setBlock(x, y, z, block);
				}
			}
		}
	}

	private static void succeedWhenAt(GameTestHelper helper, AntBot bot, BlockPos relativeTarget) {
		BlockPos target = helper.absolutePos(relativeTarget);
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getWalkTarget() == null, "still walking, at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
			helper.assertTrue(bot.blockPosition().distManhattan(target) <= 1, "stopped at " + bot.blockPosition().subtract(helper.absolutePos(BlockPos.ZERO)));
		});
	}

	@GameTest(maxTicks = 600)
	public void digsThroughWall(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 4, 1, 1, 4, 6, 6, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 1, 3)));

		succeedWhenAt(helper, bot, new BlockPos(6, 1, 3));
	}

	@GameTest(maxTicks = 600)
	public void bridgesGap(GameTestHelper helper) {
		room(helper, 3);
		// A pit too deep to drop into and climb out of.
		fill(helper, 3, 1, 1, 4, 3, 6, Blocks.AIR);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 4, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 16));
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 4, 3)));

		succeedWhenAt(helper, bot, new BlockPos(6, 4, 3));
	}

	@GameTest(maxTicks = 600)
	public void pillarsUpToLedge(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 5, 1, 1, 6, 3, 6, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 16));
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 4, 3)));

		succeedWhenAt(helper, bot, new BlockPos(6, 4, 3));
	}
}
