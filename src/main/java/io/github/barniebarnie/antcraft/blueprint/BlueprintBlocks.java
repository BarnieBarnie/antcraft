package io.github.barniebarnie.antcraft.blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The blocks of a blueprint's structure, which a client asks for when it previews the blueprint
 * ({@link io.github.barniebarnie.antcraft.network.ColonyNetworking.BlueprintBlocksRequest}) rather than getting those of
 * every blueprint with the list. The blocks are the structure's own, of the palette the blueprint builds, relative to its origin, unturned, without
 * air and inside its size, kept in layer order (by y, then z, then x); turn them with
 * {@link io.github.barniebarnie.antcraft.work.RegionPlan#placed} and {@link BlockState#mirror}/{@link BlockState#rotate}
 * as a placement does.
 *
 * @param id the blueprint's id
 * @param size the structure's size, zero when the server does not have the structure
 * @param blocks the structure's blocks other than air
 */
public record BlueprintBlocks(Identifier id, Vec3i size, List<BlueprintSummary.Block> blocks) {
	/**
	 * The most bytes the blocks may take in a reply, well under the game's limit for a payload to a client (see
	 * {@link io.github.barniebarnie.antcraft.network.ColonyNetworking#PAYLOAD_LIMIT}); see {@link #sendable()}.
	 */
	public static final int MAX_BLOCK_BYTES = 900_000;

	private static final Comparator<BlueprintSummary.Block> LAYER_ORDER = Comparator.<BlueprintSummary.Block>comparingInt(block -> block.pos().getY())
		.thenComparingInt(block -> block.pos().getZ())
		.thenComparingInt(block -> block.pos().getX());

	/**
	 * Each block as how far its cell is past the last one's, counting cells in layer order, and its state's id: a byte or
	 * two for most blocks, where a full position and state would take nine or more.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintBlocks> STREAM_CODEC = StreamCodec.of(
		(buf, blocks) -> {
			Identifier.STREAM_CODEC.encode(buf, blocks.id());
			Vec3i.STREAM_CODEC.encode(buf, blocks.size());
			buf.writeVarInt(blocks.blocks().size());
			long last = -1;
			for (BlueprintSummary.Block block : blocks.blocks()) {
				long cell = blocks.cell(block.pos());
				buf.writeVarLong(cell - last);
				buf.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(block.state()));
				last = cell;
			}
		},
		buf -> {
			Identifier id = Identifier.STREAM_CODEC.decode(buf);
			Vec3i size = Vec3i.STREAM_CODEC.decode(buf);
			int count = buf.readVarInt();
			List<BlueprintSummary.Block> blocks = new ArrayList<>(Math.min(count, 1 << 16));
			long cell = -1;
			for (int i = 0; i < count; i++) {
				cell += buf.readVarLong();
				BlockState state = Block.BLOCK_STATE_REGISTRY.byIdOrThrow(buf.readVarInt());
				blocks.add(new BlueprintSummary.Block(pos(size, cell), state));
			}
			return new BlueprintBlocks(id, size, blocks);
		});

	public BlueprintBlocks {
		blocks = blocks.stream().filter(block -> inside(size, block.pos())).sorted(LAYER_ORDER).toList();
	}

	/**
	 * The blocks of palette {@code palette} of {@code template} other than air, or none when the server does not have the
	 * structure or the structure has no such palette.
	 */
	public static BlueprintBlocks of(Identifier id, Optional<StructureTemplate> template, int palette) {
		Vec3i size = template.map(StructureTemplate::getSize).orElse(Vec3i.ZERO);
		List<BlueprintSummary.Block> blocks = template.filter(structure -> palette >= 0 && palette < structure.palettes.size())
			.map(structure -> structure.palettes.get(palette).blocks().stream()
				.filter(info -> !info.state().isAir())
				.map(info -> new BlueprintSummary.Block(info.pos(), info.state()))
				.toList())
			.orElse(List.of());
		return new BlueprintBlocks(id, size, blocks);
	}

	/**
	 * These blocks, or none but the size if they would take more than {@link #MAX_BLOCK_BYTES} in a reply, so a client is
	 * never sent more than it takes. Without blocks the preview draws nothing, but the client still knows where the
	 * structure would stand and whether the site is free.
	 */
	public BlueprintBlocks sendable() {
		long bytes = 0;
		long last = -1;
		for (BlueprintSummary.Block block : this.blocks) {
			long cell = this.cell(block.pos());
			bytes += VarLong.getByteSize(cell - last) + VarInt.getByteSize(Block.BLOCK_STATE_REGISTRY.getId(block.state()));
			last = cell;
			if (bytes > MAX_BLOCK_BYTES) {
				return new BlueprintBlocks(this.id, this.size, List.of());
			}
		}
		return this;
	}

	private static boolean inside(Vec3i size, BlockPos pos) {
		return pos.getX() >= 0 && pos.getY() >= 0 && pos.getZ() >= 0 && pos.getX() < size.getX() && pos.getY() < size.getY() && pos.getZ() < size.getZ();
	}

	/** The number of {@code pos}'s cell, counting the cells of the size's box in layer order. */
	private long cell(BlockPos pos) {
		return ((long) pos.getY() * this.size.getZ() + pos.getZ()) * this.size.getX() + pos.getX();
	}

	private static BlockPos pos(Vec3i size, long cell) {
		if (size.getX() <= 0 || size.getZ() <= 0 || cell < 0) {
			throw new IllegalArgumentException("Block " + cell + " outside a structure of size " + size);
		}
		long row = cell / size.getX();
		return new BlockPos((int) (cell % size.getX()), (int) (row / size.getZ()), (int) (row % size.getZ()));
	}
}
