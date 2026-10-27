package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.at;
import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.expect;
import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.fillInventory;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;

/**
 * The container and crafting actions of the bot API (docs/scripting.md, Actions): {@code put}, {@code take},
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
	 * Milestone 3's acceptance: "punch a tree, then make a wooden pickaxe" of docs/scripting.md, run as it is on a short
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
}
