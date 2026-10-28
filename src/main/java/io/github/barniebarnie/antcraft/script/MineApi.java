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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import org.jspecify.annotations.Nullable;

/**
 * The senses {@code mineshaft(levels, near=None)} and {@code mineshaft_at(top, levels)} (docs/scripting.md, Senses): where
 * a ladder shaft can go and what it looks like, from {@link Mineshaft}, as plain data for the mining library.
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
			Object result = Mineshaft.diggableNextTo(WorkApi.blockPos(near), levels, List.of(), world)
				.<Object>map(shaft -> describe(shaft, run.host().pos().dimension())).orElse(PyNone.NONE);
			run.charge((reads[0] + SenseApi.BLOCKS_PER_INSTRUCTION - 1) / SenseApi.BLOCKS_PER_INSTRUCTION);
			return result;
		});
		natives.register("mineshaft_at", 30, call -> {
			Object[] args = Args.bind("mineshaft_at", call, 2, "top", "levels");
			Pos top = Pos.of(args[0]);
			List<Integer> levels = levels(args[1]);
			if (run.elsewhere(top)) {
				return PyNone.NONE;
			}
			return describe(new Mineshaft(WorkApi.blockPos(top), levels), run.host().pos().dimension());
		});
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
	 * height its {@code landing}, {@code chest}, {@code hall} and {@code branches}, each branch the cells of its first
	 * round), {@code ladders} ({@code [pos, facing]}, top first) and {@code torches}, positions in {@code dimension}.
	 */
	static PyDict describe(Mineshaft shaft, @Nullable String dimension) {
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
			shaft.branches(y).forEach(branch -> branches.items.add(positions(branch.cells(), dimension)));
			level.put("branches", branches);
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
