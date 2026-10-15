package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/** A box the colony digs out, worked one horizontal layer at a time from the top. */
public record Quarry(BlockPos min, BlockPos max) {
	public static final Codec<Quarry> CODEC = RecordCodecBuilder.create(i -> i.group(
		BlockPos.CODEC.fieldOf("min").forGetter(Quarry::min),
		BlockPos.CODEC.fieldOf("max").forGetter(Quarry::max)
	).apply(i, Quarry::new));

	public Quarry {
		BlockPos a = min;
		BlockPos b = max;
		min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
		max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
	}

	public boolean contains(BlockPos pos) {
		return pos.getX() >= this.min.getX() && pos.getX() <= this.max.getX()
			&& pos.getY() >= this.min.getY() && pos.getY() <= this.max.getY()
			&& pos.getZ() >= this.min.getZ() && pos.getZ() <= this.max.getZ();
	}

	/** Up to {@code limit} layer heights that still have blocks to mine, highest first. */
	public List<Integer> layersToWork(Predicate<BlockPos> hasBlockToMine, int limit) {
		List<Integer> layers = new ArrayList<>();
		for (int y = this.max.getY(); y >= this.min.getY() && layers.size() < limit; y--) {
			if (!this.blocksInLayer(y, hasBlockToMine, this.min).isEmpty()) {
				layers.add(y);
			}
		}
		return layers;
	}

	/** Blocks left to mine in a layer, nearest to {@code from} first. */
	public List<BlockPos> blocksInLayer(int y, Predicate<BlockPos> hasBlockToMine, BlockPos from) {
		List<BlockPos> blocks = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(this.min.getX(), y, this.min.getZ(), this.max.getX(), y, this.max.getZ())) {
			if (hasBlockToMine.test(pos)) {
				blocks.add(pos.immutable());
			}
		}
		blocks.sort(Comparator.comparingDouble(pos -> pos.distSqr(from)));
		return blocks;
	}

	/** The position a layer job points at; jobs are told apart by their layer height. */
	public BlockPos layerTarget(int y) {
		return new BlockPos(this.min.getX(), y, this.min.getZ());
	}
}
