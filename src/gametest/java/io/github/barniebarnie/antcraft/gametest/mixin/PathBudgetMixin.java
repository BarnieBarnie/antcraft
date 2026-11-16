package io.github.barniebarnie.antcraft.gametest.mixin;

import io.github.barniebarnie.antcraft.gametest.PathCounts;
import io.github.barniebarnie.antcraft.pathing.PathBudget;
import io.github.barniebarnie.antcraft.pathing.PathFinder;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts the nodes each bot's path searches expanded through the path budget ({@link PathCounts#nodes}). */
@Mixin(PathBudget.class)
abstract class PathBudgetMixin {
	@Inject(method = "run", at = @At("RETURN"))
	private void antcraft$count(long tick, Object who, PathFinder.Search search, CallbackInfoReturnable<Integer> cir) {
		if (who instanceof Mob mob) {
			PathCounts.nodes(mob, cir.getReturnValueI());
		}
	}
}
