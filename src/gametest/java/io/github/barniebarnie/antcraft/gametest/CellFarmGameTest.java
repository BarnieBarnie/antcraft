package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
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
		BotScripts.failOnWarning(helper, bot);
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
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			String said = String.join("\n", BotScripts.log(bot, Output.Level.PRINT));
			helper.assertTrue(said.split("chores", -1).length > 3, "not three rounds; said " + said);
		});
	}

	/**
	 * A farm's cap from its own harvest times (#844), as the wood farm's (#662): most(name, growth, guess) is growth over
	 * guess until SAMPLES harvests are learnt, then over their mean; farm learns each round's harvests where the spec has
	 * no learn of its own. Here a harvest that takes 30 ticks is learnt once, then 200 ticks of 2 and 100 of 1 for "x".
	 * The corner walk is stubbed out: it ran only where cell 0's chunk was not the bot's, so the test's time and outcome
	 * hung on where its area fell (#881).
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
			lib.cellfarm.corner = lambda name, told: None
			f = {"size": (1, 1), "categories": {"torch": [T]}, "corners": "test_farms", "keep": [T],
			     "materials": lambda: {T: 1}, "build_cell": build_cell, "ripe": lambda low, high: ripe[0],
			     "harvest": harvest, "tend": lambda boxes, told: None, "most": lambda: 1}
			lib.cellfarm.run(f, "test_farm", "torch", Pos(%d, %d, %d), [Step("farm", lib.cellfarm.farm, (f, "test_farm", None, 8))], 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnWarning(helper, bot);
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
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT).stream()
			.filter(l -> !l.endsWith(": farm")).toList(),
			List.of("cell not built: blocked at Pos(" + at.below().toShortString() + ")", "[1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2]"),
			"said"));
	}

	/**
	 * The farm keeps its cells (keep_cells) when it starts and once it built a cell, not each round (#906): the kept
	 * blocks stay kept till the script ends, also across a reload (ScriptRun saves them). Here one cell is built and
	 * twelve rounds go by; keep_cells is counted.
	 */
	// Measured: passes at tick 30 (red: keeps 14, once a round).
	@GameTest(maxTicks = 90)
	public void theCellsAreKeptOnStartAndAfterABuildOnly(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.TORCH));
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import lib.cellfarm
			from lib.project import Step
			from lib.result import result
			T = "minecraft:torch"
			keeps = [0]
			rounds = [0]
			keep_cells = lib.cellfarm.keep_cells
			def counted(f, name):
			    keeps[0] += 1
			    keep_cells(f, name)
			def build_cell(low, high):
			    r = place(low.up(), T)
			    return result() if r else result(r.reason, low.up())
			def waited(ticks):
			    rounds[0] += 1
			    if rounds[0] == 12:
			        print("keeps", keeps[0])
			        stop()
			lib.cellfarm.keep_cells = counted
			lib.cellfarm.wait = waited
			f = {"size": (1, 1), "categories": {"torch": [T]}, "corners": "test_farms", "keep": [T],
			     "materials": lambda: {T: 1}, "build_cell": build_cell, "ripe": lambda low, high: False,
			     "harvest": lambda low, high, told: result(), "tend": lambda boxes, told: None, "most": lambda: 1,
			     "kept": lambda low, high: [low]}
			lib.cellfarm.run(f, "test_farm", "torch", Pos(%d, %d, %d), [Step("farm", lib.cellfarm.farm, (f, "test_farm", None, 8))], 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT).stream()
			.filter(l -> l.startsWith("keeps")).toList(), List.of("keeps 2"), "said"));
	}

	/**
	 * A farm's bot that dies keeps its cells once it respawns (#906): it runs its script again from the top, and
	 * lib.cellfarm.run keeps them before its first walk. After the respawn, tend walks into cell 1's floor block, which
	 * it may not break: the walk is unreachable.
	 */
	// Measured: passes at tick 364, most of it the respawn wait (botRespawnTicks), as DeathGameTest's.
	@GameTest(maxTicks = 440)
	public void theCellsAreStillKeptAfterARespawn(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		UUID id = bot.getUUID();
		bot.getInventory().addItem(new ItemStack(Items.TORCH));
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import lib.cellfarm
			from lib.project import Step
			from lib.result import result
			T = "minecraft:torch"
			def build_cell(low, high):
			    r = place(low.up(), T)
			    return result() if r else result(r.reason, low.up())
			def tend(boxes, told):
			    if deaths():
			        r = goto(boxes[0][0])
			        print(r.ok, r.reason)
			        stop()
			f = {"size": (1, 1), "categories": {"torch": [T]}, "corners": "test_farms", "keep": [T],
			     "materials": lambda: {T: 1}, "build_cell": build_cell, "ripe": lambda low, high: False,
			     "harvest": lambda low, high, told: result(), "tend": tend, "most": lambda: 1,
			     "kept": lambda low, high: [low]}
			lib.cellfarm.run(f, "test_farm", "torch", Pos(%d, %d, %d), [Step("farm", lib.cellfarm.farm, (f, "test_farm", None, 8))], 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnWarningButDeath(helper, () -> helper.getLevel().getEntity(id) instanceof AntBot now && now.isAlive() ? now : null);
		boolean[] killed = new boolean[1];
		helper.onEachTick(() -> {
			if (!killed[0] && BotScripts.log(bot, Output.Level.PRINT).contains("farm full")) {
				bot.kill(helper.getLevel());
				killed[0] = true;
			}
			if (!helper.getBlockState(new BlockPos(1, 0, 3)).is(Blocks.STONE)) {
				helper.fail("cell 1's floor at (1, 0, 3) broke: " + helper.getBlockState(new BlockPos(1, 0, 3)));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getLevel().getEntity(id) instanceof AntBot again && again != bot && again.isAlive(), "not respawned");
			AntBot again = (AntBot) helper.getLevel().getEntity(id);
			helper.assertValueEqual(BotScripts.log(again, Output.Level.PRINT).stream().filter(l -> l.startsWith("True") || l.startsWith("False"))
				.toList(), List.of("False unreachable"), "said");
		});
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

	/**
	 * A berry farm's corner spot touches no bush of any cell, not even diagonally, wherever such a spot is in the
	 * corner's chunk (#872): the block diagonal into the gap after cell 0 sat on the path beside its middle bush and the
	 * next row's, and the corner walk could end over the edge of its block toward one. Here the farm's first eight cells
	 * are laid out from each corner and whether the spot, with the farm's spec's hurts, is two blocks or more off every
	 * one of them is said.
	 */
	// Measured: passes at tick 8 (red: False for the corners at (1, 0, 3) and (5, 0, 8), the spots Pos(2, 1, 4) and
	// Pos(6, 1, 9) on the path by cell 0's middle bush).
	@GameTest(maxTicks = 20)
	public void aBerryFarmsCornerSpotTouchesNoBushWhereOneCan(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BotScripts.run(bot, """
			import lib.cellfarm
			import lib.cells
			import jobs.berry_farm
			f = jobs.berry_farm.spec()
			size = f["size"]
			for low in [Pos(1, 0, 3), Pos(5, 0, 8), Pos(15, 0, 15)]:
				name = f"test_farm_{low.x}"
				lib.cells.layout(name, low, size)
				s = lib.cellfarm.corner_spot(*lib.cells.cell(name, 0), f.get("hurts", False))
				clear = True
				for i in range(8):
					a, b = lib.cells.cell(name, i)
					clear = clear and max(a.x - s.x, s.x - b.x, a.z - s.z, s.z - b.z) >= 2
				print(f"{low}: {clear} {s}")
			""");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).size() == 3
			&& BotScripts.log(bot, Output.Level.PRINT).stream().allMatch(l -> l.contains(": True")),
			"said " + BotScripts.log(bot, Output.Level.PRINT)));
	}

	/**
	 * A clear corner spot, as for a farm whose cells hurt, touches no cell block and stays in the corner's chunk at the
	 * chunk's edges too (#888): with the corner at chunk-local x 0, or z 0 or 1, none of #872's candidates was both, so
	 * the bot fell back to a spot beside a bush. Berry cells (3 by 1) and cactus cells (2 by 2) are laid out from corners
	 * at those edges, and for each whether the clear spot is in the corner's chunk and two blocks or more off every one of
	 * the first eight cells is said.
	 */
	// Measured: passes at tick 11 (red: False for all six, the old fallback spots, such as Pos(17, 1, 6) on the path by
	// cell 0's first bush for the berry corner at chunk-local x 0).
	@GameTest(maxTicks = 20)
	public void aClearCornerSpotAtAChunkEdgeTouchesNoCell(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BotScripts.run(bot, """
			import lib.cellfarm
			import lib.cells
			cases = [((3, 1), Pos(16, 0, 5)), ((3, 1), Pos(5, 0, 16)), ((3, 1), Pos(5, 0, 17)),
				((2, 2), Pos(16, 0, 5)), ((2, 2), Pos(5, 0, 16)), ((2, 2), Pos(1, 0, 17))]
			for size, low in cases:
				name = f"test_farm_{size[0]}_{low.x}_{low.z}"
				lib.cells.layout(name, low, size)
				s = lib.cellfarm.corner_spot(*lib.cells.cell(name, 0), True)
				clear = s.x // 16 == low.x // 16 and s.z // 16 == low.z // 16
				for i in range(8):
					a, b = lib.cells.cell(name, i)
					clear = clear and max(a.x - s.x, s.x - b.x, a.z - s.z, s.z - b.z) >= 2
				print(f"{size} {low}: {clear} {s}")
			""");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).size() == 6
			&& BotScripts.log(bot, Output.Level.PRINT).stream().allMatch(l -> l.contains(": True")),
			"said " + BotScripts.log(bot, Output.Level.PRINT)));
	}

	/**
	 * by_cells and the clear corner spot take the row and gap of the farm's layout from lib.cells (#889), not a row of 4
	 * and a gap of 1 of their own. A farm of 1 by 1 cells is laid out with a row of 2 and a gap of 2; by_cells must
	 * agree, block by block, with the cells lib.cells gives, and the clear spot must be two or more off each of them.
	 */
	// Measured: passes at tick 34 (red: by_cells() takes 4 positional arguments but 6 were given).
	@GameTest(maxTicks = 70)
	public void byCellsReadsTheLayoutsRowAndGap(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BotScripts.run(bot, """
			import lib.cellfarm
			import lib.cells
			name = "test_farm_layout"
			lib.cells.layout(name, Pos(16, 0, 5), (1, 1), 2, 2)
			f = lib.cells.farm(name)
			low, high = lib.cells.cell(name, 0)
			cells = [lib.cells.cell(name, i) for i in range(8)]
			wrong = []
			for x in range(14, 22):
				for z in range(3, 16):
					near = any([a.x - 1 <= x <= b.x + 1 and a.z - 1 <= z <= b.z + 1 for a, b in cells])
					if near != lib.cellfarm.by_cells(x, z, low, high, f["row"], f["gap"]):
						wrong.append((x, z))
			s = lib.cellfarm.corner_spot(low, high, True, f["row"], f["gap"])
			clear = all([max(a.x - s.x, s.x - b.x, a.z - s.z, s.z - b.z) >= 2 for a, b in cells])
			print(f"wrong {wrong} clear {clear} {s}")
			""");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).size() == 1
			&& BotScripts.log(bot, Output.Level.PRINT).get(0).startsWith("wrong [] clear True"),
			"said " + BotScripts.log(bot, Output.Level.PRINT)));
	}
}
