package io.github.barniebarnie.antcraft.entity.navigation;

import io.github.barniebarnie.antcraft.pathing.Goal;
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
		Vec3 eyes = new Vec3(feet.getX() + 0.5, feet.getY() + Goal.Reach.EYE_HEIGHT, feet.getZ() + 0.5);
		Vec3 middle = Vec3.atCenterOf(target);
		if (clear(level, eyes, middle, target)) {
			return true;
		}
		for (Direction face : Direction.values()) {
			if (clear(level, eyes, middle.add(face.getUnitVec3().scale(INSET)), target)) {
				return true;
			}
		}
		return false;
	}

	/** Whether the first block a ray from {@code from} to {@code to} hits is {@code target}, or none. */
	private static boolean clear(BlockGetter level, Vec3 from, Vec3 to, BlockPos target) {
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
		return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
	}
}
