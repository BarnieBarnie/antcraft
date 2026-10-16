package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Storage;
import io.github.barniebarnie.antcraft.work.ToolKind;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Builder work loop: claim a build job and work through its plan step by step. For each block
 * to place it fetches what the plan still needs of it from the core, and orders it from the
 * colony's crafter when the core has none. Blocks it places are protected from other bots. It digs
 * with the best pickaxe in the core, such as down a mineshaft.
 */
public class BuilderGoal extends ColonyWorkGoal {
	public BuilderGoal(AntBot bot) {
		super(bot, BotRole.BUILDER);
		// ponytail: no scaffolding, so plans are built within reach of the ground; add it with cleanup for tall builds.
		this.follower.setMayPlace(false);
	}

	@Override
	protected void work(ServerLevel level, Colony colony) {
		if (this.upgradeTool(level, colony, ToolKind.PICKAXE)) {
			return;
		}
		Optional<Job> job = colony.board().claim(this.bot.getUUID(), Set.of(JobType.BUILD), this.bot.blockPosition(), level.getGameTime());
		this.hasJob = job.isPresent();
		if (job.isEmpty()) {
			this.cooldown = IDLE_TICKS;
			return;
		}
		Optional<BuildPlan> plan = colony.build(job.get().target());
		Optional<BuildPlan.Step> next = plan.flatMap(p -> p.nextStep(level::getBlockState));
		if (next.isEmpty()) {
			plan.ifPresent(colony::removeBuild);
			colony.board().complete(job.get().id());
			this.hasJob = false;
			this.resetMovement();
			return;
		}
		BuildPlan.Step step = next.get();
		Item item = step.item();
		if (item != Items.AIR && this.bot.getInventory().countItem(item) == 0) {
			this.fetch(level, colony, plan.get(), item);
			return;
		}
		if (!this.reach(step.pos())) {
			return;
		}
		BlockState current = level.getBlockState(step.pos());
		// Whatever stands in the way goes first, including the same block placed the wrong way round.
		if (step.isClear() || !current.isAir() && !current.canBeReplaced()) {
			if (this.breaker.tick(level, step.pos(), true)) {
				colony.unprotect(step.pos());
			}
			return;
		}
		this.place(level, colony, step);
	}

	private void place(ServerLevel level, Colony colony, BuildPlan.Step step) {
		BlockPos pos = step.pos();
		BlockState state = Block.updateFromNeighbourShapes(step.state(), level, pos);
		level.setBlockAndUpdate(pos, state);
		level.playSound(null, pos, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
		level.gameEvent(this.bot, GameEvent.BLOCK_PLACE, pos);
		this.bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		if (step.item() != Items.AIR) {
			Storage.take(this.bot.getInventory(), step.item(), 1);
		}
		colony.protect(pos);
	}

	/**
	 * Takes as many of {@code item} from the core as the plan still needs, leaving there whatever
	 * the bot carries that the plan does not need. Orders the item if the core has none.
	 */
	private void fetch(ServerLevel level, Colony colony, BuildPlan plan, Item item) {
		Container core = Storage.containerAt(level, colony.core());
		Map<Item, Integer> needed = plan.materials(level::getBlockState);
		int count = needed.getOrDefault(item, 1);
		if (core == null || core.countItem(item) == 0) {
			int ordered = colony.orders().getOrDefault(item, 0);
			if (core != null && ordered < count) {
				colony.addOrder(item, count - ordered);
			}
			this.cooldown = IDLE_TICKS;
			return;
		}
		if (this.reach(colony.core())) {
			Storage.moveItems(this.bot.getInventory(), core, stack -> !needed.containsKey(stack.getItem()) && !stack.is(ItemTags.PICKAXES));
			Storage.moveItems(core, this.bot.getInventory(), stack -> stack.is(item), count);
		}
	}
}
