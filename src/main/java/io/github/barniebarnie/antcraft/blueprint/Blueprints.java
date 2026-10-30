package io.github.barniebarnie.antcraft.blueprint;

import io.github.barniebarnie.antcraft.AntCraft;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.TreeMap;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The blueprints the server loaded from data packs ({@code data/<namespace>/blueprint/<name>.json}, and Litematica
 * schematics {@code <name>.litematic} beside them, see {@link Litematic}), refilled on every data reload ({@code /reload}).
 * The id of a blueprint is its file's, such as {@code antcraft:starter_house}; where a JSON file and a schematic share
 * one, the JSON file's wins. A file that does not parse is logged and left out.
 */
public final class Blueprints {
	public static final Identifier RELOADER = AntCraft.id("blueprints");

	private static final FileToIdConverter LITEMATICS = new FileToIdConverter("blueprint", ".litematic");

	private static volatile SequencedMap<Identifier, Blueprint> loaded = Collections.unmodifiableSequencedMap(new TreeMap<>());
	/** The structures of the loaded schematics, by structure id (that of their blueprint). */
	private static volatile Map<Identifier, StructureTemplate> imported = Map.of();

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

	/** The structure {@code blueprint} builds: a loaded schematic's, else the one {@code server}'s structure manager has by that id. */
	public static Optional<StructureTemplate> template(MinecraftServer server, Blueprint blueprint) {
		return Optional.ofNullable(imported.get(blueprint.structure())).or(() -> server.getStructureTemplateManager().get(blueprint.structure()));
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
		protected void apply(Map<Identifier, Blueprint> json, ResourceManager manager, ProfilerFiller profiler) {
			Map<Identifier, Blueprint> blueprints = new HashMap<>(json);
			Map<Identifier, StructureTemplate> templates = new HashMap<>();
			// ponytail: schematics are read here on the server thread, not in prepare; move them there if large ones stall /reload.
			for (Map.Entry<Identifier, Resource> file : LITEMATICS.listMatchingResources(manager).entrySet()) {
				Identifier id = LITEMATICS.fileToId(file.getKey());
				if (blueprints.containsKey(id)) {
					AntCraft.LOGGER.warn("Blueprint {} has a JSON file and a schematic; the schematic {} is left out", id, file.getKey());
					continue;
				}
				try (InputStream in = file.getValue().open()) {
					CompoundTag schematic = Litematic.read(in);
					templates.put(id, Litematic.template(schematic));
					blueprints.put(id, Litematic.blueprint(id, schematic));
				} catch (IOException | RuntimeException e) {
					AntCraft.LOGGER.error("Could not read schematic {}", file.getKey(), e);
				}
			}
			imported = Map.copyOf(templates);
			set(blueprints);
			AntCraft.LOGGER.info("Loaded {} blueprints, {} of them schematics", blueprints.size(), templates.size());
		}
	}
}
