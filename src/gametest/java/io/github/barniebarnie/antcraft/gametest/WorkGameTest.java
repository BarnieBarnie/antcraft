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
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.material.Fluids;

/**
 * The block actions of the bot API (docs/scripting.md, Actions): {@code mine}, {@code place}, {@code pick_up},
 * {@code equip}, {@code use} and {@code drop}, each run as a short script on a bot in the test area: one test where the
 * action succeeds, and one per reason it can fail with.
 */
public class WorkGameTest implements CustomTestMethodInvoker {
	/** The bot's inventory size, filled up for the {@code no_space} tests. */
	private static final int INVENTORY_SLOTS = 27;

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

	/** The position {@code (x, y, z)} of the test area as a script tuple. */
	private static String at(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	/** A position far above the world's build height, over the test area. */
	private static String aboveTheWorld(GameTestHelper helper) {
		BlockPos pos = helper.absolutePos(new BlockPos(4, 0, 4));
		return "(" + pos.getX() + ", " + (helper.getLevel().getMaxY() + 10) + ", " + pos.getZ() + ")";
	}

	private static List<String> printed(AntBot bot) {
		return BotScripts.log(bot, Output.Level.PRINT);
	}

	/**
	 * Runs {@code source} on {@code bot} and succeeds once it finished having printed exactly {@code expected} and
	 * {@code world} holds; fails at once when the script fails or finishes having printed something else.
	 */
	private static void expect(GameTestHelper helper, AntBot bot, String source, List<String> expected, Runnable world) {
		ScriptRun run = BotScripts.run(bot, source);
		helper.onEachTick(() -> {
			if (run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
			if (run.state() == ScriptRun.State.FINISHED && !printed(bot).equals(expected)) {
				helper.fail("printed " + printed(bot) + ", expected " + expected + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state() + ", bot at "
				+ TestPositions.relative(helper, bot.blockPosition()).toShortString());
			world.run();
		});
	}

	private static void expect(GameTestHelper helper, AntBot bot, String source, List<String> expected) {
		expect(helper, bot, source, expected, () -> {
		});
	}

	/** Fills every slot of the bot's inventory with something that stacks no further. */
	private static void fillInventory(AntBot bot) {
		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			bot.getInventory().addItem(new ItemStack(Items.ENDER_PEARL, 16));
		}
		if (bot.getInventory().canAddItem(new ItemStack(Items.ENDER_PEARL))) {
			throw new IllegalStateException("the bot's inventory has more than " + INVENTORY_SLOTS + " slots");
		}
	}

	// mine

	@GameTest(maxTicks = 70)
	public void mineWalksIntoReachBreaksWithTheBestToolAndPicksUpTheDrops(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.IRON_PICKAXE));
		expect(helper, bot, "r = mine(" + at(helper, 6, 1, 6) + ")\nprint(r.ok, r.reason, count('cobblestone'), held().item)\n",
			List.of("True None 1 minecraft:iron_pickaxe"),
			() -> helper.assertBlockPresent(Blocks.AIR, 6, 1, 6));
	}

	@GameTest(maxTicks = 20)
	public void mineAirIsTargetGoneAndLoggedAtDebug(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		String target = at(helper, 2, 1, 2);
		expect(helper, bot, "r = mine(" + target + ")\nprint(r.ok, r.reason)\n", List.of("False target_gone"), () -> {
			List<String> log = BotScripts.log(bot, Output.Level.DEBUG);
			helper.assertTrue(log.contains("mine(Pos" + target + ") -> target_gone"), "log " + log);
		});
	}

	@GameTest(maxTicks = 20)
	public void mineBedrockIsProtected(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
		expect(helper, bot, "r = mine(" + at(helper, 2, 1, 2) + ")\nprint(r.ok, r.reason)\n", List.of("False protected"),
			() -> helper.assertBlockPresent(Blocks.BEDROCK, 2, 1, 2));
	}

