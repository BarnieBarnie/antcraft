package io.github.barniebarnie.antcraft.gametest.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The ticks until the server's next autosave, which the bot benchmark puts off while it measures (BenchmarkGameTest). */
@Mixin(MinecraftServer.class)
public interface AutosaveAccessor {
	@Accessor("ticksUntilAutosave")
	int antcraft$getTicksUntilAutosave();

	@Accessor("ticksUntilAutosave")
	void antcraft$setTicksUntilAutosave(int ticks);
}
