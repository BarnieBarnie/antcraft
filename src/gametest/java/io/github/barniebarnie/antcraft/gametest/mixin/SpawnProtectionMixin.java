package io.github.barniebarnie.antcraft.gametest.mixin;

import io.github.barniebarnie.antcraft.gametest.GameTests;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spawn protection for the blocks a game test asks for ({@link GameTests#protect}). The game test server, unlike a
 * dedicated one, protects nothing; this answers as a dedicated server does for a block near spawn and a player who is
 * not an operator.
 */
@Mixin(MinecraftServer.class)
abstract class SpawnProtectionMixin {
	@Inject(method = "isUnderSpawnProtection", at = @At("HEAD"), cancellable = true)
	private void antcraft$testProtection(ServerLevel level, BlockPos pos, Player player, CallbackInfoReturnable<Boolean> cir) {
		if (GameTests.isSpawnProtected(pos)) {
			cir.setReturnValue(true);
		}
	}
}
