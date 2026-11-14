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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * The job {@code jobs/forester.py} (#607; docs/cookbook/wood-farm.md, A forester): the bot in the middle of the 24x16x24
 * {@code wide_tall} structure at (12, 1, 12), farming radius 8 (a neighbouring test's chests and trees stay out anyway: a
 * bot's scans see only its test area, {@link GameTests#hides}). The farm runs forever, so the tests end once the farm's first step has started.
 */
public class ForesterGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CHEST = new BlockPos(9, 1, 12);
	private static final BlockPos BIRCH = new BlockPos(12, 1, 17);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Spruce saplings in a chest and a birch nearby: a stored sapling comes first, so it farms spruce. */
	// Measured: passes at tick 4 to 5.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 12)
	public void choosesTheStoredSaplingOverANearbyTree(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.getBlockEntity(CHEST, ChestBlockEntity.class).setItem(0, new ItemStack(Items.SPRUCE_SAPLING, 4));
		TreesGameTest.plantTree(helper, BIRCH, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
		AntBot bot = run(helper, true);
		helper.succeedWhen(() -> assertStarted(helper, bot, "forester: spruce_sapling, saplings in a chest", "spruce_farm 1/4: kit"));
	}

	/** No saplings in a chest, a birch nearby: it farms birch. */
	// Measured: passes at tick 4 to 5.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 12)
	public void choosesANearbyTreeWithoutStoredSaplings(GameTestHelper helper) {
		helper.setBlock(CHEST, Blocks.CHEST);
		helper.getBlockEntity(CHEST, ChestBlockEntity.class).setItem(0, new ItemStack(Items.DIRT, 4));
		TreesGameTest.plantTree(helper, BIRCH, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
		AntBot bot = run(helper, true);
		helper.succeedWhen(() -> assertStarted(helper, bot, "forester: birch_sapling, a tree", "birch_farm 1/4: kit"));
	}

	/** Neither saplings nor trees within its radius: it warns no_saplings and stops. */
	// Measured: passes at tick 4 to 5.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 12)
	public void nothingAroundWarnsNoSaplings(GameTestHelper helper) {
		AntBot bot = run(helper, false);
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScript().state() == ScriptRun.State.FINISHED, "not finished: " + BotScripts.log(bot, Output.Level.DEBUG));
			helper.assertTrue(BotScripts.warnings(bot).stream().anyMatch(w -> w.contains("no_saplings")), "warned " + BotScripts.warnings(bot));
		});
	}

	/** Runs jobs.forester 8 with the bot at (12, 1, 12); with strict, fails the test at once on a warning or error. */
	private static AntBot run(GameTestHelper helper, boolean strict) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 12);
		List<String> said = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.forester 8");
		helper.assertTrue(bot.getScript() != null, "not run: " + said);
		helper.onEachTick(() -> {
			if (!strict) {
				return;
			}
			ScriptRun script = bot.getScript();
			if (script != null && script.state() == ScriptRun.State.FAILED || !BotScripts.warnings(bot).isEmpty()) {
				helper.fail("failed or warned: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		return bot;
	}

	/** The bot said its choice, starting with choice, and then started the farm's first step. */
	private static void assertStarted(GameTestHelper helper, AntBot bot, String choice, String step) {
		List<String> said = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(said.stream().anyMatch(s -> s.startsWith(choice)), "said " + said);
		helper.assertTrue(said.contains(step), "said " + said);
	}
}
