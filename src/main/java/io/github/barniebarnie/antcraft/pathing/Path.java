package io.github.barniebarnie.antcraft.pathing;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * A planned route. When {@code complete} is false the steps lead to the explored
 * position closest to the goal, so the bot can make progress and plan again. {@code cost} is the sum of the
 * steps' {@link PathFinder} costs, roughly in game ticks. {@code expanded} is how many nodes the search expanded to
 * find it, what it cost the server.
 */
public record Path(BlockPos start, List<Step> steps, boolean complete, double cost, int expanded) {
	public BlockPos end() {
		return this.steps.isEmpty() ? this.start : this.steps.getLast().to();
	}
}
