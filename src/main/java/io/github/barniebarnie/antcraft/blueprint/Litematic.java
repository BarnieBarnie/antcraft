package io.github.barniebarnie.antcraft.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * A Litematica schematic ({@code data/<namespace>/blueprint/<name>.litematic}) read as a blueprint (docs/features/blueprints.md,
 * Litematica schematics). Its regions merge into one structure from their lowest corner, a later region over an earlier where
 * they overlap. Block entity data comes along (a bot leaves out container contents, see {@code RegionPlan.blockEntities});
 * entities do not.
 */
public final class Litematic {
	private Litematic() {
	}

	/** Reads a {@code .litematic} file: gzipped NBT. */
	public static CompoundTag read(InputStream in) throws IOException {
		return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
	}

	/** The blueprint of schematic {@code schematic} loaded as {@code id}: it builds its own structure ({@link #template}), named by the schematic's name. */
	public static Blueprint blueprint(Identifier id, CompoundTag schematic) {
		return new Blueprint(id, schematic.getCompoundOrEmpty("Metadata").getStringOr("Name", id.toString()), Optional.empty(), List.of(), List.of());
	}

	/** The schematic's blocks as a structure template, its palette brought up to this game version as a structure file's would be. */
	public static StructureTemplate template(CompoundTag schematic) {
		int version = schematic.getIntOr("MinecraftDataVersion", 500);
		CompoundTag structure = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), NbtUtils.addDataVersion(toStructure(schematic), version), version);
		StructureTemplate template = new StructureTemplate();
		template.load(BuiltInRegistries.BLOCK, structure);
		return template;
	}

	/**
	 * The schematic as a structure file's NBT ({@code size}, {@code palette}, {@code blocks} with their block entities'
	 * {@code nbt}), palette entries and block entity data as the schematic has them.
	 */
	static CompoundTag toStructure(CompoundTag schematic) {
		Map<CompoundTag, Integer> palette = new LinkedHashMap<>();
		Map<BlockPos, Integer> blocks = new HashMap<>();
		Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
		CompoundTag regions = schematic.getCompoundOrEmpty("Regions");
		for (String name : regions.keySet()) {
			CompoundTag region = regions.getCompoundOrEmpty(name);
			BlockPos size = size(region);
			BlockPos min = min(region);
			int sx = Math.abs(size.getX());
			int sy = Math.abs(size.getY());
			int sz = Math.abs(size.getZ());
			ListTag states = region.getListOrEmpty("BlockStatePalette");
			int[] merged = new int[states.size()];
			for (int i = 0; i < merged.length; i++) {
				merged[i] = palette.computeIfAbsent(states.getCompoundOrEmpty(i), state -> palette.size());
			}
			long[] packed = region.getLongArray("BlockStates").orElse(new long[0]);
			int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(merged.length - 1));
			for (int y = 0; y < sy; y++) {
				for (int z = 0; z < sz; z++) {
					for (int x = 0; x < sx; x++) {
						int entry = entry(packed, bits, (y * sz + z) * sx + x);
						if (entry < merged.length) {
							blocks.put(min.offset(x, y, z), merged[entry]);
							blockEntities.remove(min.offset(x, y, z));
						}
					}
				}
			}
			// A block entity's x, y, z run from the region's lowest corner, as its blocks do.
			for (CompoundTag tag : region.getListOrEmpty("TileEntities").compoundStream().toList()) {
				CompoundTag data = tag.copy();
				List.of("x", "y", "z").forEach(data::remove);
				blockEntities.put(min.offset(vec(tag)), data);
			}
		}
		BlockPos low = blocks.keySet().stream().reduce(BlockPos::min).orElse(BlockPos.ZERO);
		BlockPos high = blocks.keySet().stream().reduce(BlockPos::max).orElse(low.offset(-1, -1, -1));
		ListTag blockList = new ListTag();
		blocks.forEach((pos, state) -> {
			CompoundTag block = new CompoundTag();
			block.put("pos", ints(pos.subtract(low)));
			block.putInt("state", state);
			if (blockEntities.containsKey(pos)) {
				block.put("nbt", blockEntities.get(pos));
			}
			blockList.add(block);
		});
		CompoundTag structure = new CompoundTag();
		structure.put("size", ints(high.subtract(low).offset(1, 1, 1)));
		ListTag paletteList = new ListTag();
		paletteList.addAll(palette.keySet());
		structure.put("palette", paletteList);
		structure.put("blocks", blockList);
		structure.put("entities", new ListTag());
		return structure;
	}

	/** The lowest corner of a region, where its blocks start: a negative size runs back from its position. */
	static BlockPos min(CompoundTag region) {
		BlockPos position = vec(region.getCompoundOrEmpty("Position"));
		BlockPos size = size(region);
		return position.offset(size.getX() < 0 ? size.getX() + 1 : 0, size.getY() < 0 ? size.getY() + 1 : 0, size.getZ() < 0 ? size.getZ() + 1 : 0);
	}

	/** A region's size as saved, a side negative where the region runs back from its position. */
	static BlockPos size(CompoundTag region) {
		return vec(region.getCompoundOrEmpty("Size"));
	}

	/** Entry {@code index} of {@code packed}, {@code bits} wide, low bits first; one that falls over the end of a long goes on in the next. */
	static int entry(long[] packed, int bits, int index) {
		long start = (long) index * bits;
		int word = (int) (start >>> 6);
		int offset = (int) (start & 63);
		if (word >= packed.length) {
			return Integer.MAX_VALUE;
		}
		long value = packed[word] >>> offset;
		if (offset + bits > 64 && word + 1 < packed.length) {
			value |= packed[word + 1] << (64 - offset);
		}
		return (int) (value & ((1L << bits) - 1));
	}

	private static BlockPos vec(CompoundTag tag) {
		return new BlockPos(tag.getIntOr("x", 0), tag.getIntOr("y", 0), tag.getIntOr("z", 0));
	}

	private static ListTag ints(BlockPos pos) {
		ListTag list = new ListTag();
		list.add(IntTag.valueOf(pos.getX()));
		list.add(IntTag.valueOf(pos.getY()));
		list.add(IntTag.valueOf(pos.getZ()));
		return list;
	}
}
