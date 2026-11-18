package io.github.barniebarnie.antcraft.item;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/** How long {@link CoreMemories} keeps the memory of a core that is never placed (#939). */
class CoreMemoriesTest {
	private static CompoundTag memory(String job) {
		CompoundTag memory = new CompoundTag();
		memory.putString("job", job);
		return memory;
	}

	@Test
	void anEntryOlderThanTheLimitIsSweptWhenAnotherIsKept() {
		CoreMemories memories = new CoreMemories();
		UUID old = memories.put(memory("cane"), 0);
		UUID recent = memories.put(memory("farm"), 10);
		UUID late = memories.put(memory("mine"), CoreMemories.KEPT_FOR + 5);
		assertEquals(new CompoundTag(), memories.peek(old));
		assertEquals(memory("farm"), memories.peek(recent));
		assertEquals(memory("mine"), memories.peek(late));
	}

	@Test
	void theTimeAnEntryWasKeptIsSaved() {
		CoreMemories memories = new CoreMemories();
		UUID id = memories.put(memory("cane"), 7);
		Tag saved = CoreMemories.CODEC.encodeStart(NbtOps.INSTANCE, memories).getOrThrow();
		CoreMemories loaded = CoreMemories.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
		loaded.put(memory("farm"), CoreMemories.KEPT_FOR + 6);
		assertEquals(memory("cane"), loaded.peek(id));
		loaded.put(memory("farm"), CoreMemories.KEPT_FOR + 8);
		assertEquals(new CompoundTag(), loaded.peek(id));
	}

	@Test
	void entriesSavedBeforeTheirTimeWasKeptStillLoad() {
		UUID id = UUID.randomUUID();
		CompoundTag saved = new CompoundTag();
		saved.put(id.toString(), memory("cane"));
		CoreMemories loaded = CoreMemories.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
		long now = 50 * CoreMemories.KEPT_FOR;
		loaded.put(memory("farm"), now);
		assertEquals(memory("cane"), loaded.peek(id), "kept from the first sweep that saw it");
		loaded.put(memory("farm"), now + CoreMemories.KEPT_FOR + 1);
		assertEquals(new CompoundTag(), loaded.peek(id));
	}
}
