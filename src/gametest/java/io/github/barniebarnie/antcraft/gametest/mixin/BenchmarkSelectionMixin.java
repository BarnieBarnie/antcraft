package io.github.barniebarnie.antcraft.gametest.mixin;

import net.minecraft.gametest.framework.GameTestInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs the manual-only game tests, the bot benchmark ({@code BenchmarkGameTest}), when the {@code antcraft.benchmark}
 * system property is set ({@code ./gradlew runGameTest -Pbenchmark}). Vanilla never runs a manual-only test from the game
 * test server, not even one the selection names, so every other run leaves the benchmark out.
 */
@Mixin(GameTestInstance.class)
abstract class BenchmarkSelectionMixin {
	@Inject(method = "manualOnly", at = @At("HEAD"), cancellable = true)
	private void antcraft$runBenchmark(CallbackInfoReturnable<Boolean> cir) {
		if (Boolean.getBoolean("antcraft.benchmark")) {
			cir.setReturnValue(false);
		}
	}
}
