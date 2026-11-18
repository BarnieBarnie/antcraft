package io.github.barniebarnie.antcraft.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;

/**
 * The bot a Bot Core was picked up from (#916; docs/features/bots.md, Picking a bot up), kept on the item as a data
 * component: the bot placed from it gets this name and memory back. Its script is the core's {@link CoreScript}.
 *
 * @param name the bot's name
 * @param id the key of its memory in {@link CoreMemories} (#927), which keeps the memory on the server; empty for a core
 *     from before that, which carries the memory itself
 * @param memory its {@code memory}, as {@code Memory.toTag} saves it, if the core carries it itself; else empty
 */
public record RememberedBot(String name, Optional<UUID> id, CompoundTag memory) {
	public static final Codec<RememberedBot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("name").forGetter(RememberedBot::name),
		UUIDUtil.CODEC.optionalFieldOf("id").forGetter(RememberedBot::id),
		CompoundTag.CODEC.optionalFieldOf("memory", new CompoundTag()).forGetter(RememberedBot::memory)
	).apply(instance, RememberedBot::new));

	/**
	 * What clients get (#923): the name, which the tooltip shows, and the id, which a creative player's client sends back
	 * with the whole stack (#927); never the memory, so a large memory does not make a large item packet.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, RememberedBot> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, RememberedBot::name,
		UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), RememberedBot::id,
		(name, id) -> new RememberedBot(name, id, new CompoundTag())).cast();

	/** Remembers the bot named {@code name}, keeping its {@code memory} on the server (#927). */
	public static RememberedBot remember(MinecraftServer server, String name, CompoundTag memory) {
		return new RememberedBot(name, Optional.of(CoreMemories.get(server).put(memory)), new CompoundTag());
	}

	/** The bot's memory, which the server stops keeping, for the core is placed and gives it back. */
	public CompoundTag takeMemory(MinecraftServer server) {
		return this.id.map(key -> CoreMemories.get(server).take(key)).orElse(this.memory);
	}

	/** The bot's memory, still kept. */
	public CompoundTag memory(MinecraftServer server) {
		return this.id.map(key -> CoreMemories.get(server).peek(key)).orElse(this.memory);
	}
}
