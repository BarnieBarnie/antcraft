package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.pathing.GridWorld;
import io.github.barniebarnie.antcraft.pathing.LoadedWorldView;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.Values;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.OreVein;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The senses {@code mineshaft(levels, near)}, {@code mineshaft_at(top, levels)} and {@code ore_vein(pos)} (docs/scripting.md, Senses). */
class MineApiTest {
	private static final BlockPos NEAR = new BlockPos(0, 64, 0);
	private static final List<Integer> LEVELS = List.of(40, 20);

	@BeforeAll
	static void bootstrap() {
		VanillaData.recipeBook();
	}

	private static Pos pos(BlockPos pos) {
		return new Pos(pos.getX(), pos.getY(), pos.getZ());
	}

	private static List<Object> positions(List<BlockPos> list) {
		return list.stream().<Object>map(MineApiTest::pos).toList();
	}

	/** The positions of the plan's steps that place {@code block}. */
	private static List<BlockPos> placed(BuildPlan plan, net.minecraft.world.level.block.Block block) {
		return plan.steps().stream().filter(step -> step.state().is(block)).map(BuildPlan.Step::pos).toList();
	}

	@Test
	void mineshaftGivesTheSiteAndItsPlanAsPlainData() {
		Mineshaft shaft = Mineshaft.diggableNextTo(NEAR, LEVELS, List.of(), new GridWorld()).orElseThrow();
		BuildPlan plan = shaft.plan();
		PyDict data = MineApi.describe(shaft, 0, null);
		assertEquals(pos(shaft.top()), data.get("top"));
		assertEquals(List.of(40L, 20L), ((PyList) data.get("levels")).items);
		assertEquals(pos(shaft.top().below()), data.get("hatch"));
		PyDict level = (PyDict) ((PyDict) data.get("level")).get(20L);
		assertEquals(pos(shaft.landing(20)), level.get("landing"));
		assertEquals(pos(shaft.landingChest(20)), level.get("chest"));
		assertTrue(placed(plan, Blocks.CHEST).contains(shaft.landingChest(20)), "the chest is not where the plan puts it");
		assertEquals(positions(shaft.hall(20)), ((PyList) level.get("hall")).items);
		List<Object> branches = ((PyList) level.get("branches")).items;
		assertEquals(shaft.branches(20).size(), branches.size());
		assertEquals(positions(shaft.branches(20).getFirst().cells()), ((PyList) branches.getFirst()).items);
		List<Object> ladders = ((PyList) data.get("ladders")).items;
		List<BlockPos> planLadders = placed(plan, Blocks.LADDER);
		assertEquals(planLadders.size(), ladders.size());
		assertEquals(pos(planLadders.getFirst()), ((PyList) ladders.getFirst()).items.get(0));
		String facing = plan.steps().stream().filter(step -> step.state().is(Blocks.LADDER)).findFirst().orElseThrow()
			.state().getValue(LadderBlock.FACING).getSerializedName();
		assertEquals(facing, ((PyList) ladders.getFirst()).items.get(1));
		assertEquals(positions(placed(plan, Blocks.TORCH)), ((PyList) data.get("torches")).items);
	}

	@Test
	void noSiteWhereEverySpotIsBlocked() {
		// Lava right under the ground everywhere a site could be.
		GridWorld lava = new GridWorld();
		for (int x = -24; x <= 24; x++) {
			for (int z = -24; z <= 24; z++) {
				lava.set(x, NEAR.getY() - 1, z, 'L');
			}
		}
		assertTrue(Mineshaft.diggableNextTo(NEAR, LEVELS, List.of(), lava).isEmpty());
	}

	/** mineshaft(levels, near, branches, length) checks a site with the hall of its own size, not the default 12 (#389). */
	@Test
	void aSmallerShaftsSiteIsCheckedWithItsOwnHall() {
		GridWorld world = new GridWorld().fill(-24, 0, -24, 24, NEAR.getY() - 1, 24, '#');
		Mineshaft small = Mineshaft.diggableOnGroundNextTo(NEAR, LEVELS, 1, 2, List.of(), world).orElseThrow();
		assertEquals(3, small.hall(20).size());
		assertEquals(2, small.branches(20).getFirst().cells().size() / 2);
		// Water where the default hall reaches, past the small one's.
		BlockPos water = new Mineshaft(small.top(), LEVELS).hall(20).get(8);
		world.set(water.getX(), water.getY(), water.getZ(), 'W');
		assertEquals(small, Mineshaft.diggableOnGroundNextTo(NEAR, LEVELS, 1, 2, List.of(), world).orElseThrow());
	}

