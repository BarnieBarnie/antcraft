package io.github.barniebarnie.antcraft.work;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.level.WorldDataConfiguration;

/**
 * Loads the vanilla data pack (recipes and tags) the way a server does, without starting one,
 * so unit tests can plan against the real recipe book.
 */
public final class VanillaData {
	private static GameRecipeBook recipeBook;
	private static Set<Item> gatherable;

	private VanillaData() {
	}

	public static synchronized GameRecipeBook recipeBook() {
		if (recipeBook == null) {
			SharedConstants.tryDetectVersion();
			Bootstrap.bootStrap();
			freezeRegistries();
			recipeBook = GameRecipeBook.of(load().getRecipeManager());
		}
		return recipeBook;
	}

	/**
	 * The items in AntCraft's {@code antcraft:gatherable} tag. Only the vanilla data pack is loaded,
	 * so the tag is read from the mod's resources here.
	 */
	public static synchronized Predicate<Item> gatherable() {
		if (gatherable == null) {
			recipeBook();
			Set<Item> items = new HashSet<>();
			try (InputStream in = VanillaData.class.getResourceAsStream("/data/antcraft/tags/item/gatherable.json")) {
				JsonObject tag = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8)).getAsJsonObject();
				for (JsonElement value : tag.getAsJsonArray("values")) {
					String entry = value.getAsString();
					if (entry.startsWith("#")) {
						BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, Identifier.parse(entry.substring(1)))).forEach(holder -> items.add(holder.value()));
					} else {
						items.add(BuiltInRegistries.ITEM.getValue(Identifier.parse(entry)));
					}
				}
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			gatherable = Set.copyOf(items);
		}
		return gatherable::contains;
	}

	/**
	 * Fabric keeps the built-in registries open after bootstrap so mods can register, and freezes
	 * them again when a server starts; loading data needs them frozen, so do that here.
	 */
	private static void freezeRegistries() {
		try {
			Method freeze = BuiltInRegistries.class.getDeclaredMethod("freeze");
			freeze.setAccessible(true);
			freeze.invoke(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("could not freeze the registries", e);
		}
	}

	private static ReloadableServerResources load() {
		PackRepository packs = ServerPacksSource.createVanillaTrustedRepository();
		WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packs, WorldDataConfiguration.DEFAULT, false, true);
		WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.DEDICATED, LevelBasedPermissionSet.OWNER);
		try {
			return WorldLoader.load(
				initConfig,
				context -> new WorldLoader.DataLoadOutput<>(Boolean.TRUE, context.datapackDimensions()),
				(resources, managers, registries, cookie) -> managers,
				Util.backgroundExecutor(),
				Runnable::run).get();
		} catch (Exception e) {
			throw new IllegalStateException("could not load the vanilla data pack", e);
		}
	}
}
