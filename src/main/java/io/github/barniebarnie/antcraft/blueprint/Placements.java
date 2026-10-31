package io.github.barniebarnie.antcraft.blueprint;

import io.github.barniebarnie.antcraft.network.BlueprintNetworking.PlacementPart;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

/**
 * The Litematica placements players sent ({@link PlacementPart}), each a schematic with where and how Litematica places it,
 * kept by player and name until the player leaves (docs/features/blueprints.md, Litematica placements). What a client sends
 * is untrusted: a file over {@link #MAX_BYTES}, a schematic spanning more than {@link #MAX_VOLUME} blocks, and one that does
 * not read are refused.
 */
public final class Placements {
	/** The most bytes of a {@code .litematic} file a player may send. */
	public static final int MAX_BYTES = 2 * 1024 * 1024;
	/** The most blocks a schematic's regions, and the box around them, may span. */
	public static final long MAX_VOLUME = 1 << 20;
	/** The most placements kept per player: a newer one drops the oldest. */
	static final int PER_PLAYER = 8;
	/** The most heap the schematic's NBT may take once unpacked, so a small file cannot unzip into a huge one. */
	private static final long MAX_NBT = 64L * 1024 * 1024;

	// ponytail: kept in memory on the server thread, lost on restart and when the player leaves; the client sends again.
	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final Map<UUID, SequencedMap<String, Placement>> RECEIVED = new HashMap<>();

	private Placements() {
	}

	/**
	 * A schematic placed as Litematica places it: each block at the origin plus its position in the schematic, mirrored,
	 * then turned, and its state mirrored, then turned; in dimension {@code dimension}.
	 *
	 * @param at where {@link RegionPlan#load} puts the lowest corner of the turned structure for that
	 */
	public record Placement(StructureTemplate template, BlockPos at, Mirror mirror, Rotation rotation, String dimension) {
		/** Schematic {@code schematic} placed at {@code origin}; refused (IllegalArgumentException) when it spans too much. */
		public static Placement of(CompoundTag schematic, BlockPos origin, Mirror mirror, Rotation rotation, String dimension) {
			BlockPos low = null;
			BlockPos high = null;
			long volume = 0;
			CompoundTag regions = schematic.getCompoundOrEmpty("Regions");
			for (String name : regions.keySet()) {
				CompoundTag region = regions.getCompoundOrEmpty(name);
				BlockPos size = Litematic.size(region);
				long blocks = Math.abs((long) size.getX() * size.getY() * size.getZ());
				if (blocks == 0) {
					continue;
				}
				volume += blocks;
				BlockPos min = Litematic.min(region);
				BlockPos max = min.offset(Math.abs(size.getX()) - 1, Math.abs(size.getY()) - 1, Math.abs(size.getZ()) - 1);
				low = low == null ? min : BlockPos.min(low, min);
				high = high == null ? max : BlockPos.max(high, max);
			}
			if (low == null) {
				throw new IllegalArgumentException("it has no blocks");
			}
			long box = ((long) high.getX() - low.getX() + 1) * ((long) high.getY() - low.getY() + 1) * ((long) high.getZ() - low.getZ() + 1);
			if (volume > MAX_VOLUME || box > MAX_VOLUME) {
				throw new IllegalArgumentException("it spans more than " + MAX_VOLUME + " blocks");
			}
			StructureTemplate template = Litematic.template(schematic);
			// The template starts at the schematic's lowest corner, low; Litematica places the schematic's own origin at origin.
			BlockPos turnedLow = StructureTemplate.transform(low, mirror, rotation, BlockPos.ZERO);
			BlockPos at = origin.offset(turnedLow).subtract(RegionPlan.placed(BlockPos.ZERO, template.getSize(), BlockPos.ZERO, mirror, rotation));
			return new Placement(template, at, mirror, rotation, dimension);
		}

		/** The plan that builds it; empty for a schematic of nothing but positions left out. */
		public Optional<BuildPlan> plan() {
			return RegionPlan.load(this.template, this.at, this.mirror, this.rotation, 0);
		}

		/** The block entity data of its blocks, without container contents, by where they go. */
		public Map<BlockPos, CompoundTag> blockEntities() {
			return RegionPlan.blockEntities(this.template, this.at, this.mirror, this.rotation, 0);
		}
	}

	/** The parts of a file received so far, the first naming the placement, and the number of the part to come next. */
	private record Pending(PlacementPart first, ByteArrayOutputStream bytes, int next) {
	}

	/**
	 * Takes part of a placement {@code player} sent from dimension {@code dimension}: once its last part is in, reads the
	 * file and keeps the placement under its name, replacing one of that name.
	 *
	 * @return what to tell the player: received or refused, with why; null while more parts are to come
	 */
	public static @Nullable Component receive(UUID player, String dimension, PlacementPart part) {
		Pending pending = part.part() == 0 ? new Pending(part, new ByteArrayOutputStream(), 0) : PENDING.get(player);
		PENDING.remove(player);
		String name = part.name();
		if (pending == null || pending.next() != part.part() || part.parts() != pending.first().parts() || !name.equals(pending.first().name())) {
			return refused(name, "its parts came out of order");
		}
		if (pending.bytes().size() + part.bytes().length > MAX_BYTES) {
			return refused(name, "it is larger than " + MAX_BYTES + " bytes");
		}
		pending.bytes().writeBytes(part.bytes());
		if (pending.next() + 1 < part.parts()) {
			PENDING.put(player, new Pending(pending.first(), pending.bytes(), pending.next() + 1));
			return null;
		}
		PlacementPart first = pending.first();
		Placement placement;
		try {
			CompoundTag schematic = NbtIo.readCompressed(new ByteArrayInputStream(pending.bytes().toByteArray()), NbtAccounter.create(MAX_NBT));
			placement = Placement.of(schematic, first.origin(), first.mirror(), first.rotation(), dimension);
			if (placement.plan().isEmpty()) {
				return refused(name, "it has no blocks");
			}
		} catch (IOException | RuntimeException e) {
			return refused(name, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
		SequencedMap<String, Placement> kept = RECEIVED.computeIfAbsent(player, id -> new LinkedHashMap<>());
		kept.remove(name);
		kept.put(name, placement);
		if (kept.size() > PER_PLAYER) {
			kept.pollFirstEntry();
		}
		return Component.translatable("message.antcraft.placement.received", name);
	}

	private static Component refused(String name, String why) {
		return Component.translatable("message.antcraft.placement.refused", name, why);
	}

	/** The placement {@code player} sent under {@code name}, if any. */
	public static Optional<Placement> get(UUID player, String name) {
		return Optional.ofNullable(RECEIVED.getOrDefault(player, new LinkedHashMap<>()).get(name));
	}

	/** Drops what {@code player} sent. */
	public static void forget(UUID player) {
		PENDING.remove(player);
		RECEIVED.remove(player);
	}

	public static void register() {
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.getPlayer().getUUID()));
	}
}
