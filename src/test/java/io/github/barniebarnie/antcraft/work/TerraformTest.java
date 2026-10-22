package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerraformTest {
	private static BlockState AIR;
	private static BlockState STONE;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		AIR = Blocks.AIR.defaultBlockState();
		STONE = Blocks.STONE.defaultBlockState();
	}

	/**
	 * A slope along x, 2 blocks deep in z: ground at y=1 in the middle, a hole down to y=0 at x=0,
	 * a hill up to y=2 at x=3 and up to y=3 at x=4 and x=5. Grass stands on the flat ground at x=2.
	 */
	private static Function<BlockPos, BlockState> slope() {
		int[] top = {0, 1, 1, 2, 3, 3};
		Map<BlockPos, BlockState> world = new HashMap<>();
		for (int x = 0; x < top.length; x++) {
			for (int z = 0; z < 2; z++) {
				for (int y = 0; y <= top[x]; y++) {
					world.put(new BlockPos(x, y, z), STONE);
				}
			}
		}
		world.put(new BlockPos(2, 2, 0), Blocks.SHORT_GRASS.defaultBlockState());
		return pos -> world.getOrDefault(pos, AIR);
	}

	@Test
	void keepsTheGroundOfTheBoxUpToTheHeight() {
		BuildPlan plan = Terraform.plan(slope(), new BlockPos(5, 4, 1), new BlockPos(0, 0, 0), 1).orElseThrow();

		assertEquals(Optional.of(new BoundingBox(0, 0, 0, 5, 1, 1)), plan.ground());
		var json = BuildPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan).getOrThrow();
		assertEquals(plan, BuildPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(), "not saved");
	}

	@Test
	void digsAboveTheHeightFillsBelowAndLeavesWhatIsRight() {
		BuildPlan plan = Terraform.plan(slope(), new BlockPos(5, 4, 1), new BlockPos(0, 0, 0), 1).orElseThrow();

		Set<BlockPos> dug = new HashSet<>();
		Set<BlockPos> filled = new HashSet<>();
		for (BuildPlan.Step step : plan.steps()) {
			if (step.isClear()) {
				dug.add(step.pos());
			} else {
				assertTrue(step.anySolid(), "a fill step takes any solid block: " + step);
				filled.add(step.pos());
			}
		}
		Set<BlockPos> hill = new HashSet<>();
		Set<BlockPos> hole = new HashSet<>();
		for (int z = 0; z < 2; z++) {
			hill.addAll(List.of(new BlockPos(3, 2, z), new BlockPos(4, 2, z), new BlockPos(4, 3, z), new BlockPos(5, 2, z), new BlockPos(5, 3, z)));
			hole.add(new BlockPos(0, 1, z));
		}
		hill.add(new BlockPos(2, 2, 0));
		assertEquals(hill, dug);
		assertEquals(hole, filled);
		assertEquals(BuildPlan.Kind.TERRAFORM, plan.kind());
	}

	@Test
	void digsTopDownAndFillsBottomUp() {
		Map<BlockPos, BlockState> world = new HashMap<>();
		world.put(new BlockPos(0, 0, 0), STONE);
		world.put(new BlockPos(0, 4, 0), STONE);
		world.put(new BlockPos(0, 5, 0), STONE);
		BuildPlan plan = Terraform.plan(pos -> world.getOrDefault(pos, AIR), new BlockPos(0, 0, 0), new BlockPos(0, 5, 0), 3).orElseThrow();

		assertEquals(List.of(
			BuildPlan.Step.clear(new BlockPos(0, 5, 0)),
			BuildPlan.Step.clear(new BlockPos(0, 4, 0)),
			Terraform.fill(new BlockPos(0, 1, 0)),
			Terraform.fill(new BlockPos(0, 2, 0)),
			Terraform.fill(new BlockPos(0, 3, 0))), plan.steps());
	}

	@Test
	void fillBatchesNeedTheDigBatchesAboveThem() {
		// Wide enough for several batches.
		int[] top = new int[12];
		for (int x = 0; x < top.length; x++) {
			top[x] = x % 3;
		}
		Function<BlockPos, BlockState> world = pos -> pos.getY() <= top[Math.floorMod(pos.getX(), top.length)] ? STONE : AIR;
		BuildPlan plan = Terraform.plan(world, new BlockPos(0, 0, 0), new BlockPos(top.length - 1, 4, 9), 1).orElseThrow();

		assertTrue(plan.batches().size() > 2, "batches: " + plan.batches());
		boolean anyFill = false;
		for (int i = 0; i < plan.batches().size(); i++) {
			BuildPlan.Batch batch = plan.batches().get(i);
			List<BuildPlan.Step> steps = plan.steps().subList(batch.from(), batch.to());
			assertFalse(steps.isEmpty(), "empty batch " + i);
			assertTrue(steps.stream().allMatch(step -> step.isClear() == batch.dig()), "batch " + i + " mixes digging and filling");
			if (batch.dig()) {
				assertTrue(batch.needs().isEmpty(), "dig batch " + i + " waits for " + batch.needs());
				continue;
			}
			anyFill = true;
			Set<Long> columns = new HashSet<>();
			steps.forEach(step -> columns.add(column(step.pos())));
			for (int j = 0; j < plan.batches().size(); j++) {
				BuildPlan.Batch other = plan.batches().get(j);
				boolean above = other.dig() && plan.steps().subList(other.from(), other.to()).stream().anyMatch(step -> columns.contains(column(step.pos())));
				assertEquals(above, batch.needs().contains(j), "fill batch " + i + " and dig batch " + j);
			}
		}
		assertTrue(anyFill, "no fill batches");
	}

	private static long column(BlockPos pos) {
		return new BlockPos(pos.getX(), 0, pos.getZ()).asLong();
	}

	/**
	 * Stone up to y=2 with a 3x3 pond, 2 deep, in the corner at x and z 0..2 of a 4x4 area, stone
	 * all round it; the water at (2, 2, 2) flows rather than being a source.
	 */
	private static Function<BlockPos, BlockState> pond() {
		BlockState water = Blocks.WATER.defaultBlockState();
		BlockState flowing = water.setValue(LiquidBlock.LEVEL, 1);
		return pos -> {
			if (pos.getY() > 2) {
				return AIR;
			}
			boolean inPond = pos.getY() >= 1 && pos.getX() >= 0 && pos.getX() <= 2 && pos.getZ() >= 0 && pos.getZ() <= 2;
			if (!inPond) {
				return STONE;
			}
			return pos.equals(new BlockPos(2, 2, 2)) ? flowing : water;
		};
	}

	@Test
	void waterIsFilledFromTheEdgeInwardsSourcesBeforeFlowingWater() {
		Function<BlockPos, BlockState> world = pond();
		BuildPlan plan = Terraform.plan(world, new BlockPos(0, 0, 0), new BlockPos(3, 3, 3), 2).orElseThrow();

		List<BlockPos> order = plan.steps().stream().map(BuildPlan.Step::pos).toList();
		assertTrue(plan.steps().stream().allMatch(step -> step.anySolid()), "only fill steps: " + plan.steps());
		assertEquals(18, order.size(), "every water block is filled: " + order);
		BlockPos flowing = new BlockPos(2, 2, 2);
		// The middle of the pond is farthest from its edge.
		int firstMiddle = Math.min(order.indexOf(new BlockPos(1, 1, 1)), order.indexOf(new BlockPos(1, 2, 1)));
		for (BlockPos pos : order) {
			boolean middle = pos.getX() == 1 && pos.getZ() == 1;
			if (!middle) {
				assertTrue(order.indexOf(pos) < firstMiddle, pos.toShortString() + " at the edge comes after the middle: " + order);
			}
			if (!middle && !pos.equals(flowing)) {
				assertTrue(order.indexOf(pos) < order.indexOf(flowing), "source " + pos.toShortString() + " after the flowing water: " + order);
			}
			if (pos.getY() == 2 && !pos.equals(flowing)) {
				assertTrue(order.indexOf(pos.below()) < order.indexOf(pos), "a column fills from the bottom: " + order);
			}
		}
	}

	@Test
	void eachBatchIsATerraformToo() {
		BuildPlan plan = Terraform.plan(slope(), new BlockPos(0, 0, 0), new BlockPos(5, 4, 1), 1).orElseThrow();
		for (int i = 0; i < plan.batches().size(); i++) {
			assertEquals(BuildPlan.Kind.TERRAFORM, plan.batch(i).kind(), "batch " + i);
		}
	}

	@Test
	void lavaAndBlocksBesideItAreLeftAlone() {
		BlockState lava = Blocks.LAVA.defaultBlockState();
		Map<BlockPos, BlockState> world = new HashMap<>(Map.of(new BlockPos(1, 1, 1), lava));
		Function<BlockPos, BlockState> at = pos -> world.getOrDefault(pos, AIR);
		assertTrue(Terraform.touchesLava(at, new BlockPos(1, 1, 1)));
		assertTrue(Terraform.touchesLava(at, new BlockPos(1, 2, 1)));
		assertTrue(Terraform.touchesLava(at, new BlockPos(2, 1, 1)));
		assertFalse(Terraform.touchesLava(at, new BlockPos(2, 2, 1)));
		world.put(new BlockPos(1, 1, 1), Blocks.WATER.defaultBlockState());
		assertFalse(Terraform.touchesLava(at, new BlockPos(1, 1, 1)), "water is filled");
	}

	@Test
	void aFlatAreaNeedsNoSteps() {
		Function<BlockPos, BlockState> flat = pos -> pos.getY() <= 1 ? STONE : AIR;
		assertTrue(Terraform.plan(flat, new BlockPos(0, 0, 0), new BlockPos(5, 4, 5), 1).isEmpty());
	}

	@Test
	void theCodecKeepsTheKindAndTheDigBatches() {
		BuildPlan plan = Terraform.plan(slope(), new BlockPos(0, 0, 0), new BlockPos(5, 4, 1), 1).orElseThrow();
		var json = BuildPlan.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, plan).getOrThrow();
		assertEquals(plan, BuildPlan.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow());
	}
}
