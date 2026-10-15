package io.github.barniebarnie.antcraft.registry;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	public static final ResourceKey<EntityType<?>> ANT_BOT_KEY = ResourceKey.create(Registries.ENTITY_TYPE, AntCraft.id("ant_bot"));

	public static final EntityType<AntBot> ANT_BOT = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		ANT_BOT_KEY,
		EntityType.Builder.<AntBot>of(AntBot::new, MobCategory.MISC)
			.sized(0.6F, 1.8F)
			.eyeHeight(1.62F)
			.clientTrackingRange(10)
			.build(ANT_BOT_KEY)
	);

	private ModEntities() {
	}

	public static void register() {
		FabricDefaultAttributeRegistry.register(ANT_BOT, AntBot.createAttributes());
	}
}
