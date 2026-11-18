package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
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
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code jobs/mushroom_farm.py} (#836; docs/cookbook/mushroom-farm.md): a mushroom farm on lib.cellfarm, its cells a row
 * of three podzol, a brown mushroom on the west one and a red one on the east one, which spread onto the middle one. Each
 * test's farm has its corner at (1, 1, 3), so cell 1 is the floor from (1, 0, 3) to (3, 0, 3); the depot's first chest is
 * at (1, 1, 1); the huge mushroom test's at (8, 1, 8) in a tall area instead. A mushroom spreads on some 1 in 34000 ticks,
 * so the tests that harvest put the spread one themselves.
 */
public class MushroomFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A supply chest of a cell's podzol and mushrooms, the bot by it, and jobs.mushroom_farm run with one cell. */
	private static AntBot farm(GameTestHelper helper) {
		return farm(helper, new BlockPos(1, 1, 3), new BlockPos(6, 1, 6));
	}

	/** farm with the farm's corner at corner, the supply chest at supply and the bot 2 west of it, extra in the chest. */
	private static AntBot farm(GameTestHelper helper, BlockPos corner, BlockPos supply, ItemStack... extra) {
		return run(helper, corner, supply, extra).bot();
	}

	/** farm's bot and its script run. */
	private record Farm(AntBot bot, ScriptRun run) {
		/** What the bot carries, where it is and the call its script runs: which step it is stuck in (#852). */
		String where(GameTestHelper helper) {
			return BotScripts.carries(bot) + " at " + TestPositions.relative(helper, bot.position()) + ", running "
				+ run.action() + " on line " + run.line() + ", last " + run.lastResult();
		}
	}

	private static Farm run(GameTestHelper helper, BlockPos corner, BlockPos supply, ItemStack... extra) {
		helper.setBlock(supply, Blocks.CHEST);
		Container chest = helper.getBlockEntity(supply, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.PODZOL, 3));
		chest.setItem(1, new ItemStack(Items.BROWN_MUSHROOM, 1));
		chest.setItem(2, new ItemStack(Items.RED_MUSHROOM, 1));
		chest.setItem(3, new ItemStack(Items.STONE_PICKAXE));
		chest.setItem(4, new ItemStack(Items.CHEST, 3)); // the cobblestone dug goes in a chest of its own
		for (int i = 0; i < extra.length; i++) {
			chest.setItem(5 + i, extra[i]);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, supply.getX() - 2, supply.getY(), supply.getZ());
		BlockPos at = helper.absolutePos(corner);
		ScriptRun run = BotScripts.run(bot, """
			import jobs.mushroom_farm
			jobs.mushroom_farm.run(Pos(%d, %d, %d), 1, 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnError(helper, bot);
		// The cobblestone dug building a cell has a depot chest to go in (#859).
		helper.onEachTick(() -> {
			if (said(bot, "store:")) {
				helper.fail("said " + BotScripts.log(bot, Output.Level.PRINT));
			}
		});
		return new Farm(bot, run);
	}

	private static boolean said(AntBot bot, String what) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(l -> l.contains(what));
	}

	/** The cell's podzol, with a brown mushroom on its west spot and a red one on its east spot. */
	private static void assertKept(GameTestHelper helper) {
		for (int x = 1; x <= 3; x++) {
			helper.assertBlockPresent(Blocks.PODZOL, x, 0, 3);
		}
		helper.assertBlockPresent(Blocks.BROWN_MUSHROOM, 1, 1, 3);
		helper.assertBlockPresent(Blocks.RED_MUSHROOM, 3, 1, 3);
	}

	/** On a lit stone floor: the cell's spots get podzol, which a mushroom grows on in any light, and are planted. */
	// Measured: passes at tick 69 (red: No module named 'jobs.mushroom_farm').
	@GameTest(maxTicks = 140)
	public void aCellIsBuiltOnPodzolAndPlanted(GameTestHelper helper) {
		AntBot bot = farm(helper);
		helper.succeedWhen(() -> {
			assertKept(helper);
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * The cobblestone dug building the cell goes in a depot chest of its own, from the supply chest's chests (#859): the
	 * tests were seen saying "store: no_logs, kept {'minecraft:cobblestone': 3}", on which run now fails at once.
	 */
	// Measured: passes at tick 179 (green already: not seen to say it in four runs of the class).
	@GameTest(maxTicks = 360)
	public void dugStoneIsStored(GameTestHelper helper) {
		AntBot bot = farm(helper);
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			int stored = 0;
			for (int x = 1; x < 5; x++) {
				if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
					stored += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(Items.COBBLESTONE);
				}
			}
			helper.assertTrue(stored == 3 && bot.getInventory().countItem(Items.COBBLESTONE) == 0,
				stored + " cobblestone stored; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/** A mushroom spread onto the cell's middle spot is harvested and stored; the two it spread from are left. */
	// Measured: passes at tick 181.
	@GameTest(maxTicks = 360)
	public void spreadMushroomsAreHarvestedAFewLeft(GameTestHelper helper) {
		AntBot bot = farm(helper);
		boolean[] spread = new boolean[1];
		helper.onEachTick(() -> {
			if (!spread[0] && said(bot, "built cell 1")) {
				helper.setBlock(2, 1, 3, Blocks.BROWN_MUSHROOM);
				spread[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(spread[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			assertKept(helper);
			helper.assertBlockPresent(Blocks.AIR, 2, 1, 3);
			int stored = 0;
			for (int x = 1; x < 5; x++) {
				if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
					stored += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(Items.BROWN_MUSHROOM);
				}
			}
			helper.assertTrue(stored >= 1, "no mushroom in the depot; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * Bone meal from the chests grows a huge mushroom on a kept mushroom, which is mined and its mushrooms stored (#842).
	 * The cap reaches 3 blocks round its stem and up to 7 over the floor, so a tall area with the cell in its middle. A
	 * use grows it on 2 in 5, so 64 bone meal leave no room for chance. More than 3 mushrooms stored come only from a cap:
	 * the cell's two spread on some 1 in 34000 ticks. The dirt is scaffold for one of the tall kind (1 in 12), up to 13
	 * over the floor: the 3 cobblestone the cell dug climbed only a short one (#852).
	 */
	// Measured: passes at tick 658 to 991, the felling some 300 of it (red: no huge mushroom grew); a tall one's felling
	// takes some 1000 (aTallHugeMushroom...), so the limit leaves room for one.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 3000)
	public void boneMealGrowsAHugeMushroomWhichIsMinedAndStored(GameTestHelper helper) {
		Farm farm = run(helper, new BlockPos(8, 1, 8), new BlockPos(12, 1, 11), new ItemStack(Items.BONE_MEAL, 64),
			new ItemStack(Items.DIRT, 16));
		AntBot bot = farm.bot();
		boolean[] grew = new boolean[1];
		helper.onEachTick(() -> grew[0] |= helper.getBlockState(new BlockPos(8, 1, 8)).is(Blocks.MUSHROOM_STEM)
			|| helper.getBlockState(new BlockPos(10, 1, 8)).is(Blocks.MUSHROOM_STEM));
		helper.succeedWhen(() -> {
			helper.assertTrue(grew[0], "no huge mushroom grew; said " + BotScripts.log(bot, Output.Level.PRINT));
			assertCapStored(helper, farm);
		});
	}

	/**
	 * A huge mushroom of the tall kind, which bone meal grows on 1 in 12, is felled with scaffold from the chests, its
	 * mushrooms stored (#852): the bot took 8 scaffold at most, too few to climb its stem, and spent the test walking
	 * under its cap. The test grows it once the cell is built: a stem 13 high on the brown spot, a brown cap over it.
	 */
	// Measured: passes at tick 1060 to 1098 (red: harvest: out_of_reach at a cap block, 2 mushrooms stored on tick 2002).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 2200)
	public void aTallHugeMushroomIsFelledWithScaffoldFromTheChests(GameTestHelper helper) {
		Farm farm = run(helper, new BlockPos(8, 1, 8), new BlockPos(12, 1, 11), new ItemStack(Items.DIRT, 16));
		long[] grew = growOnceBuilt(helper, farm, 13);
		helper.succeedWhen(() -> {
			helper.assertTrue(grew[0] > 0, "cell 1 not built; said " + BotScripts.log(farm.bot(), Output.Level.PRINT));
			assertCapStored(helper, farm);
		});
	}

	/**
	 * A huge mushroom taller than the scaffold the chests hold is left standing and the shortage said (#859): the bot
	 * climbed what it had, failed out_of_reach at a cap block and left the cap half mined and its drops on the ground.
	 */
	// Measured: passes at tick 183 (red: nothing said by tick 602, the bot climbing its 3 cobblestone).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 400)
	public void aHugeMushroomTooTallForTheScaffoldIsLeft(GameTestHelper helper) {
		Farm farm = run(helper, new BlockPos(8, 1, 8), new BlockPos(12, 1, 11));
		long[] grew = growOnceBuilt(helper, farm, 13);
		helper.onEachTick(() -> {
			if (said(farm.bot(), "out_of_reach")) {
				helper.fail("said " + BotScripts.log(farm.bot(), Output.Level.PRINT));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grew[0] > 0 && said(farm.bot(), "short of scaffold"),
				"said " + BotScripts.log(farm.bot(), Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.BROWN_MUSHROOM_BLOCK, 8, 14, 8);
		});
	}

	/**
	 * With an axe in the supply chest, a huge mushroom is felled faster than by hand (#859): the bot takes the axe for it.
	 * The test grows one once the cell is built, a stem 5 high on the brown spot and a brown cap over it, and fails where
	 * its blocks are not all gone within FELL_BY_AXE ticks of that.
	 */
	// Measured: passes at tick 383, the felling 269 ticks (red: by hand, not felled within 370; 468 ticks).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 800)
	public void aHugeMushroomIsFelledWithAnAxeFromTheChests(GameTestHelper helper) {
		Farm farm = run(helper, new BlockPos(8, 1, 8), new BlockPos(12, 1, 11), new ItemStack(Items.DIRT, 16),
			new ItemStack(Items.STONE_AXE));
		long[] grew = growOnceBuilt(helper, farm, 5);
		helper.onEachTick(() -> {
			if (grew[0] > 0 && helper.getTick() - grew[0] >= FELL_BY_AXE && !hugeGone(helper, 5)) {
				helper.fail("not felled within " + FELL_BY_AXE + " ticks; " + farm.where(helper));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grew[0] > 0 && hugeGone(helper, 5), "not felled; " + farm.where(helper));
			assertCapStored(helper, farm);
		});
	}

	/** Between the some 270 ticks a felling with an axe takes and the some 470 by hand, the round it waits for included. */
	private static final int FELL_BY_AXE = 370;

	/**
	 * Grows a huge mushroom on the brown spot once the bot said it built cell 1: a stem height high at (8, 1, 8) and a brown
	 * cap over it. Gives the tick it grew on, 0 till then.
	 */
	private static long[] growOnceBuilt(GameTestHelper helper, Farm farm, int height) {
		long[] grew = new long[1];
		helper.onEachTick(() -> {
			if (grew[0] == 0 && said(farm.bot(), "built cell 1")) {
				for (int y = 1; y <= height; y++) {
					helper.setBlock(8, y, 8, Blocks.MUSHROOM_STEM);
				}
				for (int x = 5; x <= 11; x++) {
					for (int z = 5; z <= 11; z++) {
						if (Math.abs(x - 8) + Math.abs(z - 8) < 6) { // no corners, as vanilla's cap
							helper.setBlock(x, height + 1, z, Blocks.BROWN_MUSHROOM_BLOCK);
						}
					}
				}
				grew[0] = helper.getTick();
			}
		});
		return grew;
	}

	/** Whether growOnceBuilt's huge mushroom of height is all gone. */
	private static boolean hugeGone(GameTestHelper helper, int height) {
		for (int x = 5; x <= 11; x++) {
			for (int z = 5; z <= 11; z++) {
				for (int y = 1; y <= height + 1; y++) {
					var s = helper.getBlockState(new BlockPos(x, y, z));
					if (s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** More than 3 mushrooms in the depot's chests, along its row east of (8, 1, 6): only a huge mushroom's cap gives those. */
	private static void assertCapStored(GameTestHelper helper, Farm farm) {
		int stored = 0;
		for (int x = 8; x < 12; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 6)).is(Blocks.CHEST)) {
				ChestBlockEntity c = helper.getBlockEntity(new BlockPos(x, 1, 6), ChestBlockEntity.class);
				stored += c.countItem(Items.BROWN_MUSHROOM) + c.countItem(Items.RED_MUSHROOM);
			}
		}
		helper.assertTrue(stored > 3, stored + " mushrooms stored; said " + BotScripts.log(farm.bot(), Output.Level.PRINT)
			+ "; " + farm.where(helper));
	}

	/**
	 * With no bone meal anywhere, tend does not look in the chests for it every round (#859): after a miss it skips 1, 2,
	 * 4 rounds and on. 16 rounds of tend on a cell with nothing to plant, lib.stock.take_up_to stood in for to count the
	 * bone meal lookups.
	 */
	// Measured: passes at tick 9 (red: scans 16).
	@GameTest(maxTicks = 40)
	public void noBoneMealIsLookedForEveryRound(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		BlockPos low = helper.absolutePos(new BlockPos(1, 0, 3));
		BotScripts.run(bot, """
			import jobs.mushroom_farm
			import lib.stock
			scans = []
			take = lib.stock.take_up_to
			def counted(item, n, radius=32, chests=None):
			    if item == jobs.mushroom_farm.BONE_MEAL:
			        scans.append(item)
			    return take(item, n, radius, chests)
			lib.stock.take_up_to = counted
			low = Pos(%d, %d, %d)
			for i in range(16):
			    jobs.mushroom_farm.tend([(low, low + (2, 0, 0))], {}, 8)
			print("scans", len(scans))
			""".formatted(low.getX(), low.getY(), low.getZ()));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("scans 4"), "said"));
	}

	/** A mushroom that spread onto the path beside a cell, as one does in the dark, is mined (#842). */
	// Measured: passes at tick 172 to 193 (red: the brown mushroom still at (2, 1, 4) on tick 362).
	@GameTest(maxTicks = 360)
	public void aMushroomOnThePathIsRemoved(GameTestHelper helper) {
		AntBot bot = farm(helper);
		boolean[] spread = new boolean[1];
		helper.onEachTick(() -> {
			if (!spread[0] && said(bot, "built cell 1")) {
				helper.setBlock(2, 0, 4, Blocks.PODZOL); // under it, so it stays in the light
				helper.setBlock(2, 1, 4, Blocks.BROWN_MUSHROOM);
				spread[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(spread[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.AIR, 2, 1, 4);
			assertKept(helper);
		});
	}
}
