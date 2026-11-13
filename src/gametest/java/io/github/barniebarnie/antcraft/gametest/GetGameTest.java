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
 * {@code lib/get.py} (#508): the resolver gets anything by the cheapest of carried, chests, crafting and SOURCES, and
 * picks a tool by cost. Each test passes radius=8 so a neighbouring test's chest or tree is not one of its own.
 */
public class GetGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CHEST = new BlockPos(6, 1, 3);

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
			r = get("stone_axe", radius=8)
			print(r["ok"], count("stone_axe"))
			before = [(s.item, s.count) for s in inventory()]
			r = get("stone_axe", radius=8)
			print(r["ok"], [(s.item, s.count) for s in inventory()] == before)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True 1", "True True"));
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
			r = ensure_tool("hoe", 20, radius=8)
			print(r["ok"], held().item)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True minecraft:wooden_hoe"));
	}

	/** Logs and cobblestone carried for a stone axe, an iron axe in a chest: ensure_tool borrows and holds the iron one. */
	@GameTest(maxTicks = 80)
	public void borrowsAChestAxeRatherThanCrafting(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.IRON_AXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		run(helper, bot, """
			from lib.get import ensure_tool
			from lib.borrow import borrowed
			r = ensure_tool("axe", 50, radius=8)
			print(r["ok"], held().item, count("stone_axe"), count("cobblestone"), [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:iron_axe 0 3 [('iron_axe', 1)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
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
	@GameTest(maxTicks = 40)
	public void takesPartFromAChestAndCraftsTheRest(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_PLANKS, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG));
		run(helper, bot, """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("oak_planks", 8, radius=8)
			print(r["ok"], count("oak_planks"), count("oak_log"), [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 9 0 [('minecraft:oak_planks', 5)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/** 5 planks in a chest, no log and no tree: get fails for the other 3 and gives the 5 back (#535). */
	@GameTest(maxTicks = 40)
	public void givesBackWhatItBorrowedWhenItFails(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_PLANKS, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("oak_planks", 8, radius=8)
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
			r = get("oak_planks", 8, radius=8)
			print(r["ok"], count("oak_planks") >= 8, [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True True [('#minecraft:logs', 1)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * An empty bot, no chest, exposed iron ore in sight: a stone axe for few logs, an iron one for many; nothing is done.
	 * Without the ore in sight the make cost digs stairs for it (#529), and the stone axes win; without coal ore it burns
	 * wood for the fuel, as get would (#536).
	 */
	@GameTest(maxTicks = 140)
	public void chooseStoneForFewUsesIronForMany(GameTestHelper helper) {
		helper.setBlock(6, 1, 7, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			from lib.get import choose
			print(choose("axe", 30, radius=8)[1:3], choose("axe", 400, radius=8)[1:3], choose("axe", 30, "iron", radius=8)[1:3])
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
	 * fuel, as get would, rather than dig for coal (#536). With DEEP a million, digging for it would cost more than that.
	 */
	@GameTest(maxTicks = 40)
	public void ironFuelIsWoodWithoutCoalInSight(GameTestHelper helper) {
		helper.setBlock(6, 1, 7, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			import lib.get
			lib.get.DEEP = 1000000
			print(lib.get.make_cost("iron_axe", 2, radius=8) < 1000000)
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
	 * A source's cost counts DEEP once for all it gets, as one flight of stairs reaches a vein, and takes the pickaxe tier
	 * from the nearest exposed ore block (#536): lodestone, which the game test tags put in needs_iron_tool, 2 blocks away.
	 */
	@GameTest(maxTicks = 40)
	public void aSourceCostCountsDeepOnceAndTheBlocksTier(GameTestHelper helper) {
		helper.setBlock(5, 1, 4, Blocks.LODESTONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			import lib.dig
			from lib.get import ore, DEEP
			lib.dig.ORES["lodestone"] = "minecraft:lodestone"
			s = ore("lodestone", "minecraft:lodestone", 60)
			print(s.cost(3, 8), s.cost(3, 1)[0] - DEEP, s.cost(3, 1)[1])
			""");
		helper.succeedWhen(() -> printed(helper, bot, "(180, 'iron') 180 wooden"));
	}

	/**
	 * What one choose call costs an empty bot with no chest near (#522): measured at 27,000 instructions (14 ticks), most
	 * of it lib.craft.plan of each tier's tool (needs costs 3000); 45,600 (24 ticks) since the make cost counts the pickaxe
	 * a raw item needs (#529), a plan of each pickaxe tier more, and some 1000 of it the ore scans of Source.ticks; 48,200
	 * (26 ticks) since that tool may come from a chest (#536), a chest scan for each tool costed. The limit keeps it there.
	 */
	@GameTest(maxTicks = 60)
	public void chooseCost(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		GameTests.cost(helper, bot, "from lib.get import choose\n", "print(choose('axe', 30, radius=8)[1:3])\n",
			List.of("('make', 'stone')"), "choose", 60_000);
	}

	/**
	 * lib.find.WHERE limits get to where it is true (#540): with it true only within 2 blocks of the bot, an iron axe in a
	 * chest, the oak and iron ore just outside are not used. choose makes a stone axe from what the bot carries instead of
	 * the chest's, the iron source costs DEEP more, ensure_tool crafts the stone axe and get finds no logs.
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
			r = ensure_tool("axe", 30, radius=8)
			print(r["ok"], held().item)
			r = get("oak_log", 1, radius=8)
			print(r["ok"], r["reason"])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "('chest', 'iron') 60", "('make', 'stone') 2060", "True minecraft:stone_axe", "False no_logs");
			helper.assertTrue(chest.countItem(Items.IRON_AXE) == 1, "the iron axe left the chest");
			helper.assertBlockPresent(Blocks.OAK_LOG, TreesGameTest.TREE);
		});
	}

	/**
	 * An iron axe in a chest: it beats making one for 30 logs, but for 400 it is not enough and an iron one is made, with
	 * iron ore in sight (see chooseStoneForFewUsesIronForMany).
	 */
	@GameTest(maxTicks = 110)
	public void chooseAChestToolThatIsEnough(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.IRON_AXE));
		helper.setBlock(6, 1, 7, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			from lib.get import choose
			print(choose("axe", 30, radius=8)[1:3], choose("axe", 400, radius=8)[1:3])
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
			print(choose("pickaxe", 30, radius=8)[1:3], choose("pickaxe", 50, radius=8)[1:3])
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
			r = get("clay", radius=8)
			print(r["ok"], count("clay"), count("clay_ball"), used)
			r = get("dirt", radius=8)
			print(r["ok"], count("dirt"))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True 1 0 [4]", "True 1"));
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
			r = get("lodestone", radius=8)
			print(r["ok"], r["reason"], count("lodestone"), held().item)
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True None 1 minecraft:iron_pickaxe");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
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
			r = get("iron_ingot", 8, radius=8)
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
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
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
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
	}

	private static void printed(GameTestHelper helper, AntBot bot, String... lines) {
		List<String> log = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(log.equals(List.of(lines)), "printed " + log + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
	}
}
