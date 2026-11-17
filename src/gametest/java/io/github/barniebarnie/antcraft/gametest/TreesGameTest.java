package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.at;
import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.expect;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.SenseApi;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

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
		plantTree(helper, base, log, leaves, true);
	}

	/**
	 * As {@link #plantTree(GameTestHelper, BlockPos, Block, Block)}; without {@code persistent} the leaves are a grown
	 * tree's, which lib.get breaks for saplings (#609), each at its distance from the logs, so none decays while they stand.
	 */
	static void plantTree(GameTestHelper helper, BlockPos base, Block log, Block leaves, boolean persistent) {
		helper.setBlock(base.below(), Blocks.DIRT);
		int b = base.getY();
		for (int y = b; y <= b + 3; y++) {
			helper.setBlock(base.getX(), y, base.getZ(), log);
		}
		for (int y = b + 2; y <= b + 4; y++) {
			int r = y == b + 4 ? 1 : 2;
			for (int x = base.getX() - r; x <= base.getX() + r; x++) {
				for (int z = base.getZ() - r; z <= base.getZ() + r; z++) {
					boolean corner = Math.abs(x - base.getX()) == r && Math.abs(z - base.getZ()) == r;
					if (!corner && helper.getBlockState(new BlockPos(x, y, z)).isAir()) {
						int distance = Math.abs(x - base.getX()) + Math.abs(z - base.getZ()) + Math.max(0, y - (b + 3));
						helper.setBlock(x, y, z, leaves.defaultBlockState().setValue(LeavesBlock.PERSISTENT, persistent)
							.setValue(LeavesBlock.DISTANCE, distance));
					}
				}
			}
		}
	}

	/** The oak's base as a script expression. */
	private static String tree(GameTestHelper helper) {
		return "Pos(" + at(helper, TREE.getX(), TREE.getY(), TREE.getZ()) + ")";
	}

	/**
	 * The sapling of a log: a table for the vanilla logs whose sapling is no {@code _sapling}, else {@code _sapling} where
	 * the game knows it as a sapling, else None, so the lumberjack gets none for an unknown log (#553).
	 */
	@GameTest(maxTicks = 20)
	public void saplingOfALog(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		expect(helper, bot, "from lib.trees import sapling\nprint(sapling('minecraft:mangrove_log'), sapling('minecraft:crimson_stem'), "
			+ "sapling('minecraft:oak_log'), sapling('minecraft:oak_wood'), sapling('mymod:ebony_log'))\n",
			List.of("minecraft:mangrove_propagule minecraft:crimson_fungus minecraft:oak_sapling None None"));
	}

	/** A wall of two logs on stone next to the bot is no tree; the oak further off is. */
	@GameTest(maxTicks = 20)
	public void nextTreeFindsATreeAndSkipsALogWall(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.OAK_LOG);
		helper.setBlock(1, 2, 1, Blocks.OAK_LOG);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.next_tree() == " + tree(helper) + ", lib.trees.is_tree(Pos("
			+ at(helper, 1, 1, 1) + ")))\n", List.of("True False"));
	}

	/**
	 * An oak just outside the test area, nearer the bot than the oak inside, is out of the bot's scans at the default
	 * radius (GameTests.hides, #618): next_tree finds the inside one.
	 */
	@GameTest(maxTicks = 20)
	public void nextTreeLeavesOutATreeOutsideTheTestArea(GameTestHelper helper) {
		plantTree(helper, new BlockPos(8, 1, 1), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 7, 1, 1);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.next_tree() == " + tree(helper) + ")\n", List.of("True"));
	}

	/**
	 * A crimson stem on netherrack under a nether wart block is a tree, as the nylium under a huge fungus's stem turns to
	 * netherrack (#575, #582); an oak log on netherrack under leaves is not.
	 */
	@GameTest(maxTicks = 20)
	public void aStemOnNetherrackIsATree(GameTestHelper helper) {
		helper.setBlock(1, 0, 1, Blocks.NETHERRACK);
		helper.setBlock(1, 1, 1, Blocks.CRIMSON_STEM);
		helper.setBlock(1, 2, 1, Blocks.CRIMSON_STEM);
		helper.setBlock(1, 3, 1, Blocks.NETHER_WART_BLOCK);
		helper.setBlock(3, 0, 1, Blocks.NETHERRACK);
		helper.setBlock(3, 1, 1, Blocks.OAK_LOG);
		helper.setBlock(3, 2, 1, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.is_tree(Pos(" + at(helper, 1, 1, 1) + ")), lib.trees.is_tree(Pos("
			+ at(helper, 3, 1, 1) + ")))\n", List.of("True False"));
	}

	/**
	 * A mangrove's trunk stands on its roots, not on dirt (#751): a mangrove log on mangrove roots under mangrove leaves is
	 * a tree, so the forester and next_tree find it.
	 */
	@GameTest(maxTicks = 20)
	public void aMangroveTrunkOnRootsIsATree(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.MANGROVE_ROOTS);
		helper.setBlock(1, 2, 1, Blocks.MANGROVE_LOG);
		helper.setBlock(1, 3, 1, Blocks.MANGROVE_LOG);
		helper.setBlock(1, 4, 1, Blocks.MANGROVE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.is_tree(Pos(" + at(helper, 1, 2, 1) + ")))\n", List.of("True"));
	}

	/**
	 * tree_logs keeps the logs that are part of a tree: the oak's four and a branch log touching its top only at a
	 * corner, and leaves out a wall of two logs on stone, so the lumberjack weighs an axe for trees alone (#563).
	 */
	@GameTest(maxTicks = 20)
	public void treeLogsLeavesOutALogWall(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.OAK_LOG);
		helper.setBlock(1, 2, 1, Blocks.OAK_LOG);
		helper.setBlock(6, 5, 6, Blocks.OAK_LOG);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		// The logs of this area alone: a scan reaches the logs of tests running beside it.
		StringBuilder logs = new StringBuilder("logs = [");
		for (int[] p : new int[][] {{1, 1, 1}, {1, 2, 1}, {5, 1, 5}, {5, 2, 5}, {5, 3, 5}, {5, 4, 5}, {6, 5, 6}}) {
			logs.append("Pos(").append(at(helper, p[0], p[1], p[2])).append("), ");
		}
		expect(helper, bot, "import lib.trees\n" + logs + "]\nprint(sorted([(p.x, p.y, p.z) for p in lib.trees.tree_logs(logs)]) == "
			+ "sorted([(p.x, p.y, p.z) for p in logs[2:]]))\n", List.of("True"));
	}

	/**
	 * A tree near the bot is found without reading the whole sphere of radius 32 (some 137,000 blocks of the block search
	 * budget every bot shares): next_tree looks within 8 blocks first, then 16, then its radius (#527).
	 */
	@GameTest(maxTicks = 20)
	public void nextTreeLooksNearTheBotFirst(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		GameTests.scansFreely(helper, bot);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.next_tree() == " + tree(helper) + ")\n", List.of("True"));
		ScriptRun run = bot.getScript();
		helper.onEachTick(() -> {
			if (run.executed() > 20_000 / SenseApi.BLOCKS_PER_INSTRUCTION) {
				helper.fail("read too much: " + run.executed() + " instructions, " + run.action());
			}
		});
	}

	/** low_log finds the oak's base near the bot without reading the whole sphere of radius 32, as next_tree does (#532). */
	@GameTest(maxTicks = 20)
	public void lowLogLooksNearTheBotFirst(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		GameTests.scansFreely(helper, bot);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.low_log() == " + tree(helper) + ")\n", List.of("True"));
		ScriptRun run = bot.getScript();
		helper.onEachTick(() -> {
			if (run.executed() > 20_000 / SenseApi.BLOCKS_PER_INSTRUCTION) {
				helper.fail("read too much: " + run.executed() + " instructions, " + run.action());
			}
		});
	}

	/** The oak's logs are in KEEP, so low_log passes over its base by the bot for a log further off (#532). */
	@GameTest(maxTicks = 20)
	public void lowLogSkipsATreeInKeep(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.OAK_LOG);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		expect(helper, bot, "import lib.trees\nfirst = lib.trees.low_log() == " + tree(helper) + "\n"
			+ "for p in lib.trees.tree_blocks(" + tree(helper) + "):\n    lib.trees.KEEP[p] = True\n"
			+ "print(first, lib.trees.low_log() == Pos" + at(helper, 1, 1, 1) + ")\n", List.of("True True"));
	}

	/** A {@code where} that rejects every base finds no tree; one that takes the oak's base finds it. */
	@GameTest(maxTicks = 20)
	public void nextTreeTakesOnlyABaseWherePasses(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, """
			import lib.trees
			def nowhere(p):
			    return False
			def here(p):
			    return p == %s
			print(lib.trees.next_tree(radius=8, where=nowhere), lib.trees.next_tree(radius=8, where=here) == %s)
			""".formatted(tree(helper), tree(helper)), List.of("None True"));
	}

	/** Within 2 blocks of the bot there is no tree; within 2 of a position by the oak, the oak (#513). */
	@GameTest(maxTicks = 20)
	public void nextTreeSearchesAroundAPosition(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.next_tree(2), lib.trees.next_tree(2, around=Pos"
			+ at(helper, 6, 1, 6) + ") == " + tree(helper) + ")\n", List.of("None True"));
	}

	/** SCAFFOLD is read from the item tag the walks pillar and bridge with, so a data pack that changes the tag changes it. */
	@GameTest(maxTicks = 20)
	public void scaffoldIsTheScaffoldTagsItems(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 0, 1, 0);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.SCAFFOLD == tag_items('#antcraft:scaffold_blocks'))\n", List.of("True"));
	}

	/** A wall of 73 logs on stone, all nearer the bot than the oak, does not hide the oak (#520). */
	@GameTest(maxTicks = 20)
	public void nextTreeFindsATreeBehindMoreThan64NearerLogs(GameTestHelper helper) {
		for (int x = 0; x <= 4; x++) {
			for (int z = 0; z <= 2; z++) {
				for (int y = 1; y <= 5; y++) {
					if (x != 0 || z != 0 || y > 2) {
						helper.setBlock(x, y, z, Blocks.OAK_LOG);
					}
				}
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 0, 1, 0);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.next_tree(9) == " + tree(helper) + ")\n", List.of("True"));
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

	/**
	 * Two dirt the script pillared up before fell_whole are not forgotten (#657): fell_whole takes back only what its own
	 * walks placed, so the script's pillar stands and placed_scaffold() still lists it after.
	 */
	// Measured: passes at tick 355.
	@GameTest(maxTicks = 720)
	public void fellWholeKeepsTheScaffoldPlacedBeforeIt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		expect(helper, bot, "import lib.trees\ngoto(Pos" + at(helper, 1, 3, 1) + ")\nr = lib.trees.fell_whole(" + tree(helper)
			+ ", False)\nprint(r['ok'], count('oak_log'), count('dirt'), len(placed_scaffold()))\n", List.of("True 4 14 2"), () -> {
				helper.assertBlockPresent(Blocks.DIRT, new BlockPos(1, 1, 1));
				helper.assertBlockPresent(Blocks.DIRT, new BlockPos(1, 2, 1));
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
	@GameTest(maxTicks = 290)
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

	/**
	 * A sapling that fell onto leaves is not lost to logs (#672): fell_whole leaves it there (#656), the leaves decay once
	 * the tree is down and it falls to the floor, and the next tree's felling picks it up. The first oak's leaves stand in
	 * as a column of three at (1, 1..3, 3) with a birch sapling on top; the test takes them away, as decay would but without
	 * loot, once the first oak (nearest the bot) is felled and replanted. Before #656 fell_whole pillared up to it: one birch
	 * sapling then too.
	 */
	// Measured: passes at tick 598 (with fell_whole's sapling pick-up off: printed True 8 0).
	@GameTest(maxTicks = 1200)
	public void logsPicksUpASaplingTheLeavesDropAfterAFelling(GameTestHelper helper) {
		plantTree(helper, NEAR_TREE, Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(1, y, 3, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		}
		ItemEntity high = helper.spawnItem(Items.BIRCH_SAPLING, new BlockPos(1, 4, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING, 2));
		AtomicBoolean decayed = new AtomicBoolean();
		helper.onEachTick(() -> {
			if (!decayed.get() && helper.getBlockState(NEAR_TREE).is(Blocks.OAK_SAPLING)) {
				helper.assertTrue(high.isAlive(), "the sapling on the leaves was picked up before they decayed");
				decayed.set(true);
				for (int y = 1; y <= 3; y++) {
					helper.setBlock(1, y, 3, Blocks.AIR);
				}
			}
		});
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(8)\nprint(r['ok'], count('oak_log'), count('birch_sapling'))\n",
			List.of("True 8 1"), () -> {
				helper.assertBlockPresent(Blocks.OAK_SAPLING, NEAR_TREE);
				helper.assertBlockPresent(Blocks.OAK_SAPLING, TREE);
			});
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
	@GameTest(maxTicks = 20)
	public void logsFailsWithNoLogsInRadius(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(1, radius=2)\nprint(r['ok'], r['reason'], r['missing'])\n",
			List.of("False no_logs {'#minecraft:logs': 1}"));
	}

	/**
	 * Clears the oak every test starts with and builds a 2x2 spruce with its north-west log at (3, 1, 3), six logs high on
	 * dirt, with a branch of two logs that each touch the last only at a corner: (5, 4, 2) and (6, 5, 1). No leaves, so
	 * nothing hides a log.
	 */
	static void plantGiant(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
				for (int y = 1; y < 8; y++) {
					helper.setBlock(x, y, z, Blocks.AIR);
				}
			}
		}
		for (int x = 3; x <= 4; x++) {
			for (int z = 3; z <= 4; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
				for (int y = 1; y <= 6; y++) {
					helper.setBlock(x, y, z, Blocks.SPRUCE_LOG);
				}
			}
		}
		helper.setBlock(5, 4, 2, Blocks.SPRUCE_LOG);
		helper.setBlock(6, 5, 1, Blocks.SPRUCE_LOG);
	}

	/**
	 * tree_blocks of the giant spruce gives its 24 trunk logs and two branch logs, and not the spruce of three logs a block
	 * west of it; is_giant says its trunk is 2x2 and the small one's not (#510).
	 */
	@GameTest(maxTicks = 30)
	public void treeBlocksGivesTheLogsOfOneGiantTree(GameTestHelper helper) {
		plantGiant(helper);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(1, y, 3, Blocks.SPRUCE_LOG);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, """
			import lib.trees
			b = lib.trees.tree_blocks(Pos%s)
			print(len(b), all([lib.trees.is_kind(p, 'spruce_log') for p in b]), Pos%s in b, Pos%s in b)
			print(lib.trees.is_giant(Pos%s), lib.trees.is_giant(Pos%s))
			""".formatted(at(helper, 4, 1, 4), at(helper, 6, 5, 1), at(helper, 1, 1, 3), at(helper, 4, 1, 4), at(helper, 1, 1, 3)),
			List.of("26 True True False", "True False"));
	}

	/**
	 * fell_whole fells the giant spruce: no log left, all 26 in the bot's inventory, the scaffold it stood on taken back so
	 * no dirt stands above the floor and it carries its six again (it placed no more than those), and its four spruce
	 * saplings planted where the trunk stood (#510).
	 */
	@GameTest(maxTicks = 1400)
	public void fellWholeFellsAGiantTreeAndTakesItsScaffoldBack(GameTestHelper helper) {
		plantGiant(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 6));
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		bot.getInventory().addItem(new ItemStack(Items.SPRUCE_SAPLING, 4));
		expect(helper, bot, "import lib.trees\nr = lib.trees.fell_whole(Pos" + at(helper, 3, 1, 3) + ")\n"
			+ "print(r['ok'], count('spruce_log'), count('dirt'), count('spruce_sapling'))\n", List.of("True 26 6 0"),
			() -> assertGiantFelled(helper));
	}

	/**
	 * A dirt block another player puts next to the giant spruce while the bot fells it is left standing: fell_whole takes
	 * back only the scaffold its walks placed (placed_scaffold()), not every new block of the scaffold kinds (#533).
	 */
	@GameTest(maxTicks = 1400)
	public void fellWholeLeavesDirtSomeoneElsePlacedMeanwhile(GameTestHelper helper) {
		plantGiant(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 6));
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		bot.getInventory().addItem(new ItemStack(Items.SPRUCE_SAPLING, 4));
		BlockPos theirs = new BlockPos(7, 1, 7);
		// Once the bot placed its first scaffold block, so after anything fell_whole looks at before it walks.
		AtomicBoolean placed = new AtomicBoolean();
		helper.onEachTick(() -> {
			if (GameTests.carried(bot, Items.DIRT) < 6 && !placed.getAndSet(true)) {
				helper.setBlock(theirs, Blocks.DIRT);
			}
		});
		expect(helper, bot, "import lib.trees\nr = lib.trees.fell_whole(Pos" + at(helper, 3, 1, 3) + ")\n"
			+ "print(r['ok'], count('spruce_log'), count('dirt'), count('spruce_sapling'))\n", List.of("True 26 6 0"), () -> {
				helper.assertBlockPresent(Blocks.DIRT, theirs);
				assertGiantFelled(helper, theirs);
			});
	}

	/**
	 * The bot's first scaffold block, mined and put back as dirt by someone else in the same tick, is theirs now: the bot
	 * leaves it standing though it is dirt where its walk placed dirt (#539).
	 */
	@GameTest(maxTicks = 1400)
	public void fellWholeLeavesAScaffoldSpotSomeoneElseReplaced(GameTestHelper helper) {
		plantGiant(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 6));
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		bot.getInventory().addItem(new ItemStack(Items.SPRUCE_SAPLING, 4));
		BlockPos[] theirs = new BlockPos[1];
		helper.onEachTick(() -> {
			if (theirs[0] == null && GameTests.carried(bot, Items.DIRT) < 6) {
				theirs[0] = BlockPos.betweenClosedStream(0, 1, 0, 7, 7, 7)
					.filter(p -> helper.getBlockState(p).is(Blocks.DIRT)).findFirst().orElseThrow().immutable();
				helper.setBlock(theirs[0], Blocks.AIR);
				helper.setBlock(theirs[0], Blocks.DIRT);
			}
		});
		expect(helper, bot, "import lib.trees\nr = lib.trees.fell_whole(Pos" + at(helper, 3, 1, 3) + ")\n"
			+ "print(r['ok'], count('spruce_log'), count('dirt'))\n", List.of("True 26 5"), () -> {
				helper.assertBlockPresent(Blocks.DIRT, theirs[0]);
				assertGiantFelled(helper, theirs[0]);
			});
	}

	/**
	 * A fell that stops because the logs no longer fit still takes back the scaffold it placed: dirt fits on the bot's
	 * stack of dirt where no log does (#539).
	 */
	@GameTest(maxTicks = 340)
	public void fellWholeTakesBackItsScaffoldWhenItStopsForNoSpace(GameTestHelper helper) {
		plantGiant(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 6));
		// No axe: one taken into the hand would free a slot for the logs.
		while (bot.getInventory().canAddItem(new ItemStack(Items.SPRUCE_LOG))) {
			bot.getInventory().addItem(new ItemStack(Items.STICK, 64));
		}
		expect(helper, bot, "import lib.trees\nr = lib.trees.fell_whole(Pos" + at(helper, 3, 1, 3) + ")\n"
			+ "print(r['ok'], r['reason'], count('dirt'))\n", List.of("False no_space 6"), () -> {
				for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
					helper.assertBlockNotPresent(Blocks.DIRT, p);
				}
			});
	}

	/**
	 * take_back goes on while a round takes a block back (#637): placed_scaffold(False), stood in for, lists one dirt block
	 * more a round, as walks that each place one more would, and leaves out those mined (#657), six rounds in all; every
	 * one is taken back, not the first four. Then
	 * a round that takes nothing back (a spot left air every round) ends it. Passes at about tick 110.
	 */
	@GameTest(maxTicks = 220)
	public void takeBackGoesOnWhileARoundTakesABlockBack(GameTestHelper helper) {
		StringBuilder spots = new StringBuilder();
		for (int x = 1; x <= 6; x++) {
			helper.setBlock(x, 1, 5, Blocks.DIRT);
			spots.append("Pos").append(at(helper, x, 1, 5)).append(", ");
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 2);
		expect(helper, bot, "import lib.trees\nspots = [" + spots + "]\ncalls = [0]\n"
			+ "def one(forget=True, keep=None):\n    if keep != None:\n        return []\n    calls[0] += 1\n    return [p for p in spots[:calls[0]] if block(p).id == 'minecraft:dirt']\n"
			+ "lib.trees.placed_scaffold = one\nlib.trees.take_back()\nprint(count('dirt'), calls[0])\n"
			+ "calls[0] = 0\n"
			+ "def air(forget=True, keep=None):\n    if keep != None:\n        return []\n    calls[0] += 1\n    return [Pos" + at(helper, 1, 3, 1) + "]\n"
			+ "lib.trees.placed_scaffold = air\nlib.trees.take_back()\nprint(calls[0])\n",
			List.of("6 7", "1"), () -> {
				for (int x = 1; x <= 6; x++) {
					helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(x, 1, 5));
				}
			});
	}

	/**
	 * take_back forgets the scaffold it could not reach once it is done, but what keep lists (#671): the bot pillars out of a
	 * bedrock pit on one cobblestone, which the test then seals in bedrock. take_back(keep) with it kept leaves it listed;
	 * take_back() tries it, cannot reach it and forgets it, so a later take_back does not try it again.
	 */
	@GameTest(maxTicks = 60)
	public void takeBackForgetsTheScaffoldItCouldNotReach(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				for (int y = 1; y <= 2; y++) {
					helper.setBlock(x, y, z, x == 2 && z == 2 ? Blocks.AIR : Blocks.BEDROCK);
				}
			}
		}
		helper.setBlock(2, 0, 2, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 4));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		helper.onEachTick(() -> {
			if (WorkGameTest.printed(bot).contains("out")) {
				helper.setBlock(2, 2, 2, Blocks.BEDROCK);
			}
		});
		expect(helper, bot, "import lib.trees\ngoto(Pos" + at(helper, 2, 3, 4) + ")\nprint('out')\nwait(2)\n"
			+ "k = placed_scaffold(False)\nlib.trees.take_back(k)\nkept = placed_scaffold(False) == k\n"
			+ "lib.trees.take_back()\nprint(len(k), kept, placed_scaffold(False))\n",
			List.of("out", "1 True []"), () -> helper.assertBlockPresent(Blocks.COBBLESTONE, 2, 1, 2));
	}

	/**
	 * take_back does not climb on a lone scaffold block high over air (#739): the bot pillars up 9 and the test takes away
	 * the pillar under its top block. mine will not break the block the bot stands on over that drop, so its walk placed a
	 * block beside and over it to mine it from, which the next round took back the same way, one up a round to the 32nd;
	 * a bot respawned by a giant jungle climbed so from its dead run's scaffold to the top of the test area and died of the
	 * fall down. take_back now ends once a round's walks placed scaffold over all it had left, and first drops the bot off
	 * a lone block it rests on to the ground and mines the block from there (#758): no dirt is left in the air.
	 */
	// Measured: passes at tick 230 (red: climbed to 13 on tick 236; with a 6-block drop mine dropped off, no climb; since
	// #739 the dirt it climbed onto was left at (3, 10, 2)).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 460)
	public void takeBackDoesNotClimbOnALoneBlockOverAir(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		// Cut once: the pillar it takes the block back from (#758) may stand in that column.
		boolean[] cut = {false};
		helper.onEachTick(() -> {
			if (!cut[0] && WorkGameTest.printed(bot).contains("up")) {
				cut[0] = true;
				for (int y = 1; y <= 8; y++) {
					helper.setBlock(3, y, 3, Blocks.AIR);
				}
			}
			if (TestPositions.relative(helper, bot.blockPosition()).getY() > 11) {
				helper.fail("climbed to " + TestPositions.relative(helper, bot.blockPosition()).toShortString());
			}
		});
		expect(helper, bot, "import lib.trees\nr = goto(Pos" + at(helper, 3, 10, 3) + ")\nprint('up')\nwait(2)\n"
			+ "lib.trees.take_back()\nprint(r.ok)\n", List.of("up", "True"), () -> {
				for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 23, 15, 23)) {
					helper.assertBlockNotPresent(Blocks.DIRT, p);
				}
			});
	}

	/**
	 * take_back does not drop the bot into a hazard under the scaffold it stands on (#845, #851): the bot stands on a dirt
	 * block over a ripe berry bush, which placed_scaffold, stood in for, lists. It steps off and mines the block from beside,
	 * then walks away, never hurt: mined from on top, it fell into the bush and was hurt walking out.
	 */
	// Measured: passes at tick 37 (red: hurt on tick 21).
	@GameTest(maxTicks = 80)
	public void takeBackOverABerryBushLeavesTheBotUnhurt(GameTestHelper helper) {
		helper.setBlock(3, 0, 3, Blocks.GRASS_BLOCK);
		helper.setBlock(3, 1, 3, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
		helper.setBlock(3, 2, 3, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 3, 3);
		helper.onEachTick(() -> helper.assertTrue(bot.getHealth() == bot.getMaxHealth(), "hurt at " + bot.position()));
		expect(helper, bot, "import lib.trees\nq = Pos" + at(helper, 3, 2, 3) + "\n"
			+ "def one(forget=True, keep=None):\n    return [q] if keep == None and block(q).id == 'minecraft:dirt' else []\n"
			+ "lib.trees.placed_scaffold = one\nlib.trees.take_back()\nr = goto(Pos" + at(helper, 1, 1, 1) + ")\n"
			+ "print(block(q).id, r.ok)\n", List.of("minecraft:air True"));
	}

	/** No spruce log and no dirt above the floor, and a spruce sapling on each spot of the giant's trunk. */
	static void assertGiantFelled(GameTestHelper helper) {
		assertGiantFelled(helper, null);
	}

	/** As {@link #assertGiantFelled(GameTestHelper)}, but the dirt at {@code dirt} stays. */
	static void assertGiantFelled(GameTestHelper helper, @Nullable BlockPos dirt) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				for (int y = 1; y < 8; y++) {
					BlockPos p = new BlockPos(x, y, z);
					helper.assertBlockNotPresent(Blocks.SPRUCE_LOG, p);
					if (!p.equals(dirt)) {
						helper.assertBlockNotPresent(Blocks.DIRT, p);
					}
				}
			}
		}
		for (int x = 3; x <= 4; x++) {
			for (int z = 3; z <= 4; z++) {
				helper.assertBlockPresent(Blocks.SPRUCE_SAPLING, new BlockPos(x, 1, z));
			}
		}
	}

	/**
	 * logs(26) needs all of the giant spruce, crowned with leaves so it is a tree, so it fells it whole with fell_whole,
	 * scaffold taken back and four saplings planted, rather than as four trees of one column each (#510).
	 */
	@GameTest(maxTicks = 1400)
	public void logsFellsAGiantTreeItNeedsAllOfWhole(GameTestHelper helper) {
		plantGiant(helper);
		crownGiant(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 6));
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		bot.getInventory().addItem(new ItemStack(Items.SPRUCE_SAPLING, 4));
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(26, kind='spruce_log')\nprint(r['ok'], count('spruce_log'), count('dirt'))\n",
			List.of("True 26 6"), () -> assertGiantFelled(helper));
	}

	/**
	 * A jungle giant of 100 logs: a 2x2 trunk 22 high at (3..4, 1..22, 3..4) on dirt, and from each corner of its top a
	 * branch of three logs that touch only at a corner, out to the area's corners at y=22. It stands up past the 8x8x8 area,
	 * so the air over the area up to y=30 is cleared.
	 */
	static void plantJungleGiant(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
				for (int y = 1; y <= 30; y++) {
					helper.setBlock(x, y, z, Blocks.AIR);
				}
			}
		}
		for (int x = 3; x <= 4; x++) {
			for (int z = 3; z <= 4; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
				for (int y = 1; y <= 22; y++) {
					helper.setBlock(x, y, z, Blocks.JUNGLE_LOG);
				}
			}
		}
		for (int sx : new int[] {-1, 1}) {
			for (int sz : new int[] {-1, 1}) {
				for (int i = 1; i <= 3; i++) {
					helper.setBlock((sx < 0 ? 3 : 4) + sx * i, 20 + i - 1, (sz < 0 ? 3 : 4) + sz * i, Blocks.JUNGLE_LOG);
				}
			}
		}
	}

	/**
	 * A stand needs room for the bot's head (#652): of the air beside a log at (1, 3, 1), clear of the oak, only (1, 3, 2)
	 * has air over it, leaves over the rest, over the log and under it. A log at (1, 3, 6) with leaves over every spot
	 * beside it and over it is mined from under it, the bot's head under the log. A walk to a spot under leaves got
	 * nowhere, so a dark oak's top log stayed once the logs under it were mined.
	 */
	@GameTest(maxTicks = 20)
	public void aStandHasRoomForTheHead(GameTestHelper helper) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
		helper.setBlock(1, 3, 1, Blocks.OAK_LOG);
		for (BlockPos p : List.of(new BlockPos(1, 4, 1), new BlockPos(0, 4, 1), new BlockPos(2, 4, 1), new BlockPos(1, 4, 0),
				new BlockPos(1, 2, 1))) {
			helper.setBlock(p, leaves);
		}
		helper.setBlock(1, 3, 6, Blocks.OAK_LOG);
		for (BlockPos p : List.of(new BlockPos(1, 4, 6), new BlockPos(0, 4, 6), new BlockPos(2, 4, 6), new BlockPos(1, 4, 5),
				new BlockPos(1, 4, 7))) {
			helper.setBlock(p, leaves);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 1, 1);
		expect(helper, bot, "import lib.trees\nprint(lib.trees.stands([Pos" + at(helper, 1, 3, 1) + "]) == [Pos" + at(helper, 1, 3, 2)
			+ "], lib.trees.stands([Pos" + at(helper, 1, 3, 6) + "]) == [Pos" + at(helper, 1, 1, 6) + "])\n", List.of("True True"));
	}

	/**
	 * What tree_blocks and stands cost on a jungle giant of 100 logs (#522), and the seven stands they give. Measured at
	 * 1,793,000 instructions (897 ticks) before #522 and 507,000 (254 ticks) after; the limit keeps it there.
	 */
	@GameTest(maxTicks = 600)
	public void treeBlocksAndStandsOfAHundredLogTree(GameTestHelper helper) {
		plantJungleGiant(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		GameTests.cost(helper, bot, "import lib.trees\n", "b = lib.trees.tree_blocks(Pos" + at(helper, 3, 1, 3) + ")\n"
			+ "s = lib.trees.stands(b)\nprint(len(b), sorted([(c.x - b[0].x, c.y - b[0].y, c.z - b[0].z) for c in s]))\n", List.of("100 [(-1, 20, -1), (-1, 20, 2), (1, 2, 2), (1, 9, 2), (1, 13, 2), (2, 20, -1), (2, 20, 2)]"),
			"tree_blocks and stands of 100 logs", 700_000);
	}

	/**
	 * A bot with 16 dirt and a stone axe, no log, by a giant spruce grown as the game does with seed 6 (a 2x2 trunk of 24
	 * at (10..11, 1..24, 10..11)), which turns the dirt round it to podzol: logs(4) takes its bottom logs. Podzol is not in
	 * #minecraft:dirt, so the tree stood on no GROUND and logs gave no_logs at once (#801).
	 */
	// Measured: passes at tick 74 (red: no_logs at tick 23).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 160)
	public void logsTakesAFewLogsOfAGrownGiantSpruce(GameTestHelper helper) {
		growGiantSpruce(helper, 6);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 7, 1, 7);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(4, kind='spruce_log')\nprint(r['ok'], r['reason'], count('spruce_log'))\n",
			List.of("True None 4"));
	}

	/**
	 * The giant spruce of {@link #logsTakesAFewLogsOfAGrownGiantSpruce} with all its leaves removed, as walks that cleared
	 * its crown leave it: a 2x2 trunk is still a tree, so logs(4) takes its bottom logs rather than giving no_logs (#801).
	 */
	// Measured: passes at tick 74 (red: no_logs at tick 25 once podzol was GROUND).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 160)
	public void logsTakesTheLogsOfAGiantSpruceWithoutLeaves(GameTestHelper helper) {
		growGiantSpruce(helper, 6);
		for (int x = 0; x < 24; x++) {
			for (int y = 1; y < 40; y++) {
				for (int z = 0; z < 24; z++) {
					if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.SPRUCE_LEAVES)) {
						helper.setBlock(x, y, z, Blocks.AIR);
					}
				}
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 7, 1, 7);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		expect(helper, bot, "import lib.trees\nr = lib.trees.logs(4, kind='spruce_log')\nprint(r['ok'], r['reason'], count('spruce_log'))\n",
			List.of("True None 4"));
	}

	/** Four spruce saplings on dirt at (10..11, 1, 10..11), grown into a giant spruce with the random of seed. */
	static void growGiantSpruce(GameTestHelper helper, long seed) {
		for (int x = 10; x <= 11; x++) {
			for (int z = 10; z <= 11; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
				helper.setBlock(x, 1, z, Blocks.SPRUCE_SAPLING);
			}
		}
		BlockPos at = helper.absolutePos(new BlockPos(10, 1, 10));
		BlockState sapling = helper.getLevel().getBlockState(at);
		((SaplingBlock) sapling.getBlock()).advanceTree(helper.getLevel(), at, sapling.setValue(SaplingBlock.STAGE, 1),
			RandomSource.create(seed));
		helper.assertBlockPresent(Blocks.SPRUCE_LOG, new BlockPos(10, 1, 10));
		helper.assertBlockPresent(Blocks.SPRUCE_LOG, new BlockPos(11, 1, 11));
	}

	/** Persistent spruce leaves over the giant spruce of {@link #plantGiant}, so it is a tree. */
	static void crownGiant(GameTestHelper helper) {
		for (int x = 2; x <= 5; x++) {
			for (int z = 2; z <= 5; z++) {
				helper.setBlock(x, 7, z, Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
			}
		}
	}

	/**
	 * fell_whole picks up a sapling on the floor but does not pillar up to one on three persistent leaves (#656): of a
	 * poplar, pillaring up to each sapling its decaying leaves let fall on the leaves took up to 800 ticks. It checks the two
	 * saplings, not how many the bot carries: items_near sees dropped items outside the test's area, and a neighbouring
	 * test's sapling on the floor within 8 is picked up too (#780).
	 */
	// Measured: passes at tick 133 (red: the sapling on the leaves picked up, it pillared up to it).
	@GameTest(maxTicks = 270)
	public void fellWholeDoesNotPillarToASaplingOnLeaves(GameTestHelper helper) {
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(1, y, 1, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		}
		ItemEntity high = helper.spawnItem(Items.OAK_SAPLING, new BlockPos(1, 4, 1));
		ItemEntity low = helper.spawnItem(Items.OAK_SAPLING, new BlockPos(1, 1, 6));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 7, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 8));
		expect(helper, bot, "import lib.trees\nr = lib.trees.fell_whole(" + tree(helper) + ", False)\n"
			+ "print(r['ok'], count('oak_sapling') >= 1, count('dirt'))\n", List.of("True True 8"), () -> {
				helper.assertFalse(low.isAlive(), "the sapling on the floor was not picked up");
				helper.assertTrue(high.isAlive(), "the sapling on the leaves was picked up");
				helper.assertBlockNotPresent(Blocks.OAK_LOG, TREE.above(3));
			});
	}
}
