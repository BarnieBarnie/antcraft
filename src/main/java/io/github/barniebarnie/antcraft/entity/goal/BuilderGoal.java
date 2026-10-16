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
import org.jspecify.annotations.Nullable;

/**
 * Builder work loop: claim a build job and work through its plan step by step. For each block
 * to place it fetches what the plan still needs of it from the core; steps whose item the core
 * lacks wait while the rest goes on. It places blocks only where no bot or mob stands, and blocks
 * it places are protected from other bots. It digs with the best pickaxe in the core, such as
 * down a mineshaft.
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
		Container core = Storage.containerAt(level, colony.core());
		Optional<Job> job = colony.board().claim(this.bot.getUUID(), Set.of(JobType.BUILD), this.bot.blockPosition(), level.getGameTime(),
			open -> colony.build(open.id()).flatMap(plan -> this.nextStep(level, core, plan)).isPresent());
		this.hasJob = job.isPresent();
		if (job.isEmpty()) {
			this.cooldown = IDLE_TICKS;
			return;
		}
		Optional<BuildPlan> plan = colony.build(job.get().id());
		Optional<BuildPlan.Step> next = plan.flatMap(p -> this.nextStep(level, core, p));
		if (next.isEmpty()) {
			this.putAside(level, colony, job.get(), plan);
			return;
		}
		BuildPlan.Step step = next.get();
		// Whatever stands in the way goes first; that needs no materials.
		if (step.needsClearing(level.getBlockState(step.pos()))) {
			if (this.reach(step.pos()) && this.breaker.tick(level, step.pos(), true)) {
				colony.unprotect(step.pos());
			}
			return;
		}
		if (step.item() != Items.AIR && this.bot.getInventory().countItem(step.item()) == 0) {
			this.fetch(level, colony, core, plan.get(), step.item());
			return;
		}
		if (!this.reach(step.pos())) {
			return;
		}
		if (this.isFreeToPlace(level, step.pos(), step.state())) {
			this.place(level, colony, step);
		}
	}

	/**
	 * The step to work on: the first one not done that is neither blocked by an unbreakable block
	 * nor waiting for an item that neither the bot nor the core has. Those are left for later, so
	 * the rest of the plan goes on, such as digging a mineshaft before its torches are made.
	 */
	private Optional<BuildPlan.Step> nextStep(ServerLevel level, @Nullable Container core, BuildPlan plan) {
		return plan.nextStep(level::getBlockState, step -> {
			BlockState current = level.getBlockState(step.pos());
			if (step.needsClearing(current)) {
				return !isUnbreakable(level, step.pos());
			}
			Item item = step.item();
			return item == Items.AIR || this.bot.getInventory().countItem(item) > 0 || core != null && core.countItem(item) > 0;
		});
	}

	private static boolean isUnbreakable(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getDestroySpeed(level, pos) < 0;
	}

	/**
	 * Nothing in the plan can be worked on now. With only unbreakable blocks left in the way the
	 * plan is finished and those steps are reported; while it waits for materials, which the
	 * colony orders, the job is freed for later.
	 */
	private void putAside(ServerLevel level, Colony colony, Job job, Optional<BuildPlan> plan) {
		Optional<BuildPlan.Step> waiting = plan.flatMap(p -> p.nextStep(level::getBlockState,
			step -> !step.needsClearing(level.getBlockState(step.pos())) || !isUnbreakable(level, step.pos())));
		if (waiting.isPresent()) {
			colony.board().release(this.bot.getUUID());
			this.cooldown = IDLE_TICKS;
		} else {
			plan.ifPresent(p -> {
				colony.reportBlocked(p.steps().stream().filter(step -> !step.isDone(level.getBlockState(step.pos()))).map(BuildPlan.Step::pos).toList());
				colony.removeBuild(p);
			});
			colony.board().complete(job.id());
		}
		this.hasJob = false;
		this.resetMovement();
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
	 * the bot carries that the plan does not need. The colony survey orders what the core lacks.
	 */
	private void fetch(ServerLevel level, Colony colony, @Nullable Container core, BuildPlan plan, Item item) {
		Map<Item, Integer> needed = plan.materials(level::getBlockState);
		int count = needed.getOrDefault(item, 1);
		if (core == null) {
			this.cooldown = IDLE_TICKS;
			return;
		}
		if (this.reach(colony.core())) {
			Storage.moveItems(this.bot.getInventory(), core, stack -> !needed.containsKey(stack.getItem()) && !stack.is(ItemTags.PICKAXES));
			Storage.moveItems(core, this.bot.getInventory(), stack -> stack.is(item), count);
		}
	}
}
