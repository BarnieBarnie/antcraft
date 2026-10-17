package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
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

	/**
	 * A bot with no role pillars up in a one-wide corridor. Another bot without scaffold blocks
	 * then has to get past the pillar: it gives up instead of digging it out from under the first.
	 */
	@GameTest(maxTicks = 600)
	public void leavesPillarOfOtherBotAlone(GameTestHelper helper) {
		room(helper, 0);
		fill(helper, 1, 1, 1, 6, 7, 2, Blocks.BEDROCK);
		fill(helper, 1, 1, 4, 6, 7, 6, Blocks.BEDROCK);
		AntBot owner = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		owner.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		owner.setWalkTarget(helper.absolutePos(new BlockPos(3, 4, 3)));
		AntBot other = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		Set<BlockPos> pillar = new HashSet<>();
		helper.onEachTick(() -> {
			if (pillar.isEmpty() && owner.getWalkTarget() == null) {
				for (int y = 1; y <= 6; y++) {
					if (helper.getBlockState(new BlockPos(3, y, 3)).is(Blocks.DIRT)) {
						pillar.add(new BlockPos(3, y, 3));
					}
				}
				helper.assertFalse(pillar.isEmpty(), "the owner did not pillar");
				other.setWalkTarget(helper.absolutePos(new BlockPos(6, 1, 3)));
			}
			for (BlockPos pos : pillar) {
				if (!helper.getBlockState(pos).is(Blocks.DIRT)) {
					helper.fail("the other bot broke the pillar at " + pos.toShortString());
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertFalse(pillar.isEmpty(), "the owner has not pillared yet");
			helper.assertTrue(other.getWalkTarget() == null, "the other bot is still walking");
		});
	}

	/** A bot without scaffold dropped in the middle of a 5x5 pool, 2 deep, swims to the shore and climbs out. */
	@GameTest(maxTicks = 400)
	public void swimsOutOfADeepPool(GameTestHelper helper) {
		room(helper, 2);
		fill(helper, 1, 1, 1, 5, 2, 5, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 2, 3);
		bot.setWalkTarget(helper.absolutePos(new BlockPos(6, 3, 6)));

		succeedWhenAt(helper, bot, new BlockPos(6, 3, 6));
	}
}
