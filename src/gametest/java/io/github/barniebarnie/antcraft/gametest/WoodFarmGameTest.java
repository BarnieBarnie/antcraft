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
import java.util.function.IntSupplier;
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
			} else if (grew[0] && died[0] < 0 && bot.isAlive() && TestPositions.relative(helper, bot.blockPosition()).getY() >= 4) {
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
	// Full runs: passes at up to tick 178 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 360)
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
	// Full runs: passes at up to tick 187 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 380)
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
			lib.cellfarm.wait = counted
			lib.cellfarm.stocked = lambda k, r: lib.result.result("no_item", None, {"minecraft:torch": 2})
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
			lib.cellfarm.wait = counted
			lib.cellfarm.stocked = lambda k, r: lib.result.result("no_item", None, {"minecraft:torch": 2})
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
	// Measured: passes at tick 5, some 40 ticks more with a walk into the corner's chunk before its first wait (#814).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 70)
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
			lib.cellfarm.wait = counted
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
	// Up to some 40 ticks more with a walk into the corner's chunk before its first wait (#814).
	// Full runs: passes at up to tick 52 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 110)
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
			lib.cellfarm.wait = counted
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
	// Up to some 40 ticks more with a walk into the corner's chunk before its first wait (#814).
	// Full runs: passes at up to tick 57 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 120)
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
			lib.cellfarm.wait = counted
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
	 * mean instead of HARVEST (#653; memory["wood_ticks"], a list of the last WINDOW, #662). Two trees of 2 GROWTH each are in memory, so harvest() is still HARVEST's 250; a
	 * third felled in the farm's first round makes the mean over GROWTH, so most() is 1 and the farm of one cell, run
	 * with no size, is full. With HARVEST it would have gone on to a second cell.
	 */
	// Measured: passes at tick 14, the farm's cells that tick worked out once (#812).
	// Up to some 40 ticks more with a walk into the corner's chunk before its first wait (#814).
	// Full runs: passes at up to tick 56 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 120)
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
			lib.cellfarm.wait = waited
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.treefarm.grown = lambda k, low, high: tree[0]
			lib.depot.store = lambda name, keep, radius: lib.result.result()
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", None, 12)
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("250", "farm full", "3 True"), "said"));
	}

	/**
	 * A bot carrying 5 stacks of dirt stores all but a stack of it in the depot in the round that fells a tree (#762):
	 * the birch's kit and a cell take less than a stack, so a stack is what it keeps. It carries a chest for the depot.
	 */
	// Measured: passes at tick 24.
	// Up to some 40 ticks more with a walk into the corner's chunk before its first wait (#814).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 100)
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
			lib.cellfarm.wait = waited
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
	 * A mangrove's mangrove roots, muddy mangrove roots and moss carpet, which harvest brings in (#768), go in the
	 * logs chest of the farm's depot, not a chest of their own (#788). The bot carries a chest and some logs; it stores
	 * them all with lib.depot.store and the depot has the one logs chest.
	 */
	// Measured: passes at tick 27 (red: a second chest, other, for the roots and moss).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 60)
	public void aMangrovesRootsAndMossGoInTheLogsChest(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.CHEST));
		bot.getInventory().addItem(new ItemStack(Items.MANGROVE_LOG, 4));
		bot.getInventory().addItem(new ItemStack(Items.MANGROVE_ROOTS, 4));
		bot.getInventory().addItem(new ItemStack(Items.MUDDY_MANGROVE_ROOTS, 4));
		bot.getInventory().addItem(new ItemStack(Items.MOSS_CARPET, 4));
		BlockPos depot = helper.absolutePos(new BlockPos(6, 1, 0));
		script(helper, bot, """
			import lib.depot
			lib.depot.depot("mangrove_farm", Pos(%d, %d, %d), "east", jobs.wood_farm.CATEGORIES)
			print(lib.depot.store("mangrove_farm", [], 12)["ok"])
			print([c[0] for c in lib.depot.depot("mangrove_farm")["chests"]])
			""".formatted(depot.getX(), depot.getY(), depot.getZ()));
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("True", "['logs']"), "said"));
	}

	/**
	 * As storesItsDirtButAStack in a round that fells nothing (#766): no tree is grown, yet the bot stores all but a
	 * stack of its 5 stacks of dirt before it waits, so what builds up between fellings does not fill its inventory.
	 */
	// Measured: passes at tick 23 to 40, with a walk into the corner's chunk before its wait (#814) (red: kept all 320 dirt).
	// Full runs: passes at up to tick 52 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 110)
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
			lib.cellfarm.wait = waited
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
	 * lib.depot.make_room for free(kind).
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
			lib.cellfarm.wait = waited
			w.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.depot.make_room = roomed
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
	// Measured: passes at tick 11, the farm's cells that tick worked out once (#812).
	// Up to some 40 ticks more with a walk into the corner's chunk before its first wait (#814).
	// Full runs: passes at up to tick 54 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 110)
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
			lib.cellfarm.wait = waited
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
	 * A farm with no size builds only the cells whose sapling spots lie in the chunks that get random ticks while the bot
	 * stands at the farm's corner, its own and the ring round it (#812): its corner at x and z 15 of a chunk, so birch spots
	 * 2, 8 and 14 blocks east and south fit and those 20 off do not, which leaves 9 cells, cells 4, 8 and 12 skipped. The
	 * cells are not built in the world (build_cell and what a round does to them are stubbed). Red: it built cell 4 on.
	 */
	// Measured: passes at tick 40 (red: cells 1/76 and cell 4 built); corner stubbed (#820).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 80)
	public void aFarmWithNoSizeStopsAtTheCellsThatGetRandomTicks(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		capScript(helper, bot, "None", 10, "[]");
		List<Integer> cells = List.of(1, 2, 3, 5, 6, 7, 9, 10, 11);
		List<String> said = new java.util.ArrayList<>();
		for (int i = 0; i < cells.size(); i++) {
			said.add("built cell " + cells.get(i));
			said.add("cells " + (i + 1) + "/9");
		}
		said.add("farm full");
		said.add("[0, 1, 2, 4, 5, 6, 8, 9, 10]");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), said, "said"));
	}

	/** As aFarmWithNoSizeStopsAtTheCellsThatGetRandomTicks with a size of 12: the farm builds 12 cells in turn (#812). */
	// Measured: passes at tick 47; corner stubbed (#820).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 95)
	public void aSizeBuildsThatManyCellsPastTheRandomTicks(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		capScript(helper, bot, "12", 13, "[]");
		List<String> said = new java.util.ArrayList<>();
		for (int i = 1; i <= 12; i++) {
			said.add("built cell " + i);
			said.add("cells " + i + "/12");
		}
		said.add("farm full");
		said.add("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), said, "said"));
	}

	/**
	 * As aFarmWithNoSizeStopsAtTheCellsThatGetRandomTicks with cells 4, 8 and 12, outside the cells that tick, built
	 * already (under a size, or before #812): they do not count toward the cap, so the farm still builds the 9 cells that
	 * tick, then says full (#815). Red: "farm full" after 6 cells.
	 */
	// Measured: passes at tick 51 (red: farm full at cells 9/9 after 6 built); corner stubbed (#820).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 100)
	public void cellsOutsideTheTicksDoNotCountTowardTheCap(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		capScript(helper, bot, "None", 10, "[3, 7, 11]");
		List<Integer> cells = List.of(1, 2, 3, 5, 6, 7, 9, 10, 11);
		List<String> said = new java.util.ArrayList<>();
		for (int i = 0; i < cells.size(); i++) {
			said.add("built cell " + cells.get(i));
			said.add("cells " + (i + 1) + "/9");
		}
		said.add("farm full");
		said.add("[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), said, "said"));
	}

	/**
	 * A new birch farm started at chunk-local x and z 15 has 9 cells that tick; the corner at the north-west block of
	 * the chunk south-east of it, one block off, has 20, so run says so, once: run again from the stored corner, it says
	 * nothing more (#813). The project's steps are stubbed.
	 */
	// Measured: passes at tick 13, two corners' cells counted (red: no hint).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 26)
	public void aCornerThatFitsFewerCellsSaysWhereMoreFit(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos s = helper.absolutePos(SPOT);
		int x = Math.floorDiv(s.getX(), 16) * 16 + 15;
		int z = Math.floorDiv(s.getZ(), 16) * 16 + 15;
		cornerScript(helper, bot, x, z);
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of(
			"a corner at " + (x + 1) + " " + s.getY() + " " + (z + 1) + " fits 20 growing cells, this one 9",
			"birch_farm stopped: stub at kit", "birch_farm stopped: stub at kit"), "said"));
	}

	/** As aCornerThatFitsFewerCellsSaysWhereMoreFit from chunk-local x and z 0, which fits the most: no hint (#813). */
	// Measured: passes at tick 5, nothing counted.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 12)
	public void aCornerThatFitsTheMostCellsSaysNothing(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos s = helper.absolutePos(SPOT);
		cornerScript(helper, bot, Math.floorDiv(s.getX(), 16) * 16, Math.floorDiv(s.getZ(), 16) * 16);
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot),
			List.of("birch_farm stopped: stub at kit", "birch_farm stopped: stub at kit"), "said"));
	}

	/** Runs jobs.wood_farm.run for birch twice, first from x, spot.y, z, its project's steps stubbed. */
	private static void cornerScript(GameTestHelper helper, AntBot bot, int x, int z) {
		script(helper, bot, """
			import lib.project
			import lib.trees
			lib.project.run = lambda name, steps: {"reason": "stub", "at": "kit"}
			lib.trees.recover = lambda: None
			jobs.wood_farm.run(kind, Pos(%d, spot.y, %d))
			jobs.wood_farm.run(kind)
			""".formatted(x, z));
	}

	/**
	 * Runs jobs.wood_farm.farm for birch up to size cells (a Python expression), laid out from a corner at x and z 15 of
	 * the chunk of the test's spot, each cell built by a stub, no tree grown; at the waits'th wait prints the cells built
	 * and stops. The cells of built (a Python list of indices) are marked built first. The walk into the corner's chunk
	 * (corner, #814) is stubbed: 6 to 12 blocks east of the bot, by where the area lands in its chunk, it took up to some
	 * 60 ticks (#820).
	 */
	private static void capScript(GameTestHelper helper, AntBot bot, String size, int waits, String built) {
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.terrain
			import lib.treefarm
			lib.cells.layout("birch_farm", Pos(spot.x // 16 * 16 + 15, cell[0].y, spot.z // 16 * 16 + 15), (5, 5))
			for i in %s:
			    lib.cells.add("birch_farm", i)
			waits = [0]
			def waited(ticks):
			    if ticks != jobs.wood_farm.WAIT:
			        return
			    waits[0] += 1
			    if waits[0] == %d:
			        print(lib.cells.built("birch_farm"))
			        stop()
			lib.cellfarm.wait = waited
			lib.cellfarm.visit = lambda k, b: None
			jobs.wood_farm.unplanted = lambda k, b: []
			jobs.wood_farm.tend = lambda k, b, radius, told: None
			jobs.wood_farm.stored_bone_meal = lambda k, name, boxes, radius: None
			jobs.wood_farm.compost = lambda k, name, boxes, radius: lib.result.result()
			jobs.wood_farm.sweep = lambda k, boxes: None
			lib.cellfarm.corner = lambda name, told: None
			lib.cellfarm.stocked = lambda k, radius: lib.result.result()
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result()
			lib.depot.store = lambda name, keep, radius: lib.result.result()
			lib.terrain.needs = lambda low, high, y: {"tools": {}}
			lib.treefarm.build_cell = lambda k, low, high: lib.result.result()
			lib.treefarm.grown = lambda k, low, high: False
			jobs.wood_farm.farm(kind, "birch_farm", %s, 12)
			""".formatted(built, waits, size));
	}

	/**
	 * A full farm whose bot has more than EXTRA (32) saplings beyond what replanting keeps composts them (#804): it carries
	 * 66 birch saplings (2 kept), 7 birch slabs, a crafting table and a chest. It makes a composter at (4, 1, 0), 2 west of
	 * the depot, composts the 64 extras into bone meal (at least 1: the first sapling always fills a layer, the rest 30% of
	 * the time) and, with no sapling of the farm to use it on (no cell built), stores it in the depot's bone_meal chest.
	 */
	// Measured: passes at tick 253 (red: no composter).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 500)
	public void aFullFarmCompostsItsExtraSaplingsIntoBoneMeal(GameTestHelper helper) {
		AntBot bot = compostBot(helper, 66);
		farmScript(helper, bot, 1, 2);
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.COMPOSTER, 4, 1, 0);
			helper.assertValueEqual(said(bot), List.of("farm full", "2"), "said");
			helper.assertTrue(depotHolds(helper, Items.BONE_MEAL) > 0, "no bone meal in the depot");
		});
	}

	/** As aFullFarmCompostsItsExtraSaplingsIntoBoneMeal with 32 extras: no composter (#804). */
	// Measured: passes at tick 8.
	// Up to some 40 ticks more with a walk into the corner's chunk before its first wait (#814).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 60)
	public void aFullFarmWith32ExtraSaplingsMakesNoComposter(GameTestHelper helper) {
		AntBot bot = compostBot(helper, 34);
		farmScript(helper, bot, 1, 2);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("farm full", "34"), "said");
			helper.assertBlockNotPresent(Blocks.COMPOSTER, 4, 1, 0);
		});
	}

	/** As aFullFarmCompostsItsExtraSaplingsIntoBoneMeal in a farm that still builds cells: no composter (#804). */
	// Measured: passes at tick 7.
	// Up to some 40 ticks more with a walk into the corner's chunk before its first wait (#814).
	// Full runs: passes at up to tick 51 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 110)
	public void aFarmStillBuildingMakesNoComposter(GameTestHelper helper) {
		AntBot bot = compostBot(helper, 66);
		farmScript(helper, bot, 2, 2);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("next cell: no_item, lacks {'minecraft:dirt': 1}", "66"), "said");
			helper.assertBlockNotPresent(Blocks.COMPOSTER, 4, 1, 0);
		});
	}

	/**
	 * fertilize uses the bot's bone meal on the farm's saplings (#804): a stack of 64 bone meal on the birch sapling of
	 * cell 1 grows it into a tree (each advances it a stage 45% of the time, 2 stages make a tree), and it keeps the rest.
	 * With 16, about 1 run in 250 used them all or grew no tree (#895); 64 run out about once in 10^14.
	 */
	// Measured: passes at tick 27 (red: no fertilize); 28 at the slowest of 750 copies.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 60)
	public void fertilizeGrowsTheFarmsSaplingsWithBoneMeal(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.BONE_MEAL, 64));
		script(helper, bot, """
			jobs.wood_farm.fertilize(kind, [cell])
			print(0 < count("minecraft:bone_meal") < 64)
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("True"), "said");
			helper.assertBlockPresent(Blocks.BIRCH_LOG, SPOT);
		});
	}

	/**
	 * The farm uses the bone meal stored in its depot on its saplings (#809): the bot carries 32 bone meal, which the
	 * first round's store puts in the depot's bone_meal chest; the farm takes it out again for the birch sapling of cell 1,
	 * which grows into a tree, and the next round's store puts back what is left.
	 */
	// Measured: passes at tick 36 (red: the sapling never grows).
	// Up to some 40 ticks more with a walk into the corner's chunk before its first wait (#814).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 120)
	public void theFarmUsesTheBoneMealStoredInItsDepot(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT, Blocks.BIRCH_SAPLING);
		AntBot bot = compostBot(helper, 2);
		bot.getInventory().addItem(new ItemStack(Items.BONE_MEAL, 32));
		BlockPos depot = helper.absolutePos(new BlockPos(6, 1, 0));
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			lib.depot.depot("birch_farm", Pos(%d, %d, %d), "east", jobs.wood_farm.CATEGORIES)
			waits = [0]
			def waited(ticks):
			    waits[0] += 1
			    if waits[0] == 2:
			        print(count("minecraft:bone_meal"))
			        stop()
			lib.cellfarm.wait = waited
			jobs.wood_farm.sweep = lambda k, boxes: None
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.treefarm.grown = lambda k, low, high: False
			lib.cells.built = lambda name: [0]
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", 1, 12)
			""".formatted(depot.getX(), depot.getY(), depot.getZ()));
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("farm full", "0"), "said");
			helper.assertBlockPresent(Blocks.BIRCH_LOG, SPOT);
			int left = depotHolds(helper, Items.BONE_MEAL);
			helper.assertTrue(0 < left && left < 32, "bone meal in the depot: " + left);
		});
	}

	/**
	 * A built cell in a chunk that is not loaded, 30000 blocks off, where block() gives None (#810): the farm's round
	 * leaves it alone and tends cell 1 (sapling and torches); once the cell lies where the bot can read it (the test moves
	 * it to cell 2's place at the first wait), the next round plants it too. Red: the round crashed on None.id. The farm's
	 * walk to a cell out of range (visit, #811) is left out here.
	 */
	// Measured: passes at tick 70 to 111, with walks into the corner's chunk before its waits (#814) (red:
	// jobs/wood_farm.py:308: 'NoneType' object has no attribute 'id' at tick 8).
	// Full runs: passes at up to tick 163 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 330)
	public void aCellOutOfLoadedRangeIsLeftUntilTheBotCanReadIt(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT_2.below(), Blocks.DIRT);
		AntBot bot = compostBot(helper, 4);
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 4));
		BlockPos depot = helper.absolutePos(new BlockPos(6, 1, 0));
		BlockPos s2 = helper.absolutePos(SPOT_2);
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			lib.depot.depot("birch_farm", Pos(%d, %d, %d), "east", jobs.wood_farm.CATEGORIES)
			far = (cell[0] + (-30000, 0, -30000), cell[1] + (-30000, 0, -30000))
			boxes = {0: cell, 1: far}
			waits = [0]
			def waited(ticks):
			    if ticks != jobs.wood_farm.WAIT:
			        return
			    waits[0] += 1
			    if waits[0] == 1:
			        print(block(spot).id, block(far[0]) is None)
			        boxes[1] = (cell[0] + (6, 0, 0), cell[1] + (6, 0, 0))
			    else:
			        print(block(Pos(%d, %d, %d)).id)
			        stop()
			lib.cellfarm.wait = waited
			lib.cellfarm.visit = lambda k, b: None  # not 30000 blocks off: #811's test walks to a far cell
			jobs.wood_farm.sweep = lambda k, boxes: None
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.cells.built = lambda name: [0, 1]
			lib.cells.cell = lambda name, i: boxes[i]
			lib.cells.count = lambda name: 2
			jobs.wood_farm.farm(kind, "birch_farm", 2, 12)
			""".formatted(depot.getX(), depot.getY(), depot.getZ(), s2.getX(), s2.getY(), s2.getZ()));
		helper.succeedWhen(() -> {
			helper.assertValueEqual(said(bot), List.of("farm full", "minecraft:birch_sapling True", "minecraft:birch_sapling"), "said");
			assertCell(helper, bot, SPOT);
		});
	}

	/**
	 * A farm whose cell 2, 12 blocks south-east of cell 1, lies out of the bot's loaded range (#811): the test's
	 * lib.treefarm.loaded stands in for the chunks a bot keeps loaded, a cell loaded only within 6 blocks of the bot (of
	 * its low corner), and nothing else brings the bot there. The farm walks to it and tends it (sapling and torches), as well as
	 * cell 1. Red: cell 2 was never tended.
	 */
	// Measured: passes at tick 293 (red: cell 2 never tended).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 600)
	public void aCellOutOfLoadedRangeIsVisitedAndTended(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		BlockPos far = SPOT.offset(12, 0, 12);
		helper.setBlock(far.below(), Blocks.DIRT);
		AntBot bot = compostBot(helper, 4);
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 4));
		BlockPos depot = helper.absolutePos(new BlockPos(6, 1, 0));
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			lib.depot.depot("birch_farm", Pos(%d, %d, %d), "east", jobs.wood_farm.CATEGORIES)
			boxes = {0: cell, 1: (cell[0] + (12, 0, 12), cell[1] + (12, 0, 12))}
			waits = [0]
			def waited(ticks):
			    if ticks != jobs.wood_farm.WAIT:
			        return
			    waits[0] += 1
			    if waits[0] == 3:
			        stop()
			lib.cellfarm.wait = waited
			jobs.wood_farm.sweep = lambda k, boxes: None
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.treefarm.loaded = lambda k, low, high: pos().distance(low) <= 6
			lib.cells.built = lambda name: [0, 1]
			lib.cells.cell = lambda name, i: boxes[i]
			lib.cells.count = lambda name: 2
			jobs.wood_farm.farm(kind, "birch_farm", 2, 12)
			""".formatted(depot.getX(), depot.getY(), depot.getZ()));
		helper.succeedWhen(() -> {
			assertCell(helper, bot, SPOT);
			assertCell(helper, bot, far);
		});
	}

	/**
	 * A farm whose cells 2 and 3, 12 blocks east and south-east of cell 1, lie out of the bot's loaded range (the test's
	 * lib.treefarm.loaded, as in aCellOutOfLoadedRangeIsVisitedAndTended) tends cell 1, at its corner, every round, and
	 * visits one far cell a round (#819). The test's corner walks back to cell 1 before each wait. Red: cell 1 was tended
	 * only in every third round, the one that visited no far cell.
	 */
	// Measured: passes at tick 423 (red: said False, False, True).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 860)
	public void aFarmWithTwoFarCellsTendsItsCornerCellEveryRound(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 7, 1, 3);
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			boxes = {0: cell, 1: (cell[0] + (12, 0, 0), cell[1] + (12, 0, 0)), 2: (cell[0] + (12, 0, 12), cell[1] + (12, 0, 12))}
			tended = []
			def tend(k, b, radius=32, told=None):
			    tended.append(b == boxes[0])
			waits = [0]
			def waited(ticks):
			    if ticks != jobs.wood_farm.WAIT:
			        return
			    print(True in tended)
			    tended.clear()
			    waits[0] += 1
			    if waits[0] == 3:
			        stop()
			lib.cellfarm.wait = waited
			jobs.wood_farm.tend = tend
			jobs.wood_farm.unplanted = lambda k, b: []
			jobs.wood_farm.sweep = lambda k, boxes: None
			lib.cellfarm.corner = lambda name, told: goto(cell[0] + (1, 1, 1))
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.depot.store = lambda name, keep, radius: lib.result.result()
			lib.treefarm.grown = lambda k, low, high: False
			lib.treefarm.loaded = lambda k, low, high: pos().distance(low) <= 6
			lib.cells.built = lambda name: [0, 1, 2]
			lib.cells.cell = lambda name, i: boxes[i]
			lib.cells.count = lambda name: 3
			jobs.wood_farm.farm(kind, "birch_farm", 3, 12)
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("farm full", "True", "True", "True"), "said"));
	}

	/**
	 * A bot that ends a round at the far side of the farm, (22, 1, 22), 16 blocks east of cell 1's low corner and so
	 * in another chunk, walks back into the corner's chunk before it waits, so the cells the farm's cap counts (ticking,
	 * #812) grow while it waits (#814). The round does nothing else: no tree grown, no cell to tend, the farm full.
	 */
	// Measured: passes at tick 181 (red: waited in its own chunk).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 360)
	public void aBotWaitsInItsCornersChunk(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 22, 1, 22);
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			def waited(ticks):
			    p = pos()
			    print(p.x // 16 == cell[0].x // 16 and p.z // 16 == cell[0].z // 16)
			    stop()
			lib.cellfarm.wait = waited
			jobs.wood_farm.sweep = lambda k, boxes: None
			lib.depot.store = lambda name, keep, radius: lib.result.result()
			lib.treefarm.grown = lambda k, low, high: False
			lib.cells.built = lambda name: []
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", 1, 12)
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("farm full", "True"), "said"));
	}

	/**
	 * A bot for the compost tests: saplings birch saplings, 7 birch slabs, a crafting table and a chest for the depot,
	 * at (4, 1, 6).
	 */
	private static AntBot compostBot(GameTestHelper helper, int saplings) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		for (int n = saplings; n > 0; n -= 64) {
			bot.getInventory().addItem(new ItemStack(Items.BIRCH_SAPLING, Math.min(n, 64)));
		}
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_SLAB, 7));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
		bot.getInventory().addItem(new ItemStack(Items.CHEST));
		return bot;
	}

	/**
	 * Runs jobs.wood_farm.farm for birch up to size cells, its depot at (6, 1, 0), with one cell counted but none built
	 * (no sapling to tend), no tree grown and gets failing; at the waits'th wait of a round prints the saplings carried and stops.
	 */
	private static void farmScript(GameTestHelper helper, AntBot bot, int size, int waits) {
		BlockPos depot = helper.absolutePos(new BlockPos(6, 1, 0));
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.treefarm
			lib.depot.depot("birch_farm", Pos(%d, %d, %d), "east", jobs.wood_farm.CATEGORIES)
			waits = [0]
			def waited(ticks):
			    if ticks != jobs.wood_farm.WAIT:
			        return  # the composter's
			    waits[0] += 1
			    if waits[0] == %d:
			        print(count(kind))
			        stop()
			lib.cellfarm.wait = waited
			jobs.wood_farm.sweep = lambda k, boxes: None
			jobs.wood_farm.gets = lambda item, n, radius: lib.result.result("no_item", None, {item: n})
			lib.treefarm.grown = lambda k, low, high: False
			lib.cells.built = lambda name: []
			lib.cells.cell = lambda name, i: cell
			lib.cells.count = lambda name: 1
			jobs.wood_farm.farm(kind, "birch_farm", %d, 12)
			""".formatted(depot.getX(), depot.getY(), depot.getZ(), waits, size));
	}

	/** The items of item in the chests of the depot row, (6, 1, 0) to (15, 1, 0). */
	private static int depotHolds(GameTestHelper helper, Item item) {
		int n = 0;
		for (int x = 6; x < 16; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 0)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 1, 0), ChestBlockEntity.class).countItem(item);
			}
		}
		return n;
	}

	/**
	 * A branch log a harvest left off the trunk column, such as an acacia's or a cherry's, is seen in the cell's tree
	 * space and felled (#651): grown looked only over the sapling spots.
	 */
	// Measured: passes at tick 112.
	// Full runs: passes at up to tick 120 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 240)
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
	// Full runs: passes at up to tick 181 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 370)
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

	/**
	 * No tree in the area, 4 birch saplings, a coal, a stone axe and 16 dirt in the chest: the first cell's torches take a
	 * stick, so logs, and there are none (#802). The bot plants a spare sapling off the farm, says once that it waits for
	 * it, the test grows it, and the bot fells it and builds the first cell (red: birch_farm stopped: no_logs at cell).
	 */
	// Measured: passes at tick 314 to 339 (red: stopped with no_logs at cell).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 650)
	public void growsItsOwnFirstTreeWhereThereIsNone(GameTestHelper helper) {
		AntBot bot = farm(helper, 4, 0, true, false);
		helper.getBlockEntity(new BlockPos(3, 1, 8), ChestBlockEntity.class).setItem(4, new ItemStack(Items.COAL));
		run(helper, bot, 1);
		BotScripts.failOnWarning(helper, bot);
		boolean[] grew = growsWhenWaiting(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(grew[0], "no tree grew; said " + said(bot));
			helper.assertTrue(said(bot).contains("built cell 1"), "said " + said(bot));
			helper.assertValueEqual(said(bot).stream().filter(l -> l.equals("waiting for a tree to grow")).count(), 1L, "waits said");
			assertCell(helper, bot, SPOT);
		});
	}

	/**
	 * Only saplings, no axe, no tree and no logs (#808): the kit step, whose stone axe takes sticks, grows a tree of its
	 * own as first_cell does, fells it by hand and gets the axe (red: birch_farm stopped: no_logs at kit).
	 */
	// Measured: passes at tick 710 (red: stopped with no_logs at kit at tick 12).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 1400)
	public void theKitGrowsItsOwnTreeWhereThereIsNone(GameTestHelper helper) {
		AntBot bot = farm(helper, 4, 0, true, false);
		Container chest = helper.getBlockEntity(new BlockPos(3, 1, 8), ChestBlockEntity.class);
		chest.setItem(0, ItemStack.EMPTY);
		chest.setItem(4, new ItemStack(Items.COAL));
		run(helper, bot, 1);
		BotScripts.failOnWarning(helper, bot);
		boolean[] grew = growsWhenWaiting(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(grew[0], "no tree grew; said " + said(bot));
			helper.assertTrue(bot.getInventory().hasAnyMatching(i -> i.is(Items.STONE_AXE)), "no stone axe; said " + said(bot));
		});
	}

	/**
	 * Fails the test once the bot says it stopped, and grows the birch sapling it planted off SPOT once it says it waits
	 * for a tree (#802); gives whether it grew.
	 */
	private static boolean[] growsWhenWaiting(GameTestHelper helper, AntBot bot) {
		boolean[] grew = new boolean[1];
		helper.onEachTick(() -> {
			if (said(bot).stream().anyMatch(l -> l.contains("stopped"))) {
				helper.fail("said " + said(bot));
			}
			if (grew[0] || !said(bot).contains("waiting for a tree to grow")) {
				return;
			}
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 23, 1, 23)) {
				if (!p.equals(SPOT) && helper.getBlockState(p).is(Blocks.BIRCH_SAPLING)) {
					BlockPos a = helper.absolutePos(p);
					BlockState sapling = helper.getLevel().getBlockState(a);
					((SaplingBlock) sapling.getBlock()).advanceTree(helper.getLevel(), a, sapling.setValue(SaplingBlock.STAGE, 1),
						RandomSource.create(0));
					grew[0] = !helper.getBlockState(p).is(Blocks.BIRCH_SAPLING);
					return;
				}
			}
		});
		return grew;
	}

	/**
	 * A farm whose corner is a block up, (6, 2, 2), so cell 1's floor at y 1 is a pit of 25 blocks of air over the area's
	 * stone, more fill than the kit's 16 dirt and materials' 1 (#821). With 32 more dirt in the chest and none to dig (a
	 * stone floor), the bot gets the fill from the chest and builds the cell: every hole of the pit filled with dirt, the
	 * sapling planted on it, though the bot walks into the pit it fills (#822; red: blocked at cell).
	 */
	// Measured: passes at tick 161 (red: birch_farm stopped: blocked at cell at tick 202).
	// Full runs: passes at up to tick 180 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 360)
	public void aCellOverAPitGetsItsFillFromTheChest(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, 2, false);
		Container chest = helper.getBlockEntity(new BlockPos(3, 1, 8), ChestBlockEntity.class);
		chest.setItem(5, new ItemStack(Items.DIRT, 32));
		runInPit(helper, bot, "");
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot).contains("built cell 1"), "said " + said(bot));
			for (BlockPos p : BlockPos.betweenClosed(6, 1, 2, 10, 1, 6)) {
				helper.assertBlockPresent(Blocks.DIRT, p);
			}
			helper.assertTrue(helper.getBlockState(SPOT.above()).is(Blocks.BIRCH_SAPLING), "no sapling: " + helper.getBlockState(SPOT.above()));
			helper.assertTrue(chest.countItem(Items.DIRT) <= 48 - 26, "dirt left in the chest: " + chest.countItem(Items.DIRT));
		});
	}

	/**
	 * The pit of aCellOverAPitGetsItsFillFromTheChest with only the kit's 16 dirt in the chest and none to dig: the farm
	 * stops at its cell step and says what it lacks, the dirt and how many (#821).
	 */
	// Measured: passes at tick 61 to 69 (red: birch_farm stopped: blocked at cell, the fill not got first).
	// Full runs: passes at up to tick 90 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 180)
	public void aCellThatLacksFillSaysHowMuch(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, 2, false);
		runInPit(helper, bot, "");
		helper.succeedWhen(() -> helper.assertTrue(said(bot).stream().anyMatch(
			l -> l.startsWith("birch_farm stopped: ") && l.contains(" at cell, missing minecraft:dirt x")), "said " + said(bot)));
	}

	/** Runs before, then jobs.wood_farm for birch at AT a block up, one cell, radius 12: cell 1's floor a pit at y 1. */
	private static void runInPit(GameTestHelper helper, AntBot bot, String before) {
		script(helper, bot, before + "jobs.wood_farm.run(kind, cell[0] + (0, 2, 0), 1, 12)\n");
	}

	/** With no sapling to spare (only the cell's own), first_cell still fails with no_logs where there is no tree (#802). */
	// Measured: passes at tick 10.
	// Full runs: passes at up to tick 21 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 50)
	public void theFirstCellWithNoSaplingToSpareFailsWithNoLogs(GameTestHelper helper) {
		AntBot bot = farm(helper, 0, 0, true, false);
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_SAPLING));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		bot.getInventory().addItem(new ItemStack(Items.COAL));
		script(helper, bot, """
			import lib.cells
			lib.cells.layout("birch_farm", cell[0], (5, 5))
			print(jobs.wood_farm.first_cell(kind, "birch_farm", 1, 12)["reason"])
			""");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("no_logs"), "said"));
	}

	/**
	 * jobs.wood_farm spruce_sapling with 4 spruce saplings in the chest farms giants (#803): its first cell is a giant
	 * spruce cell from (6, 0, 2), its four spots (10, 1, 6) to (11, 1, 7) planted.
	 */
	// Measured: passes at tick 427 to 516 (jungle 443 to 533), the slower with 83 tests at once.
	// Full runs: passes at up to tick 539 (#861).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 1100)
	public void aSpruceFarmWithFourSaplingsBuildsAGiantCell(GameTestHelper helper) {
		fourSaplings(helper, "spruce_sapling", Items.SPRUCE_SAPLING, Blocks.SPRUCE_SAPLING);
	}

	/** As aSpruceFarmWithFourSaplingsBuildsAGiantCell, for jungle (#803). */
	// Full runs: passes at up to tick 559 (#861).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 1200)
	public void aJungleFarmWithFourSaplingsBuildsAGiantCell(GameTestHelper helper) {
		fourSaplings(helper, "jungle_sapling", Items.JUNGLE_SAPLING, Blocks.JUNGLE_SAPLING);
	}

	/**
	 * jobs.wood_farm spruce_sapling with 1 spruce sapling builds a one-sapling cell, its spot (9, 1, 5); once the test
	 * puts 4 more in the chest, the next cell is a giant one, a farm of its own south of the small cells' rows: from (6, 0,
	 * 12), its spots (10, 1, 16) to (11, 1, 17) (#803).
	 */
	// Measured: passes at tick 586 to 680 (jungle 570 to 668), the slower with 83 tests at once.
	// Full runs: passes at up to tick 714 (#861).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 1500)
	public void aSpruceFarmWithOneSaplingMovesToGiantsOnceItHasFour(GameTestHelper helper) {
		oneThenFour(helper, "spruce_sapling", Items.SPRUCE_SAPLING, Blocks.SPRUCE_SAPLING, new BlockPos(9, 1, 5), 12);
	}

	/** As aSpruceFarmWithOneSaplingMovesToGiantsOnceItHasFour, for jungle: a cell of 5, its spot (8, 1, 4), the giant from z 10. */
	// Full runs: passes at up to tick 701 (#861).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 1500)
	public void aJungleFarmWithOneSaplingMovesToGiantsOnceItHasFour(GameTestHelper helper) {
		oneThenFour(helper, "jungle_sapling", Items.JUNGLE_SAPLING, Blocks.JUNGLE_SAPLING, new BlockPos(8, 1, 4), 10);
	}

	/**
	 * As aSpruceFarmWithOneSaplingMovesToGiantsOnceItHasFour; once the giant cell stands, the test grows the small cell's
	 * spruce: the giant farm's rounds fell it and do not replant its spot (9, 1, 5) (#807).
	 */
	// Measured: passes at tick 748 to 881 (full runs), up to 1507 with 60 copies at once (red: the small spruce stood at tick 2002).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 1700)
	public void theSmallCellsTreeIsFelledAndNotReplantedOnceTheFarmMovesToGiants(GameTestHelper helper) {
		smallCellsTree(helper, false);
	}

	/**
	 * As theSmallCellsTreeIsFelledAndNotReplantedOnceTheFarmMovesToGiants, the small spruce grown as the farm moves to
	 * giants, as random ticks grow it in about 1 run in 100: it stands once the giant cell does (#862).
	 */
	// Measured: passes at tick 1255 to 1544 (red: the giant cell built, the small cell holds spruce_log, on tick 1268).
	// Since the giant farm no longer breaks the small spruce's leaves for spare saplings (#875): passes at tick 763, as
	// the sapling case (758); before, 1432, the sapling step alone 280 to 959.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 1700)
	public void theSmallCellsTreeGrownDuringTheMoveToGiantsIsFelledAndNotReplanted(GameTestHelper helper) {
		smallCellsTree(helper, true);
	}

	/** The small cell's spruce grown by the test once the giant cell stands, or where early once the move to giants starts. */
	private static void smallCellsTree(GameTestHelper helper, boolean early) {
		BlockPos spot = new BlockPos(9, 1, 5);
		AntBot bot = saplingFarm(helper, "spruce_sapling", Items.SPRUCE_SAPLING, 1);
		BotScripts.failOnWarning(helper, bot);
		boolean[] gave = new boolean[2];
		helper.onEachTick(() -> {
			if (said(bot).stream().anyMatch(l -> l.contains("stopped"))) {
				helper.fail("said " + said(bot));
			}
			if (!gave[0] && said(bot).contains("built cell 1")) {
				helper.getBlockEntity(new BlockPos(3, 1, 16), ChestBlockEntity.class).setItem(3, new ItemStack(Items.SPRUCE_SAPLING, 4));
				gave[0] = true;
			}
			if (early && gave[0] && !gave[1] && said(bot).stream().anyMatch(l -> l.startsWith("giant_spruce_farm 1/4"))
				&& helper.getBlockState(spot).is(Blocks.SPRUCE_SAPLING)) {
				TreesGameTest.plantTree(helper, spot, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, false);
			}
			if (gave[0] && !gave[1] && said(bot).stream().filter(l -> l.contains("built cell 1")).count() == 2) {
				// The small spruce may have grown by itself already (#862): then the farm fells that one.
				BlockState small = helper.getBlockState(spot);
				helper.assertTrue(small.is(Blocks.SPRUCE_SAPLING) || small.is(Blocks.SPRUCE_LOG), "the giant cell built, the small cell holds "
					+ small + "; " + doing(helper, bot));
				if (small.is(Blocks.SPRUCE_SAPLING)) {
					TreesGameTest.plantTree(helper, spot, Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, false);
				}
				gave[1] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(gave[1], "the small spruce never grew, the small cell holds " + helper.getBlockState(spot) + "; " + doing(helper, bot));
			for (int y = 1; y < 6; y++) {
				helper.assertBlockNotPresent(Blocks.SPRUCE_LOG, spot.getX(), y, spot.getZ());
			}
			helper.assertTrue(helper.getBlockState(spot).isAir(), "the small cell replanted; said " + said(bot));
			assertGiantCell(helper, bot, Blocks.SPRUCE_SAPLING, 12);
		});
	}

	private static void fourSaplings(GameTestHelper helper, String kind, Item item, Block sapling) {
		AntBot bot = saplingFarm(helper, kind, item, 4);
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> assertGiantCell(helper, bot, sapling, 2));
	}

	private static void oneThenFour(GameTestHelper helper, String kind, Item item, Block sapling, BlockPos spot, int giantZ) {
		AntBot bot = saplingFarm(helper, kind, item, 1);
		BotScripts.failOnWarning(helper, bot);
		boolean[] gave = new boolean[1];
		helper.onEachTick(() -> {
			if (said(bot).stream().anyMatch(l -> l.contains("stopped"))) {
				helper.fail("said " + said(bot));
			}
			if (!gave[0] && said(bot).contains("built cell 1")) {
				helper.assertTrue(helper.getBlockState(spot).is(sapling), "no one-sapling cell at " + spot.toShortString() + "; said " + said(bot));
				helper.getBlockEntity(new BlockPos(3, 1, 16), ChestBlockEntity.class).setItem(3, new ItemStack(item, 4));
				gave[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(gave[0], "no first cell; said " + said(bot));
			helper.assertTrue(helper.getBlockState(spot).is(sapling), "the one-sapling cell gone; said " + said(bot));
			assertGiantCell(helper, bot, sapling, giantZ);
		});
	}

	/**
	 * A farm of kind at (6, 1, 2) in the giant structure, radius 20, up to 2 cells, on dirt from (6, 0, 2) to (15, 0, 21):
	 * a stone axe, 64 dirt, 4 torches and saplings of item in a chest at (3, 1, 16), the bot by it.
	 */
	private static AntBot saplingFarm(GameTestHelper helper, String kind, Item item, int saplings) {
		for (int x = 6; x < 16; x++) {
			for (int z = 2; z < 22; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		helper.setBlock(3, 1, 16, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(3, 1, 16), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.STONE_AXE));
		chest.setItem(1, new ItemStack(Items.DIRT, 64));
		chest.setItem(2, new ItemStack(Items.TORCH, 4));
		chest.setItem(3, new ItemStack(item, saplings));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 18);
		BlockPos at = helper.absolutePos(AT);
		List<String> out = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.wood_farm " + kind + " "
			+ at.getX() + " " + at.getY() + " " + at.getZ() + " 2 20");
		helper.assertTrue(bot.getScript() != null, "not run: " + out);
		return bot;
	}

	/** A giant cell from (6, 0, z): its four spots (10, 1, z + 4) to (11, 1, z + 5) hold sapling. */
	private static void assertGiantCell(GameTestHelper helper, AntBot bot, Block sapling, int z) {
		for (BlockPos p : BlockPos.betweenClosed(10, 1, z + 4, 11, 1, z + 5)) {
			helper.assertTrue(helper.getBlockState(p).is(sapling), "no sapling at " + p.toShortString() + "; said " + said(bot));
		}
	}

	/** An oak farm roofs its cell with the birch slabs a chest holds (#612): any wooden slabs do, not only oak ones. */
	// Measured: passes at tick 236 to 253.
	// Full runs: passes at up to tick 295 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 590)
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
	// Full runs: passes at up to tick 139 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 280)
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

	/**
	 * A farm of one cell at AT, stopped once it farms and run again with its corner 6 blocks east, where its second cell
	 * would be, lays it out afresh there (#818): it says it moved, builds its cell 0 round SPOT_2 and leaves the old cell
	 * standing. Red: it kept the old layout, full, and built nothing at SPOT_2.
	 */
	// Measured: passes at tick 188 (red: farm full at the old corner).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 400)
	public void aFarmRunWithANewCornerMovesThere(GameTestHelper helper) {
		AntBot bot = farm(helper, 4, 4);
		run(helper, bot, 1);
		BlockPos to = helper.absolutePos(AT.east(6));
		boolean[] moved = new boolean[1];
		BotScripts.failOnWarning(helper, bot);
		helper.onEachTick(() -> {
			if (!moved[0] && said(bot).contains("birch_farm 4/4: farm")) {
				moved[0] = true;
				bot.stopScript();
				List<String> out = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.wood_farm birch_sapling "
					+ to.getX() + " " + to.getY() + " " + to.getZ() + " 1 12");
				helper.assertTrue(bot.getScript() != null, "not run: " + out);
			}
		});
		helper.succeedWhen(() -> {
			List<String> said = said(bot);
			helper.assertTrue(said.contains("farm moved to " + to.getX() + " " + to.getY() + " " + to.getZ()), "said " + said);
			helper.assertTrue(Collections.frequency(said, "built cell 1") == 2, "said " + said);
			assertCell(helper, bot, SPOT);
			assertCell(helper, bot, SPOT_2);
		});
	}

	/**
	 * A farm moved to a new corner carries its old depot's stock to its new depot (#823): a farm at AT east 6 whose depot
	 * (12, 1, 0) east holds 20 birch logs and 5 saplings, run with its corner at AT, ends with both in the new depot's
	 * chests (6, 1, 0) east and the old chests empty. The bot carries the two chests the new depot takes; lib.project.run
	 * is stubbed, as only the move is under test. Red: the stock stayed in the old chests.
	 */
	// Measured: passes at tick 81.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 160)
	public void aMovedFarmCarriesItsOldDepotsStock(GameTestHelper helper) {
		BlockPos logs = new BlockPos(12, 1, 0);
		BlockPos saplings = new BlockPos(13, 1, 0);
		helper.setBlock(logs, Blocks.CHEST);
		helper.setBlock(saplings, Blocks.CHEST);
		helper.getBlockEntity(logs, ChestBlockEntity.class).setItem(0, new ItemStack(Items.BIRCH_LOG, 20));
		helper.getBlockEntity(saplings, ChestBlockEntity.class).setItem(0, new ItemStack(Items.BIRCH_SAPLING, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 2));
		BotScripts.failOnError(helper, bot);
		BlockPos old = helper.absolutePos(AT.east(6));
		BlockPos at = helper.absolutePos(AT);
		BlockPos l = helper.absolutePos(logs);
		BlockPos s = helper.absolutePos(saplings);
		BotScripts.run(bot, """
			import jobs.wood_farm
			import lib.depot
			import lib.project
			import lib.result
			kind = "minecraft:birch_sapling"
			memory["wood_farms"] = {kind: Pos(%d, %d, %d)}
			d = lib.depot.depot("birch_farm", Pos(%d, %d, %d), "east", jobs.wood_farm.CATEGORIES)
			d["chests"] = [["logs", Pos(%d, %d, %d)], ["saplings", Pos(%d, %d, %d)]]
			lib.project.run = lambda name, steps: lib.result.result("stub", "farm")
			jobs.wood_farm.run(kind, Pos(%d, %d, %d), 1, 12)
			""".formatted(old.getX(), old.getY(), old.getZ(), l.getX(), l.getY(), l.getZ(), l.getX(), l.getY(), l.getZ(),
			s.getX(), s.getY(), s.getZ(), at.getX(), at.getY(), at.getZ()));
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot).contains("birch_farm stopped: stub at farm"), "said " + said(bot));
			int newLogs = 0;
			int newSaplings = 0;
			for (int x = 6; x <= 11; x++) {
				if (helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, 1, 0))) instanceof ChestBlockEntity c) {
					newLogs += c.countItem(Items.BIRCH_LOG);
					newSaplings += c.countItem(Items.BIRCH_SAPLING);
				}
			}
			helper.assertValueEqual(newLogs, 20, "logs in the new depot");
			helper.assertValueEqual(newSaplings, 5, "saplings in the new depot");
			helper.assertTrue(helper.getBlockEntity(logs, ChestBlockEntity.class).isEmpty(), "old logs chest not empty");
			helper.assertTrue(helper.getBlockEntity(saplings, ChestBlockEntity.class).isEmpty(), "old saplings chest not empty");
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
	// Full runs: passes at up to tick 6 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 20)
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
	// Measured: passes at tick 51 to 53 alone, 90 in full runs (red: tend took no radius or told).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 190)
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
	// Measured: passes at tick 172 alone, 318 in full runs (red: the dirt under the next cell's spot dug).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 640)
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
	// Measured: passes at tick 131 alone, 265 in each of three full runs (red: the dirt under the first cell's spot dug).
	// A full run is slower as the 50 tests of its batch share SenseApi.SCAN_BUDGET: with it lifted, tick 125 (#850).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 530)
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
	 * A round of a farm with no size whose next cell to build is a later one than lib.cells.next, as the cells between do
	 * not get random ticks (#812; here ticking is stubbed to cells 1 and 3, cell 1 built): the dirt its stocked gets is not
	 * dug from under the sapling spot of cell 3, the nearer, but outside the cells (#816). Red: the dirt under cell 3 dug.
	 */
	// Measured: passes at tick 191 alone, 290 in the class run (red: the dirt under cell 3 dug, tick 79).
	// Full runs: passes at up to tick 401 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 810)
	public void getsDigsNoDirtFromUnderTheCellBuiltNext(GameTestHelper helper) {
		BlockPos next = new BlockPos(20, 0, 4);
		helper.setBlock(next, Blocks.DIRT);
		BlockPos outside = new BlockPos(4, 0, 17);
		helper.setBlock(outside, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 20, 1, 7);
		script(helper, bot, """
			import lib.cells
			import lib.depot
			import lib.result
			import lib.terrain
			import lib.treefarm
			lib.cells.layout("birch_farm", cell[0], (5, 5))
			lib.cells.add("birch_farm", 0)
			def waited(ticks):
			    if ticks == jobs.wood_farm.WAIT:
			        print(lib.cells.built("birch_farm"), count("minecraft:dirt"))
			        stop()
			lib.cellfarm.wait = waited
			lib.cellfarm.ticking = lambda k, name: [0, 2]
			lib.cellfarm.visit = lambda k, b: None
			jobs.wood_farm.unplanted = lambda k, b: []
			jobs.wood_farm.tend = lambda k, b, radius, told: None
			jobs.wood_farm.stored_bone_meal = lambda k, name, boxes, radius: None
			jobs.wood_farm.compost = lambda k, name, boxes, radius: lib.result.result()
			jobs.wood_farm.sweep = lambda k, boxes: None
			lib.cellfarm.stocked = lambda k, radius: jobs.wood_farm.gets("minecraft:dirt", 1, 32)
			lib.depot.store = lambda name, keep, radius: lib.result.result()
			lib.terrain.needs = lambda low, high, y: {"tools": {}}
			lib.treefarm.build_cell = lambda k, low, high: lib.result.result()
			lib.treefarm.grown = lambda k, low, high: False
			jobs.wood_farm.farm(kind, "birch_farm", None, 32)
			""");
		Runnable dug = () -> {
			helper.assertValueEqual(said(bot), List.of("built cell 3", "cells 2/2", "[0, 2] 1"), "said");
			helper.assertBlockPresent(Blocks.DIRT, next);
			helper.assertBlockPresent(Blocks.AIR, outside);
		};
		helper.onEachTick(() -> {
			if (said(bot).size() == 3) {
				dug.run();
			}
		});
		helper.succeedWhen(dug);
	}

	/**
	 * The job's kit step on a resumed farm, two cells built and no dirt carried (#777): the dirt under the spots of both
	 * cells is nearer, but the kit's 16 dirt are dug outside the cells (lib.bootstrap.kit with gets).
	 */
	// Measured: passes at tick 518 to 582 (red: the dirt under the first cell's spot dug, tick 519).
	// Full runs: passes at up to tick 657 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 1400)
	public void theKitDigsNoDirtFromUnderTheFarmsSpots(GameTestHelper helper) {
		helper.setBlock(SPOT.below(), Blocks.DIRT);
		helper.setBlock(SPOT_2.below(), Blocks.DIRT);
		for (int x = 2; x < 18; x++) {
			helper.setBlock(new BlockPos(x, 0, 17), Blocks.DIRT);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 11, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		script(helper, bot, """
			import lib.cells
			lib.cells.layout("birch_farm", cell[0], (5, 5))
			lib.cells.add("birch_farm", 0)
			lib.cells.add("birch_farm", 1)
			jobs.wood_farm.run(kind, cell[0].up(), None, 32)
			""");
		Runnable kitted = () -> {
			helper.assertTrue(said(bot).contains("birch_farm 2/4: sapling"), "said " + said(bot));
			helper.assertBlockPresent(Blocks.DIRT, SPOT.below());
			helper.assertBlockPresent(Blocks.DIRT, SPOT_2.below());
		};
		helper.onEachTick(() -> {
			if (said(bot).contains("birch_farm 2/4: sapling")) {
				kitted.run();
			}
		});
		helper.succeedWhen(kitted);
	}

	/**
	 * A birch whose top logs the bot cannot reach without dirt to pillar with (#644): reap says the harvest failed and why,
	 * gets dirt from the chest and harvests again, then tend replants the cell, its tree gone.
	 */
	// Measured: passes at tick 599.
	// Full runs: passes at up to tick 759 (#861).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 1600)
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
	// The slower felling of #761 is load, not a regression (#769): those 6 copies ran with 30 more giant farms (16 giant
	// jungles, 8 giant spruces, 6 mangroves), whose bots share the scan and path budgets. 6 copies alone felled in 2313 to
	// 3326 (2838 on average), as before #713; with 16 giant jungle farms at once 2531 to 3372 (3031), those 16 1929 to
	// 3206 (2338). The spread is where the bot is killed (118 to 159 ticks after the growth) and the way back up.
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
	// seeded mangroves rather than this one tree. Since #768 harvest mines roots up to 2 out of the cell too, and the
	// felling took 2150 once, past 1400's window (#782): 33 temporary copies at once, seeds 0 to 10, three each, felled
	// 437 to 3123 ticks, 1724 on average over the 25 trees of 8 seeds (seed 0 1336 to 1664, 16 logs; the logs 6 to 26,
	// 16.25 on average), so HARVEST 1700 (LOGS keeps its 16 of 188 trees). This test passed at tick 1915 alongside them,
	// 2187 alone, once 2400 (#782). Seed 4 never grew from the test's advanceTree; seeds 2 and 6 left their logs unstored (too few
	// for a chest; the tests below, #783).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 4800)
	public void aMangroveFarmFellsItsFirstTreeAndReplants(GameTestHelper helper) {
		farm(helper, "mangrove_propagule", Items.MANGROVE_PROPAGULE, Blocks.MANGROVE_PROPAGULE, Blocks.MANGROVE_LOG,
			Items.MANGROVE_LOG, 23, 0, MANGROVE_HARVEST, 0, 11, List.of(new BlockPos(11, 1, 9)), 1);
	}

	/**
	 * A dark oak farm, its kind given as a sapling id (#800): farm() waits for the job's own farm name, dark_oak_farm, not
	 * the kind's. The cell of 8, the four saplings grown with seed 0, felled, replanted and logs in the depot, the felling
	 * within {@link #HARVEST_FACTOR} of HARVEST["minecraft:dark_oak_sapling"].
	 */
	// Measured: passes at tick 986 (red: the tree never grew, at tick 4002); #795's 33 copies felled in up to 1251 ticks.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 2600)
	public void aDarkOakFarmFellsItsFirstTreeAndReplants(GameTestHelper helper) {
		farm(helper, "dark_oak_sapling", Items.DARK_OAK_SAPLING, Blocks.DARK_OAK_SAPLING, Blocks.DARK_OAK_LOG,
			Items.DARK_OAK_LOG, 16, 0, 950, 0, 8, List.of(new BlockPos(9, 1, 7), new BlockPos(10, 1, 7), new BlockPos(9, 1, 8),
				new BlockPos(10, 1, 8)), 1);
	}

	/**
	 * The mangrove farm above with a small tree (#783): seed 2 grows one of 2 logs, too few for the depot's first chest
	 * and the crafting table it is made on. The farm replants, keeps the logs with one store message, and the test grows
	 * the replanted propagule again (up to 3 trees) until the depot holds logs. lib.depot.store crashed there (put of 0)
	 * when the new chest was crafted from all the logs it was storing. A tree this small is no typical one, so its felling
	 * ticks are not checked against HARVEST.
	 */
	// Measured: passes at tick 1588 (red: the script died on put() n 0 at tick 738), seed 6 at 1594 (red: at 916).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 3200)
	public void aMangroveFarmWithASmallTreeStillReplantsAndStores(GameTestHelper helper) {
		farm(helper, "mangrove_propagule", Items.MANGROVE_PROPAGULE, Blocks.MANGROVE_PROPAGULE, Blocks.MANGROVE_LOG,
			Items.MANGROVE_LOG, 23, 2, 0, 0, 11, List.of(new BlockPos(11, 1, 9)), 3);
	}

	/** As aMangroveFarmWithASmallTreeStillReplantsAndStores with seed 6's small tree of one log (#783). */
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 3200)
	public void aMangroveFarmWithAnotherSmallTreeStillReplantsAndStores(GameTestHelper helper) {
		farm(helper, "mangrove_propagule", Items.MANGROVE_PROPAGULE, Blocks.MANGROVE_PROPAGULE, Blocks.MANGROVE_LOG,
			Items.MANGROVE_LOG, 23, 6, 0, 0, 11, List.of(new BlockPos(11, 1, 9)), 3);
	}

	/**
	 * HARVEST of each giant and of mangrove is the one its farm test above fells within {@link #HARVEST_FACTOR} of (#669,
	 * mangrove #782).
	 */
	@GameTest(maxTicks = 10)
	public void aGiantsHarvestIsWhatItsFarmTestMeasures(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		script(helper, bot, "print(jobs.wood_farm.HARVEST['giant_spruce'], jobs.wood_farm.HARVEST['giant_jungle'],"
			+ " jobs.wood_farm.HARVEST['minecraft:mangrove_propagule'])");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot),
			List.of(GIANT_SPRUCE_HARVEST + " " + GIANT_JUNGLE_HARVEST + " " + MANGROVE_HARVEST), "said"));
	}

	/**
	 * HARVEST and LOGS of each one-sapling kind but mangrove, and of dark and pale oak, are measured (#769, birch #779),
	 * no longer the birch's ticks a log times logs counted from the tree features (dark oak again #795).
	 */
	// Measured with temporary copies of aMangroveFarmFellsItsFirstTreeAndReplants for each kind, seeds 0 to 10, 24 to 32
	// copies at once: the ticks from the growth to the logs stored and the sapling replanted, and the logs grown.
	// oak 165 to 302, 207 on average (10 trees; a tree of 4 logs went into the depot's chest, none stored), 4.9 logs;
	// spruce 250 to 433, 329, 6.4 logs; jungle 176 to 539, 359, 8.7; acacia 207 to 464, 286, 7.9; cherry 378 to 1231,
	// 865, 15.9; dark oak 867 to 1510, 1236, 40.6; pale oak (the same trees) 663 to 1209, 941; poplar 378 to 1021, 613,
	// 14.3 (LOGS keeps its 15 of 200 trees, #650). HARVEST is the mean rounded to 50, LOGS to a half.
	// #779, 33 copies at once: birch 176 to 323, 237 on average, 5.4 logs (5 to 7); dark oak 808 to 1608, 1235, pale oak
	// 663 to 1275, 947, the same trees (the same logs and leaves, a seed for seed). Timed in lib.treefarm.harvest, both
	// fell in the same ticks (dark 640 to 1153, pale 640 to 1185); dark oak then picks up apples for some 220 to 390
	// ticks, pale oak its sticks for 0 to 47, as the game test data pack makes each dark oak leaves block drop an apple
	// (for GetGameTest's apple test). In the game 1 in 200 does, so dark oak takes pale oak's HARVEST.
	// Since #790 the data pack drops an apple to GetGameTest's apple bot only, none otherwise. #795, 33 copies at once of a
	// dark oak farm (a cell of 8 from (6, 0, 4), 16 dirt): 665 to 1251, 943 on average, 40.6 logs (31 to 50), so dark oak's
	// HARVEST stays 950 and LOGS 40.5, now its own measure.
	@GameTest(maxTicks = 10)
	public void eachKindsHarvestAndLogsAreMeasured(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		script(helper, bot, "print([(jobs.wood_farm.HARVEST['minecraft:' + k + '_sapling'], jobs.wood_farm.LOGS['minecraft:' + k + '_sapling'])"
			+ " for k in ['oak', 'spruce', 'jungle', 'acacia', 'cherry', 'dark_oak', 'pale_oak', 'poplar', 'birch']])");
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("[(200, 5), (350, 6.5), (350, 8.5), (300, 8), (850, 16),"
			+ " (950, 40.5), (950, 40.5), (600, 15), (250, 5.5)]"), "said"));
	}

	/** jobs.wood_farm's HARVEST of each giant, as aGiantsHarvestIsWhatItsFarmTestMeasures checks. */
	private static final int GIANT_SPRUCE_HARVEST = 3200;
	private static final int GIANT_JUNGLE_HARVEST = 2250;
	/** jobs.wood_farm's HARVEST of a mangrove, the mean of 25 seeded trees (#782). */
	private static final int MANGROVE_HARVEST = 1700;
	/** The ticks a giant jungle takes the bot killed up the tree, which its farm test fells within HARVEST_FACTOR of (#761). */
	private static final int KILLED_GIANT_JUNGLE_HARVEST = 3100;

	/** How far a giant's felling ticks may be from its HARVEST, either way. */
	private static final double HARVEST_FACTOR = 1.5;

	/**
	 * A giant farm of kind from (6, 0, 4) in the giant structure: a stone axe, dirt, 2 torches and 8 saplings in a chest;
	 * once the cell is built and planted, the test grows its four saplings as the game does with seed. It passes when no
	 * log of the tree is left in the area, the four spots hold saplings and the depot holds logs, the ticks from the
	 * growth to then within {@link #HARVEST_FACTOR} of harvest (unless it is 0). It watches the bot by its UUID,
	 * as one that dies (#694) respawns as a new entity and runs the job again, which recovers. It fails on a warning but
	 * a death's ("died at"), as a fall may kill the bot (#715). With killAt above 0 it kills the bot the first time it
	 * stands at that y once the giant grew, and passes only once the bot died.
	 */
	private static void giantFarm(GameTestHelper helper, String kind, Item saplingItem, Block saplingBlock, Block logBlock,
		Item logItem, int dirt, long seed, int harvest, int killAt) {
		farm(helper, kind, saplingItem, saplingBlock, logBlock, logItem, dirt, seed, harvest, killAt, 10,
			List.of(new BlockPos(10, 1, 8), new BlockPos(11, 1, 8), new BlockPos(10, 1, 9), new BlockPos(11, 1, 9)), 1);
	}

	/**
	 * As {@link #giantFarm} for any kind: its cell size wide from (6, 0, 4) on dirt, its sapling spots spots, the tree
	 * grown at the first, which then no longer holds a sapling (a mangrove's holds air or a root). While the depot holds
	 * no logs, it grows the replanted sapling again, up to trees trees in all (#783: a small tree's logs make no chest),
	 * harvest timing the last. The bot may say one store message, no more.
	 */
	private static void farm(GameTestHelper helper, String kind, Item saplingItem, Block saplingBlock, Block logBlock,
		Item logItem, int dirt, long seed, int harvest, int killAt, int size, List<BlockPos> spots, int trees) {
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
		long[] grewAt = {-1};  // the tick the giant grew on, the last one where it grows more
		int[] grown = {0};
		IntSupplier stored = () -> {
			int n = 0;
			for (int x = 6; x < 16; x++) {
				if (helper.getBlockState(new BlockPos(x, 1, 2)).is(Blocks.CHEST)) {
					n += helper.getBlockEntity(new BlockPos(x, 1, 2), ChestBlockEntity.class).countItem(logItem);
				}
			}
			return n;
		};
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
			if (killAt > 0 && grewAt[0] >= 0 && !killed[0] && TestPositions.relative(helper, now.blockPosition()).getY() >= killAt) {
				killed[0] = true;
				now.kill(helper.getLevel());
				return;
			}
			// The job's farm name: a sapling id's without "_sapling", as jobs.wood_farm names it (#800).
			if (grown[0] < trees && (grown[0] == 0 || stored.getAsInt() == 0) && said(now).contains(kind.replace("_sapling", "") + "_farm 4/4: farm")
				&& helper.getBlockState(spots.get(0)).is(saplingBlock)) {
				BlockPos a = helper.absolutePos(spots.get(0));
				BlockState sapling = helper.getLevel().getBlockState(a);
				((SaplingBlock) sapling.getBlock()).advanceTree(helper.getLevel(), a, sapling.setValue(SaplingBlock.STAGE, 1),
					RandomSource.create(seed));
				if (!helper.getBlockState(spots.get(0)).is(saplingBlock)) {
					grewAt[0] = helper.getTick();
					grown[0]++;
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
					helper.fail("a log left at " + p + ", health " + now.getHealth() + " at " + TestPositions.relative(helper, now.blockPosition())
						+ "; said " + said(now));
				}
			}
			for (BlockPos p : spots) {
				helper.assertTrue(helper.getBlockState(p).is(saplingBlock), "no sapling at " + p.toShortString() + ", bot at "
					+ TestPositions.relative(helper, now.blockPosition()).toShortString() + "; said " + said(now));
			}
			helper.assertTrue(said(now).stream().filter(l -> l.startsWith("store: ")).count() <= 1, "said " + said(now));
			helper.assertTrue(stored.getAsInt() > 0, "no logs in the depot after " + grown[0] + " trees; said " + said(now));
			if (took[0] < 0) {
				took[0] = helper.getTick() - grewAt[0];
			}
			helper.assertTrue(harvest == 0 || took[0] >= harvest / HARVEST_FACTOR && took[0] <= harvest * HARVEST_FACTOR,
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
			helper.assertValueEqual(said(bot), List.of("19114 47 1 True", "76 20"), "printed");
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
		return farm(helper, saplings, torches, dirt, true);
	}

	/** As farm(helper, saplings, torches, dirt), with the birch only where tree. */
	private static AntBot farm(GameTestHelper helper, int saplings, int torches, boolean dirt, boolean tree) {
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
		if (tree) {
			TreesGameTest.plantTree(helper, new BlockPos(3, 1, 12), Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, false);
		}
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

	/**
	 * A walk sent into a built birch cell's floor (#890, as the crop farm's #869): the cell's floor a block up, dirt from
	 * (1, 1, 4) to (5, 1, 8), kept with lib.cellfarm.keep_cells (the spec's kept). A goto to the cell's low corner, a spot
	 * only breaking the dirt there gives, is unreachable, and the dirt stays.
	 */
	// Measured: passes at tick 37 (red: the dirt at (1, 1, 4) broke on tick 39).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 80)
	public void aWalkSentIntoACellLeavesItsFloor(GameTestHelper helper) {
		walkIntoCell(helper, "minecraft:birch_sapling", 5);
	}

	/** As aWalkSentIntoACellLeavesItsFloor, for a giant spruce's 10-wide cell, its floor from (1, 1, 4) to (10, 1, 13). */
	// Measured: passes at tick 37 (red: the dirt at (1, 1, 4) broke on tick 39).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 80)
	public void aWalkSentIntoAGiantCellLeavesItsFloor(GameTestHelper helper) {
		walkIntoCell(helper, "giant_spruce", 10);
	}

	private static void walkIntoCell(GameTestHelper helper, String kind, int width) {
		for (BlockPos p : BlockPos.betweenClosed(1, 1, 4, width, 1, width + 3)) {
			helper.setBlock(p, Blocks.DIRT);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 0, 1, 7);
		BlockPos low = helper.absolutePos(new BlockPos(1, 1, 4));
		BotScripts.run(bot, """
			import jobs.wood_farm
			import lib.cellfarm
			import lib.cells
			low = Pos(%d, %d, %d)
			lib.cells.layout("wood_farm", low, (%d, %d))
			lib.cells.add("wood_farm", 0)
			lib.cellfarm.keep_cells(jobs.wood_farm.spec("%s", "wood_farm", 16), "wood_farm")
			r = goto(low)
			print(r.ok, r.reason)
			""".formatted(low.getX(), low.getY(), low.getZ(), width, width, kind));
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (!helper.getBlockState(new BlockPos(1, 1, 4)).is(Blocks.DIRT)) {
				helper.fail("the dirt at (1, 1, 4) broke: " + helper.getBlockState(new BlockPos(1, 1, 4)));
			}
		});
		helper.succeedWhen(() -> helper.assertValueEqual(said(bot), List.of("False unreachable"), "said"));
	}

	/** Runs body after importing jobs.wood_farm, with kind birch, cell 1's box as cell and its sapling spot as spot. */
	private static void script(GameTestHelper helper, AntBot bot, String body) {
		BlockPos l = helper.absolutePos(new BlockPos(6, 0, 2));
		BlockPos h = helper.absolutePos(new BlockPos(10, 0, 6));
		BlockPos s = helper.absolutePos(SPOT);
		BotScripts.run(bot, "import jobs.wood_farm\nimport lib.cellfarm\nkind = 'minecraft:birch_sapling'\ncell = (Pos(" + l.getX() + ", " + l.getY() + ", "
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

	/** Where the bot is, the call its script runs and what it said, for a failure message. */
	private static String doing(GameTestHelper helper, AntBot bot) {
		ScriptRun run = bot.getScript();
		return "the bot at " + TestPositions.relative(helper, bot.position()) + (run == null ? "" : ", running " + run.call() + " on line " + run.line())
			+ "; said " + said(bot);
	}

	private static List<String> said(AntBot bot) {
		return BotScripts.log(bot, Output.Level.PRINT);
	}
}
