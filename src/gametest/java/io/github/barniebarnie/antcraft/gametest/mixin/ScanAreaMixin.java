package io.github.barniebarnie.antcraft.gametest.mixin;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.gametest.GameTests;
import io.github.barniebarnie.antcraft.script.BlockScan;
import io.github.barniebarnie.antcraft.script.Pos;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps a bot's block scans (scan, find_nearest) inside its game test's area: no block outside it matches
 * ({@link GameTests#hides}), and the radius is cut to the area ({@link GameTests#scanRadius}), so tests never find each
 * other's chests or trees and a radius-32 scan stays cheap (#598, #618).
 */
@Mixin(targets = "io.github.barniebarnie.antcraft.script.SenseApi$Search")
abstract class ScanAreaMixin {
	@Shadow
	@Final
	private AntBot bot;

	@ModifyArg(method = "<init>", at = @At(value = "INVOKE",
		target = "Lio/github/barniebarnie/antcraft/script/BlockScan;<init>(Lio/github/barniebarnie/antcraft/script/Pos;IILio/github/barniebarnie/antcraft/script/BlockScan$Matcher;)V"),
		index = 1)
	private int antcraft$testAreaRadius(Pos center, int radius, int limit, BlockScan.Matcher matcher) {
		return GameTests.scanRadius(this.bot, center.x(), center.y(), center.z(), radius);
	}

	@Inject(method = "matches", at = @At("RETURN"), cancellable = true)
	private void antcraft$testArea(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ() && GameTests.hides(this.bot, new BlockPos(x, y, z))) {
			cir.setReturnValue(false);
		}
	}
}
