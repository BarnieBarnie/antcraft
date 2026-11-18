package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/**
 * {@code jobs/kelp_farm.py} (#835; docs/cookbook/kelp-farm.md): a kelp farm in a pool of water. Each test's ground is
 * stone up to y 2, the bot walking at y 3; the pool is dug into it, two deep, water at y 1 and 2 over the floor x 2 to 4,
 * z 3 to 4, the six blocks kelp stands on. The depot's first chest is at (2, 3, 1), north of the pool. A pool 3 deep
 * (#840) has its ground, water and depot a block higher.
 */
public class KelpFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** The pool, a supply chest of six kelp and a chest for the depot, the bot by it, and jobs.kelp_farm run over the pool. */
	private static AntBot farm(GameTestHelper helper) {
		return farm(helper, 2, false);
	}

	private static AntBot farm(GameTestHelper helper, int depth) {
		return farm(helper, depth, false);
	}

	/**
	 * As farm, and with dry the job smelts and crafts (#841): a furnace and a crafting table stand by the supply chest,
	 * which also holds a coal, and the bot carries 9 dried kelp.
	 */
	private static AntBot farm(GameTestHelper helper, int depth, boolean dry) {
		return farm(helper, depth, dry, 9, "", "");
	}

	/**
	 * As farm with dry, the bot carrying {@code dried} dried kelp, {@code before} run in the script before the job and
	 * {@code args} more arguments of run after dry.
	 */
	private static AntBot farm(GameTestHelper helper, int depth, boolean dry, int dried, String before, String args) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean pool = x >= 2 && x <= 4 && z >= 3 && z <= 4;
				for (int y = 1; y <= depth; y++) {
					helper.setBlock(x, y, z, pool ? Blocks.WATER : Blocks.STONE);
				}
			}
		}
		helper.setBlock(6, depth + 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, depth + 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.KELP, 6));
		chest.setItem(1, new ItemStack(Items.CHEST));
		if (dry) {
			chest.setItem(2, new ItemStack(Items.COAL));
			helper.setBlock(7, depth + 1, 6, Blocks.FURNACE);
			helper.setBlock(7, depth + 1, 4, Blocks.CRAFTING_TABLE);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, depth + 1, 6);
		if (dry) {
			bot.getInventory().addItem(new ItemStack(Items.DRIED_KELP, dried));
		}
		BlockPos low = helper.absolutePos(new BlockPos(2, 0, 3));
		BlockPos high = helper.absolutePos(new BlockPos(4, 0, 4));
		BotScripts.run(bot, """
			import jobs.kelp_farm
			%s
			jobs.kelp_farm.run(Pos(%d, %d, %d), Pos(%d, %d, %d), 8, %s%s)
			""".formatted(before, low.getX(), low.getY(), low.getZ(), high.getX(), high.getY(), high.getZ(), dry ? "True" : "False", args));
		BotScripts.failOnError(helper, bot);
		return bot;
	}

	private static boolean planted(GameTestHelper helper) {
		for (int x = 2; x <= 4; x++) {
			for (int z = 3; z <= 4; z++) {
				if (!helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.KELP)
					&& !helper.getBlockState(new BlockPos(x, 1, z)).is(Blocks.KELP_PLANT)) {
					return false;
				}
			}
		}
		return true;
	}

	private static String said(AntBot bot) {
		return "said " + BotScripts.log(bot, Output.Level.PRINT);
	}

	/** Kelp from the supply chest is planted on each of the six floor blocks, under water. */
	// Measured: passes at tick 30 to 31 (red: No module named 'jobs.kelp_farm').
	@GameTest(maxTicks = 70)
	public void kelpIsPlantedOnThePoolsFloor(GameTestHelper helper) {
		AntBot bot = farm(helper);
		helper.succeedWhen(() -> helper.assertTrue(planted(helper), "not all planted; " + said(bot)));
	}

	/** Kelp grown two high on two spots is cut at its second block, so its bottom block stays and grows again; the cut kelp is stored in the depot. */
	// Measured: passes at tick 152.
	@GameTest(maxTicks = 320)
	public void grownKelpIsCutAboveItsBottomBlockAndStored(GameTestHelper helper) {
		AntBot bot = farm(helper);
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && planted(helper)) {
				for (int x : new int[] {2, 4}) {
					helper.setBlock(x, 1, 3, Blocks.KELP_PLANT);
					helper.setBlock(x, 2, 3, Blocks.KELP);
				}
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "not planted; " + said(bot));
			for (int x : new int[] {2, 4}) {
				helper.assertTrue(helper.getBlockState(new BlockPos(x, 1, 3)).is(Blocks.KELP), "bottom block at " + x + " gone");
				helper.assertBlockPresent(Blocks.WATER, x, 2, 3);
			}
			helper.assertBlockPresent(Blocks.CHEST, 2, 3, 1);
			int stored = helper.getBlockEntity(new BlockPos(2, 3, 1), ChestBlockEntity.class).countItem(Items.KELP);
			helper.assertTrue(stored >= 2, "kelp in the depot " + stored + " of the 2 cut; " + said(bot));
		});
	}

	/**
	 * In a pool 3 deep, whose floor is out of reach from the rim, kelp is planted from the surface, and kelp grown up to the
	 * surface is cut down to its bottom block and stored (#840).
	 */
	// Measured: passes at tick 141 (red: no depot chest at (2, 4, 1), at the rim of a pool two deep).
	@GameTest(maxTicks = 300)
	public void aPoolThreeDeepIsPlantedAndCutFromTheSurface(GameTestHelper helper) {
		AntBot bot = farm(helper, 3);
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && planted(helper)) {
				for (int x : new int[] {2, 4}) {
					helper.setBlock(x, 1, 3, Blocks.KELP_PLANT);
					helper.setBlock(x, 2, 3, Blocks.KELP_PLANT);
					helper.setBlock(x, 3, 3, Blocks.KELP);
				}
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "not planted; " + said(bot));
			for (int x : new int[] {2, 4}) {
				helper.assertTrue(helper.getBlockState(new BlockPos(x, 1, 3)).is(Blocks.KELP), "bottom block at " + x + " gone");
				helper.assertBlockPresent(Blocks.WATER, x, 2, 3);
				helper.assertBlockPresent(Blocks.WATER, x, 3, 3);
			}
			helper.assertBlockPresent(Blocks.CHEST, 2, 4, 1);
			int stored = helper.getBlockEntity(new BlockPos(2, 4, 1), ChestBlockEntity.class).countItem(Items.KELP);
			helper.assertTrue(stored >= 4, "kelp in the depot " + stored + " of the 4 cut; " + said(bot));
		});
	}

	/** With dry, the 9 dried kelp the bot carries are crafted into a dried kelp block, stored in the depot (#841). */
	// Measured: passes at tick 59 (red: run() takes from 0 to 3 positional arguments but 4 were given).
	@GameTest(maxTicks = 120)
	public void nineDriedKelpAreCraftedIntoABlock(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, true);
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.CHEST, 2, 3, 1);
			Container depot = helper.getBlockEntity(new BlockPos(2, 3, 1), ChestBlockEntity.class);
			helper.assertTrue(depot.countItem(Items.DRIED_KELP_BLOCK) == 1 && depot.countItem(Items.DRIED_KELP) == 0,
				"dried kelp blocks in the depot " + depot.countItem(Items.DRIED_KELP_BLOCK) + ", dried kelp "
					+ depot.countItem(Items.DRIED_KELP) + "; " + said(bot));
		});
	}

	/** With dry, kelp cut and stored is smelted to dried kelp in the furnace, burning the coal, and the dried kelp stored in the depot (#841). */
	// Measured: passes at tick 606, the smelt taking 200 ticks a kelp (red: as above).
	@GameTest(maxTicks = 1200)
	public void cutKelpIsSmeltedToDriedKelp(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, true);
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && planted(helper)) {
				for (int x : new int[] {2, 4}) {
					helper.setBlock(x, 1, 3, Blocks.KELP_PLANT);
					helper.setBlock(x, 2, 3, Blocks.KELP);
				}
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "not planted; " + said(bot));
			helper.assertBlockPresent(Blocks.CHEST, 2, 3, 1);
			Container depot = helper.getBlockEntity(new BlockPos(2, 3, 1), ChestBlockEntity.class);
			int dried = depot.countItem(Items.DRIED_KELP);
			helper.assertTrue(dried >= 2 && depot.countItem(Items.KELP) == 0,
				"dried kelp in the depot " + dried + ", kelp " + depot.countItem(Items.KELP) + "; " + said(bot));
		});
	}

	/**
	 * With dry, a farm that ran without it before: the pool planted, its depot's first chest at (2, 3, 1) holding 2 raw
	 * kelp, in the job's memory as its kelp chest.
	 */
	private static AntBot farmWithKelpInTheDepot(GameTestHelper helper) {
		helper.setBlock(2, 3, 1, Blocks.CHEST);
		helper.getBlockEntity(new BlockPos(2, 3, 1), ChestBlockEntity.class).setItem(0, new ItemStack(Items.KELP, 2));
		BlockPos chest = helper.absolutePos(new BlockPos(2, 3, 1));
		AntBot bot = farm(helper, 2, true, 0, """
			import lib.depot
			lib.depot.depot("kelp_farm", Pos(%d, %d, %d), "east", {})["chests"].append(["kelp", Pos(%d, %d, %d)])
			""".formatted(chest.getX(), chest.getY(), chest.getZ(), chest.getX(), chest.getY(), chest.getZ()), "");
		// After farm, which fills the pool with water: the script runs from the next tick on.
		for (int x = 2; x <= 4; x++) {
			for (int z = 3; z <= 4; z++) {
				helper.setBlock(x, 1, z, Blocks.KELP);
			}
		}
		return bot;
	}

	/** How many of item the depot's first three chests, (2, 3, 1) going east, hold: the kelp chest and those added after it. */
	private static int inDepot(GameTestHelper helper, Item item) {
		int n = 0;
		for (int x = 2; x <= 4; x++) {
			if (helper.getBlockState(new BlockPos(x, 3, 1)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 3, 1), ChestBlockEntity.class).countItem(item);
			}
		}
		return n;
	}

	/** With dry, raw kelp the depot already holds is taken out, smelted to dried kelp and stored (#858). */
	// Measured: passes at tick 427 to 482, the smelt taking 200 ticks a kelp (green already: dry took the depot's kelp).
	@GameTest(maxTicks = 900)
	public void kelpInTheDepotIsSmelted(GameTestHelper helper) {
		AntBot bot = farmWithKelpInTheDepot(helper);
		helper.succeedWhen(() -> {
			int dried = inDepot(helper, Items.DRIED_KELP);
			int kelp = inDepot(helper, Items.KELP);
			helper.assertTrue(dried >= 2 && kelp == 0, "dried kelp in the depot " + dried + ", kelp " + kelp + "; " + said(bot));
		});
	}

	/**
	 * With dry, a coal taken from the supply chest that the smelt does not burn, as the furnace burns the coal already in
	 * its fuel slot, goes back to the supply chest (#858).
	 */
	// Measured: passes at tick 428 (red: coal in the supply chest 0, carried 1).
	@GameTest(maxTicks = 900)
	public void leftoverFuelGoesBackToItsChest(GameTestHelper helper) {
		AntBot bot = farmWithKelpInTheDepot(helper);
		helper.getBlockEntity(new BlockPos(7, 3, 6), FurnaceBlockEntity.class).setItem(1, new ItemStack(Items.COAL));
		Container supply = helper.getBlockEntity(new BlockPos(6, 3, 6), ChestBlockEntity.class);
		helper.succeedWhen(() -> {
			int dried = inDepot(helper, Items.DRIED_KELP);
			helper.assertTrue(dried >= 2, "dried kelp in the depot " + dried + "; " + said(bot));
			helper.assertTrue(supply.countItem(Items.COAL) == 1 && bot.getInventory().countItem(Items.COAL) == 0,
				"coal in the supply chest " + supply.countItem(Items.COAL) + ", carried "
					+ bot.getInventory().countItem(Items.COAL) + "; " + said(bot));
		});
	}

	/** With dry and a food count of 9, of the 18 dried kelp the bot carries 9 are pressed into a block, 9 stay dried kelp (#858). */
	// Measured: passes at tick 60 (red: run() takes from 0 to 4 positional arguments but 5 were given).
	@GameTest(maxTicks = 120)
	public void theFoodCountOfDriedKelpStaysUnpressed(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, true, 18, "", ", 9");
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.CHEST, 2, 3, 1);
			Container depot = helper.getBlockEntity(new BlockPos(2, 3, 1), ChestBlockEntity.class);
			helper.assertTrue(depot.countItem(Items.DRIED_KELP_BLOCK) == 1 && depot.countItem(Items.DRIED_KELP) == 9,
				"dried kelp blocks in the depot " + depot.countItem(Items.DRIED_KELP_BLOCK) + ", dried kelp "
					+ depot.countItem(Items.DRIED_KELP) + "; " + said(bot));
		});
	}
}
