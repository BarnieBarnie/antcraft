package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * A felled tree a solo lumberjack could not replant and comes back to: its base {@code pos}, the game
 * time it was {@code felled}, the {@code spots} that need a sapling (the base, or all four spots of a 2x2
 * trunk) and the {@code sapling} kind they need, or empty when any sapling will do.
 */
public record Revisit(BlockPos pos, long felled, List<BlockPos> spots, Optional<Item> sapling) {
	/** Saves from before the spots and kind were kept hold only the base: one spot, any sapling. */
	public static final Codec<Revisit> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.fieldOf("Pos").forGetter(Revisit::pos),
		Codec.LONG.fieldOf("Felled").forGetter(Revisit::felled),
		BlockPos.CODEC.listOf().optionalFieldOf("Spots", List.of()).forGetter(Revisit::spots),
		BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("Sapling").forGetter(Revisit::sapling)
	).apply(instance, Revisit::new));

	public Revisit {
		spots = spots.isEmpty() ? List.of(pos) : List.copyOf(spots);
	}
}
