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
import net.minecraft.world.item.Item;
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

	/** Oak saplings in the nearer chest, birch in the farther: birch has no roof, so it is cheaper and wins (#613). */
	// Measured: passes at tick 4.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 12)
	public void choosesTheCheaperOfTwoStoredKinds(GameTestHelper helper) {
		chest(helper, CHEST, Items.OAK_SAPLING);
		chest(helper, new BlockPos(12, 1, 6), Items.BIRCH_SAPLING);
		AntBot bot = run(helper, true);
		helper.succeedWhen(() -> assertStarted(helper, bot, "forester: birch_sapling, saplings in a chest 6", "birch_farm 1/4: kit"));
	}

	/** An oak nearer than a birch: birch has no roof, so it is cheaper and wins (#613). */
	// Measured: passes at tick 5.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 12)
	public void choosesTheCheaperOfTwoNearbyTrees(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(12, 1, 8), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		TreesGameTest.plantTree(helper, BIRCH, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
		AntBot bot = run(helper, true);
		helper.succeedWhen(() -> assertStarted(helper, bot, "forester: birch_sapling, a tree 5", "birch_farm 1/4: kit"));
	}

	/**
	 * candidates looks for the trees of all kinds in one scan of the logs, not one next_tree a kind (#613): with an oak and
	 * a birch about, it costs fewer than 5,000 instructions over importing the job.
	 * Measured: 6,663 with a next_tree a kind, 3,766 now.
	 */
	// Measured: passes at tick 5.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 12)
	public void candidatesScansTheLogsOnce(GameTestHelper helper) {
		TreesGameTest.plantTree(helper, new BlockPos(12, 1, 8), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		TreesGameTest.plantTree(helper, BIRCH, Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES);
		ScriptRun call = BotScripts.run(helper.spawn(ModEntities.ANT_BOT, 12, 1, 12), "import jobs.forester\nprint(jobs.forester.candidates(8))\n");
		ScriptRun bare = BotScripts.run(helper.spawn(ModEntities.ANT_BOT, 11, 1, 12), "import jobs.forester\n");
		helper.succeedWhen(() -> {
			helper.assertTrue(call.state() == ScriptRun.State.FINISHED && bare.state() == ScriptRun.State.FINISHED, "scripts " + call.state() + " " + bare.state());
			long cost = call.executed() - bare.executed();
			helper.assertTrue(cost < 5000, "candidates cost " + cost + " instructions");
		});
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

	/** A chest at pos holding four of item. */
	private static void chest(GameTestHelper helper, BlockPos pos, Item item) {
		helper.setBlock(pos, Blocks.CHEST);
		helper.getBlockEntity(pos, ChestBlockEntity.class).setItem(0, new ItemStack(item, 4));
	}

	/** Runs jobs.forester 8 with the bot at (12, 1, 12); with strict, fails the test at once on a warning or error. */
	private static AntBot run(GameTestHelper helper, boolean strict) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 12);
		List<String> said = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.forester 8");
		helper.assertTrue(bot.getScript() != null, "not run: " + said);
		if (strict) {
			BotScripts.failOnWarning(helper, bot);
		}
		return bot;
	}

	/** The bot said its choice, starting with choice, and then started the farm's first step. */
	private static void assertStarted(GameTestHelper helper, AntBot bot, String choice, String step) {
		List<String> said = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(said.stream().anyMatch(s -> s.startsWith(choice)), "said " + said);
		helper.assertTrue(said.contains(step), "said " + said);
	}
}
