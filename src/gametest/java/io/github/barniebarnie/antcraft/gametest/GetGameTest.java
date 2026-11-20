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
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
	 * Flint, a stick and a feather in a chest by a crafting table, nothing carried (#720): get crafts four arrows from them.
	 */
	// Measured: passes at tick 27.
	@GameTest(maxTicks = 60)
	public void getArrowsFromFlintSticksAndFeathersInAChest(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.FLINT), new ItemStack(Items.STICK), new ItemStack(Items.FEATHER));
		helper.setBlock(4, 1, 3, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, """
			from lib.get import get
			r = get("arrow", 4)
			print(r["ok"], count("arrow") >= 4)
			""");
		failOnOtherPrint(helper, bot, "True True");
		helper.succeedWhen(() -> printed(helper, bot, "True True"));
	}

	/**
	 * Two gravel and a Fortune III shovel carried, which always drops flint from gravel (#720): get gathers two flint by
	 * digging the gravel, and make_cost of them is a number.
	 */
	// Measured: passes at tick 48.
	@GameTest(maxTicks = 100)
	public void getFlintDigsGravel(GameTestHelper helper) {
		helper.setBlock(5, 1, 2, Blocks.GRAVEL);
		helper.setBlock(5, 1, 5, Blocks.GRAVEL);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);
		shovel.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), 3);
		bot.getInventory().addItem(shovel);
		run(helper, bot, """
			from lib.get import get, make_cost
			c = make_cost("flint", 2)
			r = get("flint", 2)
			print(c is not None, r["ok"], count("flint"))
			""");
		failOnOtherPrint(helper, bot, "True True 2");
		helper.succeedWhen(() -> printed(helper, bot, "True True 2"));
	}

	/**
	 * Two gravel carried, none in the world, and a Fortune III shovel (#746): get puts the gravel down and digs it for two
	 * flint, as it would dig what gravel drops again where no exposed gravel is left. Measured: passes at tick 12 to 16.
	 */
	@GameTest(maxTicks = 30)
	public void getFlintFromCarriedGravel(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(fortuneShovel(helper));
		bot.getInventory().addItem(new ItemStack(Items.GRAVEL, 2));
		run(helper, bot, """
			from lib.get import get
			r = get("flint", 2)
			print(r["ok"], count("flint"), count("gravel"))
			""");
		failOnOtherPrint(helper, bot, "True 2 0");
		helper.succeedWhen(() -> printed(helper, bot, "True 2 0"));
	}

	/**
	 * A bot in a 1x1 shaft of dirt two deep, open above, with one gravel and a Fortune III shovel (#754): get puts the
	 * gravel beside its feet, digging a spot out, never over its head, where it would fall on the bot. Measured: passes at
	 * tick 19.
	 */
	@GameTest(maxTicks = 40)
	public void getFlintInAShaftNeverPutsGravelOverItsHead(GameTestHelper helper) {
		for (int x = 2; x <= 4; x++) {
			for (int z = 2; z <= 4; z++) {
				if (x != 3 || z != 3) {
					helper.setBlock(x, 1, z, Blocks.DIRT);
					helper.setBlock(x, 2, z, Blocks.DIRT);
				}
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(fortuneShovel(helper));
		bot.getInventory().addItem(new ItemStack(Items.GRAVEL));
		helper.onEachTick(() -> {
			for (int y = 1; y <= 4; y++) {
				if (helper.getBlockState(new BlockPos(3, y, 3)).is(Blocks.GRAVEL)) {
					helper.fail("gravel over the bot at y " + y);
				}
			}
		});
		run(helper, bot, """
			from lib.get import get
			r = get("flint", 1)
			print(r["ok"], count("flint"))
			""");
		failOnOtherPrint(helper, bot, "True 1");
		helper.succeedWhen(() -> printed(helper, bot, "True 1"));
	}

	/**
	 * A Fortune III pickaxe and no shovel carried (#754): the coal source counts 2.2 coal an ore, the mean of the game's
	 * ore_drops bonus, so 11 coal are 5 ore; the flint source, with no Fortune shovel, ten gravel a flint. Copper and lapis
	 * drop 3.5 and 6.5 an ore before that bonus, redstone 4.5 and half the level more (#770): 30 of each are 4, 3 and 5
	 * ore.
	 */
	@GameTest(maxTicks = 10)
	public void sourcesCountTheFortuneOfTheirTool(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
		pickaxe.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), 3);
		bot.getInventory().addItem(pickaxe);
		run(helper, bot, """
			from lib.get import SOURCES
			print(SOURCES["minecraft:coal"].blocks(11), SOURCES["minecraft:flint"].blocks(3),
			      *[SOURCES["minecraft:" + i].blocks(30) for i in ("raw_copper", "lapis_lazuli", "redstone")])
			""");
		failOnOtherPrint(helper, bot, "5 30 4 3 5");
		helper.succeedWhen(() -> printed(helper, bot, "5 30 4 3 5"));
	}

	/**
	 * No pickaxe carried (#770): copper, lapis and redstone ores drop 2 to 5, 4 to 9 and 4 to 5 items without Fortune (the
	 * 26.3 loot tables), so 30 of each are 9, 5 and 7 ore; coal drops one, 30.
	 */
	@GameTest(maxTicks = 10)
	public void oreSourcesCountTheirBaseDrops(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		run(helper, bot, """
			from lib.get import SOURCES
			print(*[SOURCES["minecraft:" + i].blocks(30) for i in ("raw_copper", "lapis_lazuli", "redstone", "coal")])
			""");
		failOnOtherPrint(helper, bot, "9 5 7 30");
		helper.succeedWhen(() -> printed(helper, bot, "9 5 7 30"));
	}

	/**
	 * Three gravel at the area's far corners and a Fortune III shovel carried, which drops flint from each gravel (#746):
	 * make_cost of three flint, which counts one gravel a flint as it reads the shovel's Fortune (#754), is within
	 * {@link #COST_FACTOR} of the ticks get takes; it counted ten. Measured: make_cost 75, get 72 to 78 ticks (test tick 78
	 * to 92).
	 */
	@GameTest(maxTicks = 160)
	public void makeCostOfFlintIsCloseToItsTicks(GameTestHelper helper) {
		helper.setBlock(7, 1, 1, Blocks.GRAVEL);
		helper.setBlock(7, 1, 7, Blocks.GRAVEL);
		helper.setBlock(1, 1, 7, Blocks.GRAVEL);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(fortuneShovel(helper));
		run(helper, bot, """
			from lib.get import get, make_cost
			print(make_cost("flint", 3))
			r = get("flint", 3)
			print(r["ok"])
			""");
		measured(helper, bot);
	}

	/** An iron shovel with Fortune III, which always drops flint from gravel. */
	private static ItemStack fortuneShovel(GameTestHelper helper) {
		ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);
		shovel.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FORTUNE), 3);
		return shovel;
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
	 * A stone pickaxe carried, three copper ore at three corners of the area (#779): make_cost of ten raw copper, which is
	 * three ore at 3.5 each, is within {@link #COST_FACTOR} of the ticks lib.dig.ore takes to mine all three, as for raw
	 * iron. The drops are random (2 to 5), so the bot mines every exposed ore and digs no stairs (best_y None). Measured:
	 * make_cost 173, lib.dig.ore 130 to 131 ticks (test tick 137 to 138), as raw iron; lapis the same with an estimate of
	 * 60 (it was 300: make_cost 653); redstone, with the quicker iron pickaxe, make_cost 145 with 50, 107 to 108 ticks
	 * (test tick 114 to 115; it was 400: make_cost 845). Ores all of hardness 3, an ore's estimate is a walk and its
	 * mining; how rare it is underground is lib.get.deep's.
	 */
	@GameTest(maxTicks = 270)
	public void makeCostOfRawCopperIsCloseToItsTicks(GameTestHelper helper) {
		threeOres(helper, Blocks.COPPER_ORE, Items.STONE_PICKAXE, "raw_copper", 10);
	}

	/** As {@link #makeCostOfRawCopperIsCloseToItsTicks}, for 19 lapis, three ore at 6.5 each (#779). */
	@GameTest(maxTicks = 270)
	public void makeCostOfLapisIsCloseToItsTicks(GameTestHelper helper) {
		threeOres(helper, Blocks.LAPIS_ORE, Items.STONE_PICKAXE, "lapis_lazuli", 19);
	}

	/** As {@link #makeCostOfRawCopperIsCloseToItsTicks}, for 13 redstone with an iron pickaxe, three ore at 4.5 (#779). */
	@GameTest(maxTicks = 270)
	public void makeCostOfRedstoneIsCloseToItsTicks(GameTestHelper helper) {
		threeOres(helper, Blocks.REDSTONE_ORE, Items.IRON_PICKAXE, "redstone", 13);
	}

	/**
	 * As {@link #makeCostOfRawCopperIsCloseToItsTicks}, for 3 raw gold with the iron pickaxe it needs, one each (#789).
	 * Gold, diamond and emerald ore are of hardness 3 as well, so their estimate is redstone's, a walk and its mining; it
	 * was 400 for gold and 2000 for diamond and emerald, which counted how rare they are, deep()'s part. Measured with
	 * 50: make_cost 145, lib.dig.ore 107 to 110 ticks (test tick 114 to 127) for each (it was 845 for gold, 4045 for
	 * diamond and emerald).
	 */
	@GameTest(maxTicks = 270)
	public void makeCostOfRawGoldIsCloseToItsTicks(GameTestHelper helper) {
		threeOres(helper, Blocks.GOLD_ORE, Items.IRON_PICKAXE, "raw_gold", 3);
	}

	/** As {@link #makeCostOfRawGoldIsCloseToItsTicks}, for 3 diamonds (#789). */
	@GameTest(maxTicks = 270)
	public void makeCostOfDiamondIsCloseToItsTicks(GameTestHelper helper) {
		threeOres(helper, Blocks.DIAMOND_ORE, Items.IRON_PICKAXE, "diamond", 3);
	}

	/** As {@link #makeCostOfRawGoldIsCloseToItsTicks}, for 3 emeralds (#789). */
	@GameTest(maxTicks = 270)
	public void makeCostOfEmeraldIsCloseToItsTicks(GameTestHelper helper) {
		threeOres(helper, Blocks.EMERALD_ORE, Items.IRON_PICKAXE, "emerald", 3);
	}

	/**
	 * Ore at three corners of the area, the bot at the fourth with pickaxe: make_cost of n of item, three ore, is within
	 * {@link #COST_FACTOR} of the ticks lib.dig.ore takes to mine all three.
	 */
	private static void threeOres(GameTestHelper helper, Block ore, Item pickaxe, String item, int n) {
		helper.setBlock(7, 1, 1, ore);
		helper.setBlock(7, 1, 7, ore);
		helper.setBlock(1, 1, 7, ore);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(pickaxe));
		run(helper, bot, """
			import lib.dig
			from lib.get import make_cost, SOURCES
			print(SOURCES["minecraft:%1$s"].blocks(%2$d) == 3 and make_cost("%1$s", %2$d))
			lib.dig.best_y = lambda key: None
			r = lib.dig.ore("%1$s", 99)
			print(r["reason"] == "no_ore" and count("%1$s") >= min(6, %2$d))
			""".formatted(item, n));
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
	// Full runs: passes at up to tick 248 (#861).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 500)
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
	 * A stone sword carried and a still cow 6 blocks off (#719): make_cost of a beef, the walk to the cow and a kill with
	 * a sword, is within {@link #COST_FACTOR} of the ticks get takes to kill it and pick up what it drops. The default
	 * radius: no neighbouring test's animal is found, as entities are kept to the test's area (GameTests.hides, #742).
	 */
	@GameTest(maxTicks = 200)
	public void makeCostOfBeefFromACowIsCloseToItsTicks(GameTestHelper helper) {
		Cow cow = helper.spawn(EntityTypes.COW, 7, 1, 1);
		cow.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_SWORD));
		run(helper, bot, """
			from lib.get import get, make_cost
			print(make_cost("beef", 1))
			r = get("beef", 1)
			print(r["ok"])
			""");
		measured(helper, bot);
	}

	/**
	 * A still calf beside the bot and a still cow 5 blocks off (#742): get of a beef skips the calf, which drops nothing,
	 * and kills the cow. Measured: 53 ticks.
	 */
	@GameTest(maxTicks = 120)
	public void getBeefSkipsABabyCowAndKillsTheAdult(GameTestHelper helper) {
		Cow calf = helper.spawn(EntityTypes.COW, 2, 1, 1);
		calf.setNoAi(true);
		calf.setBaby(true);
		Cow cow = helper.spawn(EntityTypes.COW, 6, 1, 1);
		cow.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_SWORD));
		run(helper, bot, """
			from lib.get import get
			print(get("beef", 1)["ok"])
			""");
		helper.onEachTick(() -> {
			if (calf.isDeadOrDying() || calf.getHealth() < calf.getMaxHealth()) {
				helper.fail("hit the calf");
			}
		});
		helper.succeedWhen(() -> {
			printed(helper, bot, "True");
			helper.assertTrue(cow.isDeadOrDying(), "cow alive");
		});
	}

	/**
	 * A still chicken and a still cow each 4 blocks off, no sword (#742): make_cost of a chicken is less than of a beef,
	 * one kill each, as a chicken's 4 health dies sooner than a cow's 10. Measured: 16 ticks.
	 */
	@GameTest(maxTicks = 40)
	public void makeCostOfAChickenIsLessThanOfABeef(GameTestHelper helper) {
		helper.spawn(EntityTypes.CHICKEN, 5, 1, 1).setNoAi(true);
		helper.spawn(EntityTypes.COW, 1, 1, 5).setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, """
			from lib.get import make_cost
			print(make_cost("chicken", 1) < make_cost("beef", 1))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True"));
	}

	/** No animal within radius (#719): get of a beef fails at once with no_animal, and make_cost of it is None. */
	@GameTest(maxTicks = 40)
	public void getBeefFailsWithNoAnimalInRange(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, """
			from lib.get import get, make_cost
			print(make_cost("beef", 1))
			r = get("beef", 2)
			print(r["ok"], r["reason"], r["missing"])
			""");
		helper.succeedWhen(() -> printed(helper, bot, "None", "False no_animal {'minecraft:beef': 2}"));
	}

	/**
	 * No animal within radius and no pickaxe, but a furnace, coal and the logs for a pickaxe (#741): get of a cooked beef
	 * fails with no_animal before it makes the pickaxe the furnace would need to come back.
	 */
	@GameTest(maxTicks = 20)
	public void getCookedBeefWithNoAnimalFailsWithoutMakingAPickaxe(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 2));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 3));
		run(helper, bot, """
			from lib.get import get
			r = get("cooked_beef", 1)
			print(r["ok"], r["reason"], count("#minecraft:pickaxes"), count("oak_log"))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "False no_animal 0 3"));
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
	// Full runs: passes at up to tick 990 (#861).
	@GameTest(maxTicks = 2000)
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

	/**
	 * No fuel carried, a raw iron, a furnace and a wooden pickaxe, coal ore beside the bot and an oak at the far corner of
	 * the wide area (#954): get of an iron ingot mines the coal and smelts with it, and leaves the tree standing. Without
	 * gather costs in needs the plan burnt planks and the bot walked to the tree for a log.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 850)
	public void withNoFuelCoalBesideBeatsAFarTreeForFuel(GameTestHelper helper) {
		helper.setBlock(2, 1, 1, Blocks.COAL_ORE);
		TreesGameTest.plantTree(helper, new BlockPos(20, 1, 20), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON));
		run(helper, bot, """
			from lib.get import get
			r = get("iron_ingot")
			print(r["ok"], count("iron_ingot"), count("#minecraft:logs") + count("#minecraft:planks"))
			""");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 1 0");
			helper.assertBlockPresent(Blocks.AIR, new BlockPos(2, 1, 1));
			helper.assertBlockPresent(Blocks.OAK_LOG, new BlockPos(20, 1, 20));
		});
	}

	/**
	 * As {@link #withNoFuelCoalBesideBeatsAFarTreeForFuel}, with an oak near and no coal ore (#954): get of an iron ingot
	 * burns a plank of the oak's log, three planks left.
	 */
	@GameTest(maxTicks = 750)
	public void withNoFuelATreeNearAndNoCoalBurnsPlanks(GameTestHelper helper) {
		TreesGameTest.plantTree(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON));
		run(helper, bot, """
			from lib.get import get
			r = get("iron_ingot")
			print(r["ok"], count("iron_ingot"), count("#minecraft:planks"))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True 1 3"));
	}

	/**
	 * As {@link #withNoFuelCoalBesideBeatsAFarTreeForFuel} (#961): make_cost of an iron ingot plans with the fuel costs get
	 * plans with, so it costs the coal beside: its fuel, less the smelting and the furnace mined back, costs less than the
	 * walk to the far tree. It planned without them, burnt planks and costed a log of the far tree.
	 */
	// Measured: passes at tick 67 alone (red: printed ['minecraft:oak_log'] False), 137 to 150 in class runs, which
	// timed out at 140 with the code before #960 too (#960).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 280)
	public void makeCostWithNoFuelCostsTheCoalBesideNotAFarTree(GameTestHelper helper) {
		helper.setBlock(2, 1, 1, Blocks.COAL_ORE);
		TreesGameTest.plantTree(helper, new BlockPos(20, 1, 20), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON));
		run(helper, bot, """
			import lib.cost
			import lib.find
			from lib.get import make_cost, stations
			tools = {}
			c = make_cost("iron_ingot", 1, tools=tools)
			p = tools["plans"][("iron_ingot", 1)][1]
			fuel = c - lib.cost.SMELT - stations(p)
			print(sorted(p["gather"]), fuel < lib.cost.walk(pos(), lib.find.nearest("#minecraft:logs", 32)))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "['minecraft:coal'] True"));
	}

	/**
	 * As {@link #makeCostWithNoFuelCostsTheCoalBesideNotAFarTree} (#963): make_cost's bound (least_make) leaves out the
	 * wood its plan without fuel costs burns, as the plan with them may burn coal, so with a limit of the cost itself, as
	 * options gives a dearer tier where a cheaper one cost that much, it still gives that cost. The bound counted the log
	 * for the planks that plan burnt, a log's least (55) over the coal beside (50), so the iron ingot was left out.
	 */
	// Measured: passes at tick 212 to 216 in class runs (red: printed True False).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 420)
	public void makeCostBoundsWithTheFuelCosts(GameTestHelper helper) {
		helper.setBlock(2, 1, 1, Blocks.COAL_ORE);
		TreesGameTest.plantTree(helper, new BlockPos(20, 1, 20), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON));
		run(helper, bot, """
			from lib.get import make_cost
			c = make_cost("iron_ingot", 1)
			print(c is not None, make_cost("iron_ingot", 1, limit=c) == c)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True True"));
	}

	/**
	 * A big smelt with no fuel carried, an oak 4 blocks off and coal ore 20 off (#960): 64 iron ingots plan 8 coal, not 11
	 * logs for planks. Coal costs the walk once and 45 a block with the wooden pickaxe carried (460), the logs the short
	 * walk once and 60 each by hand, as no axe is carried (680). With the walk counted for each coal and a log broken as
	 * with a stone axe the far coal cost 1160 and the logs 385. Since a stone axe made is costed (#974), 10 logs as 8
	 * charcoal cost 304 + 10 * 15 = 454, and only the 8 charcoal's 1,600 smelt ticks, counted since #976, keep coal ahead.
	 */
	// Measured: passes at tick 55 to 113 alone and 137 to 248 in class runs (red: printed ['minecraft:dark_oak_log', 'minecraft:oak_log']);
	// 305 in a class run with the stone axe (red without #976's smelt time: printed ['minecraft:dark_oak_log']).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 500)
	public void aBigSmeltPlansCoalAWalkOffOverATreeNear(GameTestHelper helper) {
		helper.setBlock(21, 1, 1, Blocks.COAL_ORE);
		TreesGameTest.plantTree(helper, new BlockPos(5, 1, 1), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 64));
		run(helper, bot, """
			from lib.get import make_cost
			tools = {}
			make_cost("iron_ingot", 64, tools=tools)
			print(sorted(tools["plans"][("iron_ingot", 64)][1]["gather"]))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "['minecraft:coal']"));
	}

	/**
	 * A big smelt with no axe, an oak 4 blocks off and no coal ore in sight (#966): 64 iron ingots plan 11 logs for
	 * planks, and make_cost costs them with a wooden axe made, as get makes one where that is cheaper (ensure_tool): the
	 * axe (some 260) and 30 a log beat 60 a log by hand. Before, a log cost the hand's 60 with no axe carried. No pickaxe
	 * carried, so a stone axe, which needs one for its cobblestone, costs more (#974).
	 */
	// Measured: passes at tick 118 alone, 241 in a class run (red: printed 60 False False); without the pickaxe 149 alone, 339 to 341 in class runs.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 480)
	public void aBigSmeltWithNoAxeCostsTheLogsWithAnAxeMade(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(5, 1, 1), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 64));
		run(helper, bot, """
			import lib.cost
			import lib.find
			from lib.get import make_cost, LOGS
			tools = {}
			make_cost("iron_ingot", 64, tools=tools)
			once, each = tools["fuel"][LOGS]
			print(each, once > lib.cost.walk(pos(), lib.find.nearest(LOGS, 32)), tools.get("minecraft:wooden_axe") is not None)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "30 True True"));
	}

	/**
	 * As {@link #aBigSmeltWithNoAxeCostsTheLogsWithAnAxeMade}, with 3 cobblestone carried (#974): make_cost costs the
	 * 11 logs with a stone axe made, as get makes one where that is cheaper (ensure_tool): its sticks take one log where
	 * a wooden axe's planks take two, and it breaks a log in 15 ticks, not 30. Before, only a wooden axe was costed.
	 */
	// Measured: passes at tick 314 to 316 in class runs (#976).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 480)
	public void aBigSmeltWithNoAxeCostsTheLogsWithAStoneAxeMade(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(5, 1, 1), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 64));
		run(helper, bot, """
			from lib.get import make_cost, LOGS
			tools = {}
			make_cost("iron_ingot", 64, tools=tools)
			print(tools["fuel"][LOGS][1], tools.get("minecraft:stone_axe") is not None)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "15 True"));
	}

	/**
	 * The costs a big smelt plans with weigh every raw item in ticks, not only the fuels (#966): cobblestone and raw iron
	 * their source's estimate, a gatherable item get has no source for, such as string, DEEP, so the plan never picks it
	 * over one get can gather. Before, every raw item but coal and logs cost 1, a count beside the fuels' ticks.
	 */
	// Measured: passes at tick 16 alone, 32 in a class run (red: printed False False False).
	@GameTest(maxTicks = 80)
	public void aBigSmeltWeighsEveryRawItemInTicks(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 64));
		run(helper, bot, """
			from lib.get import make_cost, SOURCES, DEEP
			tools = {}
			make_cost("iron_ingot", 64, tools=tools)
			c = tools["fuel"]
			print(c.get("minecraft:cobblestone") == (0, SOURCES["minecraft:cobblestone"].estimate),
			      c.get("minecraft:raw_iron") == (0, SOURCES["minecraft:raw_iron"].estimate), c.get("minecraft:string") == (0, DEEP))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True True True"));
	}

	/**
	 * As {@link #withNoFuelCoalBesideBeatsAFarTreeForFuel}, with a script's SOURCES entry of an item the game does not
	 * know, such as a typo or a mod not loaded (#966): get of an iron ingot still mines the coal and smelts it, as
	 * fuel_costs gives needs only items of the antcraft:gatherable tag. Costing every item of SOURCES stopped the script
	 * on needs' unknown item.
	 */
	// Measured: passes at tick 429 alone, 589 in a class run, as withNoFuelCoalBesideBeatsAFarTreeForFuel's 560 (red:
	// script error, unknown item 'antcraft:no_such_item').
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 850)
	public void anUnknownItemInSourcesBreaksNoSmelt(GameTestHelper helper) {
		helper.setBlock(2, 1, 1, Blocks.COAL_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON));
		BotScripts.failOnError(helper, bot);
		run(helper, bot, """
			from lib.get import get, Source, SOURCES
			from lib.result import result
			SOURCES["antcraft:no_such_item"] = Source(lambda n, radius: result())
			r = get("iron_ingot")
			print(r["ok"], count("iron_ingot"))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True 1"));
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
	// Full runs: passes at up to tick 12 (#861).
	@GameTest(maxTicks = 30)
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
	// Full runs: passes at up to tick 18 (#861).
	@GameTest(maxTicks = 40)
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
	@GameTest(maxTicks = 40)
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
	 * A crafting table by the bot, three iron ingots in a chest across the area, more than lib.craft.REACH from the table
	 * wherever the bot stands to open it, no log or tree (#692): get takes the iron and walks back to the table for the
	 * bucket, where it planned again from beside the chest, saw no table within reach and failed no_logs crafting one.
	 */
	// Measured: passes at tick 70 (red: printed False no_logs 0 at tick 45).
	// Full runs: passes at up to tick 90 (#861).
	@GameTest(maxTicks = 180)
	public void walksBackToTheTableAfterTakingFromAFarChest(GameTestHelper helper) {
		Container chest = chest(helper, new BlockPos(7, 1, 7), new ItemStack(Items.IRON_INGOT, 3));
		helper.setBlock(1, 1, 1, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		run(helper, bot, """
			from lib.get import get
			r = get("bucket")
			print(r["ok"], r["reason"], count("bucket"))
			""");
		failOnOtherPrint(helper, bot, "True None 1");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True None 1");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * A crafting table by the bot, eight cobblestone in a chest across the area (#698): make_cost of a furnace is the walk
	 * to the chest and the walk from it back to the table, as get walks; it counted the walk to the table from where the
	 * bot stood.
	 */
	// Measured: passes at tick 5 to 13 (red: printed -55, the walk to the table from the bot less the walk back from the chest).
	@GameTest(maxTicks = 30)
	public void makeCostCountsTheWalkBackToTheTable(GameTestHelper helper) {
		chest(helper, new BlockPos(7, 1, 7), new ItemStack(Items.COBBLESTONE, 8));
		helper.setBlock(1, 1, 1, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		run(helper, bot, """
			import lib.cost
			import lib.stock
			from lib.get import make_cost
			c = lib.stock.chests(32)[0]
			t = find_nearest("crafting_table", radius=4)
			print(make_cost("furnace", 1) - lib.cost.walk(pos(), c) - lib.cost.walk(c, t))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "0"));
	}

	/**
	 * A crafting table by the bot, three iron ingots in a chest across the area, no furnace (#709): make_cost of a bucket
	 * is the walk to the chest and the walk from it back to the table, as get takes the ingots and crafts; it counted the
	 * smelting of the ingots and the furnace to smelt them in. get takes the ingots and crafts in 70 ticks
	 * (walksBackToTheTableAfterTakingFromAFarChest); make_cost gives 115, was 1147.
	 */
	// Measured: passes at tick 21 (red: printed 1032, three SMELT and the furnace).
	@GameTest(maxTicks = 45)
	public void makeCostLeavesOutTheSmeltingOfIngotsAChestHolds(GameTestHelper helper) {
		chest(helper, new BlockPos(7, 1, 7), new ItemStack(Items.IRON_INGOT, 3));
		helper.setBlock(1, 1, 1, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		run(helper, bot, """
			import lib.cost
			import lib.stock
			from lib.get import make_cost
			c = lib.stock.chests(32)[0]
			t = find_nearest("crafting_table", radius=4)
			print(make_cost("bucket", 1) - lib.cost.walk(pos(), c) - lib.cost.walk(c, t))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "0"));
	}

	/**
	 * A crafting table by the bot, which carries a furnace and nine coal, 64 iron ingots and 8 raw iron in a chest across
	 * the area (#729): make_cost of 192 iron bars (72 ingots) counts the smelting of the 8 left and a furnace put down
	 * and mined back once, for one stack; it counted a furnace for each of the two stacks of the 72.
	 */
	// Measured: passes at tick 18 (red: printed 53, the furnace of a second stack).
	@GameTest(maxTicks = 45)
	public void makeCostCountsTheFurnaceOnlyForTheSmeltingAChestLeaves(GameTestHelper helper) {
		chest(helper, new BlockPos(7, 1, 7), new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.RAW_IRON, 8));
		helper.setBlock(1, 1, 1, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 9));
		run(helper, bot, """
			import lib.cost
			import lib.stock
			from lib.get import make_cost
			c = lib.stock.chests(32)[0]
			t = find_nearest("crafting_table", radius=4)
			walks = 2 * lib.cost.walk(pos(), c) + lib.cost.walk(c, t)
			print(make_cost("iron_bars", 192) - walks - 8 * lib.cost.SMELT - lib.cost.breaking(1, "furnace", "wooden"))
			""");
		failOnOtherPrint(helper, bot, "0");
		helper.succeedWhen(() -> printed(helper, bot, "0"));
	}

	/**
	 * No tree, crafting table or furnace in the area, the bot carries a raw iron and a coal (#729): make_cost of an iron
	 * ingot is None, as get cannot gather the logs of the crafting table the furnace takes; it costed the logs as if a
	 * tree were near, as for a raw item the item itself needs.
	 */
	// Measured: passes at tick 14 (red: printed 887).
	@GameTest(maxTicks = 30)
	public void makeCostIsNoneWhereTheFurnacesRawItemsHaveNoSource(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON));
		bot.getInventory().addItem(new ItemStack(Items.COAL));
		run(helper, bot, """
			from lib.get import make_cost
			print(make_cost("iron_ingot", 1))
			""");
		failOnOtherPrint(helper, bot, "None");
		helper.succeedWhen(() -> printed(helper, bot, "None"));
	}

	/**
	 * A crafting table by the bot, which carries an oak log, 4 sticks in a chest across the area, no tree (#737): the
	 * plan of a wooden pickaxe makes its 5 planks from the log and one more to gather; with the sticks from the chest the
	 * carried log makes the 3 planks left, so make_cost is the walk to the chest and back to the table. It made the sticks'
	 * plan alone, which used the carried log too, so the log counted twice and it costed one more to gather.
	 */
	// Measured: passes at tick 9 (red: printed 55).
	// Full runs: passes at up to tick 17 (#861).
	@GameTest(maxTicks = 40)
	public void makeCostCountsACarriedItemTwoStepsUseOnce(GameTestHelper helper) {
		chest(helper, new BlockPos(7, 1, 7), new ItemStack(Items.STICK, 4));
		helper.setBlock(1, 1, 1, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG));
		run(helper, bot, """
			import lib.cost
			import lib.stock
			from lib.get import make_cost
			c = lib.stock.chests(32)[0]
			t = find_nearest("crafting_table", radius=4)
			r = make_cost("wooden_pickaxe", 1)
			print(r if r is None else r - lib.cost.walk(pos(), c) - lib.cost.walk(c, t))
			""");
		failOnOtherPrint(helper, bot, "0");
		helper.succeedWhen(() -> printed(helper, bot, "0"));
	}

	/**
	 * Three oak planks in a chest, no crafting table, the bot carrying nothing (#759): a wooden pickaxe and its table take
	 * nine planks, so the plan gathers two logs and walks to the chest once. The table's planks and the pickaxe's are two
	 * steps of the plan, and the chest's planks counted for each.
	 */
	// Measured: passes at tick 15 (red: printed 110 {}, the chest walked to twice and its planks counted twice).
	// Full runs: passes at up to tick 17 (#861).
	@GameTest(maxTicks = 40)
	public void storedCountsAChestOnceForTwoStepsOfOneItem(GameTestHelper helper) {
		chest(helper, new BlockPos(7, 1, 7), new ItemStack(Items.OAK_PLANKS, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		run(helper, bot, """
			import lib.cost
			import lib.stock
			from lib.get import planned, stored
			c = lib.stock.chests(32)[0]
			tools = {}
			p = planned("minecraft:wooden_pickaxe", 1, needs("minecraft:wooden_pickaxe"), tools)
			ticks, last, gather, smelt = stored(p, "minecraft:wooden_pickaxe", 1, 32, tools)
			print(ticks - lib.cost.walk(pos(), c), gather)
			""");
		failOnOtherPrint(helper, bot, "0 {'minecraft:oak_log': 2}");
		helper.succeedWhen(() -> printed(helper, bot, "0 {'minecraft:oak_log': 2}"));
	}

	/**
	 * A furnace by the bot, a raw iron in a chest across the area, more than lib.craft.REACH from the furnace, coal and no
	 * pickaxe carried (#698): get takes the raw iron and walks back to the furnace to smelt it. From beside the chest it
	 * saw no furnace within reach and would put one down, which it cannot mine back without a pickaxe.
	 */
	// Measured: passes at tick 283 to 294 (red: printed False no_logs 0 at tick 51).
	@GameTest(maxTicks = 570)
	public void walksBackToTheFurnaceAfterTakingFromAFarChest(GameTestHelper helper) {
		Container chest = chest(helper, new BlockPos(7, 1, 7), new ItemStack(Items.RAW_IRON));
		helper.setBlock(1, 1, 1, Blocks.FURNACE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.COAL));
		run(helper, bot, """
			from lib.get import get
			r = get("iron_ingot")
			print(r["ok"], r["reason"], count("iron_ingot"))
			""");
		failOnOtherPrint(helper, bot, "True None 1");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True None 1");
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
	// Full runs: passes at up to tick 14 (#861).
	@GameTest(maxTicks = 30)
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
	// Full runs: passes at up to tick 18 (#861).
	@GameTest(maxTicks = 40)
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
	// Full runs: passes at up to tick 26 (#861).
	@GameTest(maxTicks = 60)
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
	// Full runs: passes at up to tick 124 (#861).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 250)
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
	// Full runs: passes at up to tick 86 (#861).
	@GameTest(maxTicks = 180)
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
	// Full runs: passes at up to tick 23 (#861).
	@GameTest(maxTicks = 50)
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
	// Full runs: passes at up to tick 23 (#861).
	@GameTest(maxTicks = 50)
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
	// Full runs: passes at up to tick 12 (#861).
	@GameTest(maxTicks = 30)
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
	// Full runs: passes at up to tick 13 (#861).
	@GameTest(maxTicks = 30)
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
	 * Emerald's trip follows its height in the mountains (lib.get.TRIP_Y, 236), not the flat DEEP (#796): from below it
	 * no stairs down, so one branch, as coal. lib.dig.best_y stays None, so ore does not dig for it outside mountains.
	 */
	@GameTest(maxTicks = 20)
	public void emeraldsTripFollowsItsMountainHeight(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		run(helper, bot, """
			import lib.dig
			from lib.get import SOURCES
			s = SOURCES["minecraft:emerald"]
			print(s.cost(1, 1)[0] - s.estimate == 37 * lib.dig.LENGTH)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "True"));
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
	 * An empty bot whose scans read radius 32 (#700): make_cost of three diamonds with a limit of 500 is None without the
	 * ore scan, as no iron pickaxe is carried or in a chest and its three iron ingots take three SMELT to make, more than
	 * the limit. Source.least, a vein in sight, was 30, so the diamond ore scan ran.
	 */
	@GameTest(maxTicks = 100)
	public void aLimitLeavesOutTheOreScanOfATierWhosePickaxeCostsMore(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		GameTests.scansFreely(helper, bot);
		GameTests.cost(helper, bot, "from lib.get import make_cost\n", "print(make_cost('diamond', 3, limit=500))\n",
			List.of("None"), "diamond limit", 25_000);
	}

	/**
	 * What one choose call costs an empty bot with no chest near (#522): measured at 27,000 instructions (14 ticks), most
	 * of it lib.craft.plan of each tier's tool (needs costs 3000); 45,600 (24 ticks) since the make cost counts the pickaxe
	 * a raw item needs (#529), a plan of each pickaxe tier more, and some 1000 of it the ore scans of Source.ticks; 48,200
	 * (26 ticks) since that tool may come from a chest (#536), a chest scan for each tool costed; 61,000-64,400 (32-35
	 * ticks, 52,000-56,000 before) since copper is a tier it makes (#578): 5,600 more, 3,600 of it its
	 * plan; 41,000-44,500 (22-24 ticks) since options costs the tiers lowest first, each up to the cheapest make so far
	 * (#595); 46,500 (26 ticks) since make_cost finds each plan's crafting table and furnace within reach (#630); 33,900
	 * (19 ticks, 44,600 before) since make_cost bounds a tier by its needs alone before lib.craft.plan (#710), which leaves
	 * out the plans of copper, iron and diamond; 34,500-34,700 since #728; 30,500 since make_cost puts the crafting table's
	 * steps, planned once, before a plan's (#737), where lib.craft.plan planned each again with the table; 35,479 alone,
	 * up to 35,880 in full runs (#941), once make_cost tells a log of one kind (#938, #941); 34,107 alone, 34,522 in a
	 * run of Get, WoodFarm and Craft, since options scans the chests once for itself and every tool_cost, and only_kind
	 * looks for any log once (#947); 24,970 since options bounds copper, iron and diamond by their heads' ingots or
	 * diamonds before their needs (least_head, #958; 34,829 before). Most of it is the four needs of 3000 each: the
	 * wooden and stone axe, the crafting table and the stone axe's wooden pickaxe. The limit leaves some 10% for full
	 * runs and small additions.
	 */
	@GameTest(maxTicks = 60)
	public void chooseCost(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		GameTests.cost(helper, bot, "from lib.get import choose\n", "print(choose('axe', 30, radius=8)[1:3])\n",
			List.of("('make', 'stone')"), "choose", 27_500);
	}

	/**
	 * What one choose of a pickaxe costs an empty bot with no chest near (#728): the stone pickaxe's cobblestone needs a
	 * wooden pickaxe, whose needs and plan options has as the wooden tier already, so make_cost takes them from its cache.
	 * Measured 28,500 instructions; 34,500 before, the two needs of the wooden pickaxe made again 6,000 of it; 27,200 since
	 * the crafting table's steps are planned once (#737); 32,237 alone, up to 32,640 in full runs (#941); 30,865 alone,
	 * 31,280 in a run of Get, WoodFarm and Craft, since options scans the chests once for itself and every tool_cost, and
	 * only_kind looks for any log once (#947); 21,658 since options bounds copper, iron and diamond before their needs
	 * (least_head, #958; 31,516 before). The limit leaves some 10% for full runs and small additions.
	 */
	@GameTest(maxTicks = 60)
	public void choosePickaxeCost(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		GameTests.cost(helper, bot, "from lib.get import choose\n", "print(choose('pickaxe', 30, radius=8)[1:3])\n",
			List.of("('make', 'stone')"), "choose pickaxe", 24_000);
	}

	/**
	 * Carrying raw iron leaves the diamond tier's least_head in place (#980): only the iron tier's falls to 0, as raw
	 * iron is its raw item. In optionsOfABigSmeltCost's setup the diamond tier is bounded at 790 + 12,000 work, under
	 * iron's make of 21,817, as making diamond pickaxes is cheaper there (18,839: 2 of them last 2000 uses, iron needs 8),
	 * so no bound could leave it out; least_head is not why make_cost plans it. Measured 742 instructions alone, 1,270 in a
	 * run of the Get class (the limit, from the lone run, failed there).
	 */
	@GameTest(maxTicks = 20)
	public void carriedIronLeavesTheDiamondTiersLeastHead(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 24));
		GameTests.cost(helper, bot, "from lib.get import least_head\n",
			"c = {}\nprint(least_head('pickaxe', 'diamond', 2, 8, c) > 0, least_head('pickaxe', 'iron', 8, 8, c))\n",
			List.of("True 0"), "least head", 1_400);
	}

	/**
	 * What options of a big smelt costs (#973): 8 iron pickaxes for 2000 uses, 24 ingots of the raw iron carried, with no
	 * chest near. #963 made make_cost's bound (least_make) leave out the logs a plan burns, so it was feared the bound
	 * would be weaker for big smelts. It is not: needs without fuel costs picks fuel by item count, so it burns coal (8
	 * items) for all but a remainder of a few items, at most a couple of logs' planks whatever the smelt's size (24 ingots
	 * burn 3 coal, 64 burn 8; 9 burn 6 planks), and charcoal never. Measured 84,740 instructions (43 ticks), 84,447 with
	 * #963 reverted, the same ways; the 293 more are the sum of the burns. Only a limit within those few logs' least over
	 * the bound costs more: make_cost of 3 iron pickaxes (6 planks burnt, bound 2,073, 2,183 before #963) with a limit
	 * of 2,120 ran 25,422 instructions, 1,820 before, as it planned the tier. ponytail: so the fuel's own bound (the
	 * cheaper of coal's least and the logs') is left out; it would win back part of a band some 110 ticks wide. The limit
	 * leaves some 10% for full runs.
	 */
	@GameTest(maxTicks = 90)
	public void optionsOfABigSmeltCost(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 24));
		GameTests.cost(helper, bot, "from lib.get import options\n",
			"print([o[2] for o in options('pickaxe', 2000, radius=8)])\n",
			List.of("['diamond', 'iron', 'stone', 'wooden']"), "options big smelt", 93_000);
	}

	/**
	 * Two diamond pickaxes cost less than eight iron ones in optionsOfABigSmeltCost's setup (#979), though the iron is
	 * carried and no diamond is near, and the parts are right: 18,839 = 12,000 work (2000 stone at 6 ticks) + 5,312 for
	 * the 6 diamonds (deep(-59) from the bot's y 7: 66 steps down and up at 58, 3,828, and a branch of 32 at 37, 1,184;
	 * then 50 each) + 1,342 for the iron pickaxe that mines them (3 of the raw iron smelted, 600, its furnace's
	 * cobblestone with a wooden pickaxe, its logs) + 185 (sticks' logs, crafting table); 21,817 = 16,000 work (8 ticks a
	 * stone) + 4,800 to smelt 24 ingots + 1,017 (logs for sticks and fuel, the furnace's cobblestone, the wooden pickaxe,
	 * stations). The diamond pickaxe's faster work (4,000) and its 21 smelts fewer (4,200) outweigh the trip down. From
	 * sea level (64) the trip is 3,306 more and iron ranks first. ponytail: the rarity of diamonds is left out (#789):
	 * the 6 come from one branch, where lib.dig.ore digs up to 4 (each 1,184 more), and the iron pickaxe's 250 uses are
	 * costed for the 6 diamonds, not the trip's blocks.
	 */
	@GameTest(maxTicks = 120)
	public void twoDiamondPickaxesCostLessThanEightIronOnesFromCarriedRawIron(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 24));
		run(helper, bot, """
			from lib.get import options, make_cost, SOURCES, work
			print(options('pickaxe', 2000, radius=8)[:2])
			print(pos().y, SOURCES['minecraft:diamond'].cost(6, 8), make_cost('minecraft:iron_pickaxe', 1, 8))
			print(work('pickaxe', 2000, 'diamond'), work('pickaxe', 2000, 'iron'))
			""");
		helper.succeedWhen(() -> printed(helper, bot, "[(18839, 'make', 'diamond', 2), (21817, 'make', 'iron', 8)]",
			"7 (5312, 'iron') 1342", "12000 16000"));
	}

	/**
	 * An empty bot with no chest near (#710): make_cost of an iron axe with a limit of 300 is None without
	 * lib.craft.plan and its second needs, as the needs of the axe alone cost more: SMELT for each of three iron ingots,
	 * the least of their raw iron and of the sticks' log, and the furnace and crafting table mined back (stations).
	 * Measured 1,400 instructions; 5,100 before, most of it the plan.
	 */
	@GameTest(maxTicks = 15)
	public void aLimitLeavesOutThePlanOfADearTier(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 4);
		GameTests.cost(helper, bot, "from lib.get import make_cost\n", "print(make_cost('iron_axe', 1, radius=8, limit=300))\n",
			List.of("None"), "iron axe limit", 2_500);
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
	// Full runs: passes at up to tick 63 (#861).
	@GameTest(maxTicks = 130)
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
	// Full runs: passes at up to tick 38 (#861).
	@GameTest(maxTicks = 80)
	public void getsCherrySaplingsFromItsLeaves(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES, false);
		leavesTest(helper, "cherry_sapling");
	}

	/**
	 * As {@link #getsCherrySaplingsFromItsLeaves}, the bot carrying shears (#609): mine would break the leaves with them
	 * into leaves (the data pack's cherry leaves drop themselves to shears or silk touch, as in the game), so the source
	 * breaks them by hand.
	 */
	// Full runs: passes at up to tick 38 (#861).
	@GameTest(maxTicks = 80)
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
	 * A birch tree, a crafting table and no oak (#933): oak slabs take oak planks, so get fails no_logs for the oak log once
	 * the birch logs it got for "any log" did not do, rather than felling the birch tree round after round.
	 */
	// Measured: passes at tick 149.
	@GameTest(maxTicks = 300)
	public void oakSlabsWithOnlyBirchAroundFailForTheOakLog(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
		helper.setBlock(2, 1, 5, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			r = get("oak_slab", 6)
			print(r["ok"], r["reason"], r["missing"], count("birch_log") <= 2)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "False no_logs {'minecraft:oak_log': 1} True"));
	}

	/**
	 * A birch tree, a crafting table and no oak (#938): make_cost of oak slabs, which take oak planks, is None, as get
	 * fails no_logs for the oak log there (oakSlabsWithOnlyBirchAroundFailForTheOakLog); it costed the log as any log.
	 * Sticks, which take any planks, still cost the birch.
	 */
	// Measured: passes at tick 15.
	@GameTest(maxTicks = 30)
	public void makeCostOfOakSlabsWithOnlyBirchAroundIsNone(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
		helper.setBlock(2, 1, 5, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, where(helper, 7) + """
			from lib.get import make_cost
			print(make_cost("oak_slab", 6), make_cost("stick", 4) is not None)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "None True"));
	}

	/**
	 * A crafting table and no tree or log anywhere (#941): make_cost of oak slabs is None, as get fails no_logs there;
	 * it costed the oak log as any log. Sticks, which take any planks, still cost a number.
	 */
	@GameTest(maxTicks = 30)
	public void makeCostOfOakSlabsWithNoLogsAroundIsNone(GameTestHelper helper) {
		helper.setBlock(2, 1, 5, Blocks.CRAFTING_TABLE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, where(helper, 7) + """
			from lib.get import make_cost
			print(make_cost("oak_slab", 6), make_cost("stick", 4) is not None)
			""");
		helper.succeedWhen(() -> printed(helper, bot, "None True"));
	}

	/**
	 * A birch leaves block at y 7 on a log on a stone column, out of reach from the floor (#654): the bot pillars with
	 * its dirt to break it for a sapling, and takes the pillar back, so no dirt stands in the area after.
	 */
	// Measured: passes at tick 75.
	// Full runs: passes at up to tick 88 (#861).
	@GameTest(maxTicks = 180)
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
	 * drop an apple (1 in 200 in the game) to a bot tagged {@link #APPLES}, as leavesTest's is (#790).
	 */
	// Full runs: passes at up to tick 40 (#861).
	@GameTest(maxTicks = 80)
	public void getsApplesFromDarkOakLeaves(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES, false);
		leavesTest(helper, "apple");
	}

	/** The entity tag of a bot that gets an apple from every dark oak leaves block it breaks (the game test data pack). */
	private static final String APPLES = "antcraft_apples";

	/**
	 * Dark oak leaves drop the game's loot but its 1 apple in 200 to any other bot and when they decay (#790): with an
	 * apple each, dark oak farm tests picked up apples for some 300 ticks (#779). A bot tagged {@link #APPLES} gets one
	 * each. With no apple at all, the test does not wait on chance.
	 */
	@GameTest(maxTicks = 10)
	public void darkOakLeavesDropAnAppleOnlyToABotTaggedForIt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
		BlockState leaves = Blocks.DARK_OAK_LEAVES.defaultBlockState();
		for (int i = 0; i < 20; i++) {
			helper.assertFalse(Block.getDrops(leaves, helper.getLevel(), at, null, bot, ItemStack.EMPTY).stream()
				.anyMatch(s -> s.is(Items.APPLE)), "an apple to an untagged bot");
			helper.assertFalse(Block.getDrops(leaves, helper.getLevel(), at, null).stream().anyMatch(s -> s.is(Items.APPLE)),
				"an apple with no bot");
		}
		bot.addTag(APPLES);
		helper.assertTrue(Block.getDrops(leaves, helper.getLevel(), at, null, bot, ItemStack.EMPTY).stream()
			.anyMatch(s -> s.is(Items.APPLE)), "no apple to a tagged bot");
		helper.succeed();
	}

	/**
	 * A bot carrying only carried by the tree gets two of item with radius 8 and prints "True 2", with no warnings. It is
	 * tagged {@link #APPLES}.
	 */
	private static void leavesTest(GameTestHelper helper, String item, ItemStack... carried) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.addTag(APPLES);
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

	/**
	 * A mangrove of {@link TreesGameTest#plantTree} with two ripe propagules (age 4) and one unripe hanging from its leaves,
	 * and a ripe one planted on dirt (#650): get picks the two ripe hanging ones, the only ones whose loot drops a
	 * propagule, and leaves the unripe and the planted one.
	 */
	// Measured: passes at tick 27 (red: "False 0", no source).
	@GameTest(maxTicks = 60)
	public void getMangrovePropagulesPicksTheRipeHangingOnes(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(4, 1, 4), Blocks.MANGROVE_LOG, Blocks.MANGROVE_LEAVES, false);
		BlockState hanging = Blocks.MANGROVE_PROPAGULE.defaultBlockState().setValue(MangrovePropaguleBlock.HANGING, true);
		helper.setBlock(6, 2, 4, hanging.setValue(MangrovePropaguleBlock.AGE, 4));
		helper.setBlock(4, 2, 6, hanging.setValue(MangrovePropaguleBlock.AGE, 4));
		helper.setBlock(2, 2, 4, hanging.setValue(MangrovePropaguleBlock.AGE, 1));
		helper.setBlock(1, 0, 7, Blocks.DIRT);
		helper.setBlock(1, 1, 7, Blocks.MANGROVE_PROPAGULE.defaultBlockState().setValue(MangrovePropaguleBlock.AGE, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, where(helper, 7) + """
			from lib.get import get
			r = get("mangrove_propagule", 2)
			print(r["ok"], count("mangrove_propagule"))
			""");
		failOnOtherPrint(helper, bot, "True 2");
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 2");
			helper.assertBlockPresent(Blocks.MANGROVE_PROPAGULE, 2, 2, 4);
			helper.assertBlockPresent(Blocks.MANGROVE_PROPAGULE, 1, 1, 7);
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
	// Full runs: passes at up to tick 372 (#861).
	@GameTest(maxTicks = 750)
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
