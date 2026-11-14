package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code lib/bootstrap.py} and {@code jobs/bootstrap.py} (#511): an empty bot gets the kit from what is around, run as
 * {@code jobs.bootstrap}; a neighbouring test's chest or tree is out of every scan (GameTests.hides).
 */
public class BootstrapGameTest implements CustomTestMethodInvoker {
	/** lib.bootstrap.KIT. */
	private static final Map<Item, Integer> KIT = Map.of(Items.STONE_PICKAXE, 1, Items.STONE_AXE, 1, Items.STONE_SHOVEL, 1,
		Items.STONE_SWORD, 1, Items.CRAFTING_TABLE, 1, Items.FURNACE, 1, Items.TORCH, 16);
	private static final BlockPos CHEST = new BlockPos(1, 1, 2);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Four oaks of four logs, each under a leaf, and a wall of 24 stone. */
	private static void woods(GameTestHelper helper) {
		for (BlockPos base : List.of(new BlockPos(1, 1, 6), new BlockPos(4, 1, 6), new BlockPos(7, 1, 6), new BlockPos(6, 1, 3))) {
			helper.setBlock(base.below(), Blocks.DIRT);
			for (int y = 0; y < 4; y++) {
				helper.setBlock(base.above(y), Blocks.OAK_LOG);
			}
			helper.setBlock(base.above(4), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		}
		for (int x = 0; x <= 7; x++) {
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(x, y, 0, Blocks.STONE);
			}
		}
	}

	/** An empty bot by trees and stone: the job ends with every item of the kit. */
	@GameTest(maxTicks = 4000)
	public void anEmptyBotGetsTheKit(GameTestHelper helper) {
		woods(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		run(helper, bot);
		helper.succeedWhen(() -> {
			finished(helper, bot);
			hasKit(helper, bot);
		});
	}

	/** An iron pickaxe in a chest: it is borrowed and used instead of a stone one, and back in the chest at the end. */
	@GameTest(maxTicks = 3200)
	public void borrowsAChestPickaxeAndGivesItBack(GameTestHelper helper) {
		woods(helper);
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.IRON_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		run(helper, bot);
		helper.succeedWhen(() -> {
			finished(helper, bot);
			ItemStack pickaxe = chest.getItem(0);
			helper.assertTrue(pickaxe.is(Items.IRON_PICKAXE) && pickaxe.getDamageValue() > 0, "the chest holds " + pickaxe);
			helper.assertTrue(GameTests.carried(bot, Items.STONE_PICKAXE) == 0, "a stone pickaxe was made");
		});
	}

	private static void run(GameTestHelper helper, AntBot bot) {
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.bootstrap");
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[jobs.bootstrap, 1]")), "run answered " + started);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			List<String> log = BotScripts.log(bot, Output.Level.DEBUG);
			if (run != null && run.state() == ScriptRun.State.FAILED || log.stream().anyMatch(l -> l.startsWith("no kit"))) {
				helper.fail("script failed: " + log + ", carries " + bot.getInventory().getItems() + " and " + bot.getMainHandItem());
			}
		});
	}

	/** The job said its kit is ready and gave back what it borrowed: idle runs after it. */
	private static void finished(GameTestHelper helper, AntBot bot) {
		List<String> log = BotScripts.log(bot, Output.Level.DEBUG);
		helper.assertTrue(log.contains("kit ready") && log.contains("run idle"), "log " + log);
	}

	private static void hasKit(GameTestHelper helper, AntBot bot) {
		KIT.forEach((item, n) -> helper.assertTrue(GameTests.carried(bot, item) >= n,
			"carries " + GameTests.carried(bot, item) + " " + item + ", log " + BotScripts.log(bot, Output.Level.DEBUG)));
	}
}
