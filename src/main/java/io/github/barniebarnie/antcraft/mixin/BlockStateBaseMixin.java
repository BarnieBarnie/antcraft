package io.github.barniebarnie.antcraft.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An open fence gate counts as reaching out of its block, as a shut one does, so an entity's collisions check it from
 * the block above too: shut to animals while a bot walks through it (FenceGateBlockMixin, #863), it would not stop a
 * chicken that jumped clear of its block, as the open state's own shape is empty.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateBaseMixin {
	@Shadow
	public abstract Block getBlock();

	@Inject(method = "hasLargeCollisionShape", at = @At("HEAD"), cancellable = true)
	private void antcraft$gateReachesUp(CallbackInfoReturnable<Boolean> cir) {
		if (this.getBlock() instanceof FenceGateBlock) {
			cir.setReturnValue(true);
		}
	}
}
