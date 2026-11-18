package io.github.barniebarnie.antcraft.mixin;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A mob walking into a fence gate does not jump at it, as at a fence (#896): vanilla's MoveControl jumps at a block
 * with collision where the mob stands, unless it is in #doors or #fences, so a chicken pressed against a shut gate
 * jumped every 20 ticks.
 */
@Mixin(MoveControl.class)
abstract class MoveControlMixin {
	/** The #fences check, the second {@code is(TagKey)} in tick after #doors. */
	@Redirect(method = "tick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/block/state/BlockState;is(Lnet/minecraft/tags/TagKey;)Z", ordinal = 1))
	private boolean antcraft$gatesAreFences(BlockState state, TagKey<Block> fences) {
		return state.is(fences) || state.is(BlockTags.FENCE_GATES);
	}
}
