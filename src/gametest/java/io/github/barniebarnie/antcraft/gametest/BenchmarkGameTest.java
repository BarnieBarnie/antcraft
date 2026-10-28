package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bot benchmark: {@link #BOTS} bots in a 48x48 area, each running {@link #SCRIPT} (felling the trees, then mining the
 * ores they can find, then going home), for {@link #MEASURED_TICKS} ticks. Logs the average and worst server tick and
 * where the time went (see {@link TickProfiler}), and fails if the average tick reaches {@link #TICK_BUDGET_MS}. The layout
 * is fixed, ores included (seeded), so runs compare. Manual-only: {@code ./gradlew runGameTest -Pbenchmark} runs it.
 */
public class BenchmarkGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("AntCraft game tests");
	static final int BOTS = 30;
	/** How long the bots settle in before measuring: they spawn and start their scripts. */
	private static final int WARM_UP_TICKS = 20;
	/** Three minutes of server ticks. */
	static final int MEASURED_TICKS = 3600;
	/** One server tick of the 20 a second. */
	private static final double TICK_BUDGET_MS = 50;
	/** Height of the ground's top block; stone below it, with ores. */
	private static final int GROUND = 12;
	private static final int SIZE = 48;
	/** What every bot runs: senses, walks and block work, as a player's script would mix them. */
	static final String SCRIPT = """
		while True:
		    log = find_nearest("#minecraft:logs", radius=32)
		    if log:
		        if mine(log):
		            pick_up("#minecraft:logs", radius=6)
		        else:
		            wait(20)
		        continue
		    ore = find_nearest(["coal_ore", "iron_ore"], radius=12)
		    if ore and mine(ore):
		        pick_up(radius=6)
		        continue
		    goto(home())
		    wait(100)
		""";

	@GameTest(structure = "antcraft-gametest:benchmark", maxTicks = WARM_UP_TICKS + MEASURED_TICKS + 20, manualOnly = true)
	public void thirtyBotsRunningScripts(GameTestHelper helper) {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		ground(helper);
		for (int[] tree : new int[][] {{4, 4}, {12, 3}, {20, 4}, {30, 3}, {43, 5}, {44, 16}, {43, 28}, {3, 20}, {4, 42}, {16, 44}, {28, 44}, {42, 43}}) {
			tree(helper, tree[0], tree[1]);
		}
		List<Item> tools = List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL);
		List<AntBot> bots = new ArrayList<>();
		for (int i = 0; i < BOTS; i++) {
			AntBot bot = helper.spawn(ModEntities.ANT_BOT, 19 + 2 * (i % 6), GROUND + 1, 18 + 2 * (i / 6));
			bot.getInventory().addItem(new ItemStack(tools.get(i % tools.size())));
			BotScripts.run(bot, SCRIPT);
			bots.add(bot);
		}
		// What the bots are at, sampled every 100 ticks: the action their script waits on, or that it thinks.
		Map<String, Integer> doing = new TreeMap<>();
		helper.onEachTick(() -> {
			long tick = helper.getTick();
			if (tick > WARM_UP_TICKS && tick % 100 == 0) {
				for (AntBot bot : bots) {
					ScriptRun run = bot.getScript();
					String what = !bot.isAlive() ? "dead" : run == null || !run.running() ? "stopped"
						: run.action() == null ? "thinking" : run.action().replaceFirst("[ (].*", "");
					doing.merge(what, 1, Integer::sum);
				}
			}
		});
		helper.runAfterDelay(WARM_UP_TICKS, TickProfiler.SERVER::start);
		helper.runAfterDelay(WARM_UP_TICKS + MEASURED_TICKS, () -> {
			TickProfiler.SERVER.stop();
			TickProfiler.Report report = TickProfiler.SERVER.report();
			LOGGER.info("Benchmark: {} bots, {}", BOTS, String.join("\n  ", report.lines()));
			LOGGER.info("Benchmark: bots at (samples every 100 ticks) {}", doing);
			helper.assertTrue(report.averageTickMs() < TICK_BUDGET_MS, "average tick " + report.averageTickMs() + " ms");
			helper.succeed();
		});
	}

	/** Stone with seeded ores up to below {@link #GROUND}, dirt under grass at it, over the whole area. */
	private static void ground(GameTestHelper helper) {
		Random random = new Random(308);
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, SIZE - 1, GROUND, SIZE - 1)) {
			BlockState state;
			if (pos.getY() == GROUND) {
				state = Blocks.GRASS_BLOCK.defaultBlockState();
			} else if (pos.getY() >= GROUND - 2) {
				state = Blocks.DIRT.defaultBlockState();
			} else {
				double ore = random.nextDouble();
				state = ore < 0.02 ? Blocks.COAL_ORE.defaultBlockState() : ore < 0.03 ? Blocks.IRON_ORE.defaultBlockState() : Blocks.STONE.defaultBlockState();
			}
			helper.setBlock(pos, state);
		}
	}

	private static void tree(GameTestHelper helper, int x, int z) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
		int top = GROUND + 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(x + dx, top, z + dz, leaves);
			}
		}
		helper.setBlock(x, top + 1, z, leaves);
		for (int y = GROUND + 1; y <= top; y++) {
			helper.setBlock(x, y, z, Blocks.OAK_LOG);
		}
	}
}
