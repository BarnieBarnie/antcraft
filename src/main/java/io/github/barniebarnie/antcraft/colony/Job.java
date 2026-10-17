package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/**
 * A unit of colony work. {@code claimedBy} is the bot working on it; {@code heartbeat}
 * is the game time it last reported progress, used to free jobs of bots that vanished.
 * {@code area} is every block the job covers, such as a tree's logs, so the same work is not posted twice.
 * {@code item} is the item the job needs, if only one kind will do, such as the sapling of a 2x2 tree.
 * {@code tries} is how often a bot gave the job back to retry later, and {@code notBefore} the game time
 * from which it may be claimed again; {@link Long#MAX_VALUE} once it is given up. {@code posted} is the
 * game time it was posted, from when a 1x1 tree's replant job waits for its own sapling kind.
 */
public record Job(UUID id, JobType type, BlockPos target, Optional<UUID> claimedBy, long heartbeat, List<BlockPos> area, Optional<Item> item,
		int tries, long notBefore, long posted) {
	public static final Codec<Job> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(Job::id),
		JobType.CODEC.fieldOf("type").forGetter(Job::type),
		BlockPos.CODEC.fieldOf("target").forGetter(Job::target),
		UUIDUtil.CODEC.optionalFieldOf("claimed_by").forGetter(Job::claimedBy),
		Codec.LONG.optionalFieldOf("heartbeat", 0L).forGetter(Job::heartbeat),
		BlockPos.CODEC.listOf().optionalFieldOf("area", List.of()).forGetter(Job::area),
		BuiltInRegistries.ITEM.byNameCodec().optionalFieldOf("item").forGetter(Job::item),
		Codec.INT.optionalFieldOf("tries", 0).forGetter(Job::tries),
		Codec.LONG.optionalFieldOf("not_before", 0L).forGetter(Job::notBefore),
		Codec.LONG.optionalFieldOf("posted", 0L).forGetter(Job::posted)
	).apply(i, Job::new));

	/** The blocks the job covers, or just its target when it names no area. */
	public List<BlockPos> blocks() {
		return this.area.isEmpty() ? List.of(this.target) : this.area;
	}

	/** Given up after its last try: never claimed again, but kept so the same work is not posted anew. */
	public boolean givenUp() {
		return this.notBefore == Long.MAX_VALUE;
	}

	public boolean isOpen() {
		return this.claimedBy.isEmpty();
	}

	Job claim(UUID bot, long now) {
		return new Job(this.id, this.type, this.target, Optional.of(bot), now, this.area, this.item, this.tries, this.notBefore, this.posted);
	}

	Job release() {
		return new Job(this.id, this.type, this.target, Optional.empty(), 0L, this.area, this.item, this.tries, this.notBefore, this.posted);
	}

	Job retryLater(long notBefore) {
		return new Job(this.id, this.type, this.target, Optional.empty(), 0L, this.area, this.item, this.tries + 1, notBefore, this.posted);
	}

	Job reopen() {
		return new Job(this.id, this.type, this.target, this.claimedBy, this.heartbeat, this.area, this.item, this.tries, 0L, this.posted);
	}

	Job withHeartbeat(long now) {
		return new Job(this.id, this.type, this.target, this.claimedBy, now, this.area, this.item, this.tries, this.notBefore, this.posted);
	}
}
