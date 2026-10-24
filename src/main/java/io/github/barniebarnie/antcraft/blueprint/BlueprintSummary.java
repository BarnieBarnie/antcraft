package io.github.barniebarnie.antcraft.blueprint;

import io.github.barniebarnie.antcraft.work.BuildPlan;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * What a client knows of a blueprint the server loaded: enough to list it in the picker. It holds no blocks, so the list
 * of every blueprint stays small however large the structures are; a client asks for the blocks of the one it previews
 * ({@link BlueprintBlocks}).
 *
 * @param id the blueprint's id, as {@link Blueprints#get} takes it
 * @param name the lang key of its display name
 * @param size the structure's size, zero when the server does not have the structure
 * @param materials the items a builder places for it, most first (see {@link #materials(List)})
 */
public record BlueprintSummary(Identifier id, String name, Optional<Blueprint.Role> role, Vec3i size, SequencedMap<Item, Integer> materials) {
	/** Prefix of the picker's lang keys. */
	public static final String KEY = "screen.antcraft.blueprint_picker";
	/** Followed by a {@link Blueprint.Role#getSerializedName()}. */
	public static final String ROLE_KEY = "screen.antcraft.blueprint_picker.role.";

	/** The materials in their order, each item by its id in the item registry. */
	private static final StreamCodec<RegistryFriendlyByteBuf, SequencedMap<Item, Integer>> MATERIALS_CODEC = StreamCodec.of(
		(buf, materials) -> {
			buf.writeVarInt(materials.size());
			materials.forEach((item, count) -> {
				buf.writeVarInt(BuiltInRegistries.ITEM.getId(item));
				buf.writeVarInt(count);
			});
		},
		buf -> {
			SequencedMap<Item, Integer> materials = new LinkedHashMap<>();
			int size = buf.readVarInt();
			for (int i = 0; i < size; i++) {
				materials.put(BuiltInRegistries.ITEM.byIdOrThrow(buf.readVarInt()), buf.readVarInt());
			}
			return materials;
		});

	public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintSummary> STREAM_CODEC = StreamCodec.composite(
		Identifier.STREAM_CODEC, BlueprintSummary::id,
		ByteBufCodecs.STRING_UTF8, BlueprintSummary::name,
		ByteBufCodecs.optional(ByteBufCodecs.idMapper(i -> Blueprint.Role.values()[i], Blueprint.Role::ordinal)), BlueprintSummary::role,
		Vec3i.STREAM_CODEC, BlueprintSummary::size,
		MATERIALS_CODEC, BlueprintSummary::materials,
		BlueprintSummary::new);

	public BlueprintSummary {
		materials = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(materials));
	}

	/** A block of a structure, at {@code pos} relative to its origin. */
	public record Block(BlockPos pos, BlockState state) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Block> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, Block::pos,
			ByteBufCodecs.idMapper(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY), Block::state,
			Block::new);
	}

	/** The summary of {@code blueprint}, with the size and materials of {@code template} if the server has its structure. */
	public static BlueprintSummary of(Identifier id, Blueprint blueprint, Optional<StructureTemplate> template) {
		return of(id, blueprint, BlueprintBlocks.of(id, template));
	}

	/** The summary of {@code blueprint}, whose structure has {@code blocks}. */
	public static BlueprintSummary of(Identifier id, Blueprint blueprint, BlueprintBlocks blocks) {
		return new BlueprintSummary(id, blueprint.name(), blueprint.role(), blocks.size(), materials(blocks.blocks()));
	}

	/** Every blueprint {@code server} loaded, in id order, with the size and materials of the structures it has. */
	public static List<BlueprintSummary> all(MinecraftServer server) {
		List<BlueprintSummary> summaries = new ArrayList<>();
		Blueprints.all().forEach((id, blueprint) ->
			summaries.add(of(id, blueprint, server.getStructureTemplateManager().get(blueprint.structure()))));
		return summaries;
	}

	/**
	 * The items a builder places for {@code blocks}, most first: one per block, the item a player would place it with (see
	 * {@link BuildPlan.Step#item()}), counting a two-block block such as a door once.
	 */
	public static SequencedMap<Item, Integer> materials(List<Block> blocks) {
		Map<Item, Integer> counts = new HashMap<>();
		for (Block block : blocks) {
			if (BuildPlan.isSecondHalf(block.state())) {
				continue;
			}
			Item item = new BuildPlan.Step(block.pos(), block.state()).item();
			if (item != Items.AIR) {
				counts.merge(item, 1, Integer::sum);
			}
		}
		SequencedMap<Item, Integer> sorted = new LinkedHashMap<>();
		counts.entrySet().stream()
			.sorted(Comparator.<Map.Entry<Item, Integer>>comparingInt(Map.Entry::getValue).reversed()
				.thenComparing(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey())))
			.forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
		return sorted;
	}

	/** Its display name. */
	public Component title() {
		return Component.translatable(this.name);
	}

	/** The lines the picker shows under its name: its size, its role if it has one, and its materials if it has any. */
	public List<Component> details() {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("screen.antcraft.blueprint_picker.size", this.size.getX(), this.size.getY(), this.size.getZ()));
		this.role.ifPresent(role -> lines.add(Component.translatable("screen.antcraft.blueprint_picker.role", Component.translatable(ROLE_KEY + role.getSerializedName()))));
		SequencedMap<Item, Integer> materials = this.materials();
		if (!materials.isEmpty()) {
			List<Component> items = materials.entrySet().stream()
				.map(entry -> (Component) Component.translatable("screen.antcraft.blueprint_picker.material", entry.getValue(), Component.translatable(entry.getKey().getDescriptionId())))
				.toList();
			lines.add(Component.translatable("screen.antcraft.blueprint_picker.materials", ComponentUtils.formatList(items, ComponentUtils.DEFAULT_SEPARATOR)));
		}
		return lines;
	}
}
