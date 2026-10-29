package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.gametest.mixin.AutosaveAccessor;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bot benchmark: {@link #BOTS} bots in a 48x48 area with trees and seeded ores, for each {@link Case} in turn: every bot
 * runs the case's script for its measured ticks, then the bots go and the next case starts. Each case logs the average,
 * 99th percentile and worst server tick (judge costs on the p99: the single worst tick varies some 2 ms between runs,
 * #399), where the time went (see {@link TickProfiler}), the script instructions run per tick and what the bots were at; the run fails if a case's average tick reaches {@link #TICK_BUDGET_MS}. A script that stops is logged with
 * its error and counted as {@code stopped}. The cases that only sense, walk or craft come first and leave the world as it
 * is; {@link #WORK}, which fells the trees and mines, comes last but for the build case, which may dig. Bots that die count
 * as {@code dead}. Manual-only: {@code ./gradlew runGameTest -Pbenchmark} runs it.
 */
public class BenchmarkGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("AntCraft game tests");
	static final int BOTS = 30;
	/** How long the bots settle in before measuring: they spawn and start their scripts. */
	private static final int WARM_UP_TICKS = 20;
	/** One server tick of the 20 a second. */
	private static final double TICK_BUDGET_MS = 50;
	/** Height of the ground's top block; stone below it, with ores. */
	private static final int GROUND = 12;
	private static final int SIZE = 48;

	/**
	 * One measured script: {@code instructions} is the per-bot instruction budget a tick it runs with, {@code ticks} how
	 * long it is measured and {@code equip} what each bot gets to carry.
	 */
	record Case(String name, String script, int instructions, int ticks, Consumer<AntBot> equip) {
		Case(String name, String script, int ticks) {
			this(name, script, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, ticks, bot -> {
			});
		}
	}

	/** A loop that only computes, using the whole instruction budget every tick. */
	private static final String LOOP = """
		n = 0
		while True:
		    n += 1
		""";
	/** The full search of radius 32 (some 137,000 blocks) for a block that is not there, over and over. */
	private static final String FIND_NEAREST = """
		while True:
		    find_nearest("diamond_ore", radius=32)
		""";
	/** Scans of radius 32 for the logs of the trees, fewer than the limit, so each reads the whole radius too. */
	private static final String SCAN = """
		while True:
		    scan("#minecraft:logs", radius=32)
		""";
	/** Plans without walking to spots all over the area, near and far. */
	private static final String REACHABLE = """
		n = 0
		while True:
		    n += 1
		    reachable(home() + ((n * 7) % 40 - 20, 0, (n * 13) % 40 - 20))
		""";
	/** Walks back and forth across the area. */
	private static final String GOTO = """
		n = 0
		while True:
		    n += 1
		    goto(home() + ((n * 7) % 30 - 15, 0, (n * 11) % 30 - 15))
		""";
	/** Crafts planks and sticks from carried logs and drops the sticks, until the logs run out, then fails to craft on (#328). */
	private static final String CRAFT = """
		while True:
		    craft("oak_planks", 4)
		    craft("stick", 4)
		    drop("stick")
		""";
	/** Counts carried items by name and by tag, over and over (#340). */
	private static final String COUNT = """
		while True:
		    count("oak_log")
		    has("#minecraft:planks", 4)
		""";
	/** Holds a carried item and fails to hold one it lacks, over and over (#343). */
	private static final String EQUIP = """
		while True:
		    equip("oak_log")
		    equip("stick")
		""";
	/** Fails to drop items it lacks, by name and by tag, so no item entities pile up (#343). */
	private static final String DROP = """
		while True:
		    drop("stick")
		    drop("#minecraft:planks")
		""";
	/** Looks for items on the ground, of which there are none, near and far (#343). */
	private static final String ITEMS_NEAR = """
		while True:
		    items_near("stick")
		    items_near(radius=32)
		""";
	/** Lists the entities around, the other bots, by type and all of them (#343). */
	private static final String ENTITIES = """
		while True:
		    entities("antcraft:ant_bot")
		    entities()
		""";
	/** Fails to smelt carried raw iron with no furnace in reach, over and over (#343). */
	private static final String SMELT = """
		while True:
		    smelt("raw_iron")
		""";
	/** What the issue asks of a busy script: a tight loop, a scan of radius 32, find_nearest, goto and craft, mixed. */
	private static final String BUSY = """
		n = 0
		while True:
		    for i in range(200):
		        n += i
		    logs = scan("#minecraft:logs", radius=32)
		    ore = find_nearest(["coal_ore", "iron_ore"], radius=32)
		    if logs:
		        goto(logs[n % len(logs)], near=3)
		    craft("oak_planks", 4)
		    drop("oak_planks")
		""";
	/** Reads its colony over and over, with 30 members (#380). */
	private static final String COLONY = """
		while True:
		    colony()
		""";
	/**
	 * Posts four jobs each, some 120 on the board, then lists them and takes and drops one over and over (#380), and posts
	 * a job and cancels it (#394).
	 */
	private static final String JOBS = """
		for i in range(4):
		    post_job("dig", {"n": i})
		while True:
		    jobs()
		    job = take_job()
		    if job:
		        drop_job(job["id"])
		    finish_job(post_job("haul", {"item": "minecraft:oak_log", "n": 16}))
		""";
	/** Describes the warehouse blueprint, and where its spots are when built at a spot (#380). */
	private static final String BLUEPRINT = """
		while True:
		    blueprint("antcraft:warehouse")
		    blueprint("antcraft:warehouse", home() + (0, 0, -12))
		""";
	/** Matches item ids against a name and a tag, and its free slots (#380). */
	private static final String MATCHES = """
		while True:
		    matches("oak_log", "#minecraft:logs")
		    matches("stick", "oak_log")
		    free_slots()
		""";
	/** Reads the ore vein the nearest ore is in, over and over (#380). */
	private static final String ORE_VEIN = """
		ore = find_nearest(["coal_ore", "iron_ore"], radius=12)
		while True:
		    ore_vein(ore)
		""";
	/** Describes a shaft from its top, as the mining library does each round (#380). */
	private static final String MINESHAFT_AT = """
		while True:
		    mineshaft_at(home() + (0, 0, -12), [pos().y - 4, pos().y - 8])
		""";
	/** Finds a shaft site of two levels in the ground beside the bot, the first spot fit (#380). */
	private static final String MINESHAFT = """
		while True:
		    mineshaft([pos().y - 4, pos().y - 8])
		""";
	/**
	 * Finds no shaft site, the worst case of {@code mineshaft} (#380): every spot reads its column, five halls of 48 blocks
	 * a side in the ground and stone or the air beside the area, and its last level's hall is in the bedrock at the
	 * world's bottom (y -64), so none fits.
	 */
	private static final String MINESHAFT_NONE = """
		y = pos().y
		while True:
		    assert mineshaft([y - 3, y - 5, y - 7, y - 9, y - 11, -64], branches=16) is None, "a site fits"
		""";
	/** Builds the warehouse without its blocks: plans it and fails for missing ingredients, over and over (#380). */
	private static final String BUILD = """
		while True:
		    build("antcraft:warehouse", home() + (0, 0, -12))
		""";
	/** Members of the colony of the core the benchmark puts in the area. */
	private static final Consumer<AntBot> MEMBER = bot -> {
		ColonyManager colonies = ColonyManager.get((ServerLevel) bot.level());
		colonies.join(bot, colonies.nearest(bot.blockPosition(), ColonyManager.JOIN_DISTANCE).orElseThrow());
	};
	/**
	 * What a player's working script mixes: senses, walks and block work. Fells the trees, mines the ores it finds, goes
	 * home. It names what to pick up, as {@code pick_up} without {@code what} fails with an internal error.
	 */
	static final String WORK_SCRIPT = """
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
		        pick_up(["coal", "raw_iron", "cobblestone"], radius=6)
		        continue
		    goto(home())
		    wait(100)
		""";
	private static final List<Item> TOOLS = List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL);
	private static final Case WORK = new Case("work", WORK_SCRIPT, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 3600,
		bot -> bot.getInventory().addItem(new ItemStack(TOOLS.get(bot.getId() % TOOLS.size()))));
	private static final Consumer<AntBot> LOGS = bot -> {
		for (int i = 0; i < 4; i++) {
			bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 64));
		}
	};
	/**
	 * Four logs each, for the craft case, which measures a loop of crafts and drops that soon fail at once, as the logs run
	 * out: with four stacks each, the 30 bots dropped some 61,000 sticks, in 960 item entities that took about 8 ms of the
	 * case's average tick to tick, vanilla's work rather than the scripts' (#328).
	 */
	private static final Consumer<AntBot> FEW_LOGS = bot -> bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 4));
	static final List<Case> CASES = List.of(
		new Case("loop", LOOP, 1200),
		new Case("loop at the cap", LOOP, AntCraftConfig.MAX_SCRIPT_INSTRUCTIONS_PER_TICK, 1200, bot -> {
		}),
		new Case("find_nearest r32", FIND_NEAREST, 1200),
		new Case("scan r32", SCAN, 1200),
		new Case("reachable", REACHABLE, 1200),
		new Case("goto", GOTO, 1200),
		new Case("craft", CRAFT, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 1200, FEW_LOGS),
		new Case("count", COUNT, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 1200, LOGS),
		new Case("equip", EQUIP, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 600, LOGS),
		new Case("drop", DROP, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 600, LOGS),
		new Case("items_near", ITEMS_NEAR, 600),
		new Case("entities", ENTITIES, 600),
		new Case("smelt", SMELT, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 600,
			bot -> bot.getInventory().addItem(new ItemStack(Items.RAW_IRON, 64))),
		new Case("busy", BUSY, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 1200, LOGS),
		new Case("colony", COLONY, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 600, MEMBER),
		new Case("jobs", JOBS, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 600, MEMBER),
		new Case("blueprint", BLUEPRINT, 600),
		new Case("matches", MATCHES, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, 600, LOGS),
		new Case("ore_vein", ORE_VEIN, 600),
		new Case("mineshaft_at", MINESHAFT_AT, 600),
		new Case("mineshaft", MINESHAFT, 600),
		new Case("mineshaft none", MINESHAFT_NONE, 600),
		WORK,
		new Case("build", BUILD, 600));

	private static int totalTicks() {
		return CASES.stream().mapToInt(c -> WARM_UP_TICKS + c.ticks() + 1).sum();
	}

	@GameTest(structure = "antcraft-gametest:benchmark", maxTicks = 30_000, manualOnly = true)
	public void thirtyBotsRunningScripts(GameTestHelper helper) {
		GameTests.start(helper);
		ground(helper);
		for (int[] tree : new int[][] {{4, 4}, {12, 3}, {20, 4}, {30, 3}, {43, 5}, {44, 16}, {43, 28}, {3, 20}, {4, 42}, {16, 44}, {28, 44}, {42, 43}}) {
			tree(helper, tree[0], tree[1]);
		}
		helper.setBlock(24, GROUND + 1, 10, ModBlocks.COLONY_CORE);
		helper.assertTrue(totalTicks() < 30_000, "the cases take " + totalTicks() + " ticks");
		new Run(helper).register();
	}

	/** Runs the cases one after another from a single tick listener, as listeners must not be added from inside one. */
	private static final class Run {
		private final GameTestHelper helper;
		private final List<AntBot> bots = new ArrayList<>();
		/** What the bots are at, sampled every 100 ticks: the action their script waits on, or that it thinks. */
		private final Map<String, Integer> doing = new TreeMap<>();
		private final List<String> summary = new ArrayList<>();
		/** Bots whose stopped script was logged already. */
		private final Set<AntBot> stopped = new HashSet<>();
		private int index = -1;
		private long caseStart;
		private long executedAtStart;
		/** The ticks there were until the server's next autosave when the run began. */
		private int autosaveIn;

		Run(GameTestHelper helper) {
			this.helper = helper;
		}

		void register() {
			// The server's autosave, every 6000 ticks, writes the world in one tick: 25 to 35 ms here, the worst tick of
			// whichever case it falls in (it was the reachable case's, at server tick 6000, #329). It is vanilla's and comes
			// the same without bots, so it is put off until the cases are done.
			this.autosaveIn = this.autosave().antcraft$getTicksUntilAutosave();
			this.autosave().antcraft$setTicksUntilAutosave(Integer.MAX_VALUE);
			this.helper.onEachTick(this::tick);
		}

		private AutosaveAccessor autosave() {
			return (AutosaveAccessor) this.helper.getLevel().getServer();
		}

		private void tick() {
			long tick = this.helper.getTick();
			if (this.index < 0 || tick == this.caseStart + WARM_UP_TICKS + CASES.get(this.index).ticks()) {
				if (this.index >= 0) {
					this.finish(CASES.get(this.index));
				}
				this.index++;
				if (this.index == CASES.size()) {
					this.autosave().antcraft$setTicksUntilAutosave(this.autosaveIn);
					LOGGER.info("Benchmark: summary, {} bots\n  {}", BOTS, String.join("\n  ", this.summary));
					this.helper.succeed();
					return;
				}
				this.begin(CASES.get(this.index), tick);
				return;
			}
			long since = tick - this.caseStart;
			if (since == WARM_UP_TICKS) {
				this.executedAtStart = this.executed();
				TickProfiler.SERVER.start();
			}
			if (since > WARM_UP_TICKS && since % 100 == 0) {
				for (AntBot bot : this.bots) {
					ScriptRun run = bot.getScript();
					// A bot whose script ended runs idle.py instead.
					boolean stopped = run == null || !run.running() || !run.name().equals("test");
					if (bot.isAlive() && stopped && this.stopped.add(bot)) {
						// Measuring goes on, but a case whose scripts fail measures less than it says.
						LOGGER.warn("Benchmark: {}: a script stopped: {}", CASES.get(this.index).name(), BotScripts.log(bot, Output.Level.ERROR));
					}
					String what = !bot.isAlive() ? "dead" : stopped ? "stopped" : run.action() == null ? "thinking" : run.action().replaceFirst("[ (].*", "");
					this.doing.merge(what, 1, Integer::sum);
				}
			}
		}

		private void begin(Case c, long tick) {
			this.caseStart = tick;
			this.doing.clear();
			AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64, false, c.instructions(), AntCraftConfig.DEFAULT_SCRIPT_CHAT_LEVEL));
			for (int i = 0; i < BOTS; i++) {
				AntBot bot = this.helper.spawn(ModEntities.ANT_BOT, 19 + 2 * (i % 6), GROUND + 1, 18 + 2 * (i / 6));
				c.equip().accept(bot);
				BotScripts.run(bot, c.script());
				this.bots.add(bot);
			}
		}

		private void finish(Case c) {
			TickProfiler.SERVER.stop();
			TickProfiler.Report report = TickProfiler.SERVER.report();
			double instructions = (double) (this.executed() - this.executedAtStart) / report.ticks();
			LOGGER.info("Benchmark: {}, {} bots, {} instructions a tick each at most, {} ticks: {} instructions run a tick\n  {}\n  bots at (samples every 100 ticks) {}",
				c.name(), BOTS, c.instructions(), c.ticks(), Math.round(instructions), String.join("\n  ", report.lines()), this.doing);
			this.summary.add(String.format(Locale.ROOT, "%-18s average %6.2f ms, p99 %6.2f ms, worst %6.2f ms, %8d instructions a tick", c.name(),
				report.averageTickMs(), report.p99TickMs(), report.worstTickMs(), Math.round(instructions)));
			this.helper.assertTrue(report.averageTickMs() < TICK_BUDGET_MS, c.name() + ": average tick " + report.averageTickMs() + " ms");
			for (AntBot bot : this.bots) {
				ColonyManager.get(this.helper.getLevel()).leave(bot);
				bot.discard();
			}
			this.bots.clear();
			this.helper.getLevel().getEntitiesOfClass(ItemEntity.class, this.helper.getBounds()).forEach(ItemEntity::discard);
		}

		/** The instructions all bots' scripts ran so far. */
		private long executed() {
			long executed = 0;
			for (AntBot bot : this.bots) {
				ScriptRun run = bot.getScript();
				if (run != null) {
					executed += run.executed();
				}
			}
			return executed;
		}
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
