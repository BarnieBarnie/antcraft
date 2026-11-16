package io.github.barniebarnie.antcraft.gametest.mixin;

import io.github.barniebarnie.antcraft.gametest.PathCounts;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts each search a mob's vanilla navigation runs ({@link PathCounts#plans}), which path.plan and path.find leave out. */
@Mixin(PathNavigation.class)
abstract class PathNavigationMixin {
	@Shadow
	@Final
	protected Mob mob;

	@Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;"))
	private void antcraft$count(CallbackInfoReturnable<?> cir) {
		PathCounts.plan(this.mob);
	}
}
