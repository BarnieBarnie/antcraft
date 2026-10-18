package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Rates jobs for one bot, lower is better, so a generalist bot takes the job that suits it best.
 * The bot is described by its position, the items and tools it carries, its free inventory slots and the
 * job it last finished. {@code fetch} is the cost of the trip to get an item from the core or storage, or a
 * tool at least as good for a tool, and infinite if the colony has none.
 */
public record JobScore(BlockPos pos, List<Item> carried, int freeSlots, Optional<Job> last, ToDoubleFunction<Item> fetch) {
	// ponytail: naive weights, tune from game tests.
	/** Cost added for a job that yields items, divided by the free slots. */
	static final double ROOM_WEIGHT = 16;
	/** Blocks of distance one priority level is worth. */
	static final double PRIORITY_WEIGHT = 64;
	/** Bonus for a job of the same type within {@link #STICKY_RANGE} blocks of the last finished one. */
	static final double STICKY_BONUS = 8;
	static final double STICKY_RANGE = 4;

	/** The job's cost for this bot, or empty if it cannot take it: a full bot for a job that yields items, or a need nothing satisfies. */
	public OptionalDouble score(Job job) {
		double cost = Math.sqrt(job.target().distSqr(this.pos));
		for (Item need : Stream.concat(job.item().stream(), job.tool().stream()).toList()) {
			if (!this.carries(need)) {
				cost += this.fetch.applyAsDouble(need);
			}
		}
		if (job.type().yieldsItems()) {
			if (this.freeSlots <= 0) {
				return OptionalDouble.empty();
			}
			cost += ROOM_WEIGHT / this.freeSlots;
		}
		if (!Double.isFinite(cost)) {
			return OptionalDouble.empty();
		}
		cost -= PRIORITY_WEIGHT * job.priority();
		if (this.last.filter(l -> l.type() == job.type() && l.target().closerThan(job.target(), STICKY_RANGE + 0.5)).isPresent()) {
			cost -= STICKY_BONUS;
		}
		return OptionalDouble.of(cost);
	}

	/** Whether the bot carries {@code need}, or a tool of the same kind at least as good. */
	private boolean carries(Item need) {
		Optional<ToolKind> kind = Stream.of(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.HOE).filter(k -> k.tiers().contains(need)).findFirst();
		return this.carried.stream().anyMatch(item -> item == need
			|| kind.filter(k -> k.tiers().contains(item) && k.rank(item) >= k.rank(need)).isPresent());
	}
}
