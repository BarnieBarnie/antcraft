package io.github.barniebarnie.antcraft.entity.navigation;

import io.github.barniebarnie.antcraft.pathing.Goal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/**
 * What a bot can see, as a player must to break a block: a clear line from its eyes to the block's
 * middle or to the middle of one of its faces, with no other block in the way.
 */
public final class LineOfSight {
	/** How far inside a face to aim, so the ray ends in the block rather than on its edge. */
	private static final double INSET = 0.45;

	private LineOfSight() {
	}

	/**
	 * Whether a bot standing at {@code feet}, in the middle of the block, sees {@code target}, with the blocks
	 * {@code broken} tests true for as air, such as those a path breaks on its way there.
	 */
	public static boolean sees(BlockGetter world, BlockPos feet, BlockPos target, Predicate<BlockPos> broken) {
		BlockGetter level = new BlockGetter() {
			@Override
			public BlockEntity getBlockEntity(BlockPos pos) {
				return broken.test(pos) ? null : world.getBlockEntity(pos);
			}

			@Override
			public BlockState getBlockState(BlockPos pos) {
				return broken.test(pos) ? Blocks.AIR.defaultBlockState() : world.getBlockState(pos);
			}

			@Override
			public FluidState getFluidState(BlockPos pos) {
				return broken.test(pos) ? Fluids.EMPTY.defaultFluidState() : world.getFluidState(pos);
			}

			@Override
			public int getHeight() {
				return world.getHeight();
			}

			@Override
			public int getMinY() {
				return world.getMinY();
			}
		};
		return aim(level, eyes(feet), target) != null;
	}

	/**
	 * Where the eyes of a bot standing at {@code feet} are for sight: over the middle of the block, wherever in it the bot
	 * stands, so the walk and {@code use} agree on what it sees (docs/reference/blocks/use.md).
	 */
	public static Vec3 eyes(BlockPos feet) {
		return new Vec3(feet.getX() + 0.5, feet.getY() + Goal.Reach.EYE_HEIGHT, feet.getZ() + 0.5);
	}

	/**
	 * Where the first ray from {@code eyes} to one of the points of {@code target} a bot aims at ({@link #aims}) meets it, no
	 * other block's outline in the way; null if every ray hits another block first. A target with no outline (air, a fluid)
	 * is met at the point, on its face turned to the eyes.
	 */
	public static @Nullable BlockHitResult aim(BlockGetter level, Vec3 eyes, BlockPos target) {
		for (Vec3 point : aims(target)) {
			BlockHitResult hit = level.clip(new ClipContext(eyes, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
			if (hit.getType() == HitResult.Type.BLOCK) {
				if (hit.getBlockPos().equals(target)) {
					return hit;
				}
				continue;
			}
			Vec3 toEyes = eyes.subtract(Vec3.atCenterOf(target));
			return new BlockHitResult(point, Direction.getApproximateNearest(toEyes.x, toEyes.y, toEyes.z), target, false);
		}
		return null;
	}

	/** The points of {@code target} a bot aims at, in turn: its middle, then just inside the middle of each face. */
	public static List<Vec3> aims(BlockPos target) {
		Vec3 middle = Vec3.atCenterOf(target);
		List<Vec3> points = new ArrayList<>(List.of(middle));
		for (Direction face : Direction.values()) {
			points.add(middle.add(face.getUnitVec3().scale(INSET)));
		}
		return points;
	}
}
