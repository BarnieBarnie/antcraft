package io.github.barniebarnie.antcraft.pathing;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * A planned route. When {@code complete} is false the steps lead to the explored
 * position closest to the goal, so the bot can make progress and plan again.
 */
public record Path(BlockPos start, List<Step> steps, boolean complete) {
	public BlockPos end() {
		return this.steps.isEmpty() ? this.start : this.steps.getLast().to();
	}
}
