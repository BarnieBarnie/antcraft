package io.github.barniebarnie.antcraft.mixin;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A bot's fishing hook (docs/reference/blocks/fish.md): made with the rod's luck and lure, and whether a fish bites. */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {
	/** A hook with Luck of the Sea {@code luck} and {@code lure} ticks off the wait, as the rod makes a player's. */
	@Invoker("<init>")
	static FishingHook antcraft$create(EntityType<? extends FishingHook> type, Level level, int luck, int lure) {
		throw new AssertionError();
	}

	/** How many ticks a fish still bites: reeled in meanwhile, the hook catches it. */
	@Accessor("nibble")
	int antcraft$nibble();
}
