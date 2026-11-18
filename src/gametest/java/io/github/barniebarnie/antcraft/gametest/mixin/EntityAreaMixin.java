package io.github.barniebarnie.antcraft.gametest.mixin;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.gametest.GameTests;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Keeps what a bot sees of entities (AntBot.entitiesNear: its entities() and its goals, such as kiting) inside its game
 * test's area, as ScanAreaMixin does its block scans (#742, #899): no entity beside the area is listed
 * ({@link GameTests#hides}), so a hunting test finds no neighbouring test's cow at the default radius, nor a fight test
 * kites from a neighbouring test's creeper. Dropped items (items_near) are left in, as tests put items just outside their
 * area on purpose.
 */
@Mixin(AntBot.class)
abstract class EntityAreaMixin {
	@ModifyArg(method = "entitiesNear", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/level/Level;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"),
		index = 2)
	private Predicate<Entity> antcraft$testArea(Predicate<Entity> what) {
		AntBot bot = (AntBot) (Object) this;
		return entity -> what.test(entity) && (entity instanceof ItemEntity || !GameTests.hides(bot, entity.blockPosition()));
	}
}
