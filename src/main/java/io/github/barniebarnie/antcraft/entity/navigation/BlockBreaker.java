package io.github.barniebarnie.antcraft.entity.navigation;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.Tools;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodConstants;
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
	/** Whether drops of the last block broken with {@code collect} did not fit in the inventory and fell to the ground. */
	private boolean spilled;

	public BlockBreaker(AntBot bot) {
		this.bot = bot;
	}

	/**
	 * Works on breaking {@code pos} for one tick; drops land in the world.
	 *
	 * @return true once the block is broken; never for one that cannot be broken, such as bedrock, so callers that
	 *     may meet one check it themselves and give up rather than act as if it were gone
	 */
	public boolean tick(ServerLevel level, BlockPos pos) {
		return this.tick(level, pos, false);
	}

	/** Like {@link #tick(ServerLevel, BlockPos)}; with {@code collect} the drops go straight into the bot's inventory. */
	public boolean tick(ServerLevel level, BlockPos pos, boolean collect) {
		return this.tick(level, pos, collect, false);
	}

	/**
	 * Like {@link #tick(ServerLevel, BlockPos, boolean)}; with {@code hand} the block breaks as with a bare hand, whatever
	 * the bot holds, so leaves drop saplings, not themselves to shears (#609).
	 */
	public boolean tick(ServerLevel level, BlockPos pos, boolean collect, boolean hand) {
		this.bot.getMoveControl().setWait();
		this.bot.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		if (!pos.equals(this.target)) {
			this.reset();
			this.target = pos;
			this.progress = 0;
			if (!hand) {
				Tools.equipBestTool(this.bot, level.getBlockState(pos));
			}
			this.total = LevelWorldView.breakTicks(level.getBlockState(pos), level, pos, hand ? ItemStack.EMPTY : this.bot.getMainHandItem());
			if (this.total < 0) {
				this.target = null;
				return false;
			}
		}

		this.progress++;
		if (this.progress % 5 == 1) {
			this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		}
		level.destroyBlockProgress(this.bot.getId(), pos, Math.min(9, this.progress * 10 / Math.max(1, this.total)));
		if (this.progress >= this.total) {
			BlockState state = level.getBlockState(pos);
			ItemStack tool = hand ? ItemStack.EMPTY : this.bot.getMainHandItem();
			if (!Tools.canHarvest(tool, state)) {
				// Like a player without the right tool: the block goes, but drops nothing.
				level.destroyBlock(pos, false, this.bot);
			} else if (collect) {
				this.spilled = false;
				for (ItemStack drop : Block.getDrops(state, level, pos, level.getBlockEntity(pos), this.bot, tool)) {
					ItemStack rest = this.bot.getInventory().addItem(drop);
					if (!rest.isEmpty()) {
						Block.popResource(level, pos, rest);
						this.spilled = true;
					}
				}
				level.destroyBlock(pos, false, this.bot);
			} else {
				level.destroyBlock(pos, true, this.bot);
			}
			this.bot.exhaust(FoodConstants.EXHAUSTION_MINE); // as a player's breaking it (#702)
			// Wears the tool down like a player's.
			if (!tool.isEmpty()) {
				tool.getItem().mineBlock(tool, level, state, pos, this.bot);
			}
			this.reset();
			return true;
		}
		return false;
	}

	/** Whether some drops of the last block broken with {@code collect} did not fit in the bot's inventory. */
	public boolean spilled() {
		return this.spilled;
	}

	public void reset() {
		if (this.target != null && this.bot.level() instanceof ServerLevel level) {
			level.destroyBlockProgress(this.bot.getId(), this.target, -1);
		}
		this.target = null;
	}
}
