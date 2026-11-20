package io.github.barniebarnie.antcraft.client.mixin;

import io.github.barniebarnie.antcraft.entity.AntBot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.conditional.FishingRodCast;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A bot's rod shows cast while its hook is out (docs/reference/blocks/fish.md, #968): vanilla looks only at a player's
 * fishing field, which a bot has none of, so this looks for a hook in the bot's level whose owner is the bot.
 * ponytail: an entity query per frame per bot holding a rod; a client map of bot to hook if many bots fish at once.
 */
@Mixin(FishingRodCast.class)
abstract class FishingRodCastMixin {
	@Inject(method = "get", at = @At("HEAD"), cancellable = true)
	private void antcraft$botRodCast(ItemStack stack, ClientLevel level, LivingEntity entity, int seed, ItemDisplayContext context, CallbackInfoReturnable<Boolean> info) {
		if (!(entity instanceof AntBot bot)) {
			return;
		}
		// FishingHookRenderer.getHoldingArm, as for a player.
		HumanoidArm arm = bot.getMainHandItem().getItem() instanceof FishingRodItem ? bot.getMainArm() : bot.getMainArm().getOpposite();
		info.setReturnValue(bot.getItemHeldByArm(arm) == stack
			&& !bot.level().getEntitiesOfClass(FishingHook.class, bot.getBoundingBox().inflate(32), hook -> hook.getOwner() == bot).isEmpty());
	}
}
