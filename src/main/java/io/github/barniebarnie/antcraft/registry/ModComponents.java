package io.github.barniebarnie.antcraft.registry;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.blueprint.ChosenBlueprint;
import io.github.barniebarnie.antcraft.item.CoreScript;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

/** The mod's item data components. */
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

	private ModComponents() {
	}

	public static void register() {
		// Loading the class registers the components.
	}
}
