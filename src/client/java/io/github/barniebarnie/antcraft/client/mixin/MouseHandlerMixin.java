package io.github.barniebarnie.antcraft.client.mixin;

import io.github.barniebarnie.antcraft.client.BlueprintPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scrolling while sneaking with a Blueprint turns its ghost instead of changing the selected slot (see {@link BlueprintPreview#scroll}). */
@Mixin(MouseHandler.class)
abstract class MouseHandlerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void antcraft$turnBlueprint(long handle, double xoffset, double yoffset, CallbackInfo info) {
		if (handle == this.minecraft.getWindow().handle() && BlueprintPreview.scroll(yoffset)) {
			info.cancel();
		}
	}
}
