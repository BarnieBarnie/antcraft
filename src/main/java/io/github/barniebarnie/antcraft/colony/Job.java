package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * A unit of colony work. {@code claimedBy} is the bot working on it; {@code heartbeat}
 * is the game time it last reported progress, used to free jobs of bots that vanished.
 * {@code area} is every block the job covers, such as a tree's logs, so the same work is not posted twice.
 */
public record Job(UUID id, JobType type, BlockPos target, Optional<UUID> claimedBy, long heartbeat, List<BlockPos> area) {
	public static final Codec<Job> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(Job::id),
		JobType.CODEC.fieldOf("type").forGetter(Job::type),
		BlockPos.CODEC.fieldOf("target").forGetter(Job::target),
		UUIDUtil.CODEC.optionalFieldOf("claimed_by").forGetter(Job::claimedBy),
		Codec.LONG.optionalFieldOf("heartbeat", 0L).forGetter(Job::heartbeat),
		BlockPos.CODEC.listOf().optionalFieldOf("area", List.of()).forGetter(Job::area)
	).apply(i, Job::new));

	public boolean isOpen() {
		return this.claimedBy.isEmpty();
	}

	Job claim(UUID bot, long now) {
		return new Job(this.id, this.type, this.target, Optional.of(bot), now, this.area);
	}

	Job release() {
		return new Job(this.id, this.type, this.target, Optional.empty(), 0L, this.area);
	}

	Job withHeartbeat(long now) {
		return new Job(this.id, this.type, this.target, this.claimedBy, now, this.area);
	}
}
