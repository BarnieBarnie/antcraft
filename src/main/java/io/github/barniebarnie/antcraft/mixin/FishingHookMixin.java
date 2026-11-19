package io.github.barniebarnie.antcraft.mixin;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.barniebarnie.antcraft.entity.AntBot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A fishing hook a bot cast (docs/reference/blocks/fish.md, #962) works as a player's: vanilla drops a hook whose owner is
 * no player at once, so this keeps it, stops it as a player's when the bot lets go of its rod or walks off, and reels it
 * in as a player's, the catch from the fishing loot table with the rod's Luck of the Sea, flying to the bot.
 */
@Mixin(FishingHook.class)
abstract class FishingHookMixin {
	@Shadow
	private Entity hookedIn;
	@Shadow
	private int nibble;
	@Shadow
	@Final
	private int luck;

	@Shadow
	protected abstract void pullEntity(Entity entity);

	private FishingHook antcraft$self() {
		return (FishingHook) (Object) this;
	}

	@Definition(id = "player", local = @Local(type = Player.class))
	@Expression("player == null")
	@ModifyExpressionValue(method = "tick", at = @At("MIXINEXTRAS:EXPRESSION"))
	private boolean antcraft$keepBotHook(boolean ownerless) {
		return ownerless && !(this.antcraft$self().getOwner() instanceof AntBot);
	}

	@Definition(id = "getPlayerOwner", method = "Lnet/minecraft/world/entity/projectile/FishingHook;getPlayerOwner()Lnet/minecraft/world/entity/player/Player;")
	@Expression("this.getPlayerOwner() == null")
	@ModifyExpressionValue(method = "recreateFromPacket", at = @At("MIXINEXTRAS:EXPRESSION"))
	private boolean antcraft$keepBotHookOnClient(boolean ownerless) {
		return ownerless && !(this.antcraft$self().getOwner() instanceof AntBot);
	}

	/** As a player's: the hook goes once the bot is gone, holds no rod, or is 32 blocks off. */
	@Inject(method = "shouldStopFishing", at = @At("HEAD"), cancellable = true)
	private void antcraft$stopBotHook(Player player, CallbackInfoReturnable<Boolean> cir) {
		FishingHook hook = this.antcraft$self();
		if (player == null && hook.getOwner() instanceof AntBot bot) {
			boolean stop = !bot.isAlive() || !bot.getMainHandItem().is(Items.FISHING_ROD) && !bot.getOffhandItem().is(Items.FISHING_ROD)
				|| hook.distanceToSqr(bot) > 1024;
			if (stop) {
				hook.discard();
			}
			cir.setReturnValue(stop);
		}
	}

	/** As FishingHook.retrieve for a player, without the stats, advancements and experience a bot has none of. */
	@Inject(method = "retrieve", at = @At("HEAD"), cancellable = true)
	private void antcraft$retrieveBotHook(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
		FishingHook hook = this.antcraft$self();
		if (!(hook.getOwner() instanceof AntBot bot) || !(hook.level() instanceof ServerLevel level)) {
			return;
		}
		int damage = 0;
		if (this.hookedIn != null) {
			this.pullEntity(this.hookedIn);
			level.broadcastEntityEvent(hook, (byte) 31);
			damage = this.hookedIn instanceof ItemEntity ? 3 : 5;
		} else if (this.nibble > 0) {
			LootParams params = new LootParams.Builder(level)
				.withParameter(LootContextParams.ORIGIN, hook.position())
				.withParameter(LootContextParams.TOOL, rod)
				.withParameter(LootContextParams.THIS_ENTITY, hook)
				.withLuck(this.luck)
				.create(LootContextParamSets.FISHING);
			for (ItemStack stack : level.getServer().reloadableRegistries().getLootTable(BuiltInLootTables.FISHING).getRandomItems(params)) {
				ItemEntity item = new ItemEntity(level, hook.getX(), hook.getY(), hook.getZ(), stack);
				double dx = bot.getX() - hook.getX();
				double dy = bot.getY() - hook.getY();
				double dz = bot.getZ() - hook.getZ();
				item.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
				level.addFreshEntity(item);
			}
			damage = 1;
		}
		if (hook.onGround()) {
			damage = 2;
		}
		hook.discard();
		cir.setReturnValue(damage);
	}
}
