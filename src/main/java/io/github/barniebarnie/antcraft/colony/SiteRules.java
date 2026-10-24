package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.Sites;
import io.netty.buffer.ByteBuf;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Where a colony lets a blueprint stand: its territory, and what a site may not share columns with (see
 * {@link BlueprintPlacement#site}). The server checks placements with it, and sends it to a client holding a Blueprint so
 * the ghost turns red where the server would refuse; the server still decides.
 *
 * @param core the colony core
 * @param radius how far the territory reaches from the core sideways
 * @param bounds the game test area the territory is clipped to, if any (see {@link Territory#bounds()})
 * @param mineshaft the mineshaft's area, if it has one
 * @param occupied the areas the colony uses: its sites, builds and placed blueprints (see {@link Colony#occupied()})
 * @param placedColumns the columns, at height 0, holding blocks that builders placed
 */
public record SiteRules(BlockPos core, int radius, Optional<BoundingBox> bounds, Optional<BoundingBox> mineshaft, List<BoundingBox> occupied,
		List<BlockPos> placedColumns) {
	public static final StreamCodec<ByteBuf, SiteRules> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, SiteRules::core,
		ByteBufCodecs.VAR_INT, SiteRules::radius,
		ByteBufCodecs.optional(BoundingBox.STREAM_CODEC), SiteRules::bounds,
		ByteBufCodecs.optional(BoundingBox.STREAM_CODEC), SiteRules::mineshaft,
		BoundingBox.STREAM_CODEC.apply(ByteBufCodecs.list()), SiteRules::occupied,
		BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()), SiteRules::placedColumns,
		SiteRules::new);

	public SiteRules {
		occupied = List.copyOf(occupied);
		placedColumns = List.copyOf(placedColumns);
	}

	/** The rules of {@code colony} as it stands now. */
	public static SiteRules of(Colony colony) {
		List<BlockPos> columns = colony.placedBlocks().stream()
			.map(pos -> new BlockPos(pos.getX(), 0, pos.getZ()))
			.distinct()
			.sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ))
			.toList();
		return new SiteRules(colony.core(), colony.radius(), Optional.<BoundingBox>ofNullable(colony.bounds()), colony.mineshaft().map(Mineshaft::area),
			colony.occupied(), columns);
	}

	/**
	 * Why a structure standing in {@code area} would be refused, if it would: it leaves the territory, or it shares columns
	 * with the core, the mineshaft, an area the colony uses or a block builders placed.
	 */
	public Optional<BlueprintPlacement.Refusal> check(BoundingBox area) {
		Territory territory = new Territory(this.core, this.radius, this.bounds.orElse(null), Set.of());
		if (!territory.contains(new BlockPos(area.minX(), area.minY(), area.minZ()), new BlockPos(area.maxX(), area.maxY(), area.maxZ()))) {
			return Optional.of(BlueprintPlacement.Refusal.OUTSIDE);
		}
		if (Sites.sharesColumns(area, new BoundingBox(this.core))) {
			return Optional.of(BlueprintPlacement.Refusal.OVERLAPS_CORE);
		}
		if (this.mineshaft.filter(shaft -> Sites.sharesColumns(area, shaft)).isPresent()) {
			return Optional.of(BlueprintPlacement.Refusal.OVERLAPS_MINESHAFT);
		}
		if (this.occupied.stream().anyMatch(other -> Sites.sharesColumns(area, other))
				|| this.placedColumns.stream().anyMatch(pos -> Sites.sharesColumns(area, new BoundingBox(pos)))) {
			return Optional.of(BlueprintPlacement.Refusal.OVERLAPS_BUILD);
		}
		return Optional.empty();
	}
}
