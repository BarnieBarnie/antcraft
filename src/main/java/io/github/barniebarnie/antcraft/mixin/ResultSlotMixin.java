package io.github.barniebarnie.antcraft.mixin;

import io.github.barniebarnie.antcraft.item.RememberedBot;
import io.github.barniebarnie.antcraft.registry.ModComponents;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A remembering Bot Core used up in a crafting grid, as its wipe recipe does, drops the memory the server kept for it
 * (#939; docs/features/bots.md, Picking a bot up). {@code BotCoreWipeRecipe.assemble} has no server to do it.
 */
@Mixin(ResultSlot.class)
abstract class ResultSlotMixin {
	@Inject(method = "getRemainingItems", at = @At("HEAD"))
	private void antcraft$forgetUsedCores(CraftingInput input, Level level, CallbackInfoReturnable<NonNullList<ItemStack>> cir) {
		if (level instanceof ServerLevel server) {
			for (ItemStack stack : input.items()) {
				RememberedBot remembered = stack.get(ModComponents.REMEMBERED_BOT);
				if (remembered != null) {
					remembered.forget(server.getServer());
				}
			}
		}
	}
}
