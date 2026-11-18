package io.github.barniebarnie.antcraft.mixin;

import io.github.barniebarnie.antcraft.entity.BotTemptGoal;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.BlockGetter;
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
 * such as by jobs.ranch's {@code use} to lure an animal in, is open to them as usual, but an animal following a bot
 * that holds its food ({@link BotTemptGoal}) goes through it only the way it swung open, away from whoever opened it
 * (#879): a lured animal follows the bot in, a penned one does not follow it out.
 * <p>
 * Shut to an animal, it is {@link PathFollower#shutTo}'s panel.
 */
@Mixin(FenceGateBlock.class)
abstract class FenceGateBlockMixin {
	@Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
	private void antcraft$shutToAnimals(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext context,
		CallbackInfoReturnable<VoxelShape> cir) {
		if (state.getValue(BlockStateProperties.OPEN) && context instanceof EntityCollisionContext entityContext
			&& entityContext.getEntity() instanceof Animal animal
			&& (PathFollower.openedByBot(animal.level(), pos) || BotTemptGoal.leads(animal) && pastSwing(state, pos, animal))) {
			cir.setReturnValue(PathFollower.shutTo(animal, state, getter, pos));
		}
	}

	/** Whether the animal is on the side an open gate swung to, which vanilla turns away from the one who opened it. */
	private static boolean pastSwing(BlockState state, BlockPos pos, Animal animal) {
		Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
		return (animal.getX() - pos.getX() - 0.5) * facing.getStepX() + (animal.getZ() - pos.getZ() - 0.5) * facing.getStepZ() > 0;
	}
}
