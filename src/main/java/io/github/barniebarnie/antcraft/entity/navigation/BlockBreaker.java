package io.github.barniebarnie.antcraft.entity.navigation;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import org.jspecify.annotations.Nullable;

/** Breaks one block over time like a player would, with swing and crack animations. */
public class BlockBreaker {
	private final AntBot bot;
	private @Nullable BlockPos target;
	private int progress;
	private int total;

	public BlockBreaker(AntBot bot) {
		this.bot = bot;
	}

	/**
	 * Works on breaking {@code pos} for one tick.
	 *
	 * @return true once the block is broken, or if it cannot be broken (so callers do not wait forever)
	 */
	public boolean tick(ServerLevel level, BlockPos pos) {
		this.bot.getMoveControl().setWait();
		this.bot.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (!pos.equals(this.target)) {
			this.reset();
			this.target = pos;
			this.progress = 0;
			this.total = LevelWorldView.breakTicks(level.getBlockState(pos), level, pos, this.bot.getMainHandItem());
			if (this.total < 0) {
				this.target = null;
				return true;
			}
		}

		this.progress++;
		if (this.progress % 5 == 1) {
			this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		}
		level.destroyBlockProgress(this.bot.getId(), pos, Math.min(9, this.progress * 10 / Math.max(1, this.total)));
		if (this.progress >= this.total) {
			level.destroyBlock(pos, true, this.bot);
			this.reset();
			return true;
		}
		return false;
	}

	public void reset() {
		if (this.target != null && this.bot.level() instanceof ServerLevel level) {
			level.destroyBlockProgress(this.bot.getId(), this.target, -1);
		}
		this.target = null;
	}
}
