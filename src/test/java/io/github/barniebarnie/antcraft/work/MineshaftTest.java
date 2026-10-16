package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MineshaftTest {
	private static final BlockPos TOP = new BlockPos(0, 10, 0);
	/** Down to y=6, one branch each side of a 3 long hall, branches 3 long, a torch every 4 blocks. */
	private static final Mineshaft SHAFT = new Mineshaft(TOP, List.of(6), 1, 3, 4);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Map<BlockPos, BuildPlan.Step> steps() {
		return SHAFT.plan().steps().stream().collect(Collectors.toMap(BuildPlan.Step::pos, step -> step));
	}

	@Test
	void stairsSpiralDownAroundATwoByTwoColumn() {
		assertEquals(List.of(new BlockPos(0, 10, 0), new BlockPos(1, 9, 0), new BlockPos(1, 8, 1), new BlockPos(0, 7, 1), new BlockPos(0, 6, 0)),
			SHAFT.stairs());
	}

	@Test
	void everyStairHasThreeBlocksOfHeadroomInThePlan() {
		Map<BlockPos, BuildPlan.Step> steps = steps();
		for (BlockPos stair : SHAFT.stairs()) {
			for (int up = 0; up < 3; up++) {
				BuildPlan.Step step = steps.get(stair.above(up));
				assertTrue(step != null && (step.isClear() || step.state().is(Blocks.TORCH)), "no headroom at " + stair.above(up));
			}
			assertFalse(steps.containsKey(stair.below()), "the plan digs out the floor of " + stair);
		}
	}

	@Test
	void eachPositionHasOneStep() {
		List<BlockPos> positions = SHAFT.plan().steps().stream().map(BuildPlan.Step::pos).toList();
		assertEquals(positions.size(), new HashSet<>(positions).size());
	}

	@Test
	void planDigsDownInOrder() {
		List<BlockPos> positions = SHAFT.plan().steps().stream().map(BuildPlan.Step::pos).toList();
		// Each stair is dug before the next one down, so the builder can always stand above its work.
		for (int i = 1; i < SHAFT.stairs().size(); i++) {
			assertTrue(positions.indexOf(SHAFT.stairs().get(i - 1)) < positions.indexOf(SHAFT.stairs().get(i).above(2)));
		}
		assertTrue(positions.stream().allMatch(pos -> pos.getY() >= 6), "dug below the deepest level");
	}

	@Test
	void landingHallLeadsAwayFromTheColumn() {
		// The stair at y=6 is in the column's west half, so its hall heads west.
		assertEquals(new BlockPos(0, 6, 0), SHAFT.landing(6));
		assertEquals(List.of(new BlockPos(-1, 6, 0), new BlockPos(-2, 6, 0), new BlockPos(-3, 6, 0)), SHAFT.hall(6));
		Map<BlockPos, BuildPlan.Step> steps = steps();
		for (BlockPos cell : SHAFT.hall(6)) {
			assertTrue(steps.get(cell).isClear() && steps.get(cell.above()).isClear(), "hall not dug 2 high at " + cell);
		}
	}

	@Test
	void torchesEveryFewStairsAndAtEachLanding() {
		assertEquals(List.of(new BlockPos(0, 10, 0), new BlockPos(0, 6, 0)), SHAFT.torches());
		assertEquals(Map.of(Items.TORCH, 2, Items.CHEST, 1), SHAFT.plan().materials());
		assertTrue(steps().get(new BlockPos(0, 6, 0)).state().is(Blocks.TORCH));
	}

	@Test
	void aChestBesideEachLandingClearOfStairsHallsAndBranches() {
		// The landing at y=6 is on the column's north side, so its chest goes north of it.
		BlockPos chest = SHAFT.landingChest(6);
		assertEquals(new BlockPos(0, 6, -1), chest);
		List<BuildPlan.Step> steps = SHAFT.plan().steps();
		assertTrue(steps.stream().anyMatch(step -> step.pos().equals(chest) && step.state().is(Blocks.CHEST)), "no chest in the plan");
		// Placed once the landing and its hall are dug, so the builder can stand there.
		List<BlockPos> positions = steps.stream().map(BuildPlan.Step::pos).toList();
		assertTrue(positions.indexOf(chest) > positions.indexOf(SHAFT.hall(6).getLast()));
		Mineshaft wide = new Mineshaft(TOP, List.of(8, 7, 6, 3), 3, 16, 8);
		for (int level : wide.levels()) {
			BlockPos spot = wide.landingChest(level);
			assertEquals(1, spot.distManhattan(wide.landing(level)), "not beside the landing at " + level);
			assertFalse(wide.area().isInside(spot), "in the stair column at " + level);
			for (int other : wide.levels()) {
				assertFalse(wide.hall(other).contains(spot) || wide.hall(other).contains(spot.below()), "in a hall at " + level);
				assertTrue(wide.branches(other).stream().noneMatch(branch -> branch.cells().contains(spot)), "in a branch at " + level);
			}
		}
	}

	@Test
	void stairFloorsAndTorchesAreProtected() {
		Set<BlockPos> protectedBlocks = SHAFT.protectedBlocks();
		for (BlockPos stair : SHAFT.stairs()) {
			assertTrue(protectedBlocks.contains(stair.below()), "floor under " + stair);
		}
		for (BlockPos cell : SHAFT.hall(6)) {
			assertTrue(protectedBlocks.contains(cell.below()), "hall floor under " + cell);
		}
		assertTrue(protectedBlocks.containsAll(SHAFT.torches()));
	}

	@Test
	void levelsAtOrAboveTheTopAreDropped() {
		assertEquals(List.of(6, 2), new Mineshaft(TOP, List.of(2, 12, 10, 6), 1, 3, 4).levels());
	}

	@Test
	void branchesRunBothWaysFromTheHall() {
		List<Mineshaft.Branch> branches = SHAFT.branches(6);
		assertEquals(2, branches.size());
		Mineshaft.Branch north = branches.stream().filter(b -> b.start().getZ() < 0).findFirst().orElseThrow();
		assertEquals(new BlockPos(-3, 6, -1), north.start());
		assertEquals(List.of(new BlockPos(-3, 6, -1), new BlockPos(-3, 7, -1), new BlockPos(-3, 6, -2), new BlockPos(-3, 7, -2),
			new BlockPos(-3, 6, -3), new BlockPos(-3, 7, -3)), north.cells());
		// Halfway between torches, so the start is lit from the hall.
		assertEquals(List.of(new BlockPos(-3, 6, -2)), north.torches());
		Mineshaft.Branch south = branches.stream().filter(b -> b.start().getZ() > 0).findFirst().orElseThrow();
		assertEquals(new BlockPos(-3, 6, 1), south.start());
		assertEquals(Optional.of(south), SHAFT.branch(south.start()));
	}

	@Test
	void branchesAreAFewBlocksApart() {
		Mineshaft wide = new Mineshaft(TOP, List.of(6), 3, 16, 8);
		assertEquals(9, wide.hall(6).size());
		List<Integer> xs = wide.branches(6).stream().filter(b -> b.start().getZ() < 0).map(b -> b.start().getX()).toList();
		assertEquals(List.of(-3, -6, -9), xs);
		assertEquals(List.of(new BlockPos(-3, 6, -4), new BlockPos(-3, 6, -12)),
			wide.branches(6).getFirst().torches());
	}

	@Test
	void picksTheDeepestLevelWithWantedOres() {
		List<Integer> levels = List.of(16, -16, -58);
		assertEquals(Optional.of(-16), Mineshaft.chooseLevel(levels, item -> item == Items.RAW_GOLD));
		assertEquals(Optional.of(16), Mineshaft.chooseLevel(levels, item -> item == Items.RAW_COPPER));
		assertEquals(Optional.of(-58), Mineshaft.chooseLevel(levels, item -> item == Items.DIAMOND));
		// Nothing in particular wanted: the deepest has the best ores.
		assertEquals(Optional.of(-58), Mineshaft.chooseLevel(levels, item -> false));
		assertEquals(Optional.empty(), Mineshaft.chooseLevel(List.of(), item -> true));
	}

	@Test
	void survivesCodecRoundTrip() {
		var json = Mineshaft.CODEC.encodeStart(JsonOps.INSTANCE, SHAFT).getOrThrow();
		assertEquals(SHAFT, Mineshaft.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
	}

	@Test
	void siteBesideTheCoreKeepsClearOfStorageAndField() {
		BlockPos core = new BlockPos(0, 64, 0);
		List<Integer> levels = List.of(40);
		Mineshaft first = Mineshaft.nextTo(core, levels, List.of());
		// Storage was chosen first, right where the shaft would go.
		StorageArea storage = new StorageArea(first.top().offset(-1, 0, 0));
		Field field = new Field(core.offset(-4, -1, -4));
		Mineshaft shaft = Mineshaft.nextTo(core, levels, List.of(storage.area(), field.area()));
		assertFalse(shaft.area().intersects(storage.area()), "shaft overlaps storage at " + shaft.top());
		assertFalse(shaft.area().intersects(field.area()), "shaft overlaps the field at " + shaft.top());
	}
}
