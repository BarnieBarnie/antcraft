package io.github.barniebarnie.antcraft.blueprint;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * The blueprint a Blueprint item is set to, kept on the item as a data component. The name is kept beside the id so the
 * tooltip can show it without the blueprint list.
 *
 * @param id the blueprint's id, as {@link Blueprints#get} takes it
 * @param name the lang key of its display name when it was chosen
 */
public record ChosenBlueprint(Identifier id, String name) {
	public static final Codec<ChosenBlueprint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Identifier.CODEC.fieldOf("id").forGetter(ChosenBlueprint::id),
		Codec.STRING.fieldOf("name").forGetter(ChosenBlueprint::name)
	).apply(instance, ChosenBlueprint::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, ChosenBlueprint> STREAM_CODEC = StreamCodec.composite(
		Identifier.STREAM_CODEC, ChosenBlueprint::id,
		ByteBufCodecs.STRING_UTF8, ChosenBlueprint::name,
		ChosenBlueprint::new);
}
