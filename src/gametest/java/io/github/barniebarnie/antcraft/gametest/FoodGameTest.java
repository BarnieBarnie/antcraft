package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.Memory;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
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
}
