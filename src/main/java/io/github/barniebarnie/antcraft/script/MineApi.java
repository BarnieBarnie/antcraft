package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.pathing.LoadedBlocks;
import io.github.barniebarnie.antcraft.pathing.LoadedWorldView;
import io.github.barniebarnie.antcraft.pathing.PathBudget;
import io.github.barniebarnie.antcraft.pathing.WorldView;
import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.OreVein;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The senses {@code mineshaft(levels, near=None, branches=4, length=16)}, {@code mineshaft_at(top, levels, round=0, branches=4, length=16)} and
 * {@code ore_vein(pos)} (docs/reference/index.md, Mining): where a ladder shaft can go and what it looks like, from
 * {@link Mineshaft}, and the ore veins its branches uncover, as plain data for the mining library.
 */
public final class MineApi {
	/**
	 * What turning a found site into its dict ({@link #describe}, mainly {@link Mineshaft#plan}, some 33 us) counts against
	 * {@link SenseApi#SCAN_BUDGET} in {@code mineshaft}, in blocks of that time (#399): uncounted, the 30 bots of the benchmark
	 * described some 36 sites a tick past the budget.
	 */
	// ponytail: measured with the bot benchmark (BenchmarkGameTest, mineshaft against mineshaft none), retune with it.
	static final int DESCRIBE_READS = 700;

	private MineApi() {
	}

	static void register(Natives natives, ScriptRun run) {
		natives.register("mineshaft", call -> {
			Object[] args = Args.bind("mineshaft", call, 1, "levels", "near", "branches", "length");
			List<Integer> levels = levels(args[0]);
			int branches = count(args[2], Mineshaft.DEFAULT_BRANCHES_PER_SIDE, "branches", 1);
			int length = count(args[3], Mineshaft.DEFAULT_BRANCH_LENGTH, "length", 1);
			Pos near = Args.absent(args[1]) || args[1] == PyNone.NONE ? run.host().pos() : Pos.of(args[1]);
			AntBot bot = WorkApi.bot(run, call);
			if (run.elsewhere(near)) {
				return PyNone.NONE;
			}
			SiteSearch search = new SiteSearch(run, bot, WorkApi.blockPos(near), levels, branches, length);
			return search.tick() ? search.result() : run.start(search, call);
		});
		natives.register("mineshaft_at", 1500, call -> {
			Object[] args = Args.bind("mineshaft_at", call, 2, "top", "levels", "round", "branches", "length");
			Pos top = Pos.of(args[0]);
			List<Integer> levels = levels(args[1]);
			int round = count(args[2], 0, "round", 0);
			int branches = count(args[3], Mineshaft.DEFAULT_BRANCHES_PER_SIDE, "branches", 1);
			int length = count(args[4], Mineshaft.DEFAULT_BRANCH_LENGTH, "length", 1);
			if (run.elsewhere(top)) {
				return PyNone.NONE;
			}
			return describe(new Mineshaft(WorkApi.blockPos(top), levels, branches, length, Mineshaft.DEFAULT_TORCH_SPACING), round,
				run.host().pos().dimension());
		});
		natives.register("ore_vein", 10, call -> {
			Object[] args = Args.bind("ore_vein", call, 1, "pos");
			Pos pos = Pos.of(args[0]);
			AntBot bot = WorkApi.bot(run, call);
			if (run.elsewhere(pos)) {
				return new PyList();
			}
			LoadedBlocks loaded = new LoadedBlocks(bot.level());
			long[] reads = {0};
			PyList vein = oreVein(WorkApi.blockPos(pos), at -> {
				reads[0]++;
				BlockState state = loaded.loaded(at);
				return state == null ? Blocks.AIR.defaultBlockState() : state;
			}, run.host().pos().dimension());
			run.charge((reads[0] + SenseApi.BLOCKS_PER_INSTRUCTION - 1) / SenseApi.BLOCKS_PER_INSTRUCTION);
			return vein;
		});
	}

	/**
	 * {@code mineshaft}'s search for a site: the spots beside {@code near} a block at a time, while the server's
	 * {@link SenseApi#SCAN_BUDGET} has blocks left this tick, as a block search reads them; one that cannot finish this tick
	 * goes on over the next ticks, pausing the script, inside a spot if need be (#398). A spot with every level fit reads
	 * some 3,000 blocks with the default levels, and a search where no spot fits up to some 300,000 (#380).
	 */
	private static final class SiteSearch implements ScriptRun.Action {
		private final ScriptRun run;
		private final AntBot bot;
		private final Mineshaft.SiteSearch search;
		private long reads;

		SiteSearch(ScriptRun run, AntBot bot, BlockPos near, List<Integer> levels, int branches, int length) {
			this.run = run;
			this.bot = bot;
			this.search = new Mineshaft.SiteSearch(near, levels, branches, length, List.of());
		}

		@Override
		public boolean tick() {
			// Made anew each tick: it remembers chunks, which may unload between ticks.
			LoadedBlocks loaded = new LoadedBlocks(this.bot.level());
			long[] reads = {0};
			LoadedWorldView world = new LoadedWorldView(new LevelWorldView(this.bot.level(), this.bot.getInventory(), this.bot.getMainHandItem(), Set.of()),
				pos -> ++reads[0] > 0 && loaded.loaded(pos) != null);
			boolean done = step(this.search, world, reads, SenseApi.SCAN_BUDGET, this.bot.level().getGameTime());
			this.run.charge((reads[0] + SenseApi.BLOCKS_PER_INSTRUCTION - 1) / SenseApi.BLOCKS_PER_INSTRUCTION);
			this.reads += reads[0];
			return done;
		}

