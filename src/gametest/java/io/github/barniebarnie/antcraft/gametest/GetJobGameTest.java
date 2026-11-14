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
 * The default job {@code jobs/get.py} (#512), run by its name {@code jobs.get} as a player would. A neighbouring test's
 * tree, stone or chest is out of every scan (GameTests.hides).
 */
public class GetJobGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * An empty bot by the oak of {@link TreesGameTest}, eighteen stone and three exposed iron ore: logs by hand, a wooden
	 * pickaxe, cobblestone, a stone pickaxe, raw iron, a furnace and the smelting, then the iron pickaxe.
	 */
	@GameTest(maxTicks = 3100)
	public void ironPickaxeFromNothing(GameTestHelper helper) {
		TreesGameTest.plantTree(helper);
		for (int x = 1; x <= 3; x++) {
			for (int z = 4; z <= 6; z++) {
				for (int y = 1; y <= 2; y++) {
					helper.setBlock(x, y, z, Blocks.STONE);
				}
			}
		}
		helper.setBlock(6, 1, 1, Blocks.IRON_ORE);
		helper.setBlock(6, 2, 1, Blocks.IRON_ORE);
		helper.setBlock(6, 1, 2, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		run(helper, bot, "iron_pickaxe 1", "got 1 iron_pickaxe",
			() -> helper.assertTrue(GameTests.carried(bot, Items.IRON_PICKAXE) == 1, "no iron pickaxe"));
	}

	/** Feathers come from no source and no recipe: no_recipe, with what is missing. */
	@GameTest(maxTicks = 20)
	public void unknownRawItemFailsWithNoRecipe(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 1);
		run(helper, bot, "feather 2", "no feather: no_recipe {'minecraft:feather': 2}", () -> { });
	}

	/** The item asked for, taken from a chest, is kept: only what was borrowed on the way goes back. */
	@GameTest(maxTicks = 20)
	public void keepsTheItemItTookFromAChest(GameTestHelper helper) {
		BlockPos at = new BlockPos(6, 1, 3);
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.IRON_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		run(helper, bot, "iron_pickaxe 1", "got 1 iron_pickaxe", () -> {
			helper.assertTrue(GameTests.carried(bot, Items.IRON_PICKAXE) == 1, "no iron pickaxe");
			helper.assertTrue(chest.isEmpty(), "the chest is not empty");
		});
	}

	/**
	 * 5 oak planks borrowed by an earlier script, then jobs.get of 2 of #minecraft:planks: the job keeps 2, matched by the
	 * selector, and gives the other 3 back (#535).
	 */
	@GameTest(maxTicks = 20)
	public void keepsOnlyTheNItAskedFor(GameTestHelper helper) {
		BlockPos at = new BlockPos(6, 1, 3);
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BlockPos p = helper.absolutePos(at);
		String borrow = BotScripts.libraryScript(helper, "get_job_", "from lib.borrow import borrow\nborrow(Pos(%d, %d, %d), \"oak_planks\", 5)\n"
			.formatted(p.getX(), p.getY(), p.getZ()));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + borrow);
		boolean[] started = {false};
		String says = "got 2 #minecraft:planks";
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (!started[0] && run != null && run.state() == ScriptRun.State.FINISHED) {
				started[0] = true;
				BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.get #minecraft:planks 2");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(started[0] && BotScripts.log(bot, Output.Level.PRINT).equals(List.of(says)),
				"said " + BotScripts.log(bot, Output.Level.PRINT));
			int planks = GameTests.carried(bot, Items.OAK_PLANKS);
			helper.assertTrue(planks == 2, "planks " + planks);
			helper.assertTrue(chest.countItem(Items.OAK_PLANKS) == 3, "planks in the chest " + chest.countItem(Items.OAK_PLANKS));
		});
	}

	/**
	 * A bot that owns 1 oak plank and borrowed 3 in an earlier script, then jobs.get of 2: it ends with 2, its own and 1
	 * kept, and gives 2 back (#537).
	 */
	@GameTest(maxTicks = 20)
	public void keepsOnlyWhatItLacksBesidesItsOwn(GameTestHelper helper) {
		BlockPos at = new BlockPos(6, 1, 3);
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS));
		BlockPos p = helper.absolutePos(at);
		String borrow = BotScripts.libraryScript(helper, "get_job_", "from lib.borrow import borrow\nborrow(Pos(%d, %d, %d), \"oak_planks\", 3)\n"
			.formatted(p.getX(), p.getY(), p.getZ()));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + borrow);
		boolean[] started = {false};
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (!started[0] && run != null && run.state() == ScriptRun.State.FINISHED) {
				started[0] = true;
				BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.get oak_planks 2");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(started[0] && BotScripts.log(bot, Output.Level.PRINT).equals(List.of("got 2 oak_planks")),
				"said " + BotScripts.log(bot, Output.Level.PRINT));
			int planks = GameTests.carried(bot, Items.OAK_PLANKS);
			helper.assertTrue(planks == 2, "planks " + planks);
			helper.assertTrue(chest.countItem(Items.OAK_PLANKS) == 3, "planks in the chest " + chest.countItem(Items.OAK_PLANKS));
		});
	}

	/**
	 * Runs jobs.get with args by the command; the test succeeds once the bot said {@code says} and {@code then} holds, and
	 * fails at once when the script fails or says something else.
	 */
	private static void run(GameTestHelper helper, AntBot bot, String args, String says, Runnable then) {
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.get " + args);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[jobs.get, 1]")), "run answered " + started);
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			List<String> said = BotScripts.log(bot, Output.Level.PRINT);
			if (!said.isEmpty() && !said.equals(List.of(says))) {
				helper.fail("said " + said + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of(says)), "has not said " + says);
			then.run();
		});
	}
}
