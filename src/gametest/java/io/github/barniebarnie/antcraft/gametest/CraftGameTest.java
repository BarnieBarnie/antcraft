package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.at;
import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.expect;
import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.fillInventory;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

/**
 * The container and crafting actions of the bot API (docs/reference/index.md, Containers and crafting): {@code put}, {@code take},
 * {@code craft} and {@code smelt}, each run as a short script on a bot in the test area, one test where the action
 * succeeds and one per reason it can fail with; and the acceptance test of milestone 3, the "punch a tree, then make a
 * wooden pickaxe" example of the doc run as it is.
 */
public class CraftGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CHEST = new BlockPos(5, 1, 5);
	private static final BlockPos FURNACE = new BlockPos(5, 1, 5);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	private static Container container(GameTestHelper helper, BlockPos pos) {
		return (Container) Objects.requireNonNull(helper.getLevel().getBlockEntity(helper.absolutePos(pos)));
	}

	private static Container chest(GameTestHelper helper, ItemStack... stacks) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = container(helper, CHEST);
		for (int i = 0; i < stacks.length; i++) {
			chest.setItem(i, stacks[i]);
		}
		return chest;
	}

	private static String chestAt(GameTestHelper helper) {
		return at(helper, CHEST.getX(), CHEST.getY(), CHEST.getZ());
	}

	private static String furnaceAt(GameTestHelper helper) {
		return at(helper, FURNACE.getX(), FURNACE.getY(), FURNACE.getZ());
	}

	private static Container furnace(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.FURNACE);
		return container(helper, FURNACE);
	}

	// put

	@GameTest(maxTicks = 60)
	public void putWalksIntoReachAndPutsItemsInAChest(GameTestHelper helper) {
		Container chest = chest(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COBBLESTONE, 2));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 3));
		expect(helper, bot, "r = put(" + chestAt(helper) + ", 'cobblestone', 4)\ns = put(" + chestAt(helper) + ", 'cobblestone')\n"
				+ "print(r.ok, r.value, s.ok, s.value, count('cobblestone'), count('dirt'))\n",
			List.of("True 4 True 6 0 3"), () -> helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 10, "chest " + chest.countItem(Items.COBBLESTONE)));
	}

	@GameTest(maxTicks = 20)
	public void putWithoutTheItemIsNoItem(GameTestHelper helper) {
		chest(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		expect(helper, bot, "r = put(" + chestAt(helper) + ", 'cobblestone')\nprint(r.ok, r.reason)\n", List.of("False no_item"));
	}

	@GameTest(maxTicks = 20)
	public void putIntoAFullChestIsNoSpace(GameTestHelper helper) {
		Container chest = chest(helper);
		for (int i = 0; i < chest.getContainerSize() - 1; i++) {
			chest.setItem(i, new ItemStack(Items.DIRT, 64));
		}
		chest.setItem(chest.getContainerSize() - 1, new ItemStack(Items.COBBLESTONE, 59));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 10));
		expect(helper, bot, "r = put(" + chestAt(helper) + ", 'cobblestone')\nprint(r.ok, r.reason, r.value, count('cobblestone'))\n",
			List.of("False no_space 5 5"));
	}

	@GameTest(maxTicks = 20)
	public void putWhereNoContainerIsIsTargetGone(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 10));
		expect(helper, bot, "r = put(" + chestAt(helper) + ", 'cobblestone')\nprint(r.ok, r.reason, count('cobblestone'))\n",
			List.of("False target_gone 10"));
	}

	/** A chest high in the air, and nothing to climb with. */
	@GameTest(maxTicks = 50)
	public void putIntoAChestOutOfReachIsOutOfReach(GameTestHelper helper) {
		helper.setBlock(4, 7, 4, Blocks.CHEST);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.IRON_INGOT, 10));
		expect(helper, bot, "r = put(" + at(helper, 4, 7, 4) + ", 'iron_ingot')\nprint(r.ok, r.reason)\n", List.of("False out_of_reach"));
	}

	// take

	@GameTest(maxTicks = 60)
	public void takeWalksIntoReachAndTakesItemsOutOfAChest(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.DIRT, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = take(" + chestAt(helper) + ", 'oak_log', 4)\ns = take(" + chestAt(helper) + ", '#logs')\n"
				+ "print(r.ok, r.value, s.ok, s.value, count('oak_log'))\n",
			List.of("True 4 True 6 10"), () -> {
				helper.assertTrue(chest.countItem(Items.OAK_LOG) == 0, "logs left in the chest");
				helper.assertTrue(chest.countItem(Items.DIRT) == 5, "dirt taken");
			});
	}

	@GameTest(maxTicks = 20)
	public void takeWhatTheChestLacksIsNoItem(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.DIRT, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		expect(helper, bot, "r = take(" + chestAt(helper) + ", 'oak_log')\nprint(r.ok, r.reason, count('dirt'))\n", List.of("False no_item 0"));
	}

	@GameTest(maxTicks = 20)
	public void takeWithAFullInventoryIsNoSpace(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_LOG, 10));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		fillInventory(bot);
		expect(helper, bot, "r = take(" + chestAt(helper) + ", 'oak_log')\nprint(r.ok, r.reason, r.value)\n", List.of("False no_space 0"),
			() -> helper.assertTrue(chest.countItem(Items.OAK_LOG) == 10, "logs taken"));
	}

	@GameTest(maxTicks = 20)
	public void takeWhereNoContainerIsIsTargetGone(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		expect(helper, bot, "r = take(" + chestAt(helper) + ", 'oak_log')\nprint(r.ok, r.reason)\n", List.of("False target_gone"));
	}

	// craft

	/** Planks from logs, one of them held, and sticks from planks: 2x2 recipes, made anywhere. */
	@GameTest(maxTicks = 20)
	public void craftMakesWhatA2x2RecipeMakesFromCarriedItems(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.OAK_LOG));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		expect(helper, bot, "r = craft('oak_planks', 9)\ns = craft('stick', 2)\nt = craft('oak_planks')\n"
				+ "print(r.ok, r.value, s.ok, s.value, t.reason, count('oak_planks'), count('stick'), count('oak_log'))\n",
			List.of("True 12 True 4 missing_ingredients 10 4 0"));
	}

	/** A pickaxe needs a crafting table: one at the given position, walked to. */
	@GameTest(maxTicks = 60)
	public void craftWalksToTheTableForA3x3Recipe(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 3));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		expect(helper, bot, "r = craft('wooden_pickaxe', table=" + at(helper, 6, 1, 6) + ")\n"
				+ "print(r.ok, r.value, count('wooden_pickaxe'), count('oak_planks'), count('stick'))\n",
			List.of("True 1 1 0 0"));
	}

	/** Without {@code table}, a crafting table within reach does. */
	@GameTest(maxTicks = 20)
	public void craftUsesATableWithinReach(GameTestHelper helper) {
		helper.setBlock(5, 1, 4, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		expect(helper, bot, "r = craft('furnace')\nprint(r.ok, r.value, count('furnace'))\n", List.of("True 1 1"));
	}

	@GameTest(maxTicks = 20)
	public void craftWhatNoRecipeMakesIsNoRecipe(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		expect(helper, bot, "r = craft('bedrock')\nprint(r.ok, r.reason, count('oak_log'))\n", List.of("False no_recipe 2"));
	}

	/** Nothing to make sticks of. */
	@GameTest(maxTicks = 20)
	public void craftWithoutTheIngredientsIsMissingIngredients(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		expect(helper, bot, "r = craft('stick')\nprint(r.ok, r.reason)\n", List.of("False missing_ingredients"));
	}

	@GameTest(maxTicks = 20)
	public void craftRunningOutOfIngredientsIsMissingIngredientsWithWhatItMade(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 5));
		expect(helper, bot, "r = craft('stick', 12)\nprint(r.ok, r.reason, r.value, count('stick'), count('oak_planks'))\n",
			List.of("False missing_ingredients 8 8 1"));
	}

	/** A 3x3 recipe with no table in reach, and with a {@code table} that is not one. */
	@GameTest(maxTicks = 20)
	public void craftA3x3RecipeWithoutATableIsNoTable(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 3));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		expect(helper, bot, "r = craft('wooden_pickaxe')\ns = craft('wooden_pickaxe', table=" + at(helper, 6, 1, 6) + ")\n"
				+ "print(r.ok, r.reason, s.reason, count('oak_planks'), count('stick'))\n",
			List.of("False no_table no_table 3 2"));
	}

	/** Every slot and the hand taken: the sticks have nowhere to go. */
	@GameTest(maxTicks = 20)
	public void craftWithAFullInventoryIsNoSpace(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 64));
		fillInventory(bot);
		expect(helper, bot, "r = craft('stick')\nprint(r.ok, r.reason, r.value, count('oak_planks'))\n", List.of("False no_space 0 64"));
	}

	// needs

	/** Carrying the cobblestone (in hand) and planks, the bot is told to make sticks and the pickaxe, and does. */
	@GameTest(maxTicks = 20)
	public void needsListsTheCraftsLeftAndTheBotCraftsThem(GameTestHelper helper) {
		helper.setBlock(5, 1, 4, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.COBBLESTONE, 3));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 2));
		expect(helper, bot, """
				r = needs('stone_pickaxe')
				print(r['gather'], r['crafts'])
				for item, n, how in r['crafts']:
				    craft(item, n)
				print(count('stone_pickaxe'), count('stick'), count('oak_planks'), count('cobblestone'))
				""",
			List.of("{} [('minecraft:stick', 4, 'craft'), ('minecraft:stone_pickaxe', 1, 'craft')]", "1 2 0 0"));
	}

	@GameTest(maxTicks = 20)
	public void needsOfAnUnknownItemStopsTheScript(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		ScriptRun run = BotScripts.run(bot, "needs('stik')\n");
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FAILED, "script " + run.state());
			List<String> log = BotScripts.log(bot, Output.Level.ERROR);
			helper.assertTrue(log.size() == 1 && log.getFirst().contains("unknown item 'minecraft:stik'"), "log " + log);
		});
	}

	// lib.craft.craft_all

	/** Prints what {@code lib.craft.craft_all(args)} gives and then {@code counts}, the {@code count} of each item. */
	private static String craftAll(String args, String... counts) {
		StringBuilder printed = new StringBuilder("r['ok'], r['reason'], r['missing']");
		for (String item : counts) {
			printed.append(", count('").append(item).append("')");
		}
		return "import lib.craft\nr = lib.craft.craft_all(" + args + ")\nprint(" + printed + ")\n";
	}

	/** One log and three cobblestone: planks, sticks, then the pickaxe at the table within reach. */
	@GameTest(maxTicks = 20)
	public void craftAllMakesAStonePickaxeFromALogAndCobblestone(GameTestHelper helper) {
		helper.setBlock(5, 1, 4, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 1));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		expect(helper, bot, craftAll("'stone_pickaxe'", "stone_pickaxe", "stick", "oak_planks", "oak_log", "cobblestone"),
			List.of("True None {} 1 2 2 0 0"));
	}

	/** No cobblestone: it says so and crafts nothing, not even the planks it could. */
	@GameTest(maxTicks = 20)
	public void craftAllWithRawItemsMissingCraftsNothing(GameTestHelper helper) {
		helper.setBlock(5, 1, 4, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 1));
		expect(helper, bot, craftAll("'stone_pickaxe'", "oak_log", "oak_planks"),
			List.of("False missing_ingredients {'minecraft:cobblestone': 3} 1 0"));
	}

	/** No table within reach: the planks and sticks are made, the pickaxe step fails with no_table. */
	@GameTest(maxTicks = 20)
	public void craftAllStopsAtTheFirstFailingStep(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 1));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		expect(helper, bot, craftAll("'stone_pickaxe'", "stone_pickaxe", "stick", "cobblestone"), List.of("False no_table {} 0 4 3"));
	}

	/** An iron pickaxe from raw iron: the ingots are smelted in the furnace given, then the pickaxe crafted at the table given. */
	@GameTest(maxTicks = 1200)
	public void craftAllSmeltsWhatASmeltStepMakes(GameTestHelper helper) {
		furnace(helper);
		helper.setBlock(5, 1, 2, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 3));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, craftAll("'iron_pickaxe', table=" + at(helper, 5, 1, 2) + ", furnace=" + furnaceAt(helper), "iron_pickaxe", "iron_ingot",
			"raw_iron", "stick"), List.of("True None {} 1 0 0 0"));
	}

	// lib.craft.craft_here

	/** Prints what {@code lib.craft.craft_here(args)} gives and then {@code counts}, the {@code count} of each item. */
	private static String craftHere(String args, String... counts) {
		return craftAll(args, counts).replace("craft_all(", "craft_here(");
	}

	/** Checks the test area holds {@code n} crafting tables. */
	private static Runnable tablesInTheArea(GameTestHelper helper, int n) {
		return inTheArea(helper, Blocks.CRAFTING_TABLE, n);
	}

	/** Checks the test area holds {@code n} of {@code block}. */
	private static Runnable inTheArea(GameTestHelper helper, Block block, int n) {
		return () -> {
			int found = 0;
			for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 7, 7)) {
				if (helper.getBlockState(pos).is(block)) {
					found++;
				}
			}
			helper.assertTrue(found == n, found + " " + block + " in the area, expected " + n);
		};
	}

	/** Logs and cobblestone, no table near or carried: it crafts one, places it, makes the pickaxe and takes the table back. */
	@GameTest(maxTicks = 170)
	public void craftHereCraftsAndPlacesATableAndTakesItBack(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		expect(helper, bot, craftHere("'stone_pickaxe'", "stone_pickaxe", "crafting_table", "oak_log", "cobblestone"),
			List.of("True None {} 1 1 0 0"), tablesInTheArea(helper, 0));
	}

	/** Stone around {@code (x, 1, z)} and {@code (x, 2, z)}, a 1x1 pit two deep, and over it at {@code (x, 3, z)} where {@code covered}. */
	private static void pit(GameTestHelper helper, int x, int z, boolean covered) {
		for (int y = 1; y <= 2; y++) {
			helper.setBlock(x + 1, y, z, Blocks.STONE);
			helper.setBlock(x - 1, y, z, Blocks.STONE);
			helper.setBlock(x, y, z + 1, Blocks.STONE);
			helper.setBlock(x, y, z - 1, Blocks.STONE);
		}
		if (covered) {
			helper.setBlock(x, 3, z, Blocks.STONE);
		}
	}

	/** In a 1x1 pit, every spot beside its feet and head stone: the table goes above its head and comes back (#477). */
	@GameTest(maxTicks = 190)
	public void craftHereInAPitPlacesTheTableAboveItsHead(GameTestHelper helper) {
		pit(helper, 3, 4, false);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		expect(helper, bot, craftHere("'stone_pickaxe'", "stone_pickaxe", "crafting_table", "oak_log", "cobblestone"),
			List.of("True None {} 1 1 0 0"), tablesInTheArea(helper, 0));
	}

	@GameTest(maxTicks = 170)
	public void craftHereUsesACarriedTableAndKeepsIt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 3));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		expect(helper, bot, craftHere("'wooden_pickaxe'", "wooden_pickaxe", "crafting_table", "oak_planks"),
			List.of("True None {} 1 1 0"), tablesInTheArea(helper, 0));
	}

	/** A table within reach: it is used, and the carried one stays carried. */
	@GameTest(maxTicks = 20)
	public void craftHereWithATableInReachPlacesNone(GameTestHelper helper) {
		helper.setBlock(5, 1, 4, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 3));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		expect(helper, bot, craftHere("'wooden_pickaxe'", "wooden_pickaxe", "crafting_table"), List.of("True None {} 1 1"),
			tablesInTheArea(helper, 1));
	}

	/** Four planks: enough for the pickaxe or the table, not both, so it crafts nothing and gives the log both lack. */
	@GameTest(maxTicks = 20)
	public void craftHereWithPlanksForThePickaxeButNotATableCraftsNothing(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 4));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		expect(helper, bot, craftHere("'wooden_pickaxe'", "wooden_pickaxe", "crafting_table", "oak_planks", "stick"),
			List.of("False missing_ingredients {'minecraft:oak_log': 1} 0 0 4 2"), tablesInTheArea(helper, 0));
	}

	/** Eight cobblestone for stone bricks: the furnace would take them all, so it crafts nothing and gives the four more both take. */
	@GameTest(maxTicks = 20)
	public void craftHereWithCobblestoneForTheBricksButNotAFurnaceCraftsNothing(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
		expect(helper, bot, craftHere("'stone_bricks'", "stone_bricks", "cobblestone", "furnace"),
			List.of("False missing_ingredients {'minecraft:cobblestone': 4} 0 8 0"), inTheArea(helper, Blocks.FURNACE, 0));
	}

	/** Raw iron, sticks and coal, a furnace and a table carried: the ingots are smelted in the furnace put down, then the pickaxe made. */
	@GameTest(maxTicks = 1500)
	public void craftHereSmeltsInAPlacedFurnace(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 3));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE, 1));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
		expect(helper, bot, craftHere("'iron_pickaxe'", "iron_pickaxe", "furnace", "crafting_table", "raw_iron"), List.of("True None {} 1 1 1 0"),
			() -> {
				tablesInTheArea(helper, 0).run();
				inTheArea(helper, Blocks.FURNACE, 0).run();
			});
	}

	// lib.craft.smelt_here

	/** Prints what {@code lib.craft.smelt_here(args)} gives and then {@code counts}, the {@code count} of each item. */
	private static String smeltHere(String args, String... counts) {
		StringBuilder printed = new StringBuilder("r['ok'], r['reason']");
		for (String item : counts) {
			printed.append(", count('").append(item).append("')");
		}
		return "import lib.craft\nr = lib.craft.smelt_here(" + args + ")\nprint(" + printed + ")\n";
	}

	/** Eight cobblestone and a table, no furnace: it crafts one, puts it down, smelts and takes the furnace back. */
	@GameTest(maxTicks = 680)
	public void smeltHereCraftsAndPlacesAFurnaceAndTakesItBack(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
		expect(helper, bot, smeltHere("'raw_iron'", "iron_ingot", "furnace", "crafting_table", "raw_iron", "cobblestone"),
			List.of("True None 1 1 1 0 0"), () -> {
				tablesInTheArea(helper, 0).run();
				inTheArea(helper, Blocks.FURNACE, 0).run();
			});
	}

	/** A furnace carried: that one is put down, and the cobblestone stays. */
	@GameTest(maxTicks = 520)
	public void smeltHereUsesACarriedFurnace(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE, 1));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
		expect(helper, bot, smeltHere("'raw_iron'", "iron_ingot", "furnace", "cobblestone"), List.of("True None 1 1 8"),
			inTheArea(helper, Blocks.FURNACE, 0));
	}

	/** In a covered 1x1 pit: it mines out a spot beside its feet, keeps the cobblestone, smelts there and takes the furnace back (#477). */
	@GameTest(maxTicks = 580)
	public void smeltHereInACoveredPitMinesOutASpot(GameTestHelper helper) {
		pit(helper, 3, 4, true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE, 1));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
		expect(helper, bot, smeltHere("'raw_iron'", "iron_ingot", "furnace", "cobblestone"), List.of("True None 1 1 1"),
			inTheArea(helper, Blocks.FURNACE, 0));
	}

	/** No fuel: the smelt fails in the furnace put down, which is still taken back, the raw iron with it. */
	@GameTest(maxTicks = 120)
	public void smeltHereTakesTheFurnaceBackWhenTheSmeltFails(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE, 1));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
		expect(helper, bot, smeltHere("'raw_iron'", "iron_ingot", "furnace", "raw_iron"), List.of("False missing_ingredients 0 1 1"),
			inTheArea(helper, Blocks.FURNACE, 0));
	}

	/** A furnace, raw iron and coal but no pickaxe to mine a furnace back: it places none and smelts nothing. */
	@GameTest(maxTicks = 20)
	public void smeltHereWithoutAPickaxePlacesNoFurnace(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE, 1));
		expect(helper, bot, smeltHere("'raw_iron'", "iron_ingot", "furnace", "raw_iron", "coal"), List.of("False no_tool 0 1 1 1"),
			inTheArea(helper, Blocks.FURNACE, 0));
	}

	/** Eight cobblestone and no pickaxe: it crafts no furnace either. */
	@GameTest(maxTicks = 20)
	public void smeltHereWithoutAPickaxeCraftsNoFurnace(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
		expect(helper, bot, smeltHere("'raw_iron'", "furnace", "cobblestone", "crafting_table"), List.of("False no_tool 0 8 1"),
			() -> {
				tablesInTheArea(helper, 0).run();
				inTheArea(helper, Blocks.FURNACE, 0).run();
			});
	}

	/** Eight cobblestone to smelt and no furnace: the furnace would take them all, so it crafts none and keeps all eight. */
	@GameTest(maxTicks = 20)
	public void smeltHereKeepsItsInputBackFromTheFurnace(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
		expect(helper, bot, smeltHere("'cobblestone', 8", "cobblestone", "furnace", "stone").replace("r['reason']", "r['reason'], r['missing']"),
			List.of("False missing_ingredients {'minecraft:cobblestone': 8} 8 0 0"), () -> {
				tablesInTheArea(helper, 0).run();
				inTheArea(helper, Blocks.FURNACE, 0).run();
			});
	}

	/** Four planks to burn and no table: the table would take them all, so it crafts nothing and keeps all four. */
	@GameTest(maxTicks = 20)
	public void smeltHereKeepsItsFuelBackFromTheTable(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 4));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
		expect(helper, bot, smeltHere("'raw_iron', 1, fuel='oak_planks'", "oak_planks", "cobblestone", "crafting_table", "furnace")
				.replace("r['reason']", "r['reason'], r['missing']"),
			List.of("False missing_ingredients {'minecraft:oak_log': 1} 4 8 0 0"), () -> {
				tablesInTheArea(helper, 0).run();
				inTheArea(helper, Blocks.FURNACE, 0).run();
			});
	}

	/** One log, the fuel smelt burns without fuel=: the table would take it, so it crafts nothing and keeps it. */
	@GameTest(maxTicks = 20)
	public void smeltHereKeepsItsDefaultFuelBackFromTheTable(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 1));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE, 1));
		expect(helper, bot, smeltHere("'raw_iron'", "oak_log", "cobblestone", "crafting_table", "furnace")
				.replace("r['reason']", "r['reason'], r['missing']"),
			List.of("False missing_ingredients {'minecraft:oak_log': 1} 1 8 0 0"), () -> {
				tablesInTheArea(helper, 0).run();
				inTheArea(helper, Blocks.FURNACE, 0).run();
			});
	}

	/** A smoker within reach and no pickaxe or cobblestone: craft_here cooks the beef there and places no furnace. */
	@GameTest(maxTicks = 220)
	public void craftHereCooksInASmokerWithinReach(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.SMOKER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.BEEF, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, craftHere("'cooked_beef'", "cooked_beef", "beef"), List.of("True None {} 1 0"),
			inTheArea(helper, Blocks.FURNACE, 0));
	}

	/** Kelp, in no food tag, has a smoking recipe: craft_here cooks it in the smoker within reach and places no furnace. */
	@GameTest(maxTicks = 220)
	public void craftHereCooksKelpInASmokerWithinReach(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.SMOKER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.KELP, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, craftHere("'dried_kelp'", "dried_kelp", "kelp"), List.of("True None {} 1 0"),
			inTheArea(helper, Blocks.FURNACE, 0));
	}

	/** Chorus fruit, a food, has only a smelting recipe: plan does not let the smoker within reach stand in for the furnace. */
	@GameTest(maxTicks = 20)
	public void planPutsAFurnaceForWhatTheSmokerCannotCook(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.SMOKER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.CHORUS_FRUIT, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
		expect(helper, bot, "import lib.craft\np = lib.craft.plan('popped_chorus_fruit')\nprint(p['gather'], p['no_tool'])\n",
			List.of("{'minecraft:cobblestone': 8} True"));
	}

	/** The pickaxe does not fit in the full inventory: the step fails at the table put down, which is still taken back. */
	@GameTest(maxTicks = 40)
	public void craftHereTakesTheTableBackWhenAStepFails(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 2));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 64));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 64));
		fillInventory(bot);
		expect(helper, bot, craftHere("'wooden_pickaxe'", "wooden_pickaxe", "crafting_table"), List.of("False no_space {} 0 2"),
			tablesInTheArea(helper, 0));
	}

	/** No pickaxe: craft_here refuses an iron pickaxe before it crafts the sticks, since its furnace could not be taken back. */
	@GameTest(maxTicks = 20)
	public void craftHereWithoutAPickaxeForTheFurnaceCraftsNothing(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 3));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 2));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE, 1));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE, 1));
		expect(helper, bot, craftHere("'iron_pickaxe'", "iron_ingot", "stick", "oak_planks", "raw_iron", "furnace"),
			List.of("False no_tool {} 0 0 2 3 1"), inTheArea(helper, Blocks.FURNACE, 0));
	}

	// lib.craft.obtain

	/** Prints what {@code lib.craft.obtain(args)} gives and then {@code counts}, the {@code count} of each item. */
	private static String obtain(String args, String... counts) {
		return craftAll(args, counts).replace("craft_all(", "obtain(");
	}

	/**
	 * An empty bot, a chest of logs and cobblestone, no table: it takes the log the pickaxe needs, then the one the table
	 * it crafts takes the planks of, and ends with the pickaxe and the table carried.
	 */
	@GameTest(maxTicks = 180)
	public void obtainTakesFromAChestAndCrafts(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_LOG, 5), new ItemStack(Items.COBBLESTONE, 10));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		expect(helper, bot, obtain("'stone_pickaxe', chests=[" + chestAt(helper) + "]", "stone_pickaxe", "crafting_table"),
			List.of("True None {} 1 1"), () -> {
				tablesInTheArea(helper, 0).run();
				helper.assertTrue(chest.countItem(Items.OAK_LOG) == 3, "logs left " + chest.countItem(Items.OAK_LOG));
				helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 7, "cobblestone left " + chest.countItem(Items.COBBLESTONE));
			});
	}

	/** The log in one chest, the cobblestone in another, a table in the area: both chests are emptied for the pickaxe. */
	@GameTest(maxTicks = 20)
	public void obtainUsesEveryChest(GameTestHelper helper) {
		Container logs = chest(helper, new ItemStack(Items.OAK_LOG, 1));
		helper.setBlock(5, 1, 1, Blocks.CHEST);
		Container stone = container(helper, new BlockPos(5, 1, 1));
		stone.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		helper.setBlock(1, 1, 4, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		expect(helper, bot, obtain("'stone_pickaxe', chests=[" + chestAt(helper) + ", " + at(helper, 5, 1, 1) + "]", "stone_pickaxe"),
			List.of("True None {} 1"), () -> helper.assertTrue(logs.isEmpty() && stone.isEmpty(), "a chest is not empty"));
	}

	/** No chests given, the bot in a colony: it takes from the Colony Core. */
	@GameTest(maxTicks = 20)
	public void obtainTakesFromTheColonyCore(GameTestHelper helper) {
		helper.setBlock(CHEST, ModBlocks.COLONY_CORE);
		Container core = container(helper, CHEST);
		core.setItem(0, new ItemStack(Items.OAK_LOG, 1));
		core.setItem(1, new ItemStack(Items.COBBLESTONE, 3));
		helper.setBlock(1, 1, 4, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		ColonyManager manager = ColonyManager.get(helper.getLevel());
		manager.join(bot, manager.byCore(helper.absolutePos(CHEST)).orElseThrow());
		expect(helper, bot, obtain("'stone_pickaxe'", "stone_pickaxe"), List.of("True None {} 1"),
			() -> helper.assertTrue(core.isEmpty(), "the core is not empty"));
	}

	/** Planks for the pickaxe but not also a table: it takes a log for the table with the rest, and makes both. */
	@GameTest(maxTicks = 180)
	public void obtainTakesWhatTheTableTakesToo(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_LOG, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 4));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		expect(helper, bot, obtain("'wooden_pickaxe', chests=[" + chestAt(helper) + "]", "wooden_pickaxe", "crafting_table"),
			List.of("True None {} 1 1"), () -> {
				tablesInTheArea(helper, 0).run();
				helper.assertTrue(chest.countItem(Items.OAK_LOG) == 4, "logs left " + chest.countItem(Items.OAK_LOG));
			});
	}

	/** No cobblestone anywhere: it takes and crafts nothing and gives what it lacks, the log for the table it needs too. */
	@GameTest(maxTicks = 20)
	public void obtainWithTooLittleCraftsNothing(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_LOG, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		expect(helper, bot, obtain("'stone_pickaxe', chests=[" + chestAt(helper) + "]", "stone_pickaxe", "oak_planks", "oak_log"),
			List.of("False missing_ingredients {'minecraft:oak_log': 1, 'minecraft:cobblestone': 3} 0 0 0"),
			() -> helper.assertTrue(chest.countItem(Items.OAK_LOG) == 1, "logs left " + chest.countItem(Items.OAK_LOG)));
	}

	/** A chest of logs but no diamonds: it takes no log for the diamond pickaxe and gives the diamonds it lacks. */
	@GameTest(maxTicks = 20)
	public void obtainTakesNothingUnlessTheChestsHoldEverything(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_LOG, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		expect(helper, bot, obtain("'diamond_pickaxe', chests=[" + chestAt(helper) + "]", "diamond_pickaxe", "oak_log"),
			List.of("False missing_ingredients {'minecraft:diamond': 3} 0 0"),
			() -> helper.assertTrue(chest.countItem(Items.OAK_LOG) == 5, "logs left " + chest.countItem(Items.OAK_LOG)));
	}

	/** No pickaxe, no furnace near, a chest with raw iron, sticks and coal: obtain takes nothing for an iron pickaxe and gives no_tool. */
	@GameTest(maxTicks = 20)
	public void obtainWithoutAPickaxeForTheFurnaceTakesNothing(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.STICK, 2), new ItemStack(Items.COAL, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		expect(helper, bot, obtain("'iron_pickaxe', chests=[" + chestAt(helper) + "]", "iron_pickaxe", "raw_iron", "stick"),
			List.of("False no_tool {} 0 0 0"), () -> {
				helper.assertTrue(chest.countItem(Items.RAW_IRON) == 3, "raw iron left " + chest.countItem(Items.RAW_IRON));
				helper.assertTrue(chest.countItem(Items.STICK) == 2, "sticks left " + chest.countItem(Items.STICK));
			});
	}

	// lib.craft.ensure_tool

	/** Prints what {@code lib.craft.ensure_tool(args)} gives, the item held and then {@code counts}, the {@code count} of each item. */
	private static String ensureTool(String args, String... counts) {
		return craftAll(args, counts).replace("craft_all(", "ensure_tool(").replace("r['missing']", "r['missing'], held() and held().item");
	}

	/** Two pickaxes carried: the stone one is held, nothing is crafted. */
	@GameTest(maxTicks = 20)
	public void ensureToolHoldsTheBestCarried(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 3));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		expect(helper, bot, ensureTool("'pickaxe'", "wooden_pickaxe", "stone_pickaxe", "oak_log"),
			List.of("True None {} minecraft:stone_pickaxe 1 1 3"));
	}

	/** No pickaxe, logs and cobblestone: the diamond and iron tiers fail before crafting, and a stone pickaxe is made and held. */
	@GameTest(maxTicks = 180)
	public void ensureToolMakesAStonePickaxe(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		expect(helper, bot, ensureTool("'pickaxe'", "stone_pickaxe", "wooden_pickaxe", "cobblestone"),
			List.of("True None {} minecraft:stone_pickaxe 1 0 0"), tablesInTheArea(helper, 0));
	}

	/** Only logs: the stone tier lacks cobblestone, so a wooden pickaxe is made and held. */
	@GameTest(maxTicks = 180)
	public void ensureToolWithOnlyLogsMakesAWoodenPickaxe(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 3));
		expect(helper, bot, ensureTool("'pickaxe'", "wooden_pickaxe", "stone_pickaxe"),
			List.of("True None {} minecraft:wooden_pickaxe 1 0"), tablesInTheArea(helper, 0));
	}

	/**
	 * No pickaxe, logs and cobblestone in the core: the diamond and iron tiers take nothing, so the stone pickaxe takes only
	 * its own logs and cobblestone and no stray item is left in the bot.
	 */
	@GameTest(maxTicks = 180)
	public void ensureToolLeavesNoStrayItemsFromTheFailedTiers(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.OAK_LOG, 5), new ItemStack(Items.COBBLESTONE, 10));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		expect(helper, bot, ensureTool("'pickaxe', chests=[" + chestAt(helper) + "]", "stone_pickaxe", "oak_log", "cobblestone"),
			List.of("True None {} minecraft:stone_pickaxe 1 0 0"), () -> {
				helper.assertTrue(chest.countItem(Items.OAK_LOG) == 3, "logs left " + chest.countItem(Items.OAK_LOG));
				helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 7, "cobblestone left " + chest.countItem(Items.COBBLESTONE));
			});
	}

	/**
	 * No pickaxe, raw iron, coal, logs and cobblestone in a chest: the iron tier takes no raw iron, as its furnace could not be
	 * mined back, and a stone pickaxe is made and held.
	 */
	@GameTest(maxTicks = 180)
	public void ensureToolWithoutAPickaxeSkipsTheIronTier(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.RAW_IRON, 3), new ItemStack(Items.COAL, 1), new ItemStack(Items.OAK_LOG, 5),
			new ItemStack(Items.COBBLESTONE, 10));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		expect(helper, bot, ensureTool("'pickaxe', chests=[" + chestAt(helper) + "]", "stone_pickaxe", "raw_iron"),
			List.of("True None {} minecraft:stone_pickaxe 1 0"),
			() -> helper.assertTrue(chest.countItem(Items.RAW_IRON) == 3, "raw iron left " + chest.countItem(Items.RAW_IRON)));
	}

	/** One plank and two sticks, too little for any tier: it fails, short of the logs for the wooden pickaxe and a table, and every tier leaves them as they were. */
	@GameTest(maxTicks = 20)
	public void ensureToolWithNothingToMakeOneFromChangesNothing(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 1));
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		expect(helper, bot, ensureTool("'pickaxe'", "oak_planks", "stick", "wooden_pickaxe"),
			List.of("False missing_ingredients {'minecraft:oak_log': 2} None 1 2 0"));
	}

	/** No pickaxe, a chest with a wooden and an iron one and logs: it takes and holds the iron one, crafting nothing. */
	@GameTest(maxTicks = 20)
	public void ensureToolTakesTheBestFromAChest(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.OAK_LOG, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		expect(helper, bot, ensureTool("'pickaxe', chests=[" + chestAt(helper) + "]", "iron_pickaxe", "wooden_pickaxe", "oak_log"),
			List.of("True None {} minecraft:iron_pickaxe 1 0 0"), () -> {
				helper.assertTrue(chest.countItem(Items.WOODEN_PICKAXE) == 1, "wooden pickaxes left " + chest.countItem(Items.WOODEN_PICKAXE));
				helper.assertTrue(chest.countItem(Items.OAK_LOG) == 3, "logs left " + chest.countItem(Items.OAK_LOG));
			});
	}

	/** A wooden pickaxe carried and an iron one in the chest: upgrade takes and holds the iron one, the wooden one kept. */
	@GameTest(maxTicks = 20)
	public void ensureToolUpgradeTakesABetterOneFromAChest(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.IRON_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		expect(helper, bot, ensureTool("'pickaxe', chests=[" + chestAt(helper) + "], upgrade=True", "iron_pickaxe", "wooden_pickaxe"),
			List.of("True None {} minecraft:iron_pickaxe 1 1"), () -> helper.assertTrue(chest.isEmpty(), "the chest is not empty"));
	}

	/** A stone pickaxe carried and a wooden one in the chest: upgrade leaves the worse one there and holds the stone one. */
	@GameTest(maxTicks = 20)
	public void ensureToolUpgradeTakesNoWorseTool(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.WOODEN_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		expect(helper, bot, ensureTool("'pickaxe', chests=[" + chestAt(helper) + "], upgrade=True", "stone_pickaxe", "wooden_pickaxe"),
			List.of("True None {} minecraft:stone_pickaxe 1 0"),
			() -> helper.assertTrue(chest.countItem(Items.WOODEN_PICKAXE) == 1, "wooden pickaxes left " + chest.countItem(Items.WOODEN_PICKAXE)));
	}

	/** A wooden pickaxe, logs and cobblestone carried, no chest: upgrade makes and holds a stone one. */
	@GameTest(maxTicks = 180)
	public void ensureToolUpgradeMakesABetterOne(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		expect(helper, bot, ensureTool("'pickaxe', chests=[], upgrade=True", "stone_pickaxe", "wooden_pickaxe"),
			List.of("True None {} minecraft:stone_pickaxe 1 1"), tablesInTheArea(helper, 0));
	}

	// smelt

	/** It burns the best fuel it carries, coal before logs, and takes the ingot out. */
	@GameTest(maxTicks = 450)
	public void smeltLoadsAFurnaceWaitsAndTakesTheOutput(GameTestHelper helper) {
		Container furnace = furnace(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 3));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, "r = smelt('raw_iron', furnace=" + furnaceAt(helper) + ")\n"
				+ "print(r.ok, r.value, count('iron_ingot'), count('raw_iron'), count('coal'), count('oak_log'))\n",
			List.of("True 1 1 2 0 2"), () -> helper.assertTrue(furnace.getItem(2).isEmpty(), "output left in the furnace"));
	}

	/** {@code fuel} picks what to burn; without {@code furnace}, one within reach does. */
	@GameTest(maxTicks = 450)
	public void smeltBurnsTheFuelAskedForInAFurnaceWithinReach(GameTestHelper helper) {
		furnace(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.SAND, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 3));
		expect(helper, bot, "r = smelt('sand', fuel='oak_planks')\nprint(r.ok, r.value, count('glass'), count('coal'), count('oak_planks'))\n",
			List.of("True 1 1 1 2"));
	}

	/** A blast furnace at {@code furnace} smelts ore, twice as fast as a furnace. */
	@GameTest(maxTicks = 250)
	public void smeltInABlastFurnaceSmeltsOre(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.BLAST_FURNACE);
		Container furnace = container(helper, FURNACE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.IRON_ORE, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, "r = smelt('iron_ore', furnace=" + furnaceAt(helper) + ")\nprint(r.ok, r.value, count('iron_ingot'), count('iron_ore'))\n",
			List.of("True 1 1 0"), () -> helper.assertTrue(furnace.getItem(2).isEmpty(), "output left in the blast furnace"));
	}

	/** A smoker within reach cooks beef; it smelts no ore, which is no_recipe there. */
	@GameTest(maxTicks = 250)
	public void smeltInASmokerCooksFoodOnly(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.SMOKER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.BEEF, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, "s = smelt('raw_iron', furnace=" + furnaceAt(helper) + ")\nr = smelt('beef')\n"
				+ "print(r.ok, r.value, count('cooked_beef'), s.reason, count('raw_iron'))\n",
			List.of("True 1 1 no_recipe 1"));
	}

	/** Of two carried items the selector takes, only the second cooks in the smoker within reach: it smelts that one. */
	@GameTest(maxTicks = 250)
	public void smeltTriesEachItemForAStationWithinReach(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.SMOKER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.BEEF, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, "r = smelt(['raw_iron', 'beef'])\nprint(r.ok, r.reason, r.value, count('cooked_beef'), count('raw_iron'))\n",
			List.of("True None 1 1 1"));
	}

	/** The same with the smoker given as {@code furnace}: it skips the raw iron the smoker cannot cook. */
	@GameTest(maxTicks = 250)
	public void smeltTriesEachItemForTheStationAtFurnace(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.SMOKER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.BEEF, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, "r = smelt(['raw_iron', 'beef'], furnace=" + furnaceAt(helper) + ")\n"
				+ "print(r.ok, r.reason, r.value, count('cooked_beef'), count('raw_iron'))\n",
			List.of("True None 1 1 1"));
	}

	@GameTest(maxTicks = 20)
	public void smeltWhatNoRecipeSmeltsIsNoRecipe(GameTestHelper helper) {
		furnace(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.STICK, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, "r = smelt('stick')\nprint(r.ok, r.reason, count('stick'))\n", List.of("False no_recipe 1"));
	}

	/** Nothing to smelt, and something to smelt but nothing to burn. */
	@GameTest(maxTicks = 20)
	public void smeltWithoutInputOrFuelIsMissingIngredients(GameTestHelper helper) {
		furnace(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		bot.getInventory().addItem(new ItemStack(Items.SAND, 1));
		expect(helper, bot, "r = smelt('raw_iron')\ns = smelt('sand', fuel='oak_planks')\nprint(r.ok, r.reason, s.reason, count('sand'))\n",
			List.of("False missing_ingredients missing_ingredients 1"));
	}

	/** No furnace within reach, and a {@code furnace} that is not one. */
	@GameTest(maxTicks = 20)
	public void smeltWithoutAFurnaceIsNoFurnace(GameTestHelper helper) {
		helper.setBlock(FURNACE, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, "r = smelt('raw_iron')\ns = smelt('raw_iron', furnace=" + furnaceAt(helper) + ")\n"
				+ "print(r.ok, r.reason, s.reason, count('raw_iron'))\n",
			List.of("False no_furnace no_furnace 1"));
	}

	/** Something else is smelting in the furnace. */
	@GameTest(maxTicks = 20)
	public void smeltInABusyFurnaceIsBlocked(GameTestHelper helper) {
		Container furnace = furnace(helper);
		furnace.setItem(0, new ItemStack(Items.SAND, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 1));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 1));
		expect(helper, bot, "r = smelt('raw_iron')\nprint(r.ok, r.reason, count('raw_iron'))\n", List.of("False blocked 1"),
			() -> helper.assertTrue(furnace.getItem(0).is(Items.SAND), "the sand was taken out"));
	}

	/** The ingot is smelted, but every slot and the hand are taken: it stays in the furnace. */
	@GameTest(maxTicks = 450)
	public void smeltWithAFullInventoryIsNoSpace(GameTestHelper helper) {
		Container furnace = furnace(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 5);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 2));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 2));
		fillInventory(bot);
		expect(helper, bot, "r = smelt('raw_iron')\nprint(r.ok, r.reason, r.value, count('raw_iron'))\n", List.of("False no_space 0 1"),
			() -> helper.assertTrue(furnace.getItem(2).is(Items.IRON_INGOT), "no ingot in the furnace"));
	}

	// acceptance

	/** The doc's example, kept the same as the doc by DocExampleTest. */
	private static String treeToPickaxe() {
		try (InputStream in = CraftGameTest.class.getResourceAsStream("/antcraft-gametest/scripts/tree_to_pickaxe.py")) {
			return new String(Objects.requireNonNull(in, "tree_to_pickaxe.py").readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Milestone 3's acceptance: "punch a tree, then make a wooden pickaxe" of docs/cookbook/index.md, run as it is on a short
	 * oak (four logs under a crown of leaves) by a bot that carries nothing.
	 */
	@GameTest(maxTicks = 440)
	public void theTreeToPickaxeExampleEndsWithAWoodenPickaxe(GameTestHelper helper) {
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(5, y, 5, Blocks.OAK_LOG);
		}
		for (int y = 3; y <= 5; y++) {
			int r = y == 5 ? 1 : 2;
			for (int x = 5 - r; x <= 5 + r; x++) {
				for (int z = 5 - r; z <= 5 + r; z++) {
					boolean corner = Math.abs(x - 5) == r && Math.abs(z - 5) == r;
					if (!corner && helper.getBlockState(new BlockPos(x, y, z)).isAir()) {
						helper.setBlock(x, y, z, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
					}
				}
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.runScript("tree_to_pickaxe", Compiler.compile(Parser.parse("tree_to_pickaxe.py", treeToPickaxe())), List.of());
		ScriptRun run = Objects.requireNonNull(bot.getScript());
		helper.onEachTick(() -> {
			if (run.state() == ScriptRun.State.FAILED || run.state() == ScriptRun.State.FINISHED && !WorkGameTest.printed(bot).equals(List.of("pickaxe!"))) {
				BlockPos at = TestPositions.relative(helper, bot.blockPosition());
				helper.fail("script " + run.state() + ", log " + BotScripts.log(bot, Output.Level.DEBUG) + ", bot at " + at.toShortString()
					+ " north of it " + helper.getBlockState(at.north()));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertTrue(WorkGameTest.printed(bot).equals(List.of("pickaxe!")), "printed " + WorkGameTest.printed(bot));
			helper.assertTrue(bot.getInventory().countItem(Items.WOODEN_PICKAXE) + (bot.getMainHandItem().is(Items.WOODEN_PICKAXE) ? 1 : 0) == 1,
				"no wooden pickaxe");
		});
	}

	/** How many {@code item} the container holds. */
	private static int countIn(Container container, Item item) {
		int n = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			n += container.getItem(i).is(item) ? container.getItem(i).getCount() : 0;
		}
		return n;
	}

	/**
	 * The "keep a chest stocked" recipe of docs/cookbook/stock-chest.md, run as it is (DocExampleTest keeps the copy the
	 * same): it tops a chest holding 10 torches up to 64 torches and 16 bread from a barrel, and puts nothing more in.
	 */
	@GameTest(maxTicks = 40)
	public void theStockChestRecipeTopsTheChestUpFromTheBarrel(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.TORCH, 10));
		BlockPos barrelAt = new BlockPos(1, 1, 1);
		helper.setBlock(barrelAt, Blocks.BARREL);
		Container barrel = container(helper, barrelAt);
		barrel.setItem(0, new ItemStack(Items.TORCH, 64));
		barrel.setItem(1, new ItemStack(Items.TORCH, 6));
		barrel.setItem(2, new ItemStack(Items.BREAD, 20));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		String source;
		try (InputStream in = CraftGameTest.class.getResourceAsStream("/antcraft-gametest/scripts/stock_chest.py")) {
			source = new String(Objects.requireNonNull(in, "stock_chest.py").readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		bot.runScript("stock_chest", Compiler.compile(Parser.parse("stock_chest.py", source)), List.of());
		ScriptRun run = Objects.requireNonNull(bot.getScript());
		helper.onEachTick(() -> {
			if (run.state() == ScriptRun.State.FAILED || run.state() == ScriptRun.State.FINISHED) {
				helper.fail("script " + run.state() + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(countIn(chest, Items.TORCH) == 64 && countIn(chest, Items.BREAD) == 16,
				"chest holds " + countIn(chest, Items.TORCH) + " torches and " + countIn(chest, Items.BREAD) + " bread");
			helper.assertTrue(countIn(barrel, Items.TORCH) == 16 && countIn(barrel, Items.BREAD) == 4,
				"barrel holds " + countIn(barrel, Items.TORCH) + " torches and " + countIn(barrel, Items.BREAD) + " bread");
		});
	}
}
