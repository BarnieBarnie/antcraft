package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.at;

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
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code lib/get.py} (#508): the resolver gets anything by the cheapest of carried, chests, crafting and SOURCES, and
 * picks a tool by cost. A neighbouring test's chest or tree is out of every scan (GameTests.hides).
 */
public class GetGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CHEST = new BlockPos(6, 1, 3);
	private static final Logger LOGGER = LoggerFactory.getLogger("antcraft");

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * An empty bot by the oak of {@link TreesGameTest} and three stone: logs by hand, a wooden pickaxe, cobblestone, then
	 * the stone axe; called again it does nothing.
	 */
	@GameTest(maxTicks = 1000)
	public void stoneAxeFromNothingThenNothingMore(GameTestHelper helper) {
		TreesGameTest.plantTree(helper);
		for (int z = 4; z <= 6; z++) {
			helper.setBlock(1, 1, z, Blocks.STONE);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		run(helper, bot, """
			from lib.get import get
			r = get("stone_axe")
			print(r["ok"], count("stone_axe"))
			before = [(s.item, s.count) for s in inventory()]
			r = get("stone_axe")
			print(r["ok"], [(s.item, s.count) for s in inventory()] == before)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True 1", "True True"));
	}

	/**
	 * A wooden pickaxe carried, no station near (#635): the stations of 64 iron ingots are a table mined back by hand
	 * (hardness 2.5: 75 ticks) and a furnace with the pickaxe (3.5: 53); of 65, smelt_here puts a furnace down for each
	 * of two stacks.
	 */
	@GameTest(maxTicks = 20)
	public void stationsBreakAsTheirHardnessAndAFurnaceAStack(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		run(helper, bot, """
			import lib.get
			import lib.craft
			print(lib.get.stations(lib.craft.plan("iron_ingot", 64)), lib.get.stations(lib.craft.plan("iron_ingot", 65)))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "128 181"));
	}

	/**
	 * A stone pickaxe carried by a tree, coal ore and three iron ore (#635): make_cost of an iron axe is within a factor
	 * {@link #COST_FACTOR} of the ticks get then takes to make it, logs, cobblestone, smelting, table and furnace and all.
	 * Measured: make_cost 1182 (the three ore a vein, #661), get 1360 ticks.
	 */
	@GameTest(maxTicks = 2700)
	public void makeCostOfAnIronAxeIsCloseToItsTicks(GameTestHelper helper) {
		TreesGameTest.plantTree(helper);
		helper.setBlock(1, 1, 3, Blocks.COAL_ORE);
		for (int z = 4; z <= 6; z++) {
			helper.setBlock(1, 1, z, Blocks.IRON_ORE);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, """
			import lib.get
			print(lib.get.make_cost("iron_axe", 1))
			r = lib.get.get("iron_axe")
			print(r["ok"])
			""");
		measured(helper, bot);
	}

	/**
	 * Four coal ore buried under the floor, none exposed, four sticks and a stone pickaxe carried (#641): make_cost of 16
	 * torches (the coal source's estimate each and lib.get.deep's STEP_DOWN, BRANCH and STEP_UP) is within
	 * {@link #COST_FACTOR} of the ticks get takes to dig stairs 3 down, a branch of 4 north past the vein, climb back up
	 * and craft. The coal source and lib.dig.LENGTH are cut to that depth and length, as the area is 8 blocks wide. Stone
	 * is filled under the floor, y -4 to -1. Measured: make_cost 482, get 464 ticks.
	 */
	@GameTest(maxTicks = 950)
	public void makeCostOfBuriedOreIsCloseToItsTicks(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, -4, 0, 7, -1, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		for (BlockPos pos : BlockPos.betweenClosed(4, -2, 1, 4, -1, 2)) {
			helper.setBlock(pos, Blocks.COAL_ORE);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 7);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 4));
		run(helper, bot, """
			import lib.dig
			from lib.get import get, make_cost, Source, SOURCES
			y = pos().y - 3
			lib.dig.LENGTH = 4
			s = SOURCES["minecraft:coal"]
			SOURCES["minecraft:coal"] = Source(lambda n, radius: lib.dig.ore("coal", n, radius, y, length=4), s.tool, s.tier, s.estimate, s.ore, y=y)
			print(make_cost("torch", 16))
			r = get("torch", 16)
			print(r["ok"])
			""");
		measured(helper, bot);
	}

	/**
	 * A stick carried and a coal in a chest across the area (#641): make_cost of four torches (the walk to the chest) is
	 * within {@link #COST_FACTOR} of the ticks get takes to fetch the coal and craft them. Measured: make_cost 70, get 60
	 * ticks.
	 */
	@GameTest(maxTicks = 150)
	public void makeCostOfChestCoalIsCloseToItsTicks(GameTestHelper helper) {
		chest(helper, new BlockPos(7, 1, 7), new ItemStack(Items.COAL));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 0, 1, 0);
		bot.getInventory().addItem(new ItemStack(Items.STICK));
		run(helper, bot, """
			from lib.get import get, make_cost
			print(make_cost("torch", 4))
			r = get("torch", 4)
			print(r["ok"])
			""");
		measured(helper, bot);
	}

	/**
	 * A stone pickaxe carried, three iron ore at three corners of the area (#649): make_cost of three raw iron is the raw
	 * iron source's estimate, as get gathers it, within {@link #COST_FACTOR} of get's ticks; it was None, as make_cost turned
	 * down the raw item it was costing. The estimate (60) counts a walk to each ore: three ore beside the bot took 82 ticks.
	 * Since #675 the nearest ore costs the walk to it and its mining (30 and 23). Measured: make_cost 173 (180 before), get
	 * 126 to 133 ticks (test tick 131 to 146).
	 */
	@GameTest(maxTicks = 270)
	public void makeCostOfRawIronIsCloseToItsTicks(GameTestHelper helper) {
		helper.setBlock(7, 1, 1, Blocks.IRON_ORE);
		helper.setBlock(7, 1, 7, Blocks.IRON_ORE);
		helper.setBlock(1, 1, 7, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, """
			from lib.get import get, make_cost
			print(make_cost("raw_iron", 3))
			r = get("raw_iron", 3)
			print(r["ok"])
			""");
		measured(helper, bot);
	}

	/**
	 * A stone pickaxe carried, two raw iron in a chest along the area's side and one iron ore past it (#661): make_cost of
	 * three raw iron is the walk to the chest and one raw iron from its source, as get takes the chest's first, within
	 * {@link #COST_FACTOR} of get's ticks; it was three from the source. The ore costs the walk from the chest to it
	 * and its mining (#675). Measured: make_cost 83 (180 before #661, 90 before #675, flaky against 59), get 63 ticks
	 * (test tick 75 to 77).
	 */
	@GameTest(maxTicks = 150)
	public void makeCostOfRawIronInAChestIsCloseToItsTicks(GameTestHelper helper) {
		chest(helper, new BlockPos(1, 1, 7), new ItemStack(Items.RAW_IRON, 2));
		helper.setBlock(7, 1, 7, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, """
			from lib.get import get, make_cost
			print(make_cost("raw_iron", 3))
			r = get("raw_iron", 3)
			print(r["ok"])
			""");
		measured(helper, bot);
	}

	/**
	 * Two raw iron in a chest 20 blocks off the bot along each of x and z (#664): make_cost of two raw iron, the walk to the
	 * chest, is within {@link #DIAGONAL_FACTOR} of the ticks get takes. A path steps along x or z, never both at once, so a
	 * diagonal walk takes as long as the blocks along x and z: lib.cost.walk counted as max(dx, dz) + 0.41 min(dx, dz)
	 * gave 141 for 200 ticks. Measured: make_cost 200, get 193 to 200 ticks (test tick 225); 20 blocks along x alone
	 * 100 for 101, 10 along each 100 for 92.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 400)
	public void makeCostOfADiagonalChestIsCloseToItsTicks(GameTestHelper helper) {
		chest(helper, new BlockPos(21, 1, 21), new ItemStack(Items.RAW_IRON, 2));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, """
			from lib.get import get, make_cost
			print(make_cost("raw_iron", 2))
			r = get("raw_iron", 2)
			print(r["ok"])
			""");
		measured(helper, bot, DIAGONAL_FACTOR);
	}

	/**
	 * A stone pickaxe carried, a vein of three iron ore a few blocks off (#661): make_cost of three raw iron is the source's
	 * estimate for the first and the mining of the rest of the vein, within {@link #COST_FACTOR} of get's ticks; it was the
	 * estimate, which counts a walk, for each. Since #675 the first costs the walk to it
	 * and its mining. Measured: make_cost 99 (180 before #661, 106 before #675), get 80 to 86 ticks (test tick 98 to 100).
	 */
	@GameTest(maxTicks = 200)
	public void makeCostOfAVeinOfRawIronIsCloseToItsTicks(GameTestHelper helper) {
		helper.setBlock(4, 1, 4, Blocks.IRON_ORE);
		helper.setBlock(5, 1, 4, Blocks.IRON_ORE);
		helper.setBlock(4, 1, 5, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, """
			from lib.get import get, make_cost
			print(make_cost("raw_iron", 3))
			r = get("raw_iron", 3)
			print(r["ok"])
			""");
		measured(helper, bot);
	}

	/**
	 * A stone pickaxe carried, a vein of three iron ore in sight (#681): make_cost of three raw iron with a limit of what it
	 * costs without one is that cost, as options' limit keeps a tier whose ore in sight makes it cheapest. The limit's
	 * bound was the source's estimate (60 each, 180), more than the vein costs, so it was None.
	 */
	@GameTest(maxTicks = 50)
	public void aLimitKeepsAVeinInSightThatCostsLessThanItsEstimate(GameTestHelper helper) {
		helper.setBlock(4, 1, 4, Blocks.IRON_ORE);
		helper.setBlock(5, 1, 4, Blocks.IRON_ORE);
		helper.setBlock(4, 1, 5, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, """
			from lib.get import make_cost
			c = make_cost("raw_iron", 3)
			print(c < 180, make_cost("raw_iron", 3, limit=c) == c)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True True"));
	}

	/**
	 * A vein of two coal ore and an oak in reach, four sticks, a stone pickaxe and a furnace carried (#663): get of eight
	 * torches digs the vein (wood_for_coal gives 0) and then, with the vein gone and the coal source failing, eight more
	 * burn the oak's logs into charcoal. Each route's make_cost is within {@link #COST_FACTOR} of the ticks get takes, and
	 * the coal it chose is the faster route: the coal source costs the walk to the vein and 23 an ore (#675), charcoal 55 a
	 * log and SMELT a coal, so a vein in sight wins and #661's cheaper vein does not change the choice. Measured: coal
	 * make_cost 76 (80 before #675), get 70 to 77 ticks; charcoal 620, get 739 to 740 (test tick 820).
	 */
	@GameTest(maxTicks = 1650)
	public void coalFromAVeinInSightBeatsCharcoal(GameTestHelper helper) {
		TreesGameTest.plantTree(helper);
		helper.setBlock(1, 1, 7, Blocks.COAL_ORE);
		helper.setBlock(2, 1, 7, Blocks.COAL_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 4));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		run(helper, bot, """
			from lib.get import get, make_cost, wood_for_coal, SOURCES
			from lib.result import result
			def timed(n):
			    c = make_cost("torch", n)
			    t = game_time()
			    ok = get("torch", n)["ok"]
			    return str(c) + " " + str(game_time() - t) + " " + str(ok)
			print(wood_for_coal(2, 32))
			print(timed(8))
			SOURCES["minecraft:coal"].do = lambda n, radius: result("dug_for_coal")
			print(timed(16))
			""");
		helper.onEachTick(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			if (log.size() < 3) {
				return;
			}
			String[] coal = log.get(1).split(" ");
			String[] wood = log.get(2).split(" ");
			String measured = "printed " + log + ", log " + BotScripts.log(bot, Output.Level.DEBUG);
			if (!log.get(0).equals("0") || !coal[2].equals("True") || !wood[2].equals("True")) {
				helper.fail(measured);
			}
			long coalTicks = Long.parseLong(coal[1]);
			long woodTicks = Long.parseLong(wood[1]);
			for (String[] route : List.of(coal, wood)) {
				double cost = Double.parseDouble(route[0]);
				long ticks = Long.parseLong(route[1]);
				if (cost * COST_FACTOR < ticks || cost > ticks * COST_FACTOR) {
					helper.fail("make_cost and ticks more than a factor " + COST_FACTOR + " apart: " + measured);
				}
			}
			if (coalTicks >= woodTicks) {
				helper.fail("the coal route was not the faster: " + measured);
			}
			LOGGER.info("coal or wood: " + log);
			helper.succeed();
		});
	}

	/**
	 * A coal ore at the far corner of the wide area, 42 blocks' walk off, and a stone pickaxe carried (#675): the coal
	 * source costs lib.cost.walk to the ore and its mining (210 and 23), not a flat 40, so with SMELT cut to 50 ticks one
	 * coal as charcoal (two logs at 55 and 50) is cheaper and wood_for_coal gives those two logs; it gave 0. A vein in
	 * sight still beats charcoal (coalFromAVeinInSightBeatsCharcoal).
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 140)
	public void aFarCoalVeinDoesNotBeatCheaperCharcoal(GameTestHelper helper) {
		helper.setBlock(22, 1, 22, Blocks.COAL_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, """
			import lib.cost
			from lib.get import wood_for_coal
			lib.cost.SMELT = 50
			print(wood_for_coal(1, 32))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "2"));
	}

	/**
	 * A grown cherry tree and two persistent birch leaves, as a player places them (#649): make_cost of a cherry sapling is
	 * the source's estimate, of a birch sapling None, as get breaks no persistent leaves
	 * (persistentLeavesAreNotBrokenForSaplings). Both were None: make_cost turned down a raw item it was costing itself.
	 */
	@GameTest(maxTicks = 40)
	public void makeCostOfASaplingCountsOnlyGrownLeaves(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES, false);
		for (int y = 1; y <= 2; y++) {
			helper.setBlock(1, y, 7, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, where(helper, 7) + """
			from lib.get import make_cost, SOURCES
			print(make_cost("cherry_sapling", 2) == SOURCES["minecraft:cherry_sapling"].estimate * 2, make_cost("birch_sapling", 1))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True None"));
	}

	/** How far make_cost may be from the ticks get takes, either way. */
	private static final double COST_FACTOR = 1.5;

	/** How far make_cost of a walk alone may be from the ticks get takes, either way (#664). */
	private static final double DIAGONAL_FACTOR = 1.2;

	/**
	 * Succeeds where the bot printed an estimate and then True, and the ticks from the first print to the second are
	 * within COST_FACTOR of that estimate; fails at once where they cannot be any more, or the bot printed anything else.
	 */
	private static void measured(GameTestHelper helper, AntBot bot) {
		measured(helper, bot, COST_FACTOR);
	}

	/** As {@link #measured(GameTestHelper, AntBot)}, within factor rather than COST_FACTOR. */
	private static void measured(GameTestHelper helper, AntBot bot, double factor) {
		long[] start = {-1};
		boolean[] close = {false};
		helper.onEachTick(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			if (log.isEmpty() || close[0]) {
				return;
			}
			if (start[0] < 0) {
				start[0] = helper.getTick();
			}
			double cost = Double.parseDouble(log.get(0));
			long ticks = helper.getTick() - start[0];
			String measured = "make_cost " + log.get(0) + ", measured " + ticks + " ticks";
			if (log.size() > 1 && !log.subList(1, log.size()).equals(List.of("True"))) {
				helper.fail("printed " + log + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
			} else if (cost * factor < ticks || log.size() > 1 && cost > ticks * factor) {
				helper.fail(measured + ", more than a factor " + factor + " apart");
			} else if (log.size() > 1) {
				LOGGER.info(measured);
				close[0] = true;
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(close[0], "get has not finished"));
	}

	/**
	 * A hoe for 20 tilled blocks from two oak logs carried (#544): ensure_tool makes and holds a wooden one. colony.farmer's
	 * game tests take one from the core.
	 */
	@GameTest(maxTicks = 240)
	public void ensuresAHoe(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		run(helper, bot, """
			from lib.get import ensure_tool
			r = ensure_tool("hoe", 20)
			print(r["ok"], held().item)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True minecraft:wooden_hoe"));
	}

	/** Three iron ingots and a log carried: get crafts an empty bucket, with a crafting table of the log (#592). */
	// Measured: passes at tick 90.
	@GameTest(maxTicks = 180)
	public void craftsABucketOfIronIngots(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.IRON_INGOT, 3));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG));
		run(helper, bot, """
			from lib.get import get
			r = get("bucket")
			print(r["ok"], count("bucket"), count("iron_ingot"))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True 1 0"));
	}

	/** Logs and cobblestone carried for a stone axe, an iron axe in a chest: ensure_tool borrows and holds the iron one. */
	@GameTest(maxTicks = 20)
	public void borrowsAChestAxeRatherThanCrafting(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.IRON_AXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		run(helper, bot, """
			from lib.get import ensure_tool
			from lib.borrow import borrowed
			r = ensure_tool("axe", 50)
			print(r["ok"], held().item, count("stone_axe"), count("cobblestone"), [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:iron_axe 0 3 [('minecraft:iron_axe', 1)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * Planks and sticks carried for a wooden pickaxe and its table, no table near, a stone pickaxe in a chest 4 blocks off
	 * (#630): making the wooden one costs the table put down and mined back by hand, more than the walk, so ensure_tool
	 * takes the chest's.
	 */
	@GameTest(maxTicks = 20)
	public void theTableToMineBackMakesAChestToolCheaper(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.STONE_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 7));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		run(helper, bot, """
			from lib.get import ensure_tool
			r = ensure_tool("pickaxe", 1)
			print(r["ok"], held().item, count("oak_planks"))
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:stone_pickaxe 7");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * A wooden and a stone pickaxe carried that last (#551): ensure_tool holds the stone one at once, without costing
	 * options (replaced here by one that prints "costed").
	 */
	@GameTest(maxTicks = 20)
	public void holdsALastingCarriedToolWithoutCosting(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, """
			import lib.get
			lib.get.options = lambda *a: print("costed") or []
			r = lib.get.ensure_tool("pickaxe", 50, "stone")
			print(r["ok"], held().item)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True minecraft:stone_pickaxe"));
	}

	/** An iron pickaxe in a chest, none carried (#551): ensure_tool borrows it without costing options. */
	@GameTest(maxTicks = 20)
	public void borrowsALastingChestToolWithoutCosting(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.IRON_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 3));
		run(helper, bot, """
			import lib.get
			from lib.borrow import borrowed
			lib.get.options = lambda *a: print("costed") or []
			r = lib.get.ensure_tool("pickaxe", 50)
			print(r["ok"], held().item, [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:iron_pickaxe [('minecraft:iron_pickaxe', 1)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * A stone axe in a chest across the wide area (walk 190 ticks) and 3 logs carried (#562): the chest is past NEAR, so
	 * ensure_tool weighs it against making one and makes a wooden axe (30 ticks of work) rather than walk there. Passes at
	 * tick 164 in a full run (#619), 173-181 with the Get, WoodFarm, TreeFarm, Forester and Depot tests (#612), 179-195
	 * before #618 (and 269-362 under load before #618, #612); maxTicks is twice the slowest since #618.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 400)
	public void aFarChestToolLosesToMakingOne(GameTestHelper helper) {
		Container chest = chest(helper, new BlockPos(21, 1, 21), new ItemStack(Items.STONE_AXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 3));
		run(helper, bot, where(helper, 23) + """
			from lib.get import ensure_tool
			r = ensure_tool("axe", radius=30)
			print(r["ok"], held().item)
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:wooden_axe");
			helper.assertTrue(chest.countItem(Items.STONE_AXE) == 1, "the stone axe left the chest");
		});
	}

	/**
	 * An empty bot, no log and no chest in its area (#562): ensure_tool fails no_logs, the reason of the log source,
	 * without costing options (replaced here by one that prints "costed"): tick 4 alone and 7 in a group run, 114 before.
	 */
	@GameTest(maxTicks = 30)
	public void ensureToolFailsFastWithoutLogs(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, where(helper, 7) + """
			import lib.get
			lib.get.options = lambda *a: print("costed") or []
			r = lib.get.ensure_tool("axe", 30)
			print(r["ok"], r["reason"])
			""");
		helper.succeedWhen(() -> printed(helper, bot, "False no_logs"));
	}

	/**
	 * A copper axe carried, made a tool of no lib.tools.TIERS tier as a modded one is by taking copper out of TIERS for
	 * this run (#562, #578): tools.best counts it, and ensure_tool holds it without costing where no tier is asked; for
	 * at least stone it does not count.
	 */
	@GameTest(maxTicks = 10)
	public void anUnknownTierToolIsUsedWhereNoTierIsAsked(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.COPPER_AXE));
		run(helper, bot, """
			import lib.get
			import lib.tools
			lib.tools.TIERS.remove("copper")
			lib.get.options = lambda *a: print("costed") or []
			print(lib.tools.best("axe").item, len(lib.get.lasting("axe", 1, "stone")[1]))
			r = lib.get.ensure_tool("axe", 30)
			print(r["ok"], held().item)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "minecraft:copper_axe 0", "True minecraft:copper_axe"));
	}

	/** A line that sets lib.find.WHERE to the test's area, size blocks along x and z from its corner. */
	private static String where(GameTestHelper helper, int size) {
		BlockPos a = helper.absolutePos(BlockPos.ZERO);
		BlockPos b = helper.absolutePos(new BlockPos(size, 0, size));
		return "import lib.find\nlib.find.WHERE = lambda p: %d <= p.x <= %d and %d <= p.z <= %d\n".formatted(
			Math.min(a.getX(), b.getX()), Math.max(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getZ(), b.getZ()));
	}

	/**
	 * Logs carried and no stone within radius 1 (the floor under it dirt): a stone pickaxe is the cheapest to make but
	 * fails no_stone, so ensure_tool falls back to the wooden one (#518).
	 */
	@GameTest(maxTicks = 250)
	public void fallsBackToAWoodenPickaxeWithoutStone(GameTestHelper helper) {
		for (int x = 2; x <= 4; x++) {
			for (int z = 3; z <= 5; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
				helper.setBlock(x, -1, z, Blocks.DIRT);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 3));
		run(helper, bot, """
			from lib.get import ensure_tool
			r = ensure_tool("pickaxe", 10, radius=1)
			print(r["ok"], r["reason"], held().item, count("wooden_pickaxe"))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True None minecraft:wooden_pickaxe 1"));
	}

	/** 5 planks in a chest and a log carried: get takes the 5 and crafts the rest from the log (#517). */
	@GameTest(maxTicks = 20)
	public void takesPartFromAChestAndCraftsTheRest(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_PLANKS, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG));
		run(helper, bot, """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("oak_planks", 8)
			print(r["ok"], count("oak_planks"), count("oak_log"), [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 9 0 [('minecraft:oak_planks', 5)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/** 5 planks in a chest, no log and no tree: get fails for the other 3 and gives the 5 back (#535). */
	@GameTest(maxTicks = 20)
	public void givesBackWhatItBorrowedWhenItFails(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_PLANKS, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("oak_planks", 8)
			print(r["ok"], count("oak_planks"), borrowed())
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "False 0 []");
			helper.assertTrue(chest.countItem(Items.OAK_PLANKS) == 5, "planks in the chest " + chest.countItem(Items.OAK_PLANKS));
		});
	}

	/** A log in a chest, two needed for 8 planks: get takes that log and fells the oak for the other (#517). */
	@GameTest(maxTicks = 200)
	public void takesPartOfARawItemFromAChest(GameTestHelper helper) {
		TreesGameTest.plantTree(helper);
		Container chest = chest(helper, new ItemStack(Items.OAK_LOG));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		run(helper, bot, """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("oak_planks", 8)
			print(r["ok"], count("oak_planks") >= 8, [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True True [('#minecraft:logs', 1)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/** 6 spruce planks in a chest, a crafting table by the bot, no log or tree: get borrows the planks for a spruce trapdoor (#560). */
	@GameTest(maxTicks = 30)
	public void borrowsPlanksFromAChestForATrapdoor(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.SPRUCE_PLANKS, 6));
		helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("spruce_trapdoor")
			print(r["ok"], r["reason"], count("spruce_trapdoor"), [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True None 2 [('minecraft:spruce_planks', 6)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * 2 sticks in a chest, one oak log carried, a crafting table by the bot, no tree: get borrows the sticks for a wooden
	 * pickaxe, so the log's 4 planks do, instead of the 2 logs of planks it needs without them (#560).
	 */
	@GameTest(maxTicks = 30)
	public void borrowsSticksFromAChestForAPickaxe(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.STICK, 2));
		helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG));
		run(helper, bot, """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("wooden_pickaxe")
			print(r["ok"], r["reason"], count("wooden_pickaxe"), [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True None 1 [('minecraft:stick', 2)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * 4 birch planks in a chest, no log or tree in the area (#580): the plan picks oak planks for a crafting table, whose
	 * recipe takes any planks, so get borrows the birch ones.
	 */
	@GameTest(maxTicks = 30)
	public void borrowsOtherPlanksForAnyPlanksRecipe(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.BIRCH_PLANKS, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("crafting_table")
			print(r["ok"], r["reason"], count("crafting_table"), [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True None 1 [('#minecraft:planks', 4)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * 4 birch planks in a chest within radius 8 but not 2 (#580): the make cost of a crafting table is the walk to them, as
	 * get takes them, not the log it costs without the chest.
	 */
	@GameTest(maxTicks = 20)
	public void chestPlanksLowerTheMakeCostToTheWalk(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.BIRCH_PLANKS, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			import lib.cost, lib.stock
			from lib.get import make_cost
			far, near = make_cost("crafting_table", 1, radius=2), make_cost("crafting_table", 1, radius=8)
			print(far > near, near == lib.cost.walk(pos(), lib.stock.where("#minecraft:planks", 8)[0]))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True True"));
	}

	/**
	 * 8 oak planks in a chest, a crafting table by the bot (#580): get takes a planks step's whole output (4, 2 of them for
	 * the sticks), and the plan made again counts the rest for the pickaxe's head, so it takes one more step of 4, gathers
	 * nothing and has 3 left. This worked before #580: the planner keeps what a step makes beyond its need for later steps.
	 */
	@GameTest(maxTicks = 30)
	public void theRestOfATakenStepCountsForLaterSteps(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.OAK_PLANKS, 8));
		helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("wooden_pickaxe")
			print(r["ok"], r["reason"], count("wooden_pickaxe"), count("oak_planks"), [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True None 1 3 [('minecraft:oak_planks', 4), ('minecraft:oak_planks', 4)]"));
	}

	/**
	 * Three copper ingots and two sticks carried, a crafting table by the bot (#578): ensure_tool for at least copper makes
	 * a copper pickaxe, a tier of lib.craft.MADE, from them, cheaper than an iron one whose raw iron it would dig.
	 */
	@GameTest(maxTicks = 70)
	public void ensureToolMakesACopperPickaxeFromCopperIngots(GameTestHelper helper) {
		helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.COPPER_INGOT, 3));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		run(helper, bot, where(helper, 7) + """
			from lib.get import ensure_tool
			r = ensure_tool("pickaxe", 1, "copper")
			print(r["ok"], r["reason"], held().item, count("copper_ingot"))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True None minecraft:copper_pickaxe 0"));
	}

	/**
	 * Two sticks carried, no log or chest in the area (#580): the sticks cover a stick's plan, but every tool still needs
	 * logs, for a wooden one's planks or the pickaxe a stone one's cobblestone takes, so ensure_tool fails no_logs without
	 * costing options.
	 */
	@GameTest(maxTicks = 30)
	public void ensureToolFailsFastWithSticksButNoLogs(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		run(helper, bot, where(helper, 7) + """
			import lib.get
			lib.get.options = lambda *a: print("costed") or []
			r = lib.get.ensure_tool("axe", 30)
			print(r["ok"], r["reason"])
			""");
		helper.succeedWhen(() -> printed(helper, bot, "False no_logs"));
	}

	/**
	 * An iron axe in a chest 20 blocks off (walk 100), an empty bot (#580): walking there and felling 30 logs with it costs
	 * less than making a wooden axe from logs and felling with that, so ensure_tool takes it without costing options.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 200)
	public void aChestToolCheaperThanMakingOneIsTakenWithoutCosting(GameTestHelper helper) {
		Container chest = chest(helper, new BlockPos(12, 1, 12), new ItemStack(Items.IRON_AXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		run(helper, bot, where(helper, 23) + """
			import lib.get
			lib.get.options = lambda *a: print("costed") or []
			r = lib.get.ensure_tool("axe", 30, radius=30)
			print(r["ok"], held().item)
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:iron_axe");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * A copper axe in a chest, made a tool of no lib.tools.TIERS tier as a modded one is by taking copper out of TIERS for
	 * this run (#578), an empty bot (#580): where no tier is asked ensure_tool borrows it, as it uses a carried one (#562).
	 */
	@GameTest(maxTicks = 30)
	public void aChestToolOfUnknownTierIsTakenWhereNoTierIsAsked(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.COPPER_AXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, where(helper, 7) + """
			import lib.get
			import lib.tools
			lib.tools.TIERS.remove("copper")
			lib.get.options = lambda *a: print("costed") or []
			r = lib.get.ensure_tool("axe", 30)
			print(r["ok"], held().item)
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:copper_axe");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * An empty bot, no chest, exposed iron ore in sight: a stone axe for few logs, an iron one for many (500: for 400 the
	 * two were some 50 ticks apart, and the stations each tool's tables and furnace add tipped it to stone, #630); nothing
	 * is done.
	 * Without the ore in sight the make cost digs stairs for it (#529), and the stone axes win; without coal ore it burns
	 * wood for the fuel, as get would (#536).
	 */
	@GameTest(maxTicks = 140)
	public void chooseStoneForFewUsesIronForMany(GameTestHelper helper) {
		helper.setBlock(6, 1, 7, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			from lib.get import choose
			print(choose("axe", 30)[1:3], choose("axe", 500)[1:3], choose("axe", 30, "iron")[1:3])
			""");
		helper.succeedWhen(() -> printed(helper, bot, "('make', 'stone') ('make', 'iron') ('make', 'iron')"));
	}

	/**
	 * The make cost of an iron axe counts the pickaxe raw iron needs and whether iron ore is in sight (#529): an empty bot
	 * with no iron ore within radius 2 pays for a stone pickaxe and DEEP once for the raw iron, more than a bot that carries a
	 * stone pickaxe and sees exposed iron ore within radius 8.
	 */
	@GameTest(maxTicks = 40)
	public void ironCostsMoreWithoutAPickaxeOrOreInSight(GameTestHelper helper) {
		helper.setBlock(6, 1, 7, Blocks.IRON_ORE);
		AntBot bare = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		AntBot ready = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		ready.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		String choose = "from lib.get import choose\nprint(choose('axe', 30, 'iron', radius=%d)[0])\n";
		run(helper, bare, choose.formatted(2));
		run(helper, ready, choose.formatted(8));
		helper.succeedWhen(() -> {
			List<String> far = BotScripts.log(bare, Output.Level.PRINT);
			List<String> near = BotScripts.log(ready, Output.Level.PRINT);
			helper.assertTrue(far.size() == 1 && near.size() == 1, "printed " + far + " and " + near);
			helper.assertTrue(Integer.parseInt(far.get(0)) > Integer.parseInt(near.get(0)),
				"without a pickaxe or ore in sight " + far + ", with them " + near);
		});
	}

	/**
	 * Iron ore in sight but no coal ore: the make cost of two iron axes, whose six smelts plan a coal, burns wood for the
	 * fuel, as get would, rather than dig for coal (#536). With a branch block a million ticks (lib.get.BRANCH), digging for
	 * it would cost more than that.
	 */
	@GameTest(maxTicks = 40)
	public void ironFuelIsWoodWithoutCoalInSight(GameTestHelper helper) {
		helper.setBlock(6, 1, 7, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			import lib.get
			lib.get.BRANCH = 1000000
			print(lib.get.make_cost("iron_axe", 2) < 1000000)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True"));
	}

	/**
	 * A stone pickaxe in a chest within radius 8 but not 2, iron ore within both: the make cost of an iron axe walks to the
	 * chest for the pickaxe raw iron needs, less than making one (#536).
	 */
	@GameTest(maxTicks = 40)
	public void aChestPickaxeLowersAnIronToolsCost(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.STONE_PICKAXE));
		helper.setBlock(2, 1, 5, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		run(helper, bot, "from lib.get import make_cost\nprint(make_cost('iron_axe', 1, radius=2), make_cost('iron_axe', 1, radius=8))\n");
		helper.succeedWhen(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(log.size() == 1, "printed " + log);
			String[] costs = log.get(0).split(" ");
			helper.assertTrue(Integer.parseInt(costs[0]) > Integer.parseInt(costs[1]), "without the chest, with it: " + log);
		});
	}

	/**
	 * Coal in a chest within radius 8 but not 2 (#548): the make cost of four torches, whose one coal costs charcoal or
	 * digging without the chest, is that of their stick plus the walk to the chest with it, as get takes from chests first.
	 */
	@GameTest(maxTicks = 20)
	public void chestCoalLowersATorchsCostToTheWalk(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.COAL));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			import lib.cost, lib.stock
			from lib.get import make_cost
			far, near, stick = make_cost("torch", 4, radius=2), make_cost("torch", 4, radius=8), make_cost("stick", 1, radius=8)
			print(far > near, near - stick == lib.cost.walk(pos(), lib.stock.where("coal", 8)[0]))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True True"));
	}

	/**
	 * 1 coal in a chest covers one of the 2 coal of 8 torches (#555): make_cost counts the walk to it and the other coal
	 * as with no chest, so it lies between the walk and sticks alone and the cost with no chest within radius.
	 */
	@GameTest(maxTicks = 20)
	public void chestCoalCoversOnlyWhatTheChestHolds(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.COAL));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			import lib.cost, lib.stock
			from lib.get import make_cost
			far, near = make_cost("torch", 8, radius=2), make_cost("torch", 8, radius=8)
			walk = make_cost("stick", 2, radius=8) + lib.cost.walk(pos(), lib.stock.where("coal", 8)[0])
			print(walk < near, near < far)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True True"));
	}

	/**
	 * 1 coal in a chest, 8 torches wanted (#564): get takes it and burns 2 logs, also in the chest, into charcoal for the
	 * other, rather than dig for coal. The bot carries a furnace and the wooden pickaxe that mines it back.
	 * The coal source fails, so digging for coal fails the test at once.
	 */
	@GameTest(maxTicks = 600)
	public void chestCoalAndCharcoalMakeTorches(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.COAL), new ItemStack(Items.OAK_LOG, 2));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		run(helper, bot, """
			from lib.get import get, SOURCES
			from lib.result import result
			SOURCES["minecraft:coal"].do = lambda n, radius: result("dug_for_coal")
			r = get("torch", 8)
			print(r["ok"], r["reason"], count("torch"))
			""");
		helper.onEachTick(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			if (!log.isEmpty() && !log.equals(List.of("True None 8"))) {
				helper.fail("printed " + log + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			printed(helper, bot, "True None 8");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * Where no ore is exposed, a source's cost counts the trip to its lib.dig.best_y from the bot's height (#564): stairs
	 * down, a branch and back up, so diamond (-59) costs more trip than coal (96, no stairs) from the same height, and coal
	 * one branch: 37 ticks a block of lib.dig.LENGTH.
	 */
	@GameTest(maxTicks = 20)
	public void aDeeperOreCostsMoreTrip(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			import lib.dig
			from lib.get import SOURCES
			def trip(item):
			    s = SOURCES["minecraft:" + item]
			    return s.cost(1, 1)[0] - s.estimate
			print(trip("diamond") > trip("coal"), trip("coal") == 37 * lib.dig.LENGTH)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True True"));
	}

	/**
	 * A source's cost counts DEEP once for all it gets, as one flight of stairs reaches a vein, and takes the pickaxe tier
	 * from the nearest exposed ore block (#536): lodestone, which the game test tags put in needs_iron_tool, 2 blocks away.
	 * NEEDS says "wooden" for it, so the source's own tier by id (which reads the tags too since #566) differs. In sight it
	 * costs the walk to it (10), its mining with iron's tier (15) and the estimate for the other two (#675).
	 */
	@GameTest(maxTicks = 20)
	public void aSourceCostCountsDeepOnceAndTheBlocksTier(GameTestHelper helper) {
		helper.setBlock(5, 1, 4, Blocks.LODESTONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			import lib.dig
			from lib.get import ore, DEEP
			import lib.tools
			lib.dig.ORES["lodestone"] = "minecraft:lodestone"
			lib.tools.NEEDS["lodestone"] = "wooden"
			s = ore("lodestone", "minecraft:lodestone", 60)
			print(s.cost(3, 8), s.cost(3, 1)[0] - DEEP, s.cost(3, 1)[1])
			""");
		helper.succeedWhen(() -> printed(helper, bot, "(145, 'iron') 180 wooden"));
	}

	/**
	 * What one choose call costs an empty bot with no chest near (#522): measured at 27,000 instructions (14 ticks), most
	 * of it lib.craft.plan of each tier's tool (needs costs 3000); 45,600 (24 ticks) since the make cost counts the pickaxe
	 * a raw item needs (#529), a plan of each pickaxe tier more, and some 1000 of it the ore scans of Source.ticks; 48,200
	 * (26 ticks) since that tool may come from a chest (#536), a chest scan for each tool costed; 61,000-64,400 (32-35
	 * ticks, 52,000-56,000 before) since copper is a tier it makes (#578): 5,600 more, 3,600 of it its
	 * plan; 41,000-44,500 (22-24 ticks) since options costs the tiers lowest first, each up to the cheapest make so far (#595); 46,500 (26 ticks)
	 * since make_cost finds each plan's crafting table and furnace within reach (#630). The limit keeps it there.
	 */
	@GameTest(maxTicks = 60)
	public void chooseCost(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		GameTests.cost(helper, bot, "from lib.get import choose\n", "print(choose('axe', 30, radius=8)[1:3])\n",
			List.of("('make', 'stone')"), "choose", 60_000);
	}

	/**
	 * lib.find.WHERE limits get to where it is true (#540): with it true only within 2 blocks of the bot, an iron axe in a
	 * chest, the oak and iron ore just outside are not used (the iron source costs the walk to it, 20, and its mining with
	 * stone's tier, 23, #675). choose makes a stone axe from what the bot carries instead of the chest's, the iron source
	 * costs DEEP more, ensure_tool crafts the stone axe and get finds no logs.
	 */
	@GameTest(maxTicks = 240)
	public void whereKeepsGetFromChestsAndBlocksOutsideIt(GameTestHelper helper) {
		TreesGameTest.plantTree(helper);
		Container chest = chest(helper, new ItemStack(Items.IRON_AXE));
		helper.setBlock(6, 1, 5, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		run(helper, bot, """
			import lib.find
			from lib.get import SOURCES, choose, ensure_tool, get
			here = pos()
			iron = SOURCES["minecraft:raw_iron"]
			print(choose("axe", 30, radius=8)[1:3], iron.cost(1, 8)[0])
			lib.find.WHERE = lambda p: here.distance(p) <= 2
			print(choose("axe", 30, radius=8)[1:3], iron.cost(1, 8)[0])
			r = ensure_tool("axe", 30)
			print(r["ok"], held().item)
			r = get("oak_log", 1)
			print(r["ok"], r["reason"])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "('chest', 'iron') 43", "('make', 'stone') 1244", "True minecraft:stone_axe", "False no_logs");
			helper.assertTrue(chest.countItem(Items.IRON_AXE) == 1, "the iron axe left the chest");
			helper.assertBlockPresent(Blocks.OAK_LOG, TreesGameTest.TREE);
		});
	}

	/**
	 * An iron axe in a chest: it beats making one for 30 logs, but for 500 it is not enough and an iron one is made, with
	 * iron ore in sight (see chooseStoneForFewUsesIronForMany).
	 */
	@GameTest(maxTicks = 110)
	public void chooseAChestToolThatIsEnough(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.IRON_AXE));
		helper.setBlock(6, 1, 7, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			from lib.get import choose
			print(choose("axe", 30)[1:3], choose("axe", 500)[1:3])
			""");
		helper.succeedWhen(() -> printed(helper, bot, "('chest', 'iron') ('make', 'iron')"));
	}

	/**
	 * A stone pickaxe with 20 uses left counts toward a 30-use job: with it, a chest's stone pickaxe with 20 more is
	 * enough and is borrowed rather than one made; for 50 uses the two are not and one is made (#519).
	 */
	@GameTest(maxTicks = 80)
	public void chooseCountsAWornToolTowardTheJob(GameTestHelper helper) {
		ItemStack worn = new ItemStack(Items.STONE_PICKAXE);
		worn.setDamageValue(worn.getMaxDamage() - 20);
		chest(helper, worn.copy());
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(worn);
		run(helper, bot, """
			from lib.get import choose
			print(choose("pickaxe", 30)[1:3], choose("pickaxe", 50)[1:3])
			""");
		helper.succeedWhen(() -> printed(helper, bot, "('chest', 'stone') ('make', 'stone')"));
	}

	/**
	 * A script's own source for clay balls, from a clay block, is used to get a clay block (four balls, crafted); dirt
	 * comes from the default source, by hand.
	 */
	@GameTest(maxTicks = 150)
	public void aScriptAddedSourceIsUsed(GameTestHelper helper) {
		helper.setBlock(5, 1, 5, Blocks.CLAY);
		helper.setBlock(6, 1, 2, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		run(helper, bot, """
			from lib.get import get, SOURCES, Source
			from lib.result import result
			import lib.find
			used = []
			def clay(n, radius):
			    used.append(n)
			    return result(mine(lib.find.nearest("clay", radius)).reason)
			SOURCES["minecraft:clay_ball"] = Source(clay, tool="shovel", tier="hand")
			r = get("clay")
			print(r["ok"], count("clay"), count("clay_ball"), used)
			r = get("dirt")
			print(r["ok"], count("dirt"))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True 1 0 [4]", "True 1"));
	}

	/**
	 * A cherry tree of {@link TreesGameTest#plantTree} (#602): get breaks its leaves by hand until two cherry saplings
	 * dropped. The game test data pack makes every cherry leaves block drop one (1 in 20 in the game), so the test does
	 * not wait on chance; no other test breaks cherry leaves.
	 */
	@GameTest(maxTicks = 60)
	public void getsCherrySaplingsFromItsLeaves(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES, false);
		leavesTest(helper, "cherry_sapling");
	}

	/**
	 * As {@link #getsCherrySaplingsFromItsLeaves}, the bot carrying shears (#609): mine would break the leaves with them
	 * into leaves (the data pack's cherry leaves drop themselves to shears or silk touch, as in the game), so the source
	 * breaks them by hand.
	 */
	@GameTest(maxTicks = 60)
	public void getsSaplingsFromLeavesWithShearsCarried(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES, false);
		leavesTest(helper, "cherry_sapling", new ItemStack(Items.SHEARS));
	}

	/**
	 * A cherry tree under persistent leaves, as a player places them (#609): get breaks none of them for saplings and
	 * fails no_tree.
	 */
	@GameTest(maxTicks = 40)
	public void persistentLeavesAreNotBrokenForSaplings(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES, true);
		int leaves = cherryLeaves(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			r = get("cherry_sapling")
			print(r["ok"], r["reason"], r["missing"])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "False no_tree {'minecraft:cherry_sapling': 1}");
			helper.assertTrue(cherryLeaves(helper) == leaves, cherryLeaves(helper) + " of " + leaves + " cherry leaves left");
		});
	}

	/**
	 * A birch leaves block at y 7 on a log on a stone column, out of reach from the floor (#654): the bot pillars with
	 * its dirt to break it for a sapling, and takes the pillar back, so no dirt stands in the area after.
	 */
	// Measured: passes at tick 75.
	@GameTest(maxTicks = 150)
	public void getsSaplingsFromHighLeavesAndTakesItsPillarBack(GameTestHelper helper) {
		for (int y = 1; y <= 5; y++) {
			helper.setBlock(4, y, 4, Blocks.STONE);
		}
		helper.setBlock(4, 6, 4, Blocks.BIRCH_LOG);
		helper.setBlock(4, 7, 4, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			r = get("birch_sapling")
			print(r["ok"], count("birch_sapling"), count("dirt"))
			""");
		failOnOtherPrint(helper, bot, "True 1 16");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 1 16");
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
				helper.assertBlockNotPresent(Blocks.DIRT, p);
			}
		});
	}

	/**
	 * As {@link #getsSaplingsFromHighLeavesAndTakesItsPillarBack}, but the script first pillars two dirt up itself and
	 * stands on them (#657): get takes back only what its own climb placed, so the script's pillar stands and is still
	 * listed by placed_scaffold() after.
	 */
	// Measured: passes at tick 107.
	@GameTest(maxTicks = 220)
	public void aScriptsOwnPillarSurvivesAGetFromHighLeaves(GameTestHelper helper) {
		for (int y = 1; y <= 5; y++) {
			helper.setBlock(4, y, 4, Blocks.STONE);
		}
		helper.setBlock(4, 6, 4, Blocks.BIRCH_LOG);
		helper.setBlock(4, 7, 4, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			goto(Pos%s)
			r = get("birch_sapling")
			print(r["ok"], count("birch_sapling"), count("dirt"), len(placed_scaffold()))
			""".formatted(at(helper, 1, 3, 1)));
		failOnOtherPrint(helper, bot, "True 1 14 2");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 1 14 2");
			helper.assertBlockPresent(Blocks.DIRT, new BlockPos(1, 1, 1));
			helper.assertBlockPresent(Blocks.DIRT, new BlockPos(1, 2, 1));
		});
	}

	private static int cherryLeaves(GameTestHelper helper) {
		int n = 0;
		for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
			n += helper.getBlockState(p).is(Blocks.CHERRY_LEAVES) ? 1 : 0;
		}
		return n;
	}

	/**
	 * A dark oak tree (#602): get("apple") breaks its leaves. The game test data pack makes every dark oak leaves block
	 * drop an apple (1 in 200 in the game); no other test breaks dark oak leaves.
	 */
	@GameTest(maxTicks = 60)
	public void getsApplesFromDarkOakLeaves(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES, false);
		leavesTest(helper, "apple");
	}

	/** A bot carrying only carried by the tree gets two of item with radius 8 and prints "True 2", with no warnings. */
	private static void leavesTest(GameTestHelper helper, String item, ItemStack... carried) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		for (ItemStack stack : carried) {
			bot.getInventory().addItem(stack);
		}
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			r = get("%s", 2)
			print(r["ok"], count("%s"))
			""".formatted(item, item));
		failOnOtherPrint(helper, bot, "True 2");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 2");
			helper.assertTrue(BotScripts.warnings(bot).isEmpty(), "warned " + BotScripts.warnings(bot));
		});
	}

	/** Oak leaves and no birch (#602): get("birch_sapling") fails no_tree at once, with the sapling in missing. */
	@GameTest(maxTicks = 20)
	public void noTreeOfTheKindFailsNoTree(GameTestHelper helper) {
		helper.setBlock(5, 2, 5, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			r = get("birch_sapling")
			print(r["ok"], r["reason"], r["missing"])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "False no_tree {'minecraft:birch_sapling': 1}");
			helper.assertTrue(BotScripts.warnings(bot).isEmpty(), "warned " + BotScripts.warnings(bot));
		});
	}

	/**
	 * A modded ore stand-in: lodestone, which the game test tags put in needs_iron_tool and c:ores, with an ore Source named after a
	 * block any pickaxe drops. The bot carries a stone pickaxe, the chest holds an iron one: get takes the tier from the
	 * exposed block's tags and mines it only with the iron pickaxe (#528).
	 */
	@GameTest(maxTicks = 70)
	public void anOreSourceTakesItsTierFromTheBlock(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.IRON_PICKAXE));
		helper.setBlock(5, 1, 5, Blocks.LODESTONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, """
			import lib.dig
			from lib.get import get, ore, SOURCES
			lib.dig.ORES["lodestone"] = "minecraft:lodestone"
			SOURCES["minecraft:lodestone"] = ore("lodestone", "minecraft:lodestone", 60)
			r = get("lodestone")
			print(r["ok"], r["reason"], count("lodestone"), held().item)
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True None 1 minecraft:iron_pickaxe");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * A stone pickaxe and a branch east past a lodestone vein in its ceiling, which the game test tags put in
	 * needs_iron_tool and c:ores; an iron pickaxe in a chest by the start (#555). The branch leaves the vein and
	 * lib.dig.ore gives no_ore with the tier it left it for; get takes the iron pickaxe and tries once more, mining the vein
	 * now exposed. lib.find.WHERE, the branch's row up to the area's east edge and the chest's column x 1, stops a second branch there and leaves ore
	 * no facing to turn to (#561).
	 */
	@GameTest(maxTicks = 600)
	public void getRetriesAVeinLeftForItsTierWithABetterPickaxe(GameTestHelper helper) {
		// From y=0: the template's floor is below it, so a bot in the pocket would fall into the branch's floor.
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(1, 1, 3, Blocks.AIR);
		helper.setBlock(1, 2, 3, Blocks.AIR);
		helper.setBlock(5, 3, 3, Blocks.LODESTONE);
		Container chest = chest(helper, new BlockPos(1, 1, 2), new ItemStack(Items.IRON_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, """
			import lib.dig, lib.find
			from lib.get import get, Source, SOURCES
			lib.find.WHERE = lambda p: p.x <= %d and (p.z == %d or p.x == %d)
			lib.dig.ORES["lodestone"] = "minecraft:lodestone"
			dig = lambda n, radius: lib.dig.ore("lodestone", n, radius, y=pos().y, facing="east", length=6)
			SOURCES["minecraft:lodestone"] = Source(dig, "pickaxe", "wooden", 60, "minecraft:lodestone")
			r = get("lodestone")
			print(r["ok"], r["reason"], count("lodestone"), held().item)
			""".formatted(helper.absolutePos(new BlockPos(7, 0, 3)).getX(), helper.absolutePos(new BlockPos(7, 0, 3)).getZ(),
				helper.absolutePos(new BlockPos(1, 0, 0)).getX()));
		helper.succeedWhen(() -> {
			printed(helper, bot, "True None 1 minecraft:iron_pickaxe");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
			helper.assertBlockPresent(Blocks.AIR, 5, 3, 3);
		});
	}

	/**
	 * A bot with a stone pickaxe and a furnace, no wood or coal, by the oak of {@link TreesGameTest} and eight exposed iron
	 * ore: eight smelts plan a coal, but with no coal ore within radius get fells the oak for fuel and digs no stairs
	 * (#519). The coal source fails, so digging for coal fails the test at once.
	 */
	@GameTest(maxTicks = 4500)
	public void smeltsWithWoodWhereCoalIsDeep(GameTestHelper helper) {
		TreesGameTest.plantTree(helper);
		for (int z = 0; z <= 3; z++) {
			for (int y = 1; y <= 2; y++) {
				helper.setBlock(7, y, z, Blocks.IRON_ORE);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 2);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		run(helper, bot, """
			from lib.get import get, SOURCES
			from lib.result import result
			SOURCES["minecraft:coal"].do = lambda n, radius: result("dug_for_coal")
			r = get("iron_ingot", 8)
			print(r["ok"], r["reason"], count("iron_ingot"), count("coal"))
			""");
		helper.onEachTick(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			if (!log.isEmpty() && !log.equals(List.of("True None 8 0"))) {
				helper.fail("printed " + log + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> printed(helper, bot, "True None 8 0"));
	}

	private static Container chest(GameTestHelper helper, ItemStack... stacks) {
		return chest(helper, CHEST, stacks);
	}

	private static Container chest(GameTestHelper helper, BlockPos at, ItemStack... stacks) {
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		for (int i = 0; i < stacks.length; i++) {
			chest.setItem(i, stacks[i]);
		}
		return chest;
	}

	/** Runs source as a library script and fails the test at once when the script fails. */
	private static void run(GameTestHelper helper, AntBot bot, String source) {
		String name = BotScripts.libraryScript(helper, "get_", source);
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
		BotScripts.failOnError(helper, bot);
	}

	/** Fails the test at once where the bot printed something other than lines, so a slow test need not wait out maxTicks. */
	private static void failOnOtherPrint(GameTestHelper helper, AntBot bot, String... lines) {
		helper.onEachTick(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			if (!log.isEmpty() && !log.equals(List.of(lines))) {
				helper.fail("printed " + log + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
	}

	private static void printed(GameTestHelper helper, AntBot bot, String... lines) {
		List<String> log = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(log.equals(List.of(lines)), "printed " + log + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
	}
}
