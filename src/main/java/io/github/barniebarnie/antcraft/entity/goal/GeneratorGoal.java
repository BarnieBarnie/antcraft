package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.PlacedBlueprint;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Storage;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jspecify.annotations.Nullable;

/**
 * Cobblestone generator work: the executor of {@link JobType#MINE_GENERATOR} jobs for {@link WorkerGoal}. The bot stands
 * on the generator's second work spot, in front of its mining block, and mines the cobblestone each time it forms there,
 * the drops going straight into its pack, until the colony holds what its stock targets and orders want of cobblestone,
 * counting what the bot carries; then it stores the cobblestone in the core, or in a storage chest when the core is
 * full, and completes the job. A full pack it stores on the way. It breaks no other block of the generator and leaves
 * its fluids alone: it only walks to the spot, and paths leave the generator's blocks standing, as builders placed them,
 * and keep its mining block open. The pickaxe the job declares is fetched by {@link WorkerGoal} (see its tool check).
 */
public class GeneratorGoal extends ColonyWorkGoal implements WorkerGoal.Executor {
	private static final int MIN_FREE_SLOTS = 2;
	private enum State { WORK, DEPOSIT }

	private State state = State.WORK;
	private @Nullable Job job;
	/** The mining block and the spot the bot mines it from, of the job begun. */
	private BlockPos mine = BlockPos.ZERO;
	private BlockPos stand = BlockPos.ZERO;
	/** Whether the colony has enough cobblestone, so the job ends once the bot has stored what it carries. */
	private boolean enough;
	/** Whether to look again at what the colony wants, as at the start and after each block mined. */
	private boolean checkWanted;
	private WorkerGoal.@Nullable Outcome outcome;

	public GeneratorGoal(AntBot bot) {
		super(bot);
	}

	/** The built generator whose mining block is {@code mine}, if any. */
	private static Optional<PlacedBlueprint> generator(Colony colony, BlockPos mine) {
		return colony.finishedBlueprints(Blueprint.Role.GENERATOR).stream()
			.filter(placed -> placed.workSpots().size() >= 2 && placed.workSpots().getFirst().equals(mine)).findFirst();
	}

	@Override
	public boolean isReady(ServerLevel level, Colony colony, Job job) {
		return generator(colony, job.target()).isPresent();
	}

	@Override
	public boolean begin(ServerLevel level, Job job) {
		this.outcome = null;
		Optional<PlacedBlueprint> generator = this.colony(level).flatMap(colony -> generator(colony, job.target()));
		if (generator.isEmpty()) {
			return false;
		}
		this.job = job;
		this.mine = generator.get().workSpots().get(0);
		this.stand = generator.get().workSpots().get(1);
		this.enough = false;
		this.checkWanted = true;
		this.cooldown = 0;
		this.enter(State.WORK);
		return true;
	}

	@Override
	public boolean work(ServerLevel level) {
		Optional<Colony> colony = this.colony(level);
		if (colony.isEmpty() || this.job == null) {
			return false;
		}
		this.bot.setWorking(true);
		if (this.cooldown > 0) {
			this.cooldown--;
			return true;
		}
		switch (this.state) {
			case WORK -> this.mine(level, colony.get());
			case DEPOSIT -> this.deposit(level, colony.get());
		}
		return true;
	}

	@Override
	public Optional<WorkerGoal.Outcome> takeOutcome() {
		Optional<WorkerGoal.Outcome> taken = Optional.ofNullable(this.outcome);
		this.outcome = null;
		return taken;
	}

	@Override
	public void reset() {
		this.job = null;
		this.outcome = null;
		this.enter(State.WORK);
	}

	private void endJob(WorkerGoal.Outcome how) {
		this.outcome = how;
		this.job = null;
		this.bot.setWorking(false);
		this.enter(State.WORK);
	}

	private void enter(State next) {
		this.state = next;
		this.resetMovement();
	}

	private Optional<Colony> colony(ServerLevel level) {
		return Optional.ofNullable(this.bot.getColonyId()).flatMap(id -> ColonyManager.get(level).byId(id));
	}

	/** Mines the cobblestone at the mining block from the stand spot, waiting there while it forms again. */
	private void mine(ServerLevel level, Colony colony) {
		if (this.checkWanted) {
			this.checkWanted = false;
			// What the bot carries counts as held, so it stops once that makes up the stock target and orders.
			this.enough = !Storage.wanted(level, colony).test(Items.COBBLESTONE);
		}
		if (this.enough || freeSlots(this.bot.getInventory()) < MIN_FREE_SLOTS) {
			if (this.bot.getInventory().countItem(Items.COBBLESTONE) > 0) {
				this.enter(State.DEPOSIT);
			} else {
				// A pack full of other things leaves the job to a bot with room.
				this.endJob(this.enough ? WorkerGoal.Outcome.COMPLETE : WorkerGoal.Outcome.RELEASE);
			}
			return;
		}
		Goal spot = new Goal.Block(this.stand);
		PathFollower.Status status = this.approach(spot, this.mine);
		if (status == PathFollower.Status.FAILED) {
			this.endJob(WorkerGoal.Outcome.RETRY);
			return;
		}
		if (status != PathFollower.Status.ARRIVED) {
			return;
		}
		if (level.getBlockState(this.mine).is(Blocks.COBBLESTONE)) {
			if (this.breaker.tick(level, this.mine, true)) {
				this.checkWanted = true;
			}
		} else {
			this.breaker.reset();
			this.bot.getMoveControl().setWait();
		}
	}

	/**
	 * Stores the cobblestone it carries in the core, or in a storage chest when the core has no room for it, then mines
	 * on, or completes the job once the colony has enough. With no room anywhere it leaves the job.
	 */
	private void deposit(ServerLevel level, Colony colony) {
		ItemStack cobblestone = new ItemStack(Items.COBBLESTONE);
		Container core = Storage.containerAt(level, colony.core());
		BlockPos target = core != null && Storage.hasRoomFor(core, cobblestone) ? colony.core()
			: Storage.chestFor(Storage.storageChests(level, colony), cobblestone);
		Container container = target == null ? null : Storage.containerAt(level, target);
		if (target == null || container == null) {
			// Full core and storage: someone else's job to make room.
			this.endJob(WorkerGoal.Outcome.RELEASE);
			return;
		}
		if (!this.reach(target)) {
			return;
		}
		for (int i = 0; i < this.bot.getInventory().getContainerSize(); i++) {
			ItemStack stack = this.bot.getInventory().getItem(i);
			if (stack.is(Items.COBBLESTONE)) {
				ItemStack rest = HopperBlockEntity.addItem(null, container, stack.copy(), null);
				this.bot.getInventory().setItem(i, rest);
			}
		}
		this.bot.getInventory().setChanged();
		container.setChanged();
		if (this.bot.getInventory().countItem(Items.COBBLESTONE) > 0) {
			// What did not fit goes to the next place with room.
			this.resetMovement();
		} else if (this.enough) {
			this.endJob(WorkerGoal.Outcome.COMPLETE);
		} else {
			this.checkWanted = true;
			this.enter(State.WORK);
		}
	}
}
