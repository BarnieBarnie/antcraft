package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

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


	/**
	 * A farm bot killed up on its scaffold while it fells a tall birch (8 logs) comes back at the farm, its home the path by
	 * the depot, (6, 1, 1), walks back for what it dropped and takes back the scaffold it died on, then finishes the cell:
	 * no log left (but the supply birch's), the cell replanted, logs in the depot, no dirt above the floor and nothing it dropped lying about (#679).
	 */
	// Measured: passes at tick 824 to 847 (red: respawned where first spawned; then the scaffold it died on left at (7, 1, 3)).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 2000)
	public void aBotKilledWhileFellingComesBackAndFinishesTheCell(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, 2);
		java.util.UUID id = bot.getUUID();
		run(helper, bot, 1);
		boolean[] grew = new boolean[1];
		long[] died = {-1};
		long[] respawned = {-1};
		BlockPos home = helper.absolutePos(new BlockPos(6, 1, 1));
		helper.onEachTick(() -> {
			AntBot now = helper.getLevel().getEntity(id) instanceof AntBot b && b.isAlive() ? b : null;
			if (now != null && said(now).stream().anyMatch(l -> l.contains("stopped"))) {
				helper.fail("said " + said(now));
			}
			if (!grew[0] && said(bot).contains("birch_farm 4/4: farm") && helper.getBlockState(SPOT).is(Blocks.BIRCH_SAPLING)) {
				TreesGameTest.plantTree(helper, SPOT.above(4), Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, false);
				for (int y = 0; y < 4; y++) {
					helper.setBlock(SPOT.above(y), Blocks.BIRCH_LOG);
				}
				grew[0] = true;
			} else if (grew[0] && died[0] < 0 && bot.isAlive() && helper.relativePos(bot.blockPosition()).getY() >= 4) {
				bot.kill(helper.getLevel());
				died[0] = helper.getTick();
			} else if (died[0] >= 0 && respawned[0] < 0 && now != null && now != bot) {
				respawned[0] = helper.getTick();
				helper.assertTrue(now.blockPosition().distManhattan(home) <= 1, "respawned at " + now.blockPosition() + ", home " + home);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(respawned[0] >= 0, "not killed and respawned: killed at " + died[0]);
			AntBot again = Objects.requireNonNull((AntBot) helper.getLevel().getEntity(id));
			assertCell(helper, again, SPOT);
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 23, 15, 23)) {
				helper.assertFalse(helper.getBlockState(p).is(Blocks.BIRCH_LOG) && p.getZ() < 10, "a log left at " + p.toShortString() + "; said " + said(again));
				helper.assertFalse(helper.getBlockState(p).is(Blocks.DIRT), "scaffold left at " + p.toShortString() + "; said " + said(again));
			}
			int stored = 0;
			for (int x = 6; x < 12; x++) {
				if (helper.getBlockState(new BlockPos(x, 1, 0)).is(Blocks.CHEST)) {
					stored += helper.getBlockEntity(new BlockPos(x, 1, 0), ChestBlockEntity.class).countItem(Items.BIRCH_LOG);
				}
			}
			helper.assertTrue(stored > 0, "no birch logs in the depot; said " + said(again));
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, helper.getBounds(),
				e -> e.getItem().is(Items.STONE_AXE) || e.getItem().is(Items.DIRT)).isEmpty(), "its drops left lying; said " + said(again));
		});
	}

	/**
	 * A farm bot first spawned up on a bedrock pillar 29 high in the 24x40x24 {@code giant} structure, with nothing to
	 * build or dig down with, has no way down but a fatal drop (PathFinder's last search, #674). It takes it, comes back at
	 * the farm, now its home, not on the pillar, and the job goes on: it builds and plants the first cell (#679).
	 */
	// Measured: passes at tick 510 to 524 (red without set_home: it respawned up the pillar and dropped to its death again).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 1200)
	public void aBotStrandedUpAPillarDropsToItsDeathAndTheFarmGoesOn(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, 2);
		java.util.UUID id = bot.getUUID();
		for (int y = 1; y < 30; y++) {
			helper.setBlock(12, y, 14, Blocks.BEDROCK);
		}
		bot.snapTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(12, 30, 14))), 0, 0);
		bot.setHome(helper.absolutePos(new BlockPos(12, 30, 14)));
		run(helper, bot, 1, 32);  // the supply chest 31 blocks off
		helper.succeedWhen(() -> {
			AntBot now = helper.getLevel().getEntity(id) instanceof AntBot b && b.isAlive() ? b : null;
			helper.assertTrue(now != null && now.getDeaths() >= 1, "never died; " + BotScripts.log(bot, Output.Level.DEBUG));
			helper.assertTrue(said(now).contains("built cell 1"), "said " + said(now));
			assertCell(helper, now, SPOT);
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
			import lib.depot
			import lib.result
			waits = [0]
			def counted(ticks):
			    waits[0] += 1
			    print("wait", count(kind), count("stick"), count("apple"))
			    if waits[0] == 3:
			        stop()
			jobs.wood_farm.wait = counted
			jobs.wood_farm.stocked = lambda k, r: lib.result.result("no_item", None, {"minecraft:torch": 2})
			lib.depot.store = lambda name, keep, radius: lib.result.result()  # no depot: a farm with no store
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
			import lib.depot
			import lib.result
			def counted(ticks):
			    print("wait", count(kind), count("dirt"))
			    stop()
			jobs.wood_farm.wait = counted
			jobs.wood_farm.stocked = lambda k, r: lib.result.result("no_item", None, {"minecraft:torch": 2})
			lib.depot.store = lambda name, keep, radius: lib.result.result()  # no depot: a farm with no store
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
	 * A full depot that can take no new chest (lib.depot.store gives blocked, the logs still carried) in every round that
	 * fells a tree: the farm says so once, not every round, and goes on farming with the logs it carries (#763). Where a
	 * chest can be added, lib.depot.store adds it (DepotGameTest aFullChestGrowsTheRow).
	 */
	// Measured: passes at tick 8 (red: said the store line every round, its kept count growing).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 20)
	public void aFullDepotIsSaidOnceAndTheFarmGoesOn(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			waits = [0]
			def counted(ticks):
			    waits[0] += 1
			    if waits[0] == 3:
			        print("waits", waits[0])
			        stop()
			jobs.wood_farm.wait = counted
			# A grown tree each round that is not felled, so the round waits.
			jobs.wood_farm.reap = lambda k, b, radius, told: lib.result.result("no_tool")
			lib.treefarm.grown = lambda k, low, high: True
			# More logs kept each round, as a farm's would be.
			lib.depot.store = lambda name, keep, radius: lib.result.result("blocked", None, {"minecraft:birch_log": 5 + waits[0]})
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", 1, 12)
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("store: blocked, kept {'minecraft:birch_log': 5}", "farm full", "waits 3"),
			"said"));
	}

	/**
	 * A grown birch, a bot whose inventory is full (a stone axe and 26 stacks of cobblestone) and a depot that can take
	 * no new chest (add_chest gives blocked): the farm does not fell the tree, whose logs would have no slot and be
	 * dropped, but says once that its inventory is full and waits (#765).
	 */
	// Measured: passes at tick 12 (red: tried to fell it, harvest: no_space, and stored nothing).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 30)
	public void aFullInventoryThatCannotBeStoredStopsTheFelling(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, SPOT, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, false);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		for (int i = 0; i < 26; i++) {
			bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
		}
		BlockPos depot = helper.absolutePos(new BlockPos(6, 1, 0));
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			waits = [0]
			def counted(ticks):
			    waits[0] += 1
			    if waits[0] == 3:
			        print("waits", waits[0])
			        stop()
			lib.depot.depot("birch_farm", Pos(%d, %d, %d), "east", jobs.wood_farm.CATEGORIES)
			jobs.wood_farm.wait = counted
			lib.depot.add_chest = lambda name, cat, radius: lib.result.result("blocked")
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", 1, 12)
			""".formatted(depot.getX(), depot.getY(), depot.getZ()));
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("inventory full, store: blocked; felling stops", "farm full", "waits 3"), "said");
			helper.assertBlockPresent(Blocks.BIRCH_LOG, SPOT);
			helper.assertItemEntityNotPresent(Items.BIRCH_LOG);
		});
	}

	/**
	 * The farm keeps the ticks it spends per felled tree in memory and, from SAMPLES trees on, sizes itself from their
	 * mean instead of HARVEST (#653; memory["wood_ticks"], a list of the last WINDOW, #662). Two trees of 2 GROWTH each are in memory, so harvest() is still HARVEST's 400; a
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
			memory["wood_ticks"] = {kind: [2 * jobs.wood_farm.GROWTH, 2 * jobs.wood_farm.GROWTH]}
			print(jobs.wood_farm.harvest(kind))
			def reaped(k, b, radius, told):
			    tree[0] = False
			    wait(3)
			    return lib.result.result()
			def waited(ticks):
			    h = memory["wood_ticks"][kind]
			    print(len(h), jobs.wood_farm.harvest(kind) > jobs.wood_farm.GROWTH)
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
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("400", "farm full", "3 True"), "said"));
	}

	/**
	 * A bot carrying 5 stacks of dirt stores all but a stack of it in the depot in the round that fells a tree (#762):
	 * the birch's kit and a cell take less than a stack, so a stack is what it keeps. It carries a chest for the depot.
	 */
	// Measured: passes at tick 24.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 60)
	public void storesItsDirtButAStack(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		for (int i = 0; i < 5; i++) {
			bot.getInventory().addItem(new ItemStack(Items.DIRT, 64));
		}
		bot.getInventory().addItem(new ItemStack(Items.CHEST));
		BlockPos depot = helper.absolutePos(new BlockPos(6, 1, 0));
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			tree = [True]
			lib.depot.depot("birch_farm", Pos(%d, %d, %d), "east", jobs.wood_farm.CATEGORIES)
			def reaped(k, b, radius, told):
			    tree[0] = False
			    return lib.result.result()
			def waited(ticks):
			    print(count("minecraft:dirt"))
			    stop()
			jobs.wood_farm.reap = reaped
			jobs.wood_farm.wait = waited
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.treefarm.grown = lambda k, low, high: tree[0]
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", 1, 12)
			""".formatted(depot.getX(), depot.getY(), depot.getZ()));
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("farm full", "64"), "dirt carried");
			helper.assertValueEqual(helper.getBlockEntity(new BlockPos(6, 1, 0), ChestBlockEntity.class).countItem(Items.DIRT), 256,
				"dirt in the depot");
		});
	}

	/**
	 * As storesItsDirtButAStack in a round that fells nothing (#766): no tree is grown, yet the bot stores all but a
	 * stack of its 5 stacks of dirt before it waits, so what builds up between fellings does not fill its inventory.
	 */
	// Measured: passes at tick 23 (red: kept all 320 dirt).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 60)
	public void aRoundWithNoGrownTreeStoresItsSurplus(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		for (int i = 0; i < 5; i++) {
			bot.getInventory().addItem(new ItemStack(Items.DIRT, 64));
		}
		bot.getInventory().addItem(new ItemStack(Items.CHEST));
		BlockPos depot = helper.absolutePos(new BlockPos(6, 1, 0));
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			lib.depot.depot("birch_farm", Pos(%d, %d, %d), "east", jobs.wood_farm.CATEGORIES)
			def waited(ticks):
			    print(count("minecraft:dirt"))
			    stop()
			jobs.wood_farm.wait = waited
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.treefarm.grown = lambda k, low, high: False
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", 1, 12)
			""".formatted(depot.getX(), depot.getY(), depot.getZ()));
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("farm full", "64"), "dirt carried");
			helper.assertValueEqual(helper.getBlockEntity(new BlockPos(6, 1, 0), ChestBlockEntity.class).countItem(Items.DIRT), 256,
				"dirt in the depot");
		});
	}

	/**
	 * The empty slots the farm wants before a felling come from the kind's logs (#766): free(kind) is 5 for a birch,
	 * more for a giant, whose logs fill more stacks, and more for a mangrove, whose roots take slots too; farm asks
	 * lib.depot.room for free(kind).
	 */
	// Measured: passes at tick 6 (red: no free in jobs.wood_farm).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 20)
	public void theSlotsAFellingWantsComeFromTheKindsLogs(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			w = jobs.wood_farm
			print(w.free("minecraft:birch_sapling"), w.free("minecraft:dark_oak_sapling"), w.free("minecraft:mangrove_propagule"),
			      w.free("giant_jungle"), w.free("giant_spruce"))
			tree = [True]
			def roomed(name, free, keep, radius):
			    print("room", free)
			    return lib.result.result()
			def reaped(k, b, radius, told):
			    tree[0] = False
			    return lib.result.result()
			def waited(ticks):
			    stop()
			w.free = lambda k: 9
			w.reap = reaped
			w.wait = waited
			w.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.depot.room = roomed
			lib.depot.store = lambda name, keep, radius: lib.result.result()
			lib.treefarm.grown = lambda k, low, high: tree[0]
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			w.farm(kind, "birch_farm", 1, 12)
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("5 6 7 7 7", "room 9", "farm full"), "said"));
	}

	/**
	 * harvest() is the mean of the last WINDOW trees only (#662): after three slow trees of 2 GROWTH, WINDOW quick ones
	 * of 100 ticks (one round of two, the rest one a round) make it 100. A mean over every tree would still be some
	 * 9000.
	 */
	// Measured: passes at tick 3.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 10)
	public void harvestTimeIsTheMeanOfTheLastTrees(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		script(helper, bot, """
			w = jobs.wood_farm
			w.learn(kind, 6 * w.GROWTH, 3)
			w.learn(kind, 200, 2)
			for _ in range(w.WINDOW - 2):
			    w.learn(kind, 100, 1)
			print(len(memory["wood_ticks"][kind]), w.harvest(kind) == 100)
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("10 True"), "said"));
	}

	/**
	 * A full farm that learns its trees are quicker says its new limit and builds again (#662): three trees of 2 GROWTH
	 * in memory make it full at one cell; after its first wait the test puts three of 1000 ticks there, so the limit is
	 * 19, the farm says so and tries the next cell (gets fails, so it says what it lacks).
	 */
	// Measured: passes at tick 4.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 20)
	public void aFullFarmSaysWhenItsLimitGrows(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			slow = 2 * jobs.wood_farm.GROWTH
			memory["wood_ticks"] = {kind: [slow, slow, slow]}
			rounds = [0]
			def waited(ticks):
			    rounds[0] += 1
			    if rounds[0] > 1:
			        stop()
			    memory["wood_ticks"] = {kind: [1000, 1000, 1000]}
			jobs.wood_farm.wait = waited
			jobs.wood_farm.sweep = lambda k, boxes: None
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.treefarm.grown = lambda k, low, high: False
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", None, 12)
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot),
			List.of("farm full", "cells 1/19", "next cell: no_item, lacks {'minecraft:dirt': 1}"), "said"));
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

	/** tend leaves a cell's torches alone, one on the wall of a block beside its corner, one standing (#760). */
	// Measured: passes at tick 5.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 20)
	public void tendLeavesAWallTorchAlone(GameTestHelper helper) {
		BlockPos wall = new BlockPos(6, 1, 2);
		BlockPos standing = new BlockPos(10, 1, 6);
		BlockState wallTorch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST);
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		helper.setBlock(wall.west(), Blocks.STONE);
		helper.setBlock(wall, wallTorch);
		helper.setBlock(standing, Blocks.TORCH);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 8, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 2));
		helper.onEachTick(() -> {
			if (helper.getBlockState(wall) != wallTorch || !helper.getBlockState(standing).is(Blocks.TORCH)) {
				helper.fail("a torch was touched: " + helper.getBlockState(wall) + ", " + helper.getBlockState(standing));
			}
		});
		script(helper, bot, "jobs.wood_farm.tend(kind, cell)\njobs.wood_farm.tend(kind, cell)\nprint(count('minecraft:torch'))\n");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("2"), "printed"));
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

	/** tend puts back the dirt a path dug out under the spot of a built cell, then plants (#764). */
	// Measured: passes at tick 18 (red: printed minecraft:air minecraft:air).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 40)
	public void tendPutsBackTheDirtUnderASpot(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.AIR);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_SAPLING, 2));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 4));
		script(helper, bot, "jobs.wood_farm.tend(kind, cell)\nprint(block(spot.down()).id, block(spot).id)\n");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("minecraft:dirt minecraft:birch_sapling"), "printed"));
	}

	/**
	 * tend for a spot with no dirt under it, the bot carrying none (#764): it gets one from the chest within radius and
	 * plants; with none to get anywhere it says so once over two tends and goes on.
	 */
	// Measured: passes at tick 51 to 53 (red: tend took no radius or told).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 100)
	public void tendGetsTheDirtItLacksOrSaysSoOnce(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.AIR);
		helper.setBlock(SPOT_2.below(), Blocks.AIR);
		helper.setBlock(3, 1, 8, Blocks.CHEST);
		helper.getBlockEntity(new BlockPos(3, 1, 8), ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIRT, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_SAPLING, 2));
		BlockPos other = helper.absolutePos(SPOT_2);
		script(helper, bot, "told = {}\njobs.wood_farm.tend(kind, cell, 12, told)\nprint(block(spot.down()).id, block(spot).id)\n"
			+ "cell2 = (cell[0] + (6, 0, 0), cell[1] + (6, 0, 0))\njobs.wood_farm.tend(kind, cell2, 12, told)\n"
			+ "jobs.wood_farm.tend(kind, cell2, 12, told)\nprint(block(Pos(" + other.getX() + ", " + other.getY() + ", "
			+ other.getZ() + ")).id)\n");
		helper.succeedWhen(() -> {
			List<String> said = said(bot);
			helper.assertValueEqual(said.size(), 3, "said " + said);
			helper.assertValueEqual(said.get(0), "minecraft:dirt minecraft:birch_sapling", "first cell");
			helper.assertTrue(said.get(1).startsWith("plant: "), "said " + said);
			helper.assertValueEqual(said.get(2), "minecraft:air", "second cell");
		});
	}

	/**
	 * tend for a spot with no dirt under it, the bot carrying none, two cells built (#767): the dirt under the empty spot of
	 * the next cell is nearer, but the bot digs the dirt outside the cells.
	 */
	// Measured: passes at tick 172 (red: the dirt under the next cell's spot dug).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 350)
	public void tendDigsNoDirtFromUnderAnotherSpot(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.AIR);
		helper.setBlock(SPOT_2.below(), Blocks.DIRT);
		BlockPos outside = new BlockPos(4, 0, 17);
		helper.setBlock(outside, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_SAPLING, 2));
		script(helper, bot, """
			import lib.cells
			lib.cells.layout("birch_farm", cell[0], (5, 5))
			lib.cells.add("birch_farm", 0)
			lib.cells.add("birch_farm", 1)
			jobs.wood_farm.tend(kind, cell)
			print(block(spot.down()).id, block(spot).id)
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("minecraft:dirt minecraft:birch_sapling"), "printed");
			helper.assertBlockPresent(Blocks.DIRT, SPOT_2.below());
			helper.assertBlockPresent(Blocks.AIR, outside);
		});
	}

	/** tend for a spot with stone under it and no pickaxe (#767): the bot takes the chest's and plants. */
	// Measured: passes at tick 51 (red: printed plant: no_tool at ..., minecraft:stone minecraft:air).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 100)
	public void tendGetsAPickaxeForStoneUnderASpot(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.STONE);
		helper.setBlock(3, 1, 8, Blocks.CHEST);
		helper.getBlockEntity(new BlockPos(3, 1, 8), ChestBlockEntity.class).setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_SAPLING, 2));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 4));
		script(helper, bot, "told = {}\njobs.wood_farm.tend(kind, cell, 12, told)\nprint(block(spot.down()).id, block(spot).id)\n");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("minecraft:dirt minecraft:birch_sapling"), "printed"));
	}

	/**
	 * gets dirt, as stocked and first_cell get a cell's and reap its scaffold, two cells built (#768, as soil's of #767):
	 * the dirt under the spots of both cells is nearer, but the bot digs the dirt outside the cells.
	 */
	// Measured: passes at tick 130 (red: the dirt under the first cell's spot dug).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 260)
	public void getsDigsNoDirtFromUnderTheFarmsSpots(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT_2.below(), Blocks.DIRT);
		BlockPos outside = new BlockPos(4, 0, 17);
		helper.setBlock(outside, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 11, 1, 6);
		script(helper, bot, """
			import lib.cells
			lib.cells.layout("birch_farm", cell[0], (5, 5))
			lib.cells.add("birch_farm", 0)
			lib.cells.add("birch_farm", 1)
			print(jobs.wood_farm.gets("minecraft:dirt", 1, 32)["ok"], count("minecraft:dirt"))
			""");
		Runnable dug = () -> {
			helper.assertValueEqual(said(bot), List.of("True 1"), "printed");
			helper.assertBlockPresent(Blocks.DIRT, SPOT.below());
			helper.assertBlockPresent(Blocks.DIRT, SPOT_2.below());
			helper.assertBlockPresent(Blocks.AIR, outside);
		};
		helper.onEachTick(() -> {
			if (!said(bot).isEmpty()) {
				dug.run();
			}
		});
		helper.succeedWhen(dug);
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
	 * A giant spruce farm run end to end in the 24x40x24 {@code giant} structure (#658): the job gets its kit, 8 spruce
	 * saplings and the cell's materials from the chest, builds its 10x10 cell of four saplings from (6, 0, 4) on dirt,
	 * the test grows them as the game does with seed 6, a 2x2 trunk of 24 (of 13 to 29), and the farm fells it whole,
	 * replants all four spots and stores logs in its depot. The kit carries 31 dirt for a giant, its cell's clear height
	 * (scaffold): with the 16 of other kinds the walks to its top stands got nowhere, the falls off the leaves left the
	 * bot at 1 health and it died on the retry. With 31 it still fell: taking back a scaffold pillar whose lower blocks it
	 * took back from the ground before, it stood on the last ones to mine them (#668), so the test fails once the bot is
	 * hurt. Since #682 a drop that hurts is costed as its share of a death and taken where it is cheaper, so the bot may
	 * be hurt again (seen at health 11): it recovers from a fall (#679). Seed 6 rather than a random tree: the tallest, 28 and 29, took some 8000 to 9000 ticks before #677, some 4200
	 * since, some 4500 to 4800 since #687 (seeds 15 and 58; TreeFarmGameTest theTallestGiantSpruceIsFelledWhole fells the one of 29).
	 */
	// Measured: passes at tick 4604 to 4862 (red: died at 4496; with 31 dirt, hurt at tick 3005 to 3640).
	// Since #677 (scaffold taken back on the way down), 3441 to 4217 in 15 runs. Since #682, which breaks ties in the path
	// search the same way whatever else is in its queue, 4511 to 4990 in 7 at once (the felling 4088 to 4582; 4149 to 4653
	// in 7 more). Since #687, where a path no longer stands on a block it broke (the climb to the top stand did, and the bot
	// fell off it), 3623 to 3912 in 14 runs, 7 at once (the felling 3207 to 3495). Since #713 (#715), 3692 to 3979 in 8
	// runs, 12 to 15 giants at once (the felling 3266 to 3555), so HARVEST["giant_spruce"] 3300 and maxTicks 8000 hold.
	// Since #752 (#761), 3555 to 3814 in 8 copies at once with 6 killed giant jungles and 6 mangroves (the felling 3057 to
	// 3316, 3171 on average), so HARVEST["giant_spruce"] 3200.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 8000)
	public void aGiantSpruceFarmFellsItsFirstTreeAndReplants(GameTestHelper helper) {
		giantFarm(helper, "giant_spruce", Items.SPRUCE_SAPLING, Blocks.SPRUCE_SAPLING, Blocks.SPRUCE_LOG, Items.SPRUCE_LOG, 31, 6,
			GIANT_SPRUCE_HARVEST, 0);
	}

	/**
	 * A giant jungle farm run end to end as the giant spruce one (#669): the kit with 33 dirt (the cell's clear height),
	 * 8 jungle saplings, the cell of four, the four grown with seed 6, a trunk of 16 with its side branches, 67 logs,
	 * felled whole, four saplings replanted and logs in the depot. The felling takes within {@link #HARVEST_FACTOR} of
	 * HARVEST["giant_jungle"], which was 6500, some 97 logs at the giant spruce's rate; a giant jungle fells at some 35
	 * ticks a log (the mean of seeds 1 to 40: a trunk of 20.5, 87 logs). Seed 6 rather than a typical tree: seed 37 (a
	 * trunk of 20, 89 logs) failed 4 of 9 runs (before and after #665, #670 and #671), the bot falling to its death or
	 * leaving a branch log at (11, 18, 6), and seeds 8, 17 and 12 (trunks of 21, 24 and 28) killed it by falls each time
	 * (#668); since #713 they pass with no death (#715, temporary copies: the felling 3439, 3836 and 4994 to 5021 ticks). It fails once the bot is hurt (#673): it walked on jungle leaves left with no log in range, which decayed
	 * under it, and, landing over the edge of a block after a drop, planned from the air beside it and walked off. Seed 37
	 * no longer hurt it, but still left the branch log at (11, 18, 6) in 2 of 6 runs. Since #674 it was hurt again in 2 of 3
	 * runs (a fall to the floor); a fall, even a fatal one, is a move the farm recovers from (#679), so it may be hurt.
	 */
	// Measured: passes at tick 2597 to 4012 (red: hurt in 5 of 18 runs at tick 1709 to 3341), the felling 2085 to 3416 ticks.
	// Since #677, 2548 to 3481 in 14 runs; with #679's unhurt=false, where a fall may kill it, 2520 to 4172 in 5.
	// Since #687 (#695), 2511 to 3782 in 21 runs, 7 at once, the felling 2056 to 3340 (2685 on average in the 14 runs
	// with no kill), so HARVEST["giant_jungle"] 2700. Killed 8 up as it fells (a temporary copy), it respawned, ran the
	// job again and passed at 3534 to 3671 in 3 (red, watching for a warning: failed on its death warning at tick 784).
	// Since #713 (#715), 2427 to 3197 in 6 runs with no death, the felling 1948 to 2757 (2270 on average); HARVEST stays
	// 2700, the mean of these and the 8 killed runs below (2714), within HARVEST_FACTOR of both.
	// No seed 12 (trunk 28) farm test (#731): 6 temporary copies at once passed at tick 4489 to 7397, the felling 4057 to
	// 6953, no death. That is slower than the suite's slowest test (the seed 37 giant jungle, TreeFarmGameTest, 2467 to
	// 4909, once 7809) and would need maxTicks some 15000, so a full run would last longer; and its felling is past
	// HARVEST_FACTOR of HARVEST["giant_jungle"] (at most 4050), as a typical giant jungle is no trunk of 28.
	// Since #752 (#761; once 1782 in #751, under 2700's window), 2491 to 3446 in 16 copies at once, the felling 2009 to
	// 2970 (2223 on average), so HARVEST["giant_jungle"] 2250, the mean with no death; the killed test below has its own.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 7000)
	public void aGiantJungleFarmFellsItsFirstTreeAndReplants(GameTestHelper helper) {
		giantFarm(helper, "giant_jungle", Items.JUNGLE_SAPLING, Blocks.JUNGLE_SAPLING, Blocks.JUNGLE_LOG, Items.JUNGLE_LOG, 33, 6,
			GIANT_JUNGLE_HARVEST, 0);
	}

	/**
	 * The giant jungle farm above with the bot killed 8 blocks up as it fells the giant (#715): it respawns, runs the job
	 * again, takes back its scaffold and picks up its drops, and the farm finishes as above.
	 */
	// Measured: passes at tick 2972 to 4098 in 3 runs before #713, 3140 to 3855 in 8 since, the felling 2672 to 3385.
	// Since #752 (#761), 2899 to 4259 in 6 copies at once, the felling 2426 to 3763 (3112 on average): past
	// HARVEST_FACTOR of HARVEST["giant_jungle"] 2250, so it checks KILLED_GIANT_JUNGLE_HARVEST, 3100.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 8500)
	public void aBotKilledWhileFellingAGiantJungleComesBackAndFinishesTheFarm(GameTestHelper helper) {
		giantFarm(helper, "giant_jungle", Items.JUNGLE_SAPLING, Blocks.JUNGLE_SAPLING, Blocks.JUNGLE_LOG, Items.JUNGLE_LOG, 33, 6,
			KILLED_GIANT_JUNGLE_HARVEST, 9);
	}

	/**
	 * A mangrove farm run end to end as the giant ones (#751): the kit with 23 dirt (the cell's clear height), 8
	 * propagules, the cell of 11, the propagule grown with seed 0 (a TALL_MANGROVE on roots, as TreeFarmGameTest's), felled
	 * whole, its roots mined, a propagule replanted and logs in the depot, the felling within {@link #HARVEST_FACTOR} of
	 * HARVEST["minecraft:mangrove_propagule"].
	 */
	// Measured: passes at tick 1876 to 2085 in 3 runs, the felling within HARVEST_FACTOR of 1400. Since #752 (#761),
	// 1880 to 2227 in 6 copies at once, the felling 1398 to 1745 (1556 on average): HARVEST stays 1400, the mean of 39
	// seeded mangroves rather than this one tree.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 4500)
	public void aMangroveFarmFellsItsFirstTreeAndReplants(GameTestHelper helper) {
		farm(helper, "mangrove_propagule", Items.MANGROVE_PROPAGULE, Blocks.MANGROVE_PROPAGULE, Blocks.MANGROVE_LOG,
			Items.MANGROVE_LOG, 23, 0, 1400, 0, 11, List.of(new BlockPos(11, 1, 9)));
	}

	/** HARVEST of each giant is the one its farm test above fells within {@link #HARVEST_FACTOR} of (#669). */
	@GameTest(maxTicks = 10)
	public void aGiantsHarvestIsWhatItsFarmTestMeasures(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		script(helper, bot, "print(jobs.wood_farm.HARVEST['giant_spruce'], jobs.wood_farm.HARVEST['giant_jungle'])");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of(GIANT_SPRUCE_HARVEST + " " + GIANT_JUNGLE_HARVEST), "said"));
	}

	/** jobs.wood_farm's HARVEST of each giant, as aGiantsHarvestIsWhatItsFarmTestMeasures checks. */
	private static final int GIANT_SPRUCE_HARVEST = 3200;
	private static final int GIANT_JUNGLE_HARVEST = 2250;
	/** The ticks a giant jungle takes the bot killed up the tree, which its farm test fells within HARVEST_FACTOR of (#761). */
	private static final int KILLED_GIANT_JUNGLE_HARVEST = 3100;

	/** How far a giant's felling ticks may be from its HARVEST, either way. */
	private static final double HARVEST_FACTOR = 1.5;

	/**
	 * A giant farm of kind from (6, 0, 4) in the giant structure: a stone axe, dirt, 2 torches and 8 saplings in a chest;
	 * once the cell is built and planted, the test grows its four saplings as the game does with seed. It passes when no
	 * log of the tree is left in the area, the four spots hold saplings and the depot holds logs, the ticks from the
	 * growth to then within {@link #HARVEST_FACTOR} of harvest. It watches the bot by its UUID,
	 * as one that dies (#694) respawns as a new entity and runs the job again, which recovers. It fails on a warning but
	 * a death's ("died at"), as a fall may kill the bot (#715). With killAt above 0 it kills the bot the first time it
	 * stands at that y once the giant grew, and passes only once the bot died.
	 */
	private static void giantFarm(GameTestHelper helper, String kind, Item saplingItem, Block saplingBlock, Block logBlock,
		Item logItem, int dirt, long seed, int harvest, int killAt) {
		farm(helper, kind, saplingItem, saplingBlock, logBlock, logItem, dirt, seed, harvest, killAt, 10,
			List.of(new BlockPos(10, 1, 8), new BlockPos(11, 1, 8), new BlockPos(10, 1, 9), new BlockPos(11, 1, 9)));
	}

	/**
	 * As {@link #giantFarm} for any kind: its cell size wide from (6, 0, 4) on dirt, its sapling spots spots, the tree
	 * grown at the first, which then no longer holds a sapling (a mangrove's holds air or a root).
	 */
	private static void farm(GameTestHelper helper, String kind, Item saplingItem, Block saplingBlock, Block logBlock,
		Item logItem, int dirt, long seed, int harvest, int killAt, int size, List<BlockPos> spots) {
		for (int x = 6; x < 6 + size; x++) {
			for (int z = 4; z < 4 + size; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		helper.setBlock(3, 1, 16, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(3, 1, 16), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.STONE_AXE));
		chest.setItem(1, new ItemStack(Items.DIRT, dirt));
		chest.setItem(2, new ItemStack(Items.TORCH, 2));
		chest.setItem(3, new ItemStack(saplingItem, 8));
		AntBot first = helper.spawn(ModEntities.ANT_BOT, 4, 1, 18);
		UUID id = first.getUUID();
		BlockPos at = helper.absolutePos(new BlockPos(6, 1, 4));
		List<String> out = BotScripts.command(helper, "antcraft bot " + first.getStringUUID() + " run jobs.wood_farm " + kind + " "
			+ at.getX() + " " + at.getY() + " " + at.getZ() + " 1 20");
		helper.assertTrue(first.getScript() != null, "not run: " + out);
		Supplier<@Nullable AntBot> bot = () -> helper.getLevel().getEntity(id) instanceof AntBot b && b.isAlive() ? b : null;
		BotScripts.failOnWarningButDeath(helper, bot);
		long[] grewAt = {-1};  // the tick the giant grew on
		long[] took = {-1};  // the ticks from then to the first tick all is done
		boolean[] killed = {false};
		helper.onEachTick(() -> {
			AntBot now = bot.get();
			if (now == null) {
				return;
			}
			if (said(now).stream().anyMatch(l -> l.contains("stopped"))) {
				helper.fail("said " + BotScripts.log(now, Output.Level.DEBUG));
			}
			if (killAt > 0 && grewAt[0] >= 0 && !killed[0] && helper.relativePos(now.blockPosition()).getY() >= killAt) {
				killed[0] = true;
				now.kill(helper.getLevel());
				return;
			}
			if (grewAt[0] < 0 && said(now).contains(kind + "_farm 4/4: farm") && helper.getBlockState(spots.get(0)).is(saplingBlock)) {
				BlockPos a = helper.absolutePos(spots.get(0));
				BlockState sapling = helper.getLevel().getBlockState(a);
				((SaplingBlock) sapling.getBlock()).advanceTree(helper.getLevel(), a, sapling.setValue(SaplingBlock.STAGE, 1),
					RandomSource.create(seed));
				if (!helper.getBlockState(spots.get(0)).is(saplingBlock)) {
					grewAt[0] = helper.getTick();
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.get() != null, "no bot alive");
			AntBot now = Objects.requireNonNull(bot.get());
			helper.assertTrue(grewAt[0] >= 0, "the giant never grew; said " + said(now));
			helper.assertTrue(killAt == 0 || now.getDeaths() >= 1, "not killed; said " + said(now));
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 23, 39, 23)) {
				if (helper.getBlockState(p).is(logBlock)) {
					helper.fail("a log left at " + p + ", health " + now.getHealth() + " at " + helper.relativePos(now.blockPosition())
						+ "; said " + said(now));
				}
			}
			for (BlockPos p : spots) {
				helper.assertTrue(helper.getBlockState(p).is(saplingBlock), "no sapling at " + p.toShortString() + ", bot at "
					+ TestPositions.relative(helper, now.blockPosition()).toShortString() + "; said " + said(now));
			}
			int stored = 0;
			for (int x = 6; x < 16; x++) {
				if (helper.getBlockState(new BlockPos(x, 1, 2)).is(Blocks.CHEST)) {
					stored += helper.getBlockEntity(new BlockPos(x, 1, 2), ChestBlockEntity.class).countItem(logItem);
				}
			}
			helper.assertTrue(stored > 0, "no logs in the depot; said " + said(now));
			if (took[0] < 0) {
				took[0] = helper.getTick() - grewAt[0];
			}
			helper.assertTrue(took[0] >= harvest / HARVEST_FACTOR && took[0] <= harvest * HARVEST_FACTOR,
				"felled in " + took[0] + " ticks, HARVEST " + harvest);
		});
	}

	/**
	 * most(harvest): the cells at which the bot is always busy, a sapling's mean growth time (2 stages of 1 in 7 random
	 * ticks, 3 a tick in a section of 4096 blocks) over the ticks a tree takes; at least 1. Imported, the job runs nothing.
	 */
	@GameTest(maxTicks = 10)
	public void mostIsTheGrowthTimeOverTheHarvestTime(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		ScriptRun job = BotScripts.run(bot, """
			import jobs.wood_farm
			import lib.treefarm
			h = jobs.wood_farm.HARVEST
			print(int(jobs.wood_farm.GROWTH), jobs.wood_farm.most(400), jobs.wood_farm.most(10 ** 6), sorted(h) == sorted(lib.treefarm.KINDS))
			print(jobs.wood_farm.most(h["minecraft:birch_sapling"]), jobs.wood_farm.most(h["minecraft:dark_oak_sapling"]))
			""");
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(job.state() == ScriptRun.State.FINISHED, "not finished");
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
		run(helper, bot, cells, 12);
	}

	/** Runs jobs.wood_farm for birch at AT, up to cells cells, radius radius. */
	private static void run(GameTestHelper helper, AntBot bot, int cells, int radius) {
		BlockPos at = helper.absolutePos(AT);
		List<String> said = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.wood_farm birch_sapling "
			+ at.getX() + " " + at.getY() + " " + at.getZ() + " " + cells + " " + radius);
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
