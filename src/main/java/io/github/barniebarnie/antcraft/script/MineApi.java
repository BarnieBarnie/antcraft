package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.pathing.LoadedBlocks;
import io.github.barniebarnie.antcraft.pathing.LoadedWorldView;
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
 * The senses {@code mineshaft(levels, near=None)}, {@code mineshaft_at(top, levels, round=0, branches=4, length=16)} and
 * {@code ore_vein(pos)} (docs/scripting.md, Senses): where a ladder shaft can go and what it looks like, from
 * {@link Mineshaft}, and the ore veins its branches uncover, as plain data for the mining library.
 */
public final class MineApi {
	private MineApi() {
	}

	static void register(Natives natives, ScriptRun run) {
		natives.register("mineshaft", call -> {
			Object[] args = Args.bind("mineshaft", call, 1, "levels", "near");
			List<Integer> levels = levels(args[0]);
			Pos near = Args.absent(args[1]) || args[1] == PyNone.NONE ? run.host().pos() : Pos.of(args[1]);
			AntBot bot = WorkApi.bot(run, call);
			if (run.elsewhere(near)) {
				return PyNone.NONE;
			}
			LoadedBlocks loaded = new LoadedBlocks(bot.level());
			long[] reads = {0};
			LoadedWorldView world = new LoadedWorldView(new LevelWorldView(bot.level(), bot.getInventory(), bot.getMainHandItem(), Set.of()),
				pos -> ++reads[0] > 0 && loaded.loaded(pos) != null);
			// ponytail: charged per block read as the block searches are, estimated, not measured; benchmark it in #380. A
			// site search reads some 3,000 blocks for a site found at once, up to some 300,000 in one tick where every spot is
			// blocked; share SenseApi.SCAN_BUDGET over ticks if that shows in the tick time.
			Object result = Mineshaft.diggableOnGroundNextTo(WorkApi.blockPos(near), levels, List.of(), world)
				.<Object>map(shaft -> describe(shaft, 0, run.host().pos().dimension())).orElse(PyNone.NONE);
			run.charge((reads[0] + SenseApi.BLOCKS_PER_INSTRUCTION - 1) / SenseApi.BLOCKS_PER_INSTRUCTION);
			return result;
		});
		natives.register("mineshaft_at", 30, call -> {
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
		natives.register("ore_vein", call -> {
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
