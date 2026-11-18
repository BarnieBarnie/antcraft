package io.github.barniebarnie.antcraft.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The bot a Bot Core was picked up from (#916; docs/features/bots.md, Picking a bot up), kept on the item as a data
 * component: the bot placed from it gets this name and memory back. Its script is the core's {@link CoreScript}.
 *
 * @param name the bot's name
 * @param memory its {@code memory}, as {@code Memory.toTag} saves it
 */
public record RememberedBot(String name, CompoundTag memory) {
	public static final Codec<RememberedBot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("name").forGetter(RememberedBot::name),
		CompoundTag.CODEC.optionalFieldOf("memory", new CompoundTag()).forGetter(RememberedBot::memory)
	).apply(instance, RememberedBot::new));

	/**
	 * What clients get (#923): only the name, which the tooltip shows; a client's copy remembers an empty memory, so a
	 * large memory does not make a large item packet.
	 */
	// ponytail: a creative player's client sends stacks back whole, so a core moved in the creative inventory comes back
	// with an empty memory; keep the memory server side (saved data keyed by an id on the core) if that matters.
	public static final StreamCodec<RegistryFriendlyByteBuf, RememberedBot> STREAM_CODEC = ByteBufCodecs.STRING_UTF8
		.map(name -> new RememberedBot(name, new CompoundTag()), RememberedBot::name).cast();
}
