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
	 * The most bytes the blocks may take in one part of a reply, well under the game's limit for a payload to a client (see
	 * {@link io.github.barniebarnie.antcraft.network.ColonyNetworking#PAYLOAD_LIMIT}); see {@link #parts()}.
	 */
	public static final int MAX_BLOCK_BYTES = 900_000;

	/**
	 * The most parts a reply is split into, so a client is never sent more than about 58 MB of blocks for one preview;
	 * see {@link #parts()}.
	 */
	public static final int MAX_PARTS = 64;

	/** Blocks in layer order: by y, then z, then x. */
	public static final Comparator<BlueprintSummary.Block> LAYER_ORDER = Comparator.<BlueprintSummary.Block>comparingInt(block -> block.pos().getY())
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
			writeCells(buf, blocks.size(), blocks.blocks());
		},
		buf -> {
			Identifier id = Identifier.STREAM_CODEC.decode(buf);
			Vec3i size = Vec3i.STREAM_CODEC.decode(buf);
			return new BlueprintBlocks(id, size, readCells(buf, BlockPos.ZERO, size));
		});

	/**
	 * Blocks anywhere in the world, such as those left of a placed build, as {@link #STREAM_CODEC} sends a structure's:
	 * the corner and size of the box around them, then each block's cell in that box and its state. Decoded in
	 * {@link #LAYER_ORDER}, so a list in another order comes back sorted.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, List<BlueprintSummary.Block>> PLACED_STREAM_CODEC = StreamCodec.of(
		(buf, blocks) -> {
			BlockPos min = blocks.stream().map(BlueprintSummary.Block::pos).reduce(BlockPos::min).orElse(BlockPos.ZERO);
			BlockPos max = blocks.stream().map(BlueprintSummary.Block::pos).reduce(BlockPos::max).orElse(min.offset(-1, -1, -1));
			Vec3i size = max.subtract(min).offset(1, 1, 1);
			BlockPos.STREAM_CODEC.encode(buf, min);
			Vec3i.STREAM_CODEC.encode(buf, size);
			writeCells(buf, size, blocks.stream()
				.map(block -> new BlueprintSummary.Block(block.pos().subtract(min), block.state()))
				.sorted(LAYER_ORDER)
				.toList());
		},
		buf -> {
			BlockPos min = BlockPos.STREAM_CODEC.decode(buf);
			Vec3i size = Vec3i.STREAM_CODEC.decode(buf);
			return readCells(buf, min, size);
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
	 * These blocks split into parts of at most {@link #MAX_BLOCK_BYTES} each, to send one per payload; see
	 * {@link #parts(int, int)}.
	 */
	public List<BlueprintBlocks> parts() {
		return this.parts(MAX_BLOCK_BYTES, MAX_PARTS);
	}

	/**
	 * These blocks split into parts, in layer order, whose blocks take at most {@code maxBytes} each in a reply, and which
	 * {@link #join} puts back together; always one part, these blocks, when they fit in one. If they would take more
	 * than {@code maxParts} parts, a single part with none but the size: the preview then draws nothing, but the client
	 * still knows where the structure would stand and whether the site is free.
	 */
	public List<BlueprintBlocks> parts(int maxBytes, int maxParts) {
		List<BlueprintBlocks> parts = new ArrayList<>();
		List<BlueprintSummary.Block> part = new ArrayList<>();
		long bytes = 0;
		long last = -1;
		for (BlueprintSummary.Block block : this.blocks) {
			long cell = this.cell(block.pos());
			int blockBytes = VarLong.getByteSize(cell - last) + VarInt.getByteSize(Block.BLOCK_STATE_REGISTRY.getId(block.state()));
			if (!part.isEmpty() && bytes + blockBytes > maxBytes) {
				parts.add(new BlueprintBlocks(this.id, this.size, part));
				if (parts.size() >= maxParts) {
					return List.of(new BlueprintBlocks(this.id, this.size, List.of()));
				}
				part = new ArrayList<>();
				// The first block of a part counts from the start of the box again.
				blockBytes = VarLong.getByteSize(cell + 1) + VarInt.getByteSize(Block.BLOCK_STATE_REGISTRY.getId(block.state()));
				bytes = 0;
			}
			part.add(block);
			bytes += blockBytes;
			last = cell;
		}
		if (parts.isEmpty()) {
			return List.of(this);
		}
		parts.add(new BlueprintBlocks(this.id, this.size, part));
		return List.copyOf(parts);
	}

	/** The blueprint whose blocks {@code parts} are, the id and size those of the first part; see {@link #parts}. */
	public static BlueprintBlocks join(List<BlueprintBlocks> parts) {
		BlueprintBlocks first = parts.getFirst();
		return new BlueprintBlocks(first.id(), first.size(), parts.stream().flatMap(part -> part.blocks().stream()).toList());
	}

	private static void writeCells(RegistryFriendlyByteBuf buf, Vec3i size, List<BlueprintSummary.Block> blocks) {
		buf.writeVarInt(blocks.size());
		long last = -1;
		for (BlueprintSummary.Block block : blocks) {
			long cell = cell(size, block.pos());
			buf.writeVarLong(cell - last);
			buf.writeVarInt(Block.BLOCK_STATE_REGISTRY.getId(block.state()));
			last = cell;
		}
	}

	/** Blocks written by {@link #writeCells}, in a box of {@code size} with its corner at {@code min}. */
	private static List<BlueprintSummary.Block> readCells(RegistryFriendlyByteBuf buf, BlockPos min, Vec3i size) {
		int count = buf.readVarInt();
		List<BlueprintSummary.Block> blocks = new ArrayList<>(Math.min(count, 1 << 16));
		long cell = -1;
		for (int i = 0; i < count; i++) {
			cell += buf.readVarLong();
			BlockState state = Block.BLOCK_STATE_REGISTRY.byIdOrThrow(buf.readVarInt());
			blocks.add(new BlueprintSummary.Block(min.offset(pos(size, cell)), state));
		}
		return blocks;
	}

	private static boolean inside(Vec3i size, BlockPos pos) {
		return pos.getX() >= 0 && pos.getY() >= 0 && pos.getZ() >= 0 && pos.getX() < size.getX() && pos.getY() < size.getY() && pos.getZ() < size.getZ();
	}

	/** The number of {@code pos}'s cell, counting the cells of the size's box in layer order. */
	private long cell(BlockPos pos) {
		return cell(this.size, pos);
	}

	private static long cell(Vec3i size, BlockPos pos) {
		return ((long) pos.getY() * size.getZ() + pos.getZ()) * size.getX() + pos.getX();
	}

	private static BlockPos pos(Vec3i size, long cell) {
		if (size.getX() <= 0 || size.getZ() <= 0 || cell < 0) {
			throw new IllegalArgumentException("Block " + cell + " outside a structure of size " + size);
		}
		long row = cell / size.getX();
		return new BlockPos((int) (cell % size.getX()), (int) (row / size.getZ()), (int) (row % size.getZ()));
	}
}
