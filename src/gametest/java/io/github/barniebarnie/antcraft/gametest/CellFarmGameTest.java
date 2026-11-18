package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
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
 * {@code lib/cellfarm.py} (#826; docs/reference/modules/lib/cellfarm.md) with a farm of no trees: cells of one block,
 * planted with a torch, ripe when a hay block stands there instead.
 */
public class CellFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * A spec of cells of one block, corner at (1, 1, 3): the engine lays the farm out, builds cell 1 at (1, 0, 3) and,
	 * with the torches got from the chest, cell 2 at (3, 0, 3); the test puts hay in cell 1, and the bot harvests it,
	 * stores it in its depot north of the cells (a chest from the supply chest) and plants the cell again.
	 */
	// Measured: passes at tick 256 to 257, alone and with the wood farm tests (red: No module named 'lib.cellfarm').
	// Full runs: passes at up to tick 302 (#861).
	@GameTest(maxTicks = 610)
	public void aSpecOfOneBlockCellsIsLaidOutGrownAndHarvested(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.TORCH, 3));
		chest.setItem(1, new ItemStack(Items.CHEST));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import lib.cellfarm
			from lib.get import get
			from lib.project import Step
			from lib.result import result
			T = "minecraft:torch"
			H = "minecraft:hay_block"
			def build_cell(low, high):
			    r = place(low.up(), T)
			    return result() if r else result(r.reason, low.up())
			def harvest(low, high, told):
			    mine(low.up())
			    pick_up(H)
			    return result()
			def tend(boxes, told):
			    for b in boxes:
			        if block(b[0].up()).is_air and get(T, 1, 8)["ok"]:
			            place(b[0].up(), T)
			f = {"size": (1, 1), "categories": {"hay": [H]}, "corners": "test_farms", "keep": [T],
			     "materials": lambda: {T: 1}, "build_cell": build_cell, "ripe": lambda low, high: block(low.up()).id == H,
			     "harvest": harvest, "tend": tend, "most": lambda: 2}
			lib.cellfarm.run(f, "test_farm", "torch", Pos(%d, %d, %d), [Step("farm", lib.cellfarm.farm, (f, "test_farm", None, 8))], 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnError(helper, bot);
		boolean[] ripe = new boolean[1];
		helper.onEachTick(() -> {
			if (!ripe[0] && BotScripts.log(bot, Output.Level.PRINT).contains("built cell 2")) {
				helper.setBlock(1, 1, 3, Blocks.HAY_BLOCK);
				ripe[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(ripe[0], "cell 2 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.TORCH, 1, 1, 3);
			helper.assertBlockPresent(Blocks.TORCH, 3, 1, 3);
			helper.assertBlockPresent(Blocks.CHEST, 1, 1, 1);
			helper.assertTrue(helper.getBlockEntity(new BlockPos(1, 1, 1), ChestBlockEntity.class).countItem(Items.HAY_BLOCK) == 1,
				"no hay in the depot; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}
	/**
	 * A round whose chores harvest but whose cells give nothing, as a giant wood farm's that fells only the small farms'
	 * trees: learn is not called with no harvest of its own (#838), here a learn that divides by n.
	 */
	// Measured: passes at tick 21 (red: division by zero on tick 12).
	@GameTest(maxTicks = 60)
	public void aRoundOfOnlyChoresLearnsNothing(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.TORCH, 3));
		chest.setItem(1, new ItemStack(Items.CHEST));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import lib.cellfarm
			from lib.project import Step
			from lib.result import result
			T = "minecraft:torch"
			def build_cell(low, high):
			    r = place(low.up(), T)
			    return result() if r else result(r.reason, low.up())
			def chores(keep, told):
			    say("chores")
			    return 1, False
			f = {"size": (1, 1), "categories": {"torch": [T]}, "corners": "test_farms", "keep": [T],
			     "materials": lambda: {T: 1}, "build_cell": build_cell, "ripe": lambda low, high: False,
			     "harvest": lambda low, high, told: result(), "tend": lambda boxes, told: None, "most": lambda: 1,
			     "chores": chores, "learn": lambda ticks, n: say(f"learnt {ticks // n}")}
			lib.cellfarm.run(f, "test_farm", "torch", Pos(%d, %d, %d), [Step("farm", lib.cellfarm.farm, (f, "test_farm", None, 8))], 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			String said = String.join("\n", BotScripts.log(bot, Output.Level.PRINT));
			helper.assertTrue(said.split("chores", -1).length > 3, "not three rounds; said " + said);
		});
	}

	/**
	 * A farm's cap from its own harvest times (#844), as the wood farm's (#662): most(name, growth, guess) is growth over
	 * guess until SAMPLES harvests are learnt, then over their mean; farm learns each round's harvests where the spec has
	 * no learn of its own. Here a harvest that takes 30 ticks is learnt once, then 200 ticks of 2 and 100 of 1 for "x".
	 */
	// Measured: passes at tick 52 (red: module 'lib.cellfarm' has no attribute 'most').
	// Full runs: passes at up to tick 95 (#861).
	@GameTest(maxTicks = 190)
	public void aFarmLearnsItsHarvestTicksAndSizesItselfFromThem(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.TORCH, 3));
		chest.setItem(1, new ItemStack(Items.CHEST));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import lib.cellfarm
			from lib.project import Step
			from lib.result import result
			T = "minecraft:torch"
			print(lib.cellfarm.most("x", 1000, 500))
			lib.cellfarm.learn("x", 200, 2)
			print(lib.cellfarm.most("x", 1000, 500))
			lib.cellfarm.learn("x", 100, 1)
			print(lib.cellfarm.most("x", 1000, 500))
			ripe = [True]
			waits = [0]
			def build_cell(low, high):
			    r = place(low.up(), T)
			    return result() if r else result(r.reason, low.up())
			def harvest(low, high, told):
			    ripe[0] = False
			    wait(30)
			    return result()
			def waited(ticks):
			    waits[0] += 1
			    if waits[0] > 1:
			        h = memory["harvest_ticks"]["test_farm"]
			        print(len(h), h[0] >= 30)
			        stop()
			lib.cellfarm.wait = waited
			f = {"size": (1, 1), "categories": {"torch": [T]}, "corners": "test_farms", "keep": [T],
			     "materials": lambda: {T: 1}, "build_cell": build_cell, "ripe": lambda low, high: ripe[0],
			     "harvest": harvest, "tend": lambda boxes, told: None, "most": lambda: 1}
			lib.cellfarm.run(f, "test_farm", "torch", Pos(%d, %d, %d), [Step("farm", lib.cellfarm.farm, (f, "test_farm", None, 8))], 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT).stream()
			.filter(l -> !l.contains("cell") && !l.contains("farm")).toList(),
			List.of("2", "2", "10", "1 True"), "said"));
	}

	/**
	 * A cell build that fails (#866) is not tried again the next round but BACKOFF (10) rounds later, as where the next
	 * cell is short of stock (#651): build_cell always fails here, nothing ripens, and each round's wait notes how many
	 * builds were tried; the reason is said once (#853).
	 */
	// Measured: passes at tick 24 (red: tried every round, [1, 2, ..., 12]).
	// Full runs: passes at up to tick 45 (#861).
	@GameTest(maxTicks = 90)
	public void aFailedBuildBacksOff(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import lib.cellfarm
			from lib.project import Step
			from lib.result import result
			T = "minecraft:torch"
			builds = [0]
			seen = []
			def build_cell(low, high):
			    builds[0] += 1
			    return result("blocked", low)
			def waited(ticks):
			    seen.append(builds[0])
			    if len(seen) == 12:
			        print(seen)
			        stop()
			lib.cellfarm.wait = waited
			f = {"size": (1, 1), "categories": {"torch": [T]}, "corners": "test_farms", "keep": [T],
			     "materials": lambda: {}, "build_cell": build_cell, "ripe": lambda low, high: False,
			     "harvest": lambda low, high, told: result(), "tend": lambda boxes, told: None, "most": lambda: 1}
			lib.cellfarm.run(f, "test_farm", "torch", Pos(%d, %d, %d), [Step("farm", lib.cellfarm.farm, (f, "test_farm", None, 8))], 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT).stream()
			.filter(l -> !l.endsWith(": farm")).toList(),
			List.of("cell not built: blocked at Pos(" + at.below().toShortString() + ")", "[1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2]"),
			"said"));
	}

	/**
	 * A corner walk that fails is said, once a reason (#851): corner ignored its goto's result, so a walk to a spot it
	 * could never reach, by a cactus, went unnoticed (#846). Here cell 0 is stood in for in a chunk four over and the
	 * corner spot in mid-air, which the bot has no blocks to climb to; corner is called twice.
	 */
	// Measured: passes at tick 40 (red: corner() takes 1 positional argument but 2 were given).
	@GameTest(maxTicks = 80)
	public void aCornerWalkThatFailsIsSaidOnce(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos spot = helper.absolutePos(new BlockPos(4, 6, 4));
		BotScripts.run(bot, """
			import lib.cellfarm
			import lib.cells
			low = pos().east(64)
			lib.cells.cell = lambda name, i: (low, low)
			lib.cellfarm.corner_spot = lambda low, high: Pos(%d, %d, %d)
			told = {}
			lib.cellfarm.corner("test_farm", told)
			lib.cellfarm.corner("test_farm", told)
			print("done")
			""".formatted(spot.getX(), spot.getY(), spot.getZ()));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(
			List.of("corner: unreachable", "done")), "said " + BotScripts.log(bot, Output.Level.PRINT)));
	}

	/**
	 * Where a bot waits in its farm's corner chunk (corner, #814): diagonal into cell 0 from its low corner, but where that
	 * corner is at the chunk's edge (local 15), a block back from the cell, never on it: a berry farm's bot was sent onto
	 * a bush, its path pillared over it, and taking the scaffold back dropped it in (#845). Where the cell is deeper than
	 * a row, as a cactus farm's 2 by 2, that diagonal is on the cell, its second cactus: then a block north of the cell
	 * instead (#846).
	 */
	// Measured: passes at tick 5 (red: no attribute corner_spot; the old spot for Pos(1, 0, 15) was on the cell, Pos(2, 1, 15);
	// #846 red: corner_spot() takes 1 positional argument but 2 were given, and took no high to keep off a 2 by 2 cell).
	@GameTest(maxTicks = 20)
	public void theCornerSpotIsInTheCornersChunkOffTheCell(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BotScripts.run(bot, """
			import lib.cellfarm
			print(lib.cellfarm.corner_spot(Pos(1, 0, 3), Pos(3, 0, 3)), lib.cellfarm.corner_spot(Pos(1, 0, 15), Pos(3, 0, 15)),
				lib.cellfarm.corner_spot(Pos(-17, 0, -1), Pos(-15, 0, -1)))
			print(lib.cellfarm.corner_spot(Pos(1, 0, 3), Pos(2, 0, 4)), lib.cellfarm.corner_spot(Pos(1, 0, 16), Pos(2, 0, 17)),
				lib.cellfarm.corner_spot(Pos(16, 0, 16), Pos(17, 0, 17)))
			""");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(
			List.of("Pos(2, 1, 4) Pos(2, 1, 14) Pos(-18, 1, -3)", "Pos(2, 1, 2) Pos(0, 1, 17) Pos(18, 1, 18)")),
			"said " + BotScripts.log(bot, Output.Level.PRINT)));
	}

	/**
	 * A berry farm's corner at chunk-local (15, 15): its corner spot touches no bush of cell 0, not even diagonally
	 * (#854). It was the block diagonal back from the corner, by the bush there, and the corner walk could end with the
	 * bot over the edge of its block toward it. Here whether the spot is two blocks or more off the cell, on the farther
	 * axis, is said.
	 */
	// Measured: passes at tick 5 (red: said False, the spot Pos(14, 1, 14) diagonal to the bush at the corner).
	@GameTest(maxTicks = 20)
	public void aBerryFarmsCornerSpotAtTheChunksEdgeTouchesNoBush(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BotScripts.run(bot, """
			import lib.cellfarm
			import jobs.berry_farm
			w, d = jobs.berry_farm.spec()["size"]
			low = Pos(15, 0, 15)
			high = low + (w - 1, 0, d - 1)
			s = lib.cellfarm.corner_spot(low, high)
			print(max(low.x - s.x, s.x - high.x, low.z - s.z, s.z - high.z) >= 2)
			""");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("True")),
			"said " + BotScripts.log(bot, Output.Level.PRINT)));
	}
}
