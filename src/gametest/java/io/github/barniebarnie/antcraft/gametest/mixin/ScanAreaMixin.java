package io.github.barniebarnie.antcraft.gametest.mixin;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.gametest.GameTests;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a bot's block scans (scan, find_nearest) from finding a container outside its game test's area
 * ({@link GameTests#hidesContainer}), so tests never take from each other's chests (#598).
 */
@Mixin(targets = "io.github.barniebarnie.antcraft.script.SenseApi$Search")
abstract class ScanAreaMixin {
	@Shadow
	@Final
	private AntBot bot;

	@Inject(method = "matches", at = @At("RETURN"), cancellable = true)
	private void antcraft$testArea(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && GameTests.hidesContainer(this.bot, new BlockPos(x, y, z))) {
			cir.setReturnValue(false);
		}
	}
}
