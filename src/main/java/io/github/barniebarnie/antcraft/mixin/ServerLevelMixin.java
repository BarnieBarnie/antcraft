package io.github.barniebarnie.antcraft.mixin;

import io.github.barniebarnie.antcraft.script.ScriptRun;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells {@link ScriptRun#blockChanged} of each block that changes: {@code Level.setBlock} calls this for every block
 * whose state it changed, whoever changed it (#539).
 */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@Inject(method = "updatePOIOnBlockStateChange", at = @At("HEAD"))
	private void antcraft$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		ScriptRun.blockChanged(pos.asLong());
	}
}
