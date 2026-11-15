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
	 * twice stops at 18. Radius 6, as entities are not kept to the test's area. Measured: 378 ticks with one beef dropped,
	 * 646 to 655 with two, as ensure_food cooks the raw beef one at a time (200 ticks each); three would take some 920.
	 */
	@GameTest(maxTicks = 1300)
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
		ScriptRun run = BotScripts.run(bot, "import lib.food\nlib.food.upkeep(6)\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertTrue(cow.isDeadOrDying(), "cow alive " + BotScripts.warnings(bot));
			helper.assertValueEqual(bot.getFood().food(), 20, "food");
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
