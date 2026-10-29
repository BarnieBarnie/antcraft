package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a script asks for by name (docs/reference/index.md, Selectors): {@code "oak_log"} (namespace {@code minecraft:}
 * assumed), {@code "minecraft:oak_log"}, a tag {@code "#minecraft:logs"}, or a list or tuple of these. Parsing is pure;
 * {@link #blocks}, {@link #items} and {@link #entities} look the names up in the game's registries.
 * <p>
 * Parsed selectors and their predicates are cached, as scripts ask for the same ones in loops (#340). The predicates
 * read tags live, but whether a tag exists is checked once, so {@link #clearCache} runs when data packs reload.
 *
 * @param ids full ids such as {@code minecraft:oak_log}
 * @param tags full tag ids, without the {@code #}
 */
public record Selector(List<String> ids, List<String> tags) {
	private static final String TYPE_ERROR = "a selector is a str or a list of str, not '";
	/** ponytail: a cache is cleared whole when it grows past this; an LRU if scripts make many selectors. */
	private static final int CACHE_SIZE = 1024;
	/** Parsed selectors by the str, or the list of str, they came from. */
	private static final Map<Object, Selector> PARSED = new ConcurrentHashMap<>();
	private static final Map<Selector, Predicate<BlockState>> BLOCKS = new ConcurrentHashMap<>();
	private static final Map<Selector, Predicate<ItemStack>> ITEMS = new ConcurrentHashMap<>();
	private static final Map<Selector, Predicate<Entity>> ENTITIES = new ConcurrentHashMap<>();

	public Selector {
		ids = List.copyOf(ids);
		tags = List.copyOf(tags);
	}

	/** Forgets every cached selector, so tags are looked up again (after a data pack reload). */
	public static void clearCache() {
		PARSED.clear();
		BLOCKS.clear();
		ITEMS.clear();
		ENTITIES.clear();
	}

	private static <K, V> V cached(Map<K, V> cache, K key, Function<K, V> compute) {
		V found = cache.get(key);
		if (found == null) {
			found = compute.apply(key);
			if (cache.size() >= CACHE_SIZE) {
				cache.clear();
			}
			cache.put(key, found);
		}
		return found;
	}

	/** {@code value} as a selector: a str, or a list or tuple of str. */
	public static Selector parse(Object value) {
		Object key = switch (value) {
			case String s -> s;
			case PyList list when list.items.stream().allMatch(String.class::isInstance) -> List.copyOf(list.items);
			case PyTuple tuple when tuple.items().stream().allMatch(String.class::isInstance) -> List.copyOf(tuple.items());
			default -> null;
		};
		return key == null ? parseUncached(value) : cached(PARSED, key, k -> parseUncached(value));
	}

	private static Selector parseUncached(Object value) {
		List<Object> parts = switch (value) {
			case String s -> List.of(s);
			case PyList list -> list.items;
			case PyTuple tuple -> tuple.items();
			default -> throw new ScriptError(TYPE_ERROR + Values.typeName(value) + "'");
		};
		if (parts.isEmpty()) {
			throw new ScriptError("an empty " + Values.typeName(value) + " selects nothing");
		}
		List<String> ids = new ArrayList<>();
		List<String> tags = new ArrayList<>();
		for (Object part : parts) {
			if (!(part instanceof String text)) {
				throw new ScriptError(TYPE_ERROR + Values.typeName(part) + "'");
			}
			if (text.startsWith("#")) {
				if (text.length() == 1) {
					throw bad(text, "a tag needs a name after '#'");
				}
				tags.add(id(text, text.substring(1)));
			} else {
				ids.add(id(text, text));
			}
		}
		return new Selector(ids, tags);
	}

	/** {@code name} as a full id; {@code text} is the selector it came from, for the error. */
	private static String id(String text, String name) {
		return id(text, name, "a selector");
	}

	/** {@code name} as a full id; the error says {@code text} is not {@code what}. */
	private static String id(String text, String name, String what) {
		if (name.isEmpty()) {
			throw bad(text, what, "it is empty");
		}
		int colon = name.indexOf(':');
		if (colon != name.lastIndexOf(':')) {
			throw bad(text, what, "a name has at most one ':'");
		}
		String namespace = colon < 0 ? "minecraft" : name.substring(0, colon);
		String path = name.substring(colon + 1);
		if (namespace.isEmpty()) {
			throw bad(text, what, "the namespace before ':' is empty");
		}
		if (path.isEmpty()) {
			throw bad(text, what, "the name after ':' is empty");
		}
		if (!path.chars().allMatch(c -> namespaceChar(c) || c == '/')) {
			throw bad(text, what, "names use only a-z, 0-9, '_', '-', '.' and '/'");
		}
		if (!namespace.chars().allMatch(Selector::namespaceChar)) {
			throw bad(text, what, "a namespace uses only a-z, 0-9, '_', '-' and '.'");
		}
		return namespace + ":" + path;
	}

	private static boolean namespaceChar(int c) {
		return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.';
	}

	private static ScriptError bad(String text, String why) {
		return bad(text, "a selector", why);
	}

	private static ScriptError bad(String text, String what, String why) {
		return new ScriptError(Values.repr(text) + " is not " + what + ": " + why);
	}

	/**
	 * {@code matches(item, selector)}: whether the item, an id such as {@code "birch_log"} or a {@link Stack}, is one
	 * {@code selector} selects. An unknown item, or an unknown item or tag in the selector, fails the script.
	 */
	public static boolean matches(Object item, Object selector) {
		String text = switch (item) {
			case String s -> s;
			case Stack stack -> stack.item();
			default -> throw new ScriptError("matches() item must be an item id or a Stack, not '" + Values.typeName(item) + "'");
		};
		String id = id(text, text, "an item id");
		Item found = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
			.orElseThrow(() -> new ScriptError("unknown item '" + id + "'"));
		return parse(selector).items().test(new ItemStack(found));
	}

	/** Which blocks this selects; an unknown block or block tag fails the script. */
	public Predicate<BlockState> blocks() {
		return cached(BLOCKS, this, Selector::resolveBlocks);
	}

	private Predicate<BlockState> resolveBlocks() {
		Set<Block> blocks = resolve(BuiltInRegistries.BLOCK, "block");
		List<TagKey<Block>> tags = this.tags(BuiltInRegistries.BLOCK, Registries.BLOCK, "block");
		return state -> blocks.contains(state.getBlock()) || tags.stream().anyMatch(state::is);
	}

	/** Which item stacks this selects; an unknown item or item tag fails the script. */
	public Predicate<ItemStack> items() {
		return cached(ITEMS, this, Selector::resolveItems);
	}

	private Predicate<ItemStack> resolveItems() {
		Set<Item> items = resolve(BuiltInRegistries.ITEM, "item");
		List<TagKey<Item>> tags = this.tags(BuiltInRegistries.ITEM, Registries.ITEM, "item");
		return stack -> !stack.isEmpty() && (items.contains(stack.getItem()) || tags.stream().anyMatch(stack::is));
	}

	/** Which entities this selects by type; an unknown entity type or entity type tag fails the script. */
	public Predicate<Entity> entities() {
		return cached(ENTITIES, this, Selector::resolveEntities);
	}

	private Predicate<Entity> resolveEntities() {
		Set<EntityType<?>> types = resolve(BuiltInRegistries.ENTITY_TYPE, "entity type");
		List<TagKey<EntityType<?>>> tags = this.tags(BuiltInRegistries.ENTITY_TYPE, Registries.ENTITY_TYPE, "entity type");
		return entity -> types.contains(entity.getType()) || tags.stream().anyMatch(entity.getType().builtInRegistryHolder()::is);
	}

	private <T> Set<T> resolve(Registry<T> registry, String kind) {
		return this.ids.stream().map(id -> registry.getOptional(Identifier.parse(id))
			.orElseThrow(() -> new ScriptError("unknown " + kind + " '" + id + "'"))).collect(Collectors.toSet());
	}

	private <T> List<TagKey<T>> tags(Registry<T> registry, ResourceKey<? extends Registry<T>> key, String kind) {
		List<TagKey<T>> keys = new ArrayList<>();
		for (String tag : this.tags) {
			TagKey<T> tagKey = TagKey.create(key, Identifier.parse(tag));
			if (registry.get(tagKey).isEmpty()) {
				throw new ScriptError("unknown " + kind + " tag '#" + tag + "'");
			}
			keys.add(tagKey);
		}
		return keys;
	}

	/** Prints as the selector given, with full ids: {@code 'minecraft:oak_log'} or a list of such strings. */
	@Override
	public String toString() {
		List<String> all = new ArrayList<>(this.ids);
		this.tags.forEach(tag -> all.add("#" + tag));
		return all.size() == 1 ? Values.repr(all.getFirst()) : Values.repr(new PyList(new ArrayList<>(all)));
	}
}
