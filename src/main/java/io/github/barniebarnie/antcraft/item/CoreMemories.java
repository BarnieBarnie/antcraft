package io.github.barniebarnie.antcraft.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.barniebarnie.antcraft.AntCraft;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The memories of picked-up bots (#927; docs/features/bots.md, Picking a bot up), saved with the world on the overworld and
 * keyed by the id on each Bot Core ({@link RememberedBot#id}), so a client never gets them and a creative player's client,
 * which sends stacks back whole, cannot lose them. A core wiped in a crafting grid drops its entry ({@code ResultSlotMixin});
 * each entry keeps the game time it was kept at, and one not placed within {@link #KEPT_FOR} is swept whenever another is
 * kept, as its core was most likely burnt, lost or wiped in a crafter (#939).
 */
public final class CoreMemories extends SavedData {
	/** How long an entry is kept unplaced: 30 in-game days. */
	static final long KEPT_FOR = 30 * 24_000L;

	/** A memory and the game time it was kept at; -1 for one saved before #939, which the next sweep stamps. */
	private record Entry(CompoundTag memory, long written) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			CompoundTag.CODEC.fieldOf("memory").forGetter(Entry::memory),
			Codec.LONG.fieldOf("written").forGetter(Entry::written)
		).apply(instance, Entry::new));
	}

	// Saved before #939, an entry was the memory alone.
	private static final Codec<Entry> ENTRY_CODEC = Codec.withAlternative(Entry.CODEC,
		CompoundTag.CODEC.xmap(memory -> new Entry(memory, -1), Entry::memory));

	static final Codec<CoreMemories> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, ENTRY_CODEC)
		.xmap(CoreMemories::new, memories -> memories.memories);

	// The fix type is null: Fabric API skips data fixing for mod saved data.
	public static final SavedDataType<CoreMemories> TYPE = new SavedDataType<>(AntCraft.id("core_memories"), CoreMemories::new, CODEC, null);

	private final Map<UUID, Entry> memories;

	public CoreMemories() {
		this(Map.of());
	}

	private CoreMemories(Map<UUID, Entry> memories) {
		this.memories = new HashMap<>(memories);
	}

	public static CoreMemories get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	/** Keeps {@code memory} under a new id, which it returns, at game time {@code now}, sweeping entries kept too long. */
	public UUID put(CompoundTag memory, long now) {
		this.memories.replaceAll((id, entry) -> entry.written < 0 ? new Entry(entry.memory, now) : entry);
		this.memories.values().removeIf(entry -> now - entry.written > KEPT_FOR);
		UUID id = UUID.randomUUID();
		this.memories.put(id, new Entry(memory, now));
		this.setDirty();
		return id;
	}

	/** The memory kept under {@code id}, no longer kept; empty if none is. */
	public CompoundTag take(UUID id) {
		Entry entry = this.memories.remove(id);
		if (entry == null) {
			return new CompoundTag();
		}
		this.setDirty();
		return entry.memory;
	}

	/** The memory kept under {@code id}, still kept; empty if none is. */
	public CompoundTag peek(UUID id) {
		Entry entry = this.memories.get(id);
		return entry == null ? new CompoundTag() : entry.memory;
	}
}
