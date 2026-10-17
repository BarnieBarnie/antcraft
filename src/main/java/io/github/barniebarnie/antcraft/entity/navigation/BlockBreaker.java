package io.github.barniebarnie.antcraft.entity.navigation;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.Tools;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
	 * Works on breaking {@code pos} for one tick; drops land in the world.
	 *
	 * @return true once the block is broken, or if it cannot be broken (so callers do not wait forever)
	 */
	public boolean tick(ServerLevel level, BlockPos pos) {
		return this.tick(level, pos, false);
	}

	/** Like {@link #tick(ServerLevel, BlockPos)}; with {@code collect} the drops go straight into the bot's inventory. */
	public boolean tick(ServerLevel level, BlockPos pos, boolean collect) {
		this.bot.getMoveControl().setWait();
		this.bot.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (!pos.equals(this.target)) {
			this.reset();
			this.target = pos;
			this.progress = 0;
			Tools.equipBestTool(this.bot, level.getBlockState(pos));
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
			BlockState state = level.getBlockState(pos);
			ItemStack tool = this.bot.getMainHandItem();
			if (!Tools.canHarvest(tool, state)) {
				// Like a player without the right tool: the block goes, but drops nothing.
				level.destroyBlock(pos, false, this.bot);
			} else if (collect) {
				for (ItemStack drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), this.bot, tool)) {
					ItemStack rest = this.bot.getInventory().addItem(drop);
					if (!rest.isEmpty()) {
						Block.popResource(level, pos, rest);
					}
				}
				level.destroyBlock(pos, false, this.bot);
			} else {
				level.destroyBlock(pos, true, this.bot);
			}
			// Wears the tool down like a player's.
			if (!tool.isEmpty()) {
				tool.getItem().mineBlock(tool, level, state, pos, this.bot);
			}
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
