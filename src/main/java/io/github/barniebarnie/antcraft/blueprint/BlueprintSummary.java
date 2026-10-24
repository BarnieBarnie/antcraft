package io.github.barniebarnie.antcraft.blueprint;

import io.github.barniebarnie.antcraft.work.BuildPlan;
import java.util.ArrayList;
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
 * What a client knows of a blueprint the server loaded: enough to list it in the picker and to preview it where it would
 * stand. The blocks are the structure's own (palette 0), relative to its origin, unturned and without air; turn them with
 * {@link io.github.barniebarnie.antcraft.work.RegionPlan#placed} and {@link BlockState#mirror}/{@link BlockState#rotate}
 * as a placement does.
 *
 * @param id the blueprint's id, as {@link Blueprints#get} takes it
 * @param name the lang key of its display name
 * @param size the structure's size, zero when the server does not have the structure
 * @param blocks the structure's blocks other than air
 */
public record BlueprintSummary(Identifier id, String name, Optional<Blueprint.Role> role, Vec3i size, List<Block> blocks) {
	/** Prefix of the picker's lang keys. */
	public static final String KEY = "screen.antcraft.blueprint_picker";
	/** Followed by a {@link Blueprint.Role#getSerializedName()}. */
	public static final String ROLE_KEY = "screen.antcraft.blueprint_picker.role.";

	public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintSummary> STREAM_CODEC = StreamCodec.composite(
		Identifier.STREAM_CODEC, BlueprintSummary::id,
		ByteBufCodecs.STRING_UTF8, BlueprintSummary::name,
		ByteBufCodecs.optional(ByteBufCodecs.idMapper(i -> Blueprint.Role.values()[i], Blueprint.Role::ordinal)), BlueprintSummary::role,
		Vec3i.STREAM_CODEC, BlueprintSummary::size,
		Block.STREAM_CODEC.apply(ByteBufCodecs.list()), BlueprintSummary::blocks,
		BlueprintSummary::new);

	public BlueprintSummary {
		blocks = List.copyOf(blocks);
	}

	/** A block of the structure, at {@code pos} relative to its origin. */
	public record Block(BlockPos pos, BlockState state) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Block> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, Block::pos,
			ByteBufCodecs.idMapper(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY), Block::state,
			Block::new);
	}

	/** The summary of {@code blueprint}, with the blocks of {@code template} if the server has its structure. */
	public static BlueprintSummary of(Identifier id, Blueprint blueprint, Optional<StructureTemplate> template) {
		Vec3i size = template.map(StructureTemplate::getSize).orElse(Vec3i.ZERO);
		List<Block> blocks = template.filter(structure -> !structure.palettes.isEmpty())
			.map(structure -> structure.palettes.getFirst().blocks().stream()
				.filter(info -> !info.state().isAir())
				.map(info -> new Block(info.pos(), info.state()))
				.toList())
			.orElse(List.of());
		return new BlueprintSummary(id, blueprint.name(), blueprint.role(), size, blocks);
	}

	/** Every blueprint {@code server} loaded, in id order, with the blocks of the structures it has. */
	public static List<BlueprintSummary> all(MinecraftServer server) {
		List<BlueprintSummary> summaries = new ArrayList<>();
		Blueprints.all().forEach((id, blueprint) ->
			summaries.add(of(id, blueprint, server.getStructureTemplateManager().get(blueprint.structure()))));
		return summaries;
	}

	/**
	 * The items a builder places for it, most first: one per block, the item a player would place it with (see
	 * {@link BuildPlan.Step#item()}), counting a two-block block such as a door once.
	 */
	public SequencedMap<Item, Integer> materials() {
		Map<Item, Integer> counts = new HashMap<>();
		for (Block block : this.blocks) {
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
