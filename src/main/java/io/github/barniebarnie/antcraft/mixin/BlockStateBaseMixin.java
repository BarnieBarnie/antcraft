package io.github.barniebarnie.antcraft.mixin;

import io.github.barniebarnie.antcraft.entity.BotTemptGoal;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin {
	@Shadow
	public abstract Block getBlock();

	/**
	 * An open fence gate counts as reaching out of its block, as a shut one does, so an entity's collisions check it from
	 * the block above too: shut to animals while a bot walks through it (FenceGateBlockMixin, #863), it would not stop a
	 * chicken that jumped clear of its block, as the open state's own shape is empty.
	 */
	@Inject(method = "hasLargeCollisionShape", at = @At("HEAD"), cancellable = true)
	private void antcraft$gateReachesUp(CallbackInfoReturnable<Boolean> cir) {
		if (this.getBlock() instanceof FenceGateBlock) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * A door a bot opened to walk through stays shut to animals, as a gate does (FenceGateBlockMixin, #878): walking out
	 * through chickens crowded at a pen's door, the bot let one out. Both halves, as a chicken jumps into the upper one.
	 * Here, as DoorBlock has no collision shape of its own.
	 */
	@Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"), cancellable = true)
	private void antcraft$doorShutToAnimals(BlockGetter getter, BlockPos pos, CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
		if (this.getBlock() instanceof DoorBlock && context instanceof EntityCollisionContext entityContext
			&& entityContext.getEntity() instanceof Animal animal) {
			BlockState state = (BlockState) (Object) this;
			BlockPos lower = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
			if (state.getValue(DoorBlock.OPEN) && (PathFollower.openedByBot(animal.level(), lower) || BotTemptGoal.leads(animal) && keptIn(animal, lower))) {
				cir.setReturnValue(PathFollower.shutTo(animal, state, getter, pos));
			}
		}
	}

	/**
	 * Whether the animal is on the pen's side of a door a bot opened with use, so one following a bot's food does not
	 * follow it out (PathFollower.usedOpen, #884).
	 */
	private static boolean keptIn(Animal animal, BlockPos lower) {
		Direction inward = PathFollower.inward(animal.level(), lower);
		return inward != null && PathFollower.inside(animal, lower, inward);
	}
}
