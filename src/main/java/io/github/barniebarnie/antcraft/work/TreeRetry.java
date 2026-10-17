package io.github.barniebarnie.antcraft.work;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * A tree a solo lumberjack could not fell whole, to come back to: its {@code base}, the {@code logs} left
 * out of reach, how many visits it {@code tries} so far, and the game time from which it may try again
 * ({@code notBefore}); {@link Long#MAX_VALUE} once it is given up. Colony lumberjacks keep the same on the
 * job board instead (see {@code Job}).
 */
public record TreeRetry(BlockPos base, List<BlockPos> logs, int tries, long notBefore) {
	public static final Codec<TreeRetry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		BlockPos.CODEC.fieldOf("Base").forGetter(TreeRetry::base),
		BlockPos.CODEC.listOf().fieldOf("Logs").forGetter(TreeRetry::logs),
		Codec.INT.fieldOf("Tries").forGetter(TreeRetry::tries),
		Codec.LONG.fieldOf("NotBefore").forGetter(TreeRetry::notBefore)
	).apply(instance, TreeRetry::new));

	public TreeRetry {
		logs = List.copyOf(logs);
	}

	/**
	 * The retry after another visit at {@code now} that left {@code logs} standing: the next visit
	 * {@code wait} ticks later, or given up when that was visit {@code maxTries}.
	 *
	 * @param previous the retry this visit came back for, or null after the first visit
	 */
	public static TreeRetry after(@Nullable TreeRetry previous, BlockPos base, List<BlockPos> logs, long now, long wait, int maxTries) {
		int tries = previous == null ? 1 : previous.tries + 1;
		return new TreeRetry(base, logs, tries, tries < maxTries ? now + wait : Long.MAX_VALUE);
	}

	/** Given up after its last try: never visited again, but kept so the tree is not taken anew. */
	public boolean givenUp() {
		return this.notBefore == Long.MAX_VALUE;
	}

	public boolean isDue(long now) {
		return !this.givenUp() && now >= this.notBefore;
	}
}
