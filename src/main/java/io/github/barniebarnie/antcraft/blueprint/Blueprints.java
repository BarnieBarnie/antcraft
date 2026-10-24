package io.github.barniebarnie.antcraft.blueprint;

import io.github.barniebarnie.antcraft.AntCraft;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.TreeMap;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * The blueprints the server loaded from data packs ({@code data/<namespace>/blueprint/<name>.json}), refilled on every
 * data reload ({@code /reload}). The id of a blueprint is its file's, such as {@code antcraft:starter_house}. A file that
 * does not parse is logged and left out.
 */
public final class Blueprints {
	public static final Identifier RELOADER = AntCraft.id("blueprints");

	private static volatile SequencedMap<Identifier, Blueprint> loaded = Collections.unmodifiableSequencedMap(new TreeMap<>());

	private Blueprints() {
	}

	/** Loads the blueprints with the server's data. */
	public static void register() {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(RELOADER, new Loader());
	}

	/** The blueprint with id {@code id}, if one is loaded. */
	public static Optional<Blueprint> get(Identifier id) {
		return Optional.ofNullable(loaded.get(id));
	}

	/** Every loaded blueprint, by id in id order. */
	public static SequencedMap<Identifier, Blueprint> all() {
		return loaded;
	}

	/** The ids of the loaded blueprints, in order, such as for a client's list. */
	public static List<Identifier> ids() {
		return List.copyOf(loaded.keySet());
	}

	/** Replaces the loaded blueprints. */
	static void set(Map<Identifier, Blueprint> blueprints) {
		loaded = Collections.unmodifiableSequencedMap(new TreeMap<>(blueprints));
	}

	private static final class Loader extends SimpleJsonResourceReloadListener<Blueprint> {
		Loader() {
			super(Blueprint.CODEC, FileToIdConverter.json("blueprint"));
		}

		@Override
		protected void apply(Map<Identifier, Blueprint> blueprints, ResourceManager manager, ProfilerFiller profiler) {
			set(blueprints);
			AntCraft.LOGGER.info("Loaded {} blueprints", blueprints.size());
		}
	}
}