	/**
	 * mineshaft(levels) searches while the tick's block search budget lasts, and goes on from there on the next tick
	 * (#380), pausing inside a spot where the budget runs out there (#398): with a budget of 100 blocks a step, a search
	 * whose third spot is the first that fits reads at most a block's checks (and a column's ground) past it in each step,
	 * takes many steps and finds what the whole search at once does; with none left, a step tries nothing.
	 */
	@Test
	void aSiteSearchPausesInsideASpot() {
		GridWorld grid = new GridWorld().fill(-24, 0, -24, 24, NEAR.getY() - 1, 24, '#');
		for (BlockPos top : Mineshaft.candidates(NEAR).subList(0, 2)) {
			grid.set(top.getX(), top.getY() - 3, top.getZ(), 'W');
		}
		long[] reads = {0};
		LoadedWorldView world = new LoadedWorldView(grid, pos -> ++reads[0] > 0);
		Mineshaft.SiteSearch search = new Mineshaft.SiteSearch(NEAR, LEVELS, Mineshaft.DEFAULT_BRANCHES_PER_SIDE, Mineshaft.DEFAULT_BRANCH_LENGTH, List.of());
		int steps = 0;
		boolean done;
		do {
			reads[0] = 0;
			done = search.step(world, () -> reads[0] < 100);
			assertTrue(reads[0] < 100 + 2 * 20, "step " + steps + " read " + reads[0] + " blocks");
			steps++;
		} while (!done);
		assertTrue(steps > 10, "took " + steps + " steps");
		Mineshaft whole = Mineshaft.diggableOnGroundNextTo(NEAR, LEVELS, Mineshaft.DEFAULT_BRANCHES_PER_SIDE, Mineshaft.DEFAULT_BRANCH_LENGTH, List.of(), world)
			.orElseThrow();
		assertEquals(whole, search.found().orElseThrow());
		assertEquals(Mineshaft.candidates(NEAR).get(2), whole.top());
		Mineshaft.SiteSearch starved = new Mineshaft.SiteSearch(NEAR, LEVELS, Mineshaft.DEFAULT_BRANCHES_PER_SIDE, Mineshaft.DEFAULT_BRANCH_LENGTH, List.of());
		assertFalse(starved.step(world, () -> false));
		assertTrue(starved.found().isEmpty());
	}

	@Test
	void mineshaftAtGivesTheSameShaftBack() {
		Mineshaft shaft = Mineshaft.diggableNextTo(NEAR, LEVELS, List.of(), new GridWorld()).orElseThrow();
		assertEquals(Values.repr(MineApi.describe(shaft, 0, null)), Values.repr(MineApi.describe(new Mineshaft(shaft.top(), shaft.levels()), 0, null)));
	}

	/** A later round gives the branches of that round, and each level the torch spots of its hall and those branches. */
	@Test
	void mineshaftAtGivesALaterRoundOfBranchesAndTheirTorches() {
		Mineshaft shaft = new Mineshaft(NEAR, LEVELS, 1, 2, Mineshaft.DEFAULT_TORCH_SPACING);
		PyDict level = (PyDict) ((PyDict) MineApi.describe(shaft, 1, null).get("level")).get(20L);
		List<Object> branches = ((PyList) level.get("branches")).items;
		assertEquals(positions(shaft.branches(20, 1).getFirst().cells()), ((PyList) branches.getFirst()).items);

		Mineshaft big = new Mineshaft(NEAR, LEVELS);
		level = (PyDict) ((PyDict) MineApi.describe(big, 0, null).get("level")).get(20L);
		List<BlockPos> torches = Stream.concat(big.hallWork(20).orElseThrow().torches().stream(),
			big.branches(20).stream().flatMap(branch -> branch.torches().stream())).toList();
		assertTrue(!torches.isEmpty());
		assertEquals(positions(torches), ((PyList) level.get("torches")).items);
	}

	/** Stone with the blocks of {@code at}. */
	private static Function<BlockPos, BlockState> world(Map<BlockPos, Block> at) {
		return pos -> at.getOrDefault(pos, Blocks.STONE).defaultBlockState();
	}

	@Test
	void oreVeinGivesTheVeinFromTheBlockOut() {
		BlockPos at = new BlockPos(0, 10, 0);
		Map<BlockPos, Block> blocks = new HashMap<>(Map.of(at, Blocks.IRON_ORE, at.east(), Blocks.DEEPSLATE_IRON_ORE, at.east(2), Blocks.IRON_ORE,
			at.above(), Blocks.COPPER_ORE, at.west(), Blocks.AIR));
		assertEquals(positions(List.of(at, at.east(), at.east(2))), MineApi.oreVein(at, world(blocks), null).items);
		// Stone and air are no ore.
		assertEquals(List.of(), MineApi.oreVein(at.below(), world(blocks), null).items);
		assertEquals(List.of(), MineApi.oreVein(at.west(), world(blocks), null).items);
	}

	@Test
	void oreVeinStopsAtTheCap() {
		BlockPos at = new BlockPos(0, 10, 0);
		Map<BlockPos, Block> blocks = new HashMap<>();
		for (int i = 0; i < OreVein.MAX_SIZE + 10; i++) {
			blocks.put(at.east(i), Blocks.COAL_ORE);
		}
		assertEquals(OreVein.MAX_SIZE, MineApi.oreVein(at, world(blocks), null).items.size());
	}
}
