package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The job {@code jobs/wood_farm.py} (#606; docs/cookbook/wood-farm.md): a birch farm at (6, 1, 2) in the 24x16x24
 * {@code wide_tall} structure, its cells on a dirt floor from (6, 0, 2) (cell 1 from (6, 0, 2) to (10, 0, 6), cell 2
 * from (12, 0, 2)), its depot a row of chests from (6, 1, 0) east, a chest of supplies at (3, 1, 8) and a small birch at
 * (3, 1, 12) whose leaves give the saplings (the game test data pack makes birch leaves always drop one). Radius 12 keeps
 * a neighbouring test's chests out of reach.
 */
public class WoodFarmGameTest implements CustomTestMethodInvoker {
	private static final BlockPos AT = new BlockPos(6, 1, 2);
	private static final BlockPos SPOT = new BlockPos(8, 1, 4);
	private static final BlockPos SPOT_2 = new BlockPos(14, 1, 4);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * An empty bot, a stone axe, 16 dirt and 2 torches in the chest: it gets the kit, two saplings from the birch's
	 * leaves, builds and plants the first cell; the test grows its sapling, and the bot fells the birch, replants and
	 * stores the logs in a chest of the depot, and the cell keeps its torches (a pillar up to drops on the leaves can take
	 * one's place: the bot picks the torch up and puts it back, with no spare, #608). A farm of one cell, as for a second
	 * the bot makes torches of the birch's logs (#643).
	 */
	// Measured: passes at tick 363 to 1187 alone or with the tree tests, up to 1700 with 7 copies at once.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 3000)
	public void buildsTheFirstCellHarvestsAndStoresTheLogs(GameTestHelper helper) {
		AntBot bot = farm(helper, 0, 2);
		run(helper, bot, 1);
		boolean[] grew = new boolean[1];
		BotScripts.failOnWarning(helper, bot);
		helper.onEachTick(() -> {
			if (!grew[0] && said(bot).contains("birch_farm 4/4: farm")) {
				BlockPos at = helper.absolutePos(SPOT);
				BlockState sapling = helper.getLevel().getBlockState(at);
				if (sapling.is(Blocks.BIRCH_SAPLING)) {
					((SaplingBlock) Blocks.BIRCH_SAPLING).advanceTree(helper.getLevel(), at, sapling.setValue(SaplingBlock.STAGE, 1),
						helper.getLevel().getRandom());
				}
				grew[0] = helper.getBlockState(SPOT).is(Blocks.BIRCH_LOG);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grew[0], "the birch never grew");
			assertCell(helper, bot, SPOT);
			for (int y = 1; y < 12; y++) {
				helper.assertBlockNotPresent(Blocks.BIRCH_LOG, SPOT.getX(), y, SPOT.getZ());
			}
			int stored = 0;
			for (int x = 6; x < 12; x++) {
				if (helper.getBlockState(new BlockPos(x, 1, 0)).is(Blocks.CHEST)) {
					stored += helper.getBlockEntity(new BlockPos(x, 1, 0), ChestBlockEntity.class).countItem(Items.BIRCH_LOG);
				}
			}
			helper.assertTrue(stored > 0, "no birch logs in the depot");
		});
	}

	/** With 4 saplings and 4 torches in the chest, 2 of each more than the first cell takes, the farm builds its second cell. */
	// Measured: passes at tick 146 to 148.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 300)
	public void buildsTheSecondCellFromTheMaterialsInTheChests(GameTestHelper helper) {
		AntBot bot = farm(helper, 4, 4);
		run(helper, bot);
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot).contains("built cell 2"), "said " + said(bot));
			assertCell(helper, bot, SPOT);
			assertCell(helper, bot, SPOT_2);
		});
	}

	/**
	 * With 4 saplings, 2 torches (the first cell's), a coal and a birch log in the chest, the farm makes the second cell's
	 * torches (#643): it took only what the chests held, so it never grew without torches in a chest.
	 */
	// Measured: passes at tick 158.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 320)
	public void buildsTheSecondCellMakingTheTorches(GameTestHelper helper) {
		AntBot bot = farm(helper, 4, 2);
		Container chest = helper.getBlockEntity(new BlockPos(3, 1, 8), ChestBlockEntity.class);
		chest.setItem(4, new ItemStack(Items.COAL));
		chest.setItem(5, new ItemStack(Items.BIRCH_LOG));
		run(helper, bot);
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot).contains("built cell 2"), "said " + said(bot));
			assertCell(helper, bot, SPOT);
			assertCell(helper, bot, SPOT_2);
		});
	}

	/**
	 * Before each wait the farm picks up the saplings, sticks and apples lying in it, as leaves decay after a felling and
	 * drop them then; a stick past the farm stays. Where the next cell's materials cannot be got, it says what it lacks
	 * once, not every round (#643).
	 */
	// Measured: passes at tick 48.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 100)
	public void picksUpDropsBeforeEachWaitAndSaysOnceWhatItLacks(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		helper.spawnItem(Items.BIRCH_SAPLING, new BlockPos(7, 1, 3));
		helper.spawnItem(Items.BIRCH_SAPLING, new BlockPos(10, 1, 6));
		helper.spawnItem(Items.STICK, new BlockPos(9, 1, 5));
		helper.spawnItem(Items.APPLE, new BlockPos(6, 1, 6));
		helper.spawnItem(Items.STICK, new BlockPos(20, 1, 20));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		script(helper, bot, """
			import lib.cells
			import lib.result
			waits = [0]
			def counted(ticks):
			    waits[0] += 1
			    print("wait", count(kind), count("stick"), count("apple"))
			    if waits[0] == 3:
			        stop()
			jobs.wood_farm.wait = counted
			jobs.wood_farm.stocked = lambda k, r: lib.result.result("no_item", None, {"minecraft:torch": 2})
			lib.cells.layout("birch_farm", cell[0], (5, 5))
			lib.cells.add("birch_farm", 0)
			jobs.wood_farm.farm(kind, "birch_farm", 2, 12)
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("next cell: no_item, lacks {'minecraft:torch': 2}", "wait 2 1 1", "wait 2 1 1",
				"wait 2 1 1"), "said");
			helper.assertItemEntityPresent(Items.STICK, new BlockPos(20, 1, 20), 1.0);
		});
	}

	/** Stopped once it farms and run again, it goes on farming the same farm: no second first cell. */
	// Measured: passes at tick 91 to 94.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 200)
	public void resumesTheSameFarmAfterAStop(GameTestHelper helper) {
		AntBot bot = farm(helper, 0, 2);
		run(helper, bot);
		boolean[] rerun = new boolean[1];
		BotScripts.failOnWarning(helper, bot);
		helper.onEachTick(() -> {
			if (!rerun[0] && said(bot).contains("birch_farm 4/4: farm")) {
				rerun[0] = true;
				bot.stopScript();
				run(helper, bot);
			}
		});
		helper.succeedWhen(() -> {
			List<String> said = said(bot);
			helper.assertTrue(Collections.frequency(said, "birch_farm 4/4: farm") == 2, "said " + said);
			helper.assertTrue(Collections.frequency(said, "birch_farm 3/4: cell") == 1, "said " + said);
			helper.assertTrue(Collections.frequency(said, "built cell 1") == 1, "said " + said);
			assertCell(helper, bot, SPOT);
			helper.assertBlockNotPresent(Blocks.BIRCH_SAPLING, SPOT_2);
		});
	}

	/** tend plants no sapling under a log a failed harvest left of the cell's tree (#644): the log would stop it growing. */
	// Measured: passes at tick 3.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 10)
	public void tendPlantsNoSaplingUnderLogsLeft(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT.above(3), Blocks.BIRCH_LOG);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_SAPLING, 2));
		script(helper, bot, "jobs.wood_farm.tend(kind, cell, 12)\nprint(block(spot).id)\n");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("minecraft:air"), "printed"));
	}

	/**
	 * A birch whose top logs the bot cannot reach without dirt to pillar with (#644): reap says the harvest failed and why,
	 * gets dirt from the chest and harvests again, then tend replants the cell, its tree gone.
	 */
	// Measured: passes at tick 599.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 1200)
	public void aFailedHarvestIsRetriedBeforeTheCellIsReplanted(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		for (int y = 0; y < 9; y++) {
			helper.setBlock(SPOT.above(y), Blocks.BIRCH_LOG);
		}
		helper.setBlock(3, 1, 8, Blocks.CHEST);
		helper.getBlockEntity(new BlockPos(3, 1, 8), ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIRT, 16));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_SAPLING, 2));
		script(helper, bot, "r = jobs.wood_farm.reap(kind, cell, 12, {})\njobs.wood_farm.tend(kind, cell, 12)\nprint(r['ok'], block(spot).id)\n");
		helper.succeedWhen(() -> {
			List<String> said = said(bot);
			helper.assertTrue(said.size() == 2 && said.get(0).startsWith("harvest: out_of_reach at "), "said " + said);
			helper.assertValueEqual(said.get(1), "True minecraft:birch_sapling", "printed");
			for (int y = 1; y < 12; y++) {
				helper.assertBlockNotPresent(Blocks.BIRCH_LOG, SPOT.getX(), y, SPOT.getZ());
			}
		});
	}

	/**
	 * most(harvest): the cells at which the bot is always busy, a sapling's mean growth time (2 stages of 1 in 7 random
	 * ticks, 3 a tick in a section of 4096 blocks) over the ticks a tree takes; at least 1. Imported, the job runs nothing.
	 */
	@GameTest(maxTicks = 10)
	public void mostIsTheGrowthTimeOverTheHarvestTime(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		BotScripts.run(bot, "import jobs.wood_farm\nprint(int(jobs.wood_farm.GROWTH), jobs.wood_farm.most(400), jobs.wood_farm.most(10 ** 6))\n");
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScript().state() == ScriptRun.State.FINISHED, "not finished");
			helper.assertValueEqual(said(bot), List.of("19114 47 1"), "printed");
		});
	}

	/**
	 * The farm's ground: dirt where its cells go, the supply chest with a stone axe, 16 dirt, and saplings and torches as
	 * given, the birch; gives the bot, empty, by the chest.
	 */
	private static AntBot farm(GameTestHelper helper, int saplings, int torches) {
		for (int x = 6; x <= 16; x++) {
			for (int z = 2; z <= 6; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		helper.setBlock(3, 1, 8, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(3, 1, 8), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.STONE_AXE));
		chest.setItem(1, new ItemStack(Items.DIRT, 16));
		chest.setItem(2, new ItemStack(Items.TORCH, torches));
		if (saplings > 0) {
			chest.setItem(3, new ItemStack(Items.BIRCH_SAPLING, saplings));
		}
		TreesGameTest.plantTree(helper, new BlockPos(3, 1, 12), Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, false);
		return helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
	}

	/** Runs jobs.wood_farm for birch at AT, up to 2 cells, radius 12. */
	private static void run(GameTestHelper helper, AntBot bot) {
		run(helper, bot, 2);
	}

	/** Runs jobs.wood_farm for birch at AT, up to cells cells, radius 12. */
	private static void run(GameTestHelper helper, AntBot bot, int cells) {
		BlockPos at = helper.absolutePos(AT);
		List<String> said = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.wood_farm birch_sapling "
			+ at.getX() + " " + at.getY() + " " + at.getZ() + " " + cells + " 12");
		helper.assertTrue(bot.getScript() != null, "not run: " + said);
	}

	/** Runs body after importing jobs.wood_farm, with kind birch, cell 1's box as cell and its sapling spot as spot. */
	private static void script(GameTestHelper helper, AntBot bot, String body) {
		BlockPos l = helper.absolutePos(new BlockPos(6, 0, 2));
		BlockPos h = helper.absolutePos(new BlockPos(10, 0, 6));
		BlockPos s = helper.absolutePos(SPOT);
		BotScripts.run(bot, "import jobs.wood_farm\nkind = 'minecraft:birch_sapling'\ncell = (Pos(" + l.getX() + ", " + l.getY() + ", "
			+ l.getZ() + "), Pos(" + h.getX() + ", " + h.getY() + ", " + h.getZ() + "))\nspot = Pos(" + s.getX() + ", " + s.getY()
			+ ", " + s.getZ() + ")\n" + body);
		BotScripts.failOnError(helper, bot);
	}

	/** A built cell round the sapling spot: dirt under it, the sapling planted, torches at two opposite corners. */
	private static void assertCell(GameTestHelper helper, AntBot bot, BlockPos spot) {
		helper.assertBlockPresent(Blocks.DIRT, spot.below());
		helper.assertTrue(helper.getBlockState(spot).is(Blocks.BIRCH_SAPLING), "no sapling at " + spot + ": " + helper.getBlockState(spot)
			+ "; " + BotScripts.log(bot, Output.Level.DEBUG));
		helper.assertBlockPresent(Blocks.TORCH, spot.offset(-2, 0, -2));
		helper.assertBlockPresent(Blocks.TORCH, spot.offset(2, 0, 2));
	}

	private static List<String> said(AntBot bot) {
		return BotScripts.log(bot, Output.Level.PRINT);
	}
}
