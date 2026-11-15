package io.github.barniebarnie.antcraft.mixin;

import io.github.barniebarnie.antcraft.entity.AntBot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A bot's arrow, or any projectile a bot shot, passes through every bot, its own owner's or colony's or not (#775,
 * docs/features/bots.md, Fighting creepers): bots only ever shoot mobs, so a bot in the line of fire is always friendly fire.
 */
@Mixin(Projectile.class)
abstract class ProjectileMixin {
	@Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
	private void antcraft$passBots(Entity target, CallbackInfoReturnable<Boolean> cir) {
		if (target instanceof AntBot && ((Projectile) (Object) this).getOwner() instanceof AntBot) {
			cir.setReturnValue(false);
		}
	}
}
