package io.github.barniebarnie.antcraft.registry;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.blueprint.ChosenBlueprint;
import com.mojang.serialization.Codec;
import io.github.barniebarnie.antcraft.item.CoreScript;
import io.github.barniebarnie.antcraft.item.RememberedBot;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

/** The mod's item data components and entity data attachments. */
public final class ModComponents {
	/** The blueprint a Blueprint item is set to. */
	public static final DataComponentType<ChosenBlueprint> CHOSEN_BLUEPRINT = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		AntCraft.id("chosen_blueprint"),
		DataComponentType.<ChosenBlueprint>builder().persistent(ChosenBlueprint.CODEC).networkSynchronized(ChosenBlueprint.STREAM_CODEC).build()
	);

	/** The script a Bot Core's bot starts with (see {@code BotActivation}). */
	public static final DataComponentType<CoreScript> CORE_SCRIPT = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		AntCraft.id("script"),
		DataComponentType.<CoreScript>builder().persistent(CoreScript.CODEC).networkSynchronized(CoreScript.STREAM_CODEC).build()
	);

	/** The bot a Bot Core was picked up from, which the core brings back (#916; see {@code BotActivation}). */
	public static final DataComponentType<RememberedBot> REMEMBERED_BOT = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		AntCraft.id("remembered_bot"),
		DataComponentType.<RememberedBot>builder().persistent(RememberedBot.CODEC).networkSynchronized(RememberedBot.STREAM_CODEC).build()
	);

	/**
	 * The game time an entity was first seen by a script, saved with it, so its age goes on across chunk reloads (#886;
	 * docs/reference/types/entity.md).
	 */
	public static final AttachmentType<Long> FIRST_SEEN = AttachmentRegistry.createPersistent(AntCraft.id("first_seen"), Codec.LONG);

	private ModComponents() {
	}

	public static void register() {
		// Loading the class registers the components and attachments.
	}
}
