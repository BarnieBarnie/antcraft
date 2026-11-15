package io.github.barniebarnie.antcraft.gametest.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.gametest.GameTests;
import io.github.barniebarnie.antcraft.script.SenseApi;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Keeps a bot's entities() inside its game test's area, as ScanAreaMixin does its block scans (#742): no entity beside
 * the area is listed ({@link GameTests#hides}), so a hunting test finds no neighbouring test's cow at the default radius.
 * Dropped items (items_near) are left in, as tests put items just outside their area on purpose.
 */
@Mixin(SenseApi.class)
abstract class EntityAreaMixin {
	@ModifyArg(method = "near", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"),
		index = 2)
	private static Predicate<Entity> antcraft$testArea(Predicate<Entity> what, @Local(argsOnly = true) AntBot bot) {
		return entity -> what.test(entity) && (entity instanceof ItemEntity || !GameTests.hides(bot, entity.blockPosition()));
	}
}