		@Override
		public Object result() {
			return this.search.found().<Object>map(shaft -> MineApi.describe(shaft, 0, this.run.host().pos().dimension())).orElse(PyNone.NONE);
		}

		@Override
		public String describe() {
			return "mineshaft (" + this.reads + " blocks read)";
		}

		/** It reads blocks and does not move the bot, so a reflex only pauses it: callers get a site or None (#410). */
		@Override
		public boolean outlastsReflexes() {
			return true;
		}
	}

	/**
	 * Goes on with {@code search} in {@code world}, which counts its reads in {@code reads}, while {@code budget} has blocks
	 * left in {@code tick}, and spends them; true once done. A found site also counts {@link #DESCRIBE_READS} for its dict,
	 * and waits for a tick with that left.
	 */
	static boolean step(Mineshaft.SiteSearch search, WorldView world, long[] reads, PathBudget budget, long tick) {
		boolean done = search.step(world, () -> reads[0] < budget.available(tick));
		if (done && search.found().isPresent()) {
			if (reads[0] + DESCRIBE_READS > budget.available(tick)) {
				done = false;
			} else {
				reads[0] += DESCRIBE_READS;
			}
		}
		budget.spend(tick, (int) Math.min(reads[0], Integer.MAX_VALUE));
		return done;
	}

	/** An optional whole-number argument {@code name}, {@code absent} when not given, at least {@code min}. */
	private static int count(Object value, int absent, String name, int min) {
		if (Args.absent(value)) {
			return absent;
		}
		long n = Args.integer(value);
		if (n < min || n > 1024) {
			throw new ScriptError(name + " must be from " + min + " to 1024, not " + n);
		}
		return (int) n;
	}

	/**
	 * {@code ore_vein(pos)}: the blocks of the ore vein at {@code pos} ({@link OreVein#of}, at most {@link OreVein#MAX_SIZE}), from
	 * {@code pos} out, each after one it touches, or none where {@code pos} is no ore.
	 */
	static PyList oreVein(BlockPos pos, Function<BlockPos, BlockState> world, @Nullable String dimension) {
		if (!OreVein.isOre(world.apply(pos))) {
			return new PyList();
		}
		return positions(OreVein.of(pos, world, OreVein.MAX_SIZE), dimension);
	}

	private static List<Integer> levels(Object value) {
		List<Object> items = switch (value) {
			case PyList list -> list.items;
			case PyTuple tuple -> tuple.items();
			default -> throw new ScriptError("levels must be a list of heights, not '" + Values.typeName(value) + "'");
		};
		return items.stream().map(y -> (int) Math.clamp(Args.integer(y), Integer.MIN_VALUE, Integer.MAX_VALUE)).toList();
	}

	/**
	 * A ladder shaft as plain data: {@code top}, {@code hatch}, {@code levels} (heights, deepest last), {@code level} (per
	 * height its {@code landing}, {@code chest}, {@code hall}, {@code branches}, each branch the cells of its round
	 * {@code round}, and {@code torches}, the torch spots of the hall and those branches), {@code ladders} ({@code [pos,
	 * facing]}, top first) and {@code torches}, positions in {@code dimension}.
	 */
	static PyDict describe(Mineshaft shaft, int round, @Nullable String dimension) {
		PyDict dict = new PyDict();
		dict.put("top", pos(shaft.top(), dimension));
		dict.put("hatch", pos(shaft.hatch(), dimension));
		dict.put("levels", new PyList(new ArrayList<>(shaft.levels().stream().<Object>map(Integer::longValue).toList())));
		PyDict byLevel = new PyDict();
		for (int y : shaft.levels()) {
			PyDict level = new PyDict();
			level.put("landing", pos(shaft.landing(y), dimension));
			level.put("chest", pos(shaft.landingChest(y), dimension));
			level.put("hall", positions(shaft.hall(y), dimension));
			PyList branches = new PyList();
			List<Mineshaft.Branch> inRound = shaft.branches(y, round);
			inRound.forEach(branch -> branches.items.add(positions(branch.cells(), dimension)));
			level.put("branches", branches);
			List<BlockPos> torches = new ArrayList<>(shaft.hallWork(y).map(Mineshaft.Branch::torches).orElse(List.of()));
			inRound.forEach(branch -> torches.addAll(branch.torches()));
			level.put("torches", positions(torches, dimension));
			byLevel.put((long) y, level);
		}
		dict.put("level", byLevel);
		PyList ladders = new PyList();
		for (BuildPlan.Step step : shaft.plan().steps()) {
			if (step.state().is(Blocks.LADDER)) {
				ladders.items.add(new PyList(new ArrayList<>(List.of(pos(step.pos(), dimension), step.state().getValue(LadderBlock.FACING).getSerializedName()))));
			}
		}
		dict.put("ladders", ladders);
		dict.put("torches", positions(shaft.torches(), dimension));
		return dict;
	}

	private static Pos pos(BlockPos pos, @Nullable String dimension) {
		return new Pos(pos.getX(), pos.getY(), pos.getZ(), dimension);
	}

	private static PyList positions(List<BlockPos> list, @Nullable String dimension) {
		return new PyList(new ArrayList<>(list.stream().<Object>map(pos -> pos(pos, dimension)).toList()));
	}
}
