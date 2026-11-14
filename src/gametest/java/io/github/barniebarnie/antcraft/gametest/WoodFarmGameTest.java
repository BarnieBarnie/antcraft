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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The job {@code jobs/wood_farm.py} (#606; docs/cookbook/wood-farm.md): a birch farm at (6, 1, 2) in the 24x16x24
 * {@code wide_tall} structure, its cells on a dirt floor (the area's stone in the stone floor test) from (6, 0, 2)
 * (cell 1 from (6, 0, 2) to (10, 0, 6), cell 2 from (12, 0, 2)), its depot a row of chests from (6, 1, 0) east, a chest of supplies at (3, 1, 8) and a small birch at
 * (3, 1, 12) whose leaves give the saplings (the game test data pack makes birch leaves always drop one). Radius 12 keeps
 * a neighbouring test's chests out of reach.
 */
public class WoodFarmGameTest implements CustomTestMethodInvoker {
	private static final BlockPos AT = new BlockPos(6, 1, 2);
	private static final BlockPos SPOT = new BlockPos(8, 1, 4);
	private static final BlockPos SPOT_2 = new BlockPos(14, 1, 4);
	private static final BlockPos OAK_SPOT = new BlockPos(9, 1, 5);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * An empty bot, a stone axe, 16 dirt, 2 saplings and 2 torches in the chest: it gets the kit and the saplings, builds
	 * and plants the first cell; the test puts a small birch (4 logs) in its sapling's place, and the bot fells it,
	 * replants and stores the logs in a chest of the depot, and the cell keeps its torches (a pillar up to drops on the
	 * leaves can take one's place: the bot picks the torch up and puts it back, with no spare, #608). A farm of one cell,
	 * as for a second the bot makes torches of the birch's logs (#643). Saplings from leaves (GetGameTest) and a tall
	 * tree's climb (aFailedHarvestIsRetriedBeforeTheCellIsReplanted) are left to other tests (#612): with them this took
	 * 363 to 1700 ticks.
	 */
	// Measured: passes at tick 282 to 348 with 7 copies at once.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 700)
	public void buildsTheFirstCellHarvestsAndStoresTheLogs(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, 2);
		run(helper, bot, 1);
		boolean[] grew = new boolean[1];
		BotScripts.failOnWarning(helper, bot);
		helper.onEachTick(() -> {
			if (!grew[0] && said(bot).contains("birch_farm 4/4: farm") && helper.getBlockState(SPOT).is(Blocks.BIRCH_SAPLING)) {
				TreesGameTest.plantTree(helper, SPOT, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, false);
				grew[0] = true;
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

	/**
	 * A sapling lying on a column of three persistent leaves in the cell: the sweep pillars up to it with the bot's dirt,
	 * and the round takes that pillar back before it waits, so no dirt stands in the farm (#654).
	 */
	// Measured: passes at tick 107.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 220)
	public void aSweepThatPillarsToADropTakesThePillarBack(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(7, y, 3, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		}
		helper.spawnItem(Items.BIRCH_SAPLING, new BlockPos(7, 4, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		script(helper, bot, """
			import lib.cells
			import lib.result
			def counted(ticks):
			    print("wait", count(kind), count("dirt"))
			    stop()
			jobs.wood_farm.wait = counted
			jobs.wood_farm.stocked = lambda k, r: lib.result.result("no_item", None, {"minecraft:torch": 2})
			lib.cells.layout("birch_farm", cell[0], (5, 5))
			lib.cells.add("birch_farm", 0)
			jobs.wood_farm.farm(kind, "birch_farm", 2, 12)
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("next cell: no_item, lacks {'minecraft:torch': 2}", "wait 1 16"), "said");
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 23, 15, 23)) {
				helper.assertBlockNotPresent(Blocks.DIRT, p);
			}
		});
	}

	/**
	 * Where the next cell's materials cannot be got, the farm does not run get every round, which can be a long chain
	 * (#651): it tries again after BACKOFF rounds, or at once after a felling, whose logs it may make them of. Here
	 * gets fails, and a tree "grows" once, after the first wait: gets runs in the first round and in the felling's,
	 * not in the waits' rounds after. The farm is lib.cells's functions swapped for one cell, with no lib.cells.layout:
	 * a module attribute assigned is what the job's functions see (docs/language/modules.md).
	 */
	// Measured: passes at tick 5.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 20)
	public void aFarmThatCannotGetAMaterialBacksOff(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			calls = [0]
			waits = [0]
			tree = [False]
			def failing(item, n, radius):
			    calls[0] += 1
			    return lib.result.result("no_item", None, {item: n})
			def counted(ticks):
			    waits[0] += 1
			    tree[0] = waits[0] == 1
			    if waits[0] == 3:
			        print("gets", calls[0])
			        stop()
			def reaped(k, b, radius, told):
			    tree[0] = False
			    return lib.result.result()
			jobs.wood_farm.gets = failing
			jobs.wood_farm.wait = counted
			jobs.wood_farm.reap = reaped
			lib.treefarm.grown = lambda k, low, high: tree[0]
			lib.depot.store = lambda name, keep, radius: lib.result.result()
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", 2, 12)
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("next cell: no_item, lacks {'minecraft:dirt': 1}", "gets 2"),
			"said"));
	}

	/**
	 * The farm keeps the ticks it spends per felled tree in memory and, from SAMPLES trees on, sizes itself from their
	 * mean instead of HARVEST (#653). Two trees of 2 GROWTH each are in memory, so harvest() is still HARVEST's 400; a
	 * third felled in the farm's first round makes the mean over GROWTH, so most() is 1 and the farm of one cell, run
	 * with no size, is full. With HARVEST it would have gone on to a second cell.
	 */
	// Measured: passes at tick 7.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 20)
	public void sizesItselfFromTheTicksItSpendsPerTree(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			tree = [True]
			memory["wood_harvest"] = {kind: [4 * int(jobs.wood_farm.GROWTH), 2]}
			print(jobs.wood_farm.harvest(kind))
			def reaped(k, b, radius, told):
			    tree[0] = False
			    wait(3)
			    return lib.result.result()
			def waited(ticks):
			    h = memory["wood_harvest"][kind]
			    print(h[1], h[0] > 4 * int(jobs.wood_farm.GROWTH), jobs.wood_farm.harvest(kind) > jobs.wood_farm.GROWTH)
			    stop()
			jobs.wood_farm.reap = reaped
			jobs.wood_farm.wait = waited
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.treefarm.grown = lambda k, low, high: tree[0]
			lib.depot.store = lambda name, keep, radius: lib.result.result()
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", None, 12)
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("400", "farm full", "3 True True"), "said"));
	}

	/**
	 * A branch log a harvest left off the trunk column, such as an acacia's or a cherry's, is seen in the cell's tree
	 * space and felled (#651): grown looked only over the sapling spots.
	 */
	// Measured: passes at tick 112.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 200)
	public void aLeftoverBranchOffTheTrunkIsFelled(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		BlockPos branch = new BlockPos(7, 3, 3);
		helper.setBlock(branch, Blocks.BIRCH_LOG);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		script(helper, bot, "import lib.treefarm\ng = lib.treefarm.grown(kind, cell[0], cell[1])\nr = jobs.wood_farm.reap(kind, cell, 12, {})\n"
			+ "print(g, r['ok'], lib.treefarm.grown(kind, cell[0], cell[1]), block(spot).id)\n");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("True True False minecraft:birch_sapling"), "printed");
			helper.assertBlockNotPresent(Blocks.BIRCH_LOG, branch);
		});
	}

	/**
	 * On the area's stone floor, with no pickaxe in the kit or the chest, the farm builds its first cell (#612): the stone
	 * under the sapling spot gives way to dirt, mined with a pickaxe it makes of the logs it carries.
	 */
	// Measured: passes at tick 138 to 156.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 320)
	public void buildsTheFirstCellOnAStoneFloor(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, 2, false);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 3));
		run(helper, bot, 1);
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot).contains("built cell 1"), "said " + said(bot));
			assertCell(helper, bot, SPOT);
		});
	}

	/** An oak farm roofs its cell with the birch slabs a chest holds (#612): any wooden slabs do, not only oak ones. */
	// Measured: passes at tick 236 to 253.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 500)
	public void roofsAnOakCellWithBirchSlabsFromTheChest(GameTestHelper helper) {
		AntBot bot = farm(helper, 0, 2);
		Container chest = helper.getBlockEntity(new BlockPos(3, 1, 8), ChestBlockEntity.class);
		chest.setItem(3, new ItemStack(Items.OAK_SAPLING, 2));
		chest.setItem(4, new ItemStack(Items.BIRCH_SLAB, 9));
		BlockPos at = helper.absolutePos(AT);
		List<String> out = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.wood_farm oak_sapling "
			+ at.getX() + " " + at.getY() + " " + at.getZ() + " 1 12");
		helper.assertTrue(bot.getScript() != null, "not run: " + out);
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot).contains("built cell 1"), "said " + said(bot));
			helper.assertBlockPresent(Blocks.OAK_SAPLING, OAK_SPOT);
			for (int x = -1; x <= 1; x++) {
				for (int z = -1; z <= 1; z++) {
					helper.assertBlockPresent(Blocks.BIRCH_SLAB, OAK_SPOT.offset(x, 8, z));
				}
			}
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
		script(helper, bot, "jobs.wood_farm.tend(kind, cell)\nprint(block(spot).id)\n");
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
		script(helper, bot, "r = jobs.wood_farm.reap(kind, cell, 12, {})\njobs.wood_farm.tend(kind, cell)\nprint(r['ok'], block(spot).id)\n");
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
		BotScripts.run(bot, """
			import jobs.wood_farm
			import lib.treefarm
			h = jobs.wood_farm.HARVEST
			print(int(jobs.wood_farm.GROWTH), jobs.wood_farm.most(400), jobs.wood_farm.most(10 ** 6), sorted(h) == sorted(lib.treefarm.KINDS))
			print(jobs.wood_farm.most(h["minecraft:birch_sapling"]), jobs.wood_farm.most(h["minecraft:dark_oak_sapling"]))
			""");
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScript().state() == ScriptRun.State.FINISHED, "not finished");
			helper.assertValueEqual(said(bot), List.of("19114 47 1 True", "47 8"), "printed");
		});
	}

	/**
	 * The farm's ground: dirt where its cells go, the supply chest with a stone axe, 16 dirt, and saplings and torches as
	 * given, the birch; gives the bot, empty, by the chest.
	 */
	private static AntBot farm(GameTestHelper helper, int saplings, int torches) {
		return farm(helper, saplings, torches, true);
	}

	/** As farm(helper, saplings, torches), the cells' floor of dirt (up to z 8, an oak cell's) or else the area's stone. */
	private static AntBot farm(GameTestHelper helper, int saplings, int torches, boolean dirt) {
		for (int x = 6; dirt && x <= 16; x++) {
			for (int z = 2; z <= 8; z++) {
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
