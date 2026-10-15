package io.github.barniebarnie.antcraft.registry;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.item.BotCoreItem;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public final class ModItems {
	public static final ResourceKey<Item> BOT_CORE_KEY = ResourceKey.create(Registries.ITEM, AntCraft.id("bot_core"));

	public static final Item BOT_CORE = Registry.register(
		BuiltInRegistries.ITEM,
		BOT_CORE_KEY,
		new BotCoreItem(new Item.Properties().setId(BOT_CORE_KEY).stacksTo(16))
	);

	private ModItems() {
	}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(BOT_CORE));
	}
}
