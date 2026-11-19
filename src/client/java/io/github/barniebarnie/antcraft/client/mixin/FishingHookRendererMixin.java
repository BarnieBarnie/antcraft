package io.github.barniebarnie.antcraft.client.mixin;

import io.github.barniebarnie.antcraft.entity.AntBot;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.FishingHookRenderer;
import net.minecraft.client.renderer.entity.state.FishingHookRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Draws the bobber and line of a hook a bot cast (docs/reference/blocks/fish.md, #968): vanilla draws only a hook whose
 * owner is a player, so this draws a bot's too, the line from its rod hand as for a player seen in third person.
 */
@Mixin(FishingHookRenderer.class)
abstract class FishingHookRendererMixin {
	@Inject(method = "shouldRender(Lnet/minecraft/world/entity/projectile/FishingHook;Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z", at = @At("HEAD"), cancellable = true)
	private void antcraft$renderBotHook(FishingHook hook, Frustum frustum, double x, double y, double z, float partialTicks, CallbackInfoReturnable<Boolean> info) {
		if (hook.getOwner() instanceof AntBot) {
			info.setReturnValue(true);
		}
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/projectile/FishingHook;Lnet/minecraft/client/renderer/entity/state/FishingHookRenderState;F)V", at = @At("TAIL"))
	private void antcraft$botLine(FishingHook hook, FishingHookRenderState state, float partialTicks, CallbackInfo info) {
		if (!(hook.getOwner() instanceof AntBot bot)) {
			return;
		}
		// FishingHookRenderer.getPlayerHandPos, third person.
		HumanoidArm arm = bot.getMainHandItem().getItem() instanceof FishingRodItem ? bot.getMainArm() : bot.getMainArm().getOpposite();
		int side = arm == HumanoidArm.RIGHT ? 1 : -1;
		float yaw = Mth.lerp(partialTicks, bot.yBodyRotO, bot.yBodyRot) * Mth.DEG_TO_RAD;
		double sin = Mth.sin(yaw);
		double cos = Mth.cos(yaw);
		float scale = bot.getScale();
		double sideways = side * 0.35 * scale;
		double forward = 0.8 * scale;
		float crouch = bot.isCrouching() ? -0.1875F : 0.0F;
		Vec3 hand = bot.getEyePosition(partialTicks).add(-cos * sideways - sin * forward, crouch - 0.45 * scale, -sin * sideways + cos * forward);
		state.lineOriginOffset = hand.subtract(hook.getPosition(partialTicks).add(0.0, 0.25, 0.0));
	}
}
