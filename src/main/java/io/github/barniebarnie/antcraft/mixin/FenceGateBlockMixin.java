package io.github.barniebarnie.antcraft.mixin;

import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A fence gate a bot opened to walk through stays shut to animals (#863): walking out of a pen through animals crowded
 * at its gate, the bot shoved one out ahead of it, or the crowd pressed one through behind it. A gate opened otherwise,
 * such as by jobs.ranch's {@code use} to lure an animal in, is open to them as usual.
 * <p>
 * Shut to an animal, it is a shut gate's panel reaching on to the far side of its block, away from the animal, as high:
 * a chicken held only by the thin panel stood in it once the gate shut, and so slipped through it, as an entity moves
 * freely out of a shape it is in. Its near face is the shut panel's, so the gate shuts clear of any animal against it.
 */
@Mixin(FenceGateBlock.class)
abstract class FenceGateBlockMixin {
	private static final VoxelShape TO_NORTH = Block.box(0, 0, 0, 16, 24, 10);
	private static final VoxelShape TO_SOUTH = Block.box(0, 0, 6, 16, 24, 16);
	private static final VoxelShape TO_WEST = Block.box(0, 0, 0, 10, 24, 16);
	private static final VoxelShape TO_EAST = Block.box(6, 0, 0, 16, 24, 16);

	@Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
	private void antcraft$shutToAnimals(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext context,
		CallbackInfoReturnable<VoxelShape> cir) {
		if (state.getValue(BlockStateProperties.OPEN) && context instanceof EntityCollisionContext entityContext
			&& entityContext.getEntity() instanceof Animal animal && PathFollower.openedByBot(animal.level(), pos)) {
			boolean acrossZ = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis() == Direction.Axis.Z;
			cir.setReturnValue(acrossZ
				? animal.getZ() > pos.getZ() + 0.5 ? TO_NORTH : TO_SOUTH
				: animal.getX() > pos.getX() + 0.5 ? TO_WEST : TO_EAST);
		}
	}
}
