package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.Memory;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Bots keep themselves fed (#703, docs/reference/modules/lib/food.md): lib.food's upkeep in the long-running loops. */
public class FoodGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Founds a colony with its core at (6, 1, 1), radius 7, and gives a hungry member at (6, 1, 4): food 5, no saturation. */
	private static AntBot hungryMember(GameTestHelper helper) {
		helper.setBlock(6, 1, 1, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(6, 1, 1))).orElseThrow();
		colony.setRadius(7);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 1, 4);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.getFood().setFoodLevel(5);
		bot.getFood().setSaturation(0);
		return bot;
	}

	private static void runDefault(GameTestHelper helper, AntBot bot, String script) {
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + script);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + script + ", 1]")), "run answered " + started);
	}

	private static Container container(GameTestHelper helper, int x, int y, int z) {
		return (Container) Objects.requireNonNull(helper.getBlockEntity(new BlockPos(x, y, z), BlockEntity.class));
	}

	/** A hungry hauler carrying two bread eats both (5 + 5 + 5 food) and still hauls the logs to the core. */
	@GameTest(maxTicks = 200)
	public void aHungryHaulerEatsItsBreadAndKeepsHauling(GameTestHelper helper) {
		AntBot hauler = hungryMember(helper);
		hauler.getInventory().addItem(new ItemStack(Items.BREAD, 2));
		helper.setBlock(1, 1, 1, Blocks.CHEST);
		container(helper, 1, 1, 1).setItem(0, new ItemStack(Items.BIRCH_LOG, 3));
		Container core = container(helper, 6, 1, 1);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(6, 1, 1))).orElseThrow();
		PyDict data = new PyDict();
		data.put("item", "#minecraft:logs");
		colony.jobs().post("haul", Memory.encode(data), hauler.getUUID());
		runDefault(helper, hauler, "colony.hauler");
		BotScripts.failOnWarning(helper, hauler);
		helper.succeedWhen(() -> {
			helper.assertTrue(core.countItem(Items.BIRCH_LOG) == 3, "birch logs in the core: " + core.countItem(Items.BIRCH_LOG));
			helper.assertValueEqual(hauler.getFood().food(), 15, "food");
			helper.assertValueEqual(hauler.getInventory().countItem(Items.BREAD), 0, "bread left");
		});
	}

	/** An idle bot (no script: idle.py) at food 5 eats the two bread it carries (#723). */
	@GameTest(maxTicks = 100)
	public void anIdleHungryBotEatsItsBread(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.setHome(helper.absolutePos(new BlockPos(3, 1, 3)));
		bot.getFood().setFoodLevel(5);
		bot.getFood().setSaturation(0);
		bot.getInventory().addItem(new ItemStack(Items.BREAD, 2));
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(bot.getFood().food(), 15, "food");
			helper.assertValueEqual(bot.getInventory().countItem(Items.BREAD), 0, "bread left");
		});
	}

	/**
	 * An idle bot at food 10 eats the rabbit stew it carries (10 points), a food the hand-written FOODS left out: FOODS now
	 * comes from the game's food components (#722). It keeps the bowl.
	 */
	@GameTest(maxTicks = 100)
	public void anIdleHungryBotEatsAFoodOfTheGamesFoodComponents(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.setHome(helper.absolutePos(new BlockPos(3, 1, 3)));
		bot.getFood().setFoodLevel(10);
		bot.getFood().setSaturation(0);
		bot.getInventory().addItem(new ItemStack(Items.RABBIT_STEW));
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(bot.getFood().food(), 20, "food");
			helper.assertValueEqual(bot.getInventory().countItem(Items.RABBIT_STEW), 0, "rabbit stew left");
			helper.assertValueEqual(bot.getInventory().countItem(Items.BOWL), 1, "bowls");
		});
	}

	/** A hungry hauler with no food makes bread from the core's wheat at the crafting table beside it, and eats. */
	@GameTest(maxTicks = 250)
	public void aHungryBotWithNoFoodMakesBreadFromTheCoresWheat(GameTestHelper helper) {
		AntBot hauler = hungryMember(helper);
		helper.setBlock(4, 1, 1, Blocks.CRAFTING_TABLE);
		Container core = container(helper, 6, 1, 1);
		core.setItem(0, new ItemStack(Items.WHEAT, 6));
		runDefault(helper, hauler, "colony.hauler");
		BotScripts.failOnError(helper, hauler);
		helper.succeedWhen(() -> {
			helper.assertTrue(hauler.getFood().food() > 5, "food " + hauler.getFood().food());
			helper.assertValueEqual(core.countItem(Items.WHEAT), 0, "wheat left in the core");
		});
	}

	/**
	 * A hungry bot with a stone sword and a furnace kit (a furnace, a wooden pickaxe to take it back and coal) and no food
	 * kills the cow 5 blocks off, cooks its beef and eats (#719): food 12 to 20 only on cooked beef (8), as raw beef (3)
	 * twice stops at 18. The default radius, as entities are kept to the test's area (#742). Measured: 381 to 384 ticks with one beef
	 * dropped, 579 with two (646 to 655 before) and 787 with three, cooked in one smelt (200 ticks each, #741); one at a time three took some 920. A cow drops
	 * at most three, so 787 is the slowest pass: maxTicks about twice it.
	 */
	@GameTest(maxTicks = 1600)
	public void aHungryBotWithASwordHuntsACowAndEatsItsCookedBeef(GameTestHelper helper) {
		Cow cow = helper.spawn(EntityTypes.COW, 6, 1, 3);
		cow.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		bot.getFood().setFoodLevel(12);
		bot.getFood().setSaturation(0);
		bot.getInventory().addItem(new ItemStack(Items.STONE_SWORD));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 2));
		ScriptRun run = BotScripts.run(bot, "import lib.food\nlib.food.upkeep()\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertTrue(cow.isDeadOrDying(), "cow alive " + BotScripts.warnings(bot));
			helper.assertValueEqual(bot.getFood().food(), 20, "food");
		});
	}

	/**
	 * A bot carrying three raw beef and a furnace kit, and no animal near, cooks all three in one smelt (#741): its cooked
	 * beef goes from none to three at once, never one or two, as smelt takes the output when the input is all smelted.
	 */
	@GameTest(maxTicks = 1400)
	public void ensureFoodCooksTheRawBeefCarriedInOneSmelt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.BEEF, 3));
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 2));
		ScriptRun run = BotScripts.run(bot, "import lib.food\nlib.food.ensure_food(4, 6)\n");
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			int cooked = bot.getInventory().countItem(Items.COOKED_BEEF);
			if (cooked == 1 || cooked == 2) {
				helper.fail("cooked " + cooked + " beef of three: not in one smelt");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertValueEqual(bot.getInventory().countItem(Items.COOKED_BEEF), 3, "cooked beef");
			helper.assertValueEqual(bot.getInventory().countItem(Items.BEEF), 0, "raw beef");
		});
	}

	/** A hungry member of a colony that has no food, nor wheat or a table to make some, warns so the player sees why (#704). */
	@GameTest(maxTicks = 150)
	public void aHungryMemberOfAColonyWithNoFoodWarns(GameTestHelper helper) {
		AntBot hauler = hungryMember(helper);
		runDefault(helper, hauler, "colony.hauler");
		BotScripts.failOnError(helper, hauler);
		helper.succeedWhen(() -> helper.assertTrue(BotScripts.warnings(hauler).stream().anyMatch(w -> w.contains("no food")),
				"warnings " + BotScripts.warnings(hauler)));
	}

	/**
	 * A hungry bot with no food anywhere warns once, not every RETRY ticks (#743): the second try, RETRY on, finds none
	 * again and stays quiet, as the bot has carried no food since it warned.
	 */
	// Measured: passes at tick 60.
	@GameTest(maxTicks = 120)
	public void aHungryBotWithNoFoodWarnsOnce(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getFood().setFoodLevel(5);
		ScriptRun run = BotScripts.run(bot, "import lib.food\nlib.food.upkeep()\nlib.food.TRIED[\"at\"] = None\nlib.food.upkeep()\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertValueEqual(BotScripts.warnings(bot).stream().filter(w -> w.contains("no food")).count(), 1L,
					"no food warnings " + BotScripts.warnings(bot));
		});
	}

	/** A farmer bakes the core's potato in the colony's furnace with the core's logs and keeps the baked potato in the core (#704). */
	@GameTest(maxTicks = 420)
	public void aFarmerBakesTheCoresPotatoes(GameTestHelper helper) {
		AntBot farmer = hungryMember(helper);
		farmer.getFood().setFoodLevel(20);
		helper.setBlock(4, 1, 1, Blocks.FURNACE);
		Container core = container(helper, 6, 1, 1);
		core.setItem(0, new ItemStack(Items.POTATO));
		core.setItem(1, new ItemStack(Items.OAK_LOG, 3));
		runDefault(helper, farmer, "colony.farmer");
		BotScripts.failOnWarning(helper, farmer);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(core.countItem(Items.BAKED_POTATO), 1, "baked potatoes in the core");
			helper.assertValueEqual(core.countItem(Items.OAK_LOG), 2, "logs back in the core");
		});
	}

	/** A farmer crafts bowls from the core's planks where the core has beetroot but no bowls, and makes beetroot soup (#744). Measured: 11 ticks. */
	@GameTest(maxTicks = 30)
	public void aFarmerCraftsBowlsForBeetrootSoup(GameTestHelper helper) {
		AntBot farmer = hungryMember(helper);
		farmer.getFood().setFoodLevel(20);
		helper.setBlock(4, 1, 1, Blocks.CRAFTING_TABLE);
		Container core = container(helper, 6, 1, 1);
		core.setItem(0, new ItemStack(Items.BEETROOT, 6));
		core.setItem(1, new ItemStack(Items.OAK_PLANKS, 3));
		runDefault(helper, farmer, "colony.farmer");
		BotScripts.failOnWarning(helper, farmer);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(core.countItem(Items.BEETROOT_SOUP), 1, "beetroot soup in the core");
			helper.assertValueEqual(core.countItem(Items.BOWL), 3, "bowls in the core");
			helper.assertValueEqual(core.countItem(Items.OAK_PLANKS), 0, "planks left in the core");
		});
	}

	/**
	 * A farmer with wheat to bake but no crafting table in reach, nor one or logs in the core or trees in the colony, warns
	 * that it has none and tries again only after lib.food.RETRY (1200) ticks, not every round of 400 ticks (#744): 3 tries by tick 950 before.
	 */
	@GameTest(maxTicks = 1000)
	public void aFarmerWithNoTableTriesOncePerRetry(GameTestHelper helper) {
		AntBot farmer = hungryMember(helper);
		farmer.getFood().setFoodLevel(20);
		Container core = container(helper, 6, 1, 1);
		core.setItem(0, new ItemStack(Items.WHEAT, 3));
		runDefault(helper, farmer, "colony.farmer");
		BotScripts.failOnError(helper, farmer);
		helper.runAtTickTime(950, () -> {
			List<String> tries = BotScripts.warnings(farmer).stream().filter(w -> w.contains("crafting_table")).toList();
			helper.assertValueEqual(tries.size(), 1, "warnings about the crafting table " + BotScripts.warnings(farmer));
			helper.succeed();
		});
	}

	/** A farmer makes beetroot soup from the core's 6 beetroot and bowl at the colony's crafting table and keeps it in the core (#721). Measured: 9 ticks. */
	@GameTest(maxTicks = 40)
	public void aFarmerMakesBeetrootSoupFromTheCoresBeetroot(GameTestHelper helper) {
		AntBot farmer = hungryMember(helper);
		farmer.getFood().setFoodLevel(20);
		helper.setBlock(4, 1, 1, Blocks.CRAFTING_TABLE);
		Container core = container(helper, 6, 1, 1);
		core.setItem(0, new ItemStack(Items.BEETROOT, 6));
		core.setItem(1, new ItemStack(Items.BOWL));
		runDefault(helper, farmer, "colony.farmer");
		BotScripts.failOnWarning(helper, farmer);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(core.countItem(Items.BEETROOT_SOUP), 1, "beetroot soup in the core");
			helper.assertValueEqual(core.countItem(Items.BEETROOT), 0, "beetroot left in the core");
		});
	}

	/**
	 * A farmer with no crafting table or furnace in reach places the core's table and furnace beside the core and bakes the
	 * core's wheat and potato into bread and a baked potato there (#721). Measured: 245 ticks.
	 */
	@GameTest(maxTicks = 500)
	public void aFarmerPlacesTheCoresTableAndFurnaceToBake(GameTestHelper helper) {
		AntBot farmer = hungryMember(helper);
		farmer.getFood().setFoodLevel(20);
		Container core = container(helper, 6, 1, 1);
		core.setItem(0, new ItemStack(Items.WHEAT, 3));
		core.setItem(1, new ItemStack(Items.POTATO));
		core.setItem(2, new ItemStack(Items.OAK_LOG, 3));
		core.setItem(3, new ItemStack(Items.CRAFTING_TABLE));
		core.setItem(4, new ItemStack(Items.FURNACE));
		runDefault(helper, farmer, "colony.farmer");
		BotScripts.failOnWarning(helper, farmer);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(core.countItem(Items.BREAD), 1, "bread in the core");
			helper.assertValueEqual(core.countItem(Items.BAKED_POTATO), 1, "baked potatoes in the core");
			helper.assertValueEqual(core.countItem(Items.CRAFTING_TABLE) + core.countItem(Items.FURNACE), 0, "stations left in the core");
		});
	}
}