	@GameTest(maxTicks = 20)
	public void mineStoneWithoutAPickaxeIsNoTool(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.IRON_SHOVEL));
		expect(helper, bot, "r = mine(" + at(helper, 2, 1, 2) + ")\nprint(r.ok, r.reason)\n", List.of("False no_tool"),
			() -> helper.assertBlockPresent(Blocks.STONE, 2, 1, 2));
	}

	/** Dirt high in the air, and nothing to climb with. */
	@GameTest(maxTicks = 50)
	public void mineABlockOutOfReachIsOutOfReach(GameTestHelper helper) {
		helper.setBlock(4, 7, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = mine(" + at(helper, 4, 7, 4) + ")\nprint(r.ok, r.reason)\n", List.of("False out_of_reach"),
			() -> helper.assertBlockPresent(Blocks.DIRT, 4, 7, 4));
	}

	/** The block is mined, but its drop does not fit and stays on the ground. */
	@GameTest(maxTicks = 20)
	public void mineWithAFullInventoryIsNoSpace(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
		fillInventory(bot);
		expect(helper, bot, "r = mine(" + at(helper, 2, 1, 2) + ")\nprint(r.ok, r.reason, count('cobblestone'))\n",
			List.of("False no_space 0"), () -> {
				helper.assertBlockPresent(Blocks.AIR, 2, 1, 2);
				helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(2, 1, 2), 2.0);
			});
	}

	// place

	@GameTest(maxTicks = 60)
	public void placeWalksIntoReachAndPlacesFacingTheWayAsked(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		expect(helper, bot, "r = place(" + at(helper, 6, 1, 6) + ", 'furnace', facing='east')\n"
				+ "s = place(" + at(helper, 6, 1, 5) + ", ['#logs'], 'east')\n"
				+ "t = place(" + at(helper, 6, 2, 5) + ", 'oak_log')\n"
				+ "print(r.ok, r.reason, s.ok, t.ok, count('furnace'), count('oak_log'))\n",
			List.of("True None True True 0 0"), () -> {
				helper.assertBlockProperty(new BlockPos(6, 1, 6), FurnaceBlock.FACING, Direction.EAST);
				helper.assertBlockProperty(new BlockPos(6, 1, 5), RotatedPillarBlock.AXIS, Direction.Axis.X);
				helper.assertBlockPresent(Blocks.OAK_LOG, 6, 2, 5);
			});
	}

	@GameTest(maxTicks = 20)
	public void placeWithoutTheItemIsNoItem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STICK));
		expect(helper, bot, "r = place(" + at(helper, 2, 1, 2) + ", 'dirt')\ns = place(" + at(helper, 2, 1, 2) + ", 'stick')\n"
			+ "print(r.ok, r.reason, s.reason)\n", List.of("False no_item no_item"),
			() -> helper.assertBlockPresent(Blocks.AIR, 2, 1, 2));
	}

	@GameTest(maxTicks = 20)
	public void placeWhereABlockIsIsBlocked(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.GLASS);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		expect(helper, bot, "r = place(" + at(helper, 2, 1, 2) + ", 'dirt')\nprint(r.ok, r.reason, count('dirt'))\n",
			List.of("False blocked 1"), () -> helper.assertBlockPresent(Blocks.GLASS, 2, 1, 2));
	}

	@GameTest(maxTicks = 20)
	public void placeAboveTheWorldIsProtected(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		expect(helper, bot, "r = place(" + aboveTheWorld(helper) + ", 'dirt')\nprint(r.ok, r.reason, count('dirt'))\n",
			List.of("False protected 1"));
	}

	/** A spot high in the air, and nothing the bot can climb with: a furnace is no scaffold. */
	@GameTest(maxTicks = 50)
	public void placeOutOfReachIsOutOfReach(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		expect(helper, bot, "r = place(" + at(helper, 4, 7, 4) + ", 'furnace')\nprint(r.ok, r.reason, count('furnace'))\n",
			List.of("False out_of_reach 1"), () -> helper.assertBlockPresent(Blocks.AIR, 4, 7, 4));
	}

	// pick_up

	@GameTest(maxTicks = 100)
	public void pickUpCollectsTheMatchingItemsAround(GameTestHelper helper) {
		helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(6, 1, 6));
		helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(6, 1, 4));
		helper.spawnItem(Items.HEART_OF_THE_SEA, new BlockPos(1, 1, 6));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = pick_up('nautilus_shell', radius=7)\nprint(r.ok, r.reason, r.value, count('nautilus_shell'), count('heart_of_the_sea'))\n",
			List.of("True None 2 2 0"), () -> helper.assertItemEntityPresent(Items.HEART_OF_THE_SEA));
	}

	@GameTest(maxTicks = 20)
	public void pickUpWithNothingAroundIsTargetGone(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = pick_up('nautilus_shell', radius=7)\nprint(r.ok, r.reason)\n", List.of("False target_gone"));
	}

	@GameTest(maxTicks = 20)
	public void pickUpWithAFullInventoryIsNoSpace(GameTestHelper helper) {
		helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(3, 1, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		fillInventory(bot);
		expect(helper, bot, "r = pick_up('nautilus_shell', radius=7)\nprint(r.ok, r.reason)\n", List.of("False no_space"),
			() -> helper.assertItemEntityPresent(Items.NAUTILUS_SHELL));
	}

	/** An item on top of a bedrock pillar, which the bot cannot dig away, and nothing to climb with. */
	@GameTest(maxTicks = 80)
	public void pickUpAnItemOutOfReachIsOutOfReach(GameTestHelper helper) {
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(5, y, 5, Blocks.BEDROCK);
		}
		ItemEntity shell = helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(5, 5, 5));
		shell.setPos(shell.getX() + 0.5, shell.getY(), shell.getZ() + 0.5);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = pick_up('nautilus_shell', radius=7)\nprint(r.ok, r.reason)\n", List.of("False out_of_reach"),
			() -> helper.assertItemEntityPresent(Items.NAUTILUS_SHELL));
	}

	// equip

	@GameTest(maxTicks = 20)
	public void equipHoldsTheItem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
		bot.getInventory().addItem(new ItemStack(Items.DIAMOND_SWORD));
		expect(helper, bot, "r = equip('#swords')\nprint(r.ok, r.reason, held().item, count('stick'))\n",
			List.of("True None minecraft:diamond_sword 1"));
	}

	@GameTest(maxTicks = 20)
	public void equipWithoutTheItemIsNoItem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
		expect(helper, bot, "r = equip('diamond_sword')\nprint(r.ok, r.reason, held().item)\n",
			List.of("False no_item minecraft:stick"));
	}

	// use

	@GameTest(maxTicks = 70)
	public void useTillsDirtWithAHoe(GameTestHelper helper) {
		helper.setBlock(6, 0, 6, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		expect(helper, bot, "r = use(" + at(helper, 6, 0, 6) + ", 'wooden_hoe')\nprint(r.ok, r.reason, held().item)\n",
			List.of("True None minecraft:wooden_hoe"), () -> helper.assertBlockPresent(Blocks.FARMLAND, 6, 0, 6));
	}

	@GameTest(maxTicks = 20)
	public void useScoopsAndPoursWaterWithABucket(GameTestHelper helper) {
		helper.setBlock(3, 0, 3, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.BUCKET));
		expect(helper, bot, "r = use(" + at(helper, 3, 0, 3) + ", 'bucket')\nprint(r.ok, held().item)\n"
				+ "s = use(" + at(helper, 1, 0, 3) + ")\nprint(s.ok, held().item)\n",
			List.of("True minecraft:water_bucket", "True minecraft:bucket"), () -> {
				helper.assertBlockPresent(Blocks.AIR, 3, 0, 3);
				helper.assertTrue(helper.getBlockState(new BlockPos(1, 1, 3)).getFluidState().is(Fluids.WATER), "no water poured at (1, 1, 3)");
			});
	}

	@GameTest(maxTicks = 20)
	public void useGrowsWheatWithBoneMeal(GameTestHelper helper) {
		helper.setBlock(3, 0, 3, Blocks.FARMLAND);
		helper.setBlock(3, 1, 3, Blocks.WHEAT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.BONE_MEAL, 3));
		expect(helper, bot, "r = use(" + at(helper, 3, 1, 3) + ", 'bone_meal')\nprint(r.ok, count('bone_meal'))\n",
			List.of("True 2"), () -> helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 3)).getValue(CropBlock.AGE) > 0, "wheat did not grow"));
	}

	@GameTest(maxTicks = 20)
	public void useLightsAFireWithFlintAndSteel(GameTestHelper helper) {
		helper.setBlock(3, 0, 3, Blocks.NETHERRACK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FLINT_AND_STEEL));
		expect(helper, bot, "r = use(" + at(helper, 3, 0, 3) + ", 'flint_and_steel')\nprint(r.ok, r.reason)\n",
			List.of("True None"), () -> helper.assertBlockPresent(Blocks.FIRE, 3, 1, 3));
	}

	@GameTest(maxTicks = 20)
	public void useWithoutTheItemIsNoItem(GameTestHelper helper) {
		helper.setBlock(2, 0, 2, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = use(" + at(helper, 2, 0, 2) + ", 'wooden_hoe')\nprint(r.ok, r.reason)\n", List.of("False no_item"),
			() -> helper.assertBlockPresent(Blocks.DIRT, 2, 0, 2));
	}

	@GameTest(maxTicks = 20)
	public void useAboveTheWorldIsProtected(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		expect(helper, bot, "r = use(" + aboveTheWorld(helper) + ", 'wooden_hoe')\nprint(r.ok, r.reason)\n", List.of("False protected"));
	}

	@GameTest(maxTicks = 20)
	public void useThatDoesNothingIsBlocked(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		expect(helper, bot, "r = use(" + at(helper, 2, 0, 2) + ", 'wooden_hoe')\nprint(r.ok, r.reason)\n", List.of("False blocked"),
			() -> helper.assertBlockPresent(Blocks.STONE, 2, 0, 2));
	}

	@GameTest(maxTicks = 50)
	public void useOutOfReachIsOutOfReach(GameTestHelper helper) {
		helper.setBlock(4, 7, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		expect(helper, bot, "r = use(" + at(helper, 4, 7, 4) + ", 'wooden_hoe')\nprint(r.ok, r.reason)\n", List.of("False out_of_reach"),
			() -> helper.assertBlockPresent(Blocks.DIRT, 4, 7, 4));
	}

	// drop

	/** Dropped items land on the ground, and the bot does not pick them up again by itself. */
	@GameTest(maxTicks = 100)
	public void dropThrowsItemsOutAndLeavesThem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.NAUTILUS_SHELL, 5));
		expect(helper, bot, "r = drop('nautilus_shell', 2)\nprint(r.ok, r.reason, r.value, count('nautilus_shell'))\n"
				+ "wait(60)\nprint(count('nautilus_shell'), len(items_near('nautilus_shell', 4)))\n",
			List.of("True None 2 3", "3 1"));
	}

	@GameTest(maxTicks = 20)
	public void dropWithoutTheItemIsNoItem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		expect(helper, bot, "r = drop('nautilus_shell')\nprint(r.ok, r.reason)\n", List.of("False no_item"));
	}
}
