package io.github.barniebarnie.antcraft.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The script a Bot Core carries, kept on the item as a data component: the bot it makes starts it at once
 * ({@code /antcraft core script}).
 *
 * @param name the script's name, as {@code /antcraft bot run} takes it
 * @param args its arguments, the script's {@code ARGS}
 */
public record CoreScript(String name, List<String> args) {
	public static final Codec<CoreScript> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.STRING.fieldOf("name").forGetter(CoreScript::name),
		Codec.STRING.listOf().optionalFieldOf("args", List.of()).forGetter(CoreScript::args)
	).apply(instance, CoreScript::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, CoreScript> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.STRING_UTF8, CoreScript::name,
		ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), CoreScript::args,
		CoreScript::new);

	public CoreScript {
		args = List.copyOf(args);
	}

	/** The script and its args, as typed after {@code bot run}. */
	@Override
	public String toString() {
		return args.isEmpty() ? name : name + " " + String.join(" ", args);
	}
}
