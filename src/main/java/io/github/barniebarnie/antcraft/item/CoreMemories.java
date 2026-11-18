package io.github.barniebarnie.antcraft.item;

import com.mojang.serialization.Codec;
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
 * which sends stacks back whole, cannot lose them.
 */
// ponytail: a core that is wiped, burnt or lost leaves its entry behind; harmless, as nothing asks for that id again, and
// small. Sweep old entries if worlds ever pile up many.
public final class CoreMemories extends SavedData {
	private static final Codec<CoreMemories> CODEC = Codec.unboundedMap(UUIDUtil.STRING_CODEC, CompoundTag.CODEC)
		.xmap(CoreMemories::new, memories -> memories.memories);

	// The fix type is null: Fabric API skips data fixing for mod saved data.
	public static final SavedDataType<CoreMemories> TYPE = new SavedDataType<>(AntCraft.id("core_memories"), CoreMemories::new, CODEC, null);

	private final Map<UUID, CompoundTag> memories;

	public CoreMemories() {
		this(Map.of());
	}

	private CoreMemories(Map<UUID, CompoundTag> memories) {
		this.memories = new HashMap<>(memories);
	}

	public static CoreMemories get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	/** Keeps {@code memory} under a new id, which it returns. */
	public UUID put(CompoundTag memory) {
		UUID id = UUID.randomUUID();
		this.memories.put(id, memory);
		this.setDirty();
		return id;
	}

	/** The memory kept under {@code id}, no longer kept; empty if none is. */
	public CompoundTag take(UUID id) {
		CompoundTag memory = this.memories.remove(id);
		if (memory == null) {
			return new CompoundTag();
		}
		this.setDirty();
		return memory;
	}

	/** The memory kept under {@code id}, still kept; empty if none is. */
	public CompoundTag peek(UUID id) {
		return this.memories.getOrDefault(id, new CompoundTag());
	}
}
