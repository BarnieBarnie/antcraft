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
import net.minecraft.world.level.block.LeavesBlock;
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

	/** Three iron ingots and a log carried: get crafts an empty bucket, with a crafting table of the log (#592). */
	// Measured: passes at tick 90.
	@GameTest(maxTicks = 180)
	public void craftsABucketOfIronIngots(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.IRON_INGOT, 3));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG));
		run(helper, bot, """
			from lib.get import get
			r = get("bucket", radius=8)
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
			r = ensure_tool("axe", 50, radius=8)
			print(r["ok"], held().item, count("stone_axe"), count("cobblestone"), [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:iron_axe 0 3 [('minecraft:iron_axe', 1)]");
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
			r = lib.get.ensure_tool("pickaxe", 50, "stone", radius=8)
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
			r = lib.get.ensure_tool("pickaxe", 50, radius=8)
			print(r["ok"], held().item, [(b["item"], b["n"]) for b in borrowed()])
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:iron_pickaxe [('minecraft:iron_pickaxe', 1)]");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * A stone axe in a chest across the wide area (walk 190 ticks) and 3 logs carried (#562): the chest is past NEAR, so
	 * ensure_tool weighs it against making one and makes a wooden axe (30 ticks of work) rather than walk there.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 360)
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
	@GameTest(maxTicks = 15)
	public void ensureToolFailsFastWithoutLogs(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, where(helper, 7) + """
			import lib.get
			lib.get.options = lambda *a: print("costed") or []
			r = lib.get.ensure_tool("axe", 30, radius=8)
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
			r = lib.get.ensure_tool("axe", 30, radius=8)
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
			r = get("oak_planks", 8, radius=8)
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

	/** 6 spruce planks in a chest, a crafting table by the bot, no log or tree: get borrows the planks for a spruce trapdoor (#560). */
	@GameTest(maxTicks = 30)
	public void borrowsPlanksFromAChestForATrapdoor(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.SPRUCE_PLANKS, 6));
		helper.setBlock(3, 1, 5, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			from lib.get import get
			from lib.borrow import borrowed
			r = get("spruce_trapdoor", radius=8)
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
			r = get("wooden_pickaxe", radius=8)
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
			r = get("crafting_table", radius=8)
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
			r = get("wooden_pickaxe", radius=8)
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
			r = ensure_tool("pickaxe", 1, "copper", radius=8)
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
			r = lib.get.ensure_tool("axe", 30, radius=8)
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
			r = lib.get.ensure_tool("axe", 30, radius=8)
			print(r["ok"], held().item)
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:copper_axe");
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
			r = get("torch", 8, radius=8)
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
	 * NEEDS says "wooden" for it, so the source's own tier by id (which reads the tags too since #566) differs.
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
		helper.succeedWhen(() -> printed(helper, bot, "(180, 'iron') 180 wooden"));
	}

	/**
	 * What one choose call costs an empty bot with no chest near (#522): measured at 27,000 instructions (14 ticks), most
	 * of it lib.craft.plan of each tier's tool (needs costs 3000); 45,600 (24 ticks) since the make cost counts the pickaxe
	 * a raw item needs (#529), a plan of each pickaxe tier more, and some 1000 of it the ore scans of Source.ticks; 48,200
	 * (26 ticks) since that tool may come from a chest (#536), a chest scan for each tool costed; 61,000-64,400 (32-35
	 * ticks, 52,000-56,000 before) since copper is a tier it makes (#578): 5,600 more, 3,600 of it its
	 * plan; 41,000-44,500 (22-24 ticks) since options costs the tiers lowest first, each up to the cheapest make so far (#595).
	 * The limit keeps it there.
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
			printed(helper, bot, "('chest', 'iron') 60", "('make', 'stone') 1244", "True minecraft:stone_axe", "False no_logs");
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
	 * A cherry tree of {@link TreesGameTest#plantTree} (#602): get breaks its leaves by hand until two cherry saplings
	 * dropped. The game test data pack makes every cherry leaves block drop one (1 in 20 in the game), so the test does
	 * not wait on chance; no other test breaks cherry leaves.
	 */
	@GameTest(maxTicks = 60)
	public void getsCherrySaplingsFromItsLeaves(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES);
		leavesTest(helper, "cherry_sapling");
	}

	/**
	 * A dark oak tree (#602): get("apple") breaks its leaves. The game test data pack makes every dark oak leaves block
	 * drop an apple (1 in 200 in the game); no other test breaks dark oak leaves.
	 */
	@GameTest(maxTicks = 60)
	public void getsApplesFromDarkOakLeaves(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES);
		leavesTest(helper, "apple");
	}

	/** An empty bot by the tree gets two of item with radius 8 and prints "True 2", with no warnings. */
	private static void leavesTest(GameTestHelper helper, String item) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			r = get("%s", 2, radius=8)
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
			r = get("birch_sapling", radius=8)
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
			r = get("lodestone", radius=8)
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
			r = get("lodestone", radius=8)
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
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
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
