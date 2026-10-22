package io.github.barniebarnie.antcraft.gametest.mixin;

import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.GameTestServer;

/**
 * Lets {@code -PgameTest} (the {@code fabric-api.gametest.filter} property) take a comma-separated
 * list of patterns and run the union. Vanilla parses one glob and silently ignores everything from
 * the first comma on. If one of the patterns matches no test, no test is selected, so the server
 * stops with "found no tests" instead of running only the other patterns.
 */
@Mixin(GameTestServer.class)
abstract class GameTestSelectionMixin {
	// Vanilla's logger: a static field of our own here would be merged into GameTestServer.
	@Shadow
	@Final
	private static Logger LOGGER;

	@Inject(method = "getTestsForSelection", at = @At("HEAD"), cancellable = true)
	private static void antcraft$unionOfPatterns(RegistryAccess registries, String selection,
			CallbackInfoReturnable<Stream<Holder.Reference<GameTestInstance>>> cir) {
		if (!selection.contains(",")) {
			return;
		}
		Stream.Builder<Holder.Reference<GameTestInstance>> union = Stream.builder();
		for (String pattern : selection.split(",")) {
			List<Holder.Reference<GameTestInstance>> tests =
					GameTestServer.getTestsForSelection(registries, pattern.strip()).toList();
			if (tests.isEmpty()) {
				LOGGER.warn("Game test pattern {} matches no test", pattern.strip());
				cir.setReturnValue(Stream.empty());
				return;
			}
			tests.forEach(union);
		}
		cir.setReturnValue(union.build().distinct());
	}
}
