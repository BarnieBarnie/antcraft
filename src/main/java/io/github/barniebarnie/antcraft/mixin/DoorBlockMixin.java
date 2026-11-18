package io.github.barniebarnie.antcraft.mixin;

import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Has {@link PathFollower#usedOpen} note the pen's side of a door opened or shut by hand, a player's or a bot's (#905). */
@Mixin(DoorBlock.class)
abstract class DoorBlockMixin {
	@Inject(method = "useWithoutItem", at = @At("RETURN"))
	private void antcraft$noteInward(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir) {
		if (level instanceof ServerLevel server) {
			PathFollower.usedOpen(server, pos);
		}
	}
}
