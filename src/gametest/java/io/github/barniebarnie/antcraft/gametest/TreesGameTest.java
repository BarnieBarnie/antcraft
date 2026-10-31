package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.at;
import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.expect;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

/**
 * {@code lib/trees.py} (#450): {@code next_tree} finds the base of an oak at (5, 1, 5), four logs on dirt under persistent
 * leaves, and passes over a wall of logs on stone nearer the bot; {@code fell} fells it, picks up the logs and replants.
 */
public class TreesGameTest implements CustomTestMethodInvoker {
	static final BlockPos TREE = new BlockPos(5, 1, 5);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		plantTree(helper);
		method.invoke(this, helper);
	}

	/** An oak of four logs at {@link #TREE}, on dirt, under persistent leaves. */
	static void plantTree(GameTestHelper helper) {
		plantTree(helper, TREE, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
	}

	/** A tree of four {@code log} at {@code base}, on dirt, under persistent {@code leaves}. */
	static void plantTree(GameTestHelper helper, BlockPos base, Block log, Block leaves) {
		helper.setBlock(base.below(), Blocks.DIRT);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(base.getX(), y, base.getZ(), log);
		}
		for (int y = 3; y <= 5; y++) {
			int r = y == 5 ? 1 : 2;
			for (int x = base.getX() - r; x <= base.getX() + r; x++) {
				for (int z = base.getZ() - r; z <= base.getZ() + r; z++) {
					boolean corner = Math.abs(x - base.getX()) == r && Math.abs(z - base.getZ()) == r;
					if (!corner && helper.getBlockState(new BlockPos(x, y, z)).isAir()) {
						helper.setBlock(x, y, z, leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
					}
				}
			}
		}
	}

	/** The oak's base as a script expression. */
	private static String tree(GameTestHelper helper) {
		return "Pos(" + at(helper, TREE.getX(), TREE.getY(), TREE.getZ()) + ")";
	}

	/** A wall of two logs on stone next to the bot is no tree; the oak further off is. */
	@GameTest(maxTicks = 40)
	public void nextTreeFindsATreeAndSkipsALogWall(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.OAK_LOG);
		helper.setBlock(1, 2, 1, Blocks.OAK_LOG);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.next_tree() == " + tree(helper) + ", lib.trees.is_tree(Pos("
			+ at(helper, 1, 1, 1) + ")))\n", List.of("True False"));
	}

	/** A {@code where} that rejects every base finds no tree; one that takes the oak's base finds it. */
	@GameTest(maxTicks = 40)
	public void nextTreeTakesOnlyABaseWherePasses(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, """
			import lib.trees
			def nowhere(p):
			    return False
			def here(p):
			    return p == %s
			print(lib.trees.next_tree(where=nowhere), lib.trees.next_tree(radius=8, where=here) == %s)
			""".formatted(tree(helper), tree(helper)), List.of("None True"));
	}

	/** Felling leaves no log standing, the four logs in the bot's inventory and the sapling it carried where the tree stood. */
	@GameTest(maxTicks = 560)
	public void fellLeavesNoLogsPicksThemUpAndPlantsASapling(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
		expect(helper, bot, "import lib.trees\nlib.trees.fell(" + tree(helper) + ")\nprint(count('oak_log'))\n", List.of("4"),
			() -> {
				helper.assertBlockPresent(Blocks.OAK_SAPLING, TREE);
				for (int y = 2; y <= 4; y++) {
					helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(TREE.getX(), y, TREE.getZ()));
				}
			});
	}

	/** With replant False the sapling stays with the bot and the tree's spot stays air. */
	@GameTest(maxTicks = 560)
	public void fellWithoutReplantKeepsTheSapling(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
		expect(helper, bot, "import lib.trees\nlib.trees.fell(" + tree(helper) + ", replant=False)\n"
			+ "print(count('oak_log'), count('oak_sapling') > 0)\n", List.of("4 True"), () -> helper.assertBlockPresent(Blocks.AIR, TREE));
	}

	/** logs(2) mines the two lowest logs of the oak, picks them up and leaves the two above standing (#481). */
	@GameTest(maxTicks = 440)
	public void logsMinesTheLowestLogs(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(2)\nprint(r['ok'], count('oak_log'))\n", List.of("True 2"),
			() -> {
				helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(TREE.getX(), 3, TREE.getZ()));
				helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(TREE.getX(), 4, TREE.getZ()));
			});
	}

	/** A second oak nearer the bot than {@link #TREE}. */
	static final BlockPos NEAR_TREE = new BlockPos(2, 1, 6);

	/** Whether the logs of the trunk at {@code base} from y=1 stand up to {@code top} and not above it. */
	private static void assertTrunk(GameTestHelper helper, BlockPos base, Block log, int top) {
		for (int y = 1; y <= 4; y++) {
			BlockPos p = new BlockPos(base.getX(), y, base.getZ());
			if (y <= top) {
				helper.assertBlockPresent(log, p);
			} else {
				helper.assertBlockNotPresent(log, p);
			}
		}
	}

	/** logs(3) takes the three bottom logs of the nearest oak and leaves the other oak whole, not the lowest of each. */
	@GameTest(maxTicks = 440)
	public void logsTakesOneTreeAtATime(GameTestHelper helper) {
		plantTree(helper, NEAR_TREE, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(3)\nprint(r['ok'], count('oak_log'))\n", List.of("True 3"),
			() -> {
				assertTrunk(helper, TREE, Blocks.OAK_LOG, 4);
				helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(NEAR_TREE.getX(), 4, NEAR_TREE.getZ()));
				helper.assertBlockNotPresent(Blocks.OAK_LOG, NEAR_TREE);
			});
	}

	/** logs(4) needs the whole trunk, so it fells the tree and plants the sapling it carried where it stood. */
	@GameTest(maxTicks = 600)
	public void logsFellsATreeItNeedsAllOf(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(4)\nprint(r['ok'], count('oak_log'))\n", List.of("True 4"),
			() -> helper.assertBlockPresent(Blocks.OAK_SAPLING, TREE));
	}

	/** logs(2, kind="spruce_log") passes over the nearer oak for the spruce. */
	@GameTest(maxTicks = 360)
	public void logsOfAKindTakesOnlyThatTree(GameTestHelper helper) {
		helper.setBlock(TREE.below(), Blocks.STONE);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(TREE.getX(), y, TREE.getZ(), Blocks.AIR);
		}
		plantTree(helper, TREE, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES);
		plantTree(helper, NEAR_TREE, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(2, kind='spruce_log')\nprint(r['ok'], count('spruce_log'), count('oak_log'))\n",
			List.of("True 2 0"), () -> assertTrunk(helper, NEAR_TREE, Blocks.OAK_LOG, 4));
	}

	/** With no log within radius, logs fails with no_logs and what it lacks instead of looping. */
	@GameTest(maxTicks = 40)
	public void logsFailsWithNoLogsInRadius(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(1, radius=2)\nprint(r['ok'], r['reason'], r['missing'])\n",
			List.of("False no_logs {'#minecraft:logs': 1}"));
	}
}
