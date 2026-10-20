package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import io.github.barniebarnie.antcraft.work.TreeScanner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

/**
 * Shared list of colony jobs. Each job is worked on by at most one bot, and each
 * bot holds at most one job.
 */
public class JobBoard {
	public static final Codec<JobBoard> CODEC = Job.CODEC.listOf().xmap(JobBoard::new, JobBoard::jobs);

	private final List<Job> jobs;

	public JobBoard() {
		this.jobs = new ArrayList<>();
	}

	private JobBoard(List<Job> jobs) {
		this.jobs = new ArrayList<>(jobs);
	}

	public List<Job> jobs() {
		return List.copyOf(this.jobs);
	}

	/** Adds a job unless one of the same type already targets that position. */
	public Optional<Job> post(JobType type, BlockPos target) {
		return this.post(type, target, List.of());
	}

	/**
	 * Adds a job covering {@code area} unless one of the same type already targets that position
	 * or covers any of it. So the rest of a tree being felled, which has a new lowest log, is not
	 * posted as a second tree.
	 */
	public Optional<Job> post(JobType type, BlockPos target, Collection<BlockPos> area) {
		return this.post(type, target, area, Optional.empty());
	}

	/** Like {@link #post(JobType, BlockPos, Collection)}, for a job that needs {@code item}. */
	public Optional<Job> post(JobType type, BlockPos target, Collection<BlockPos> area, Optional<Item> item) {
		return this.post(type, target, area, item, 0L);
	}

	/** Like {@link #post(JobType, BlockPos, Collection, Optional)}, remembering the game time it was posted. */
	public Optional<Job> post(JobType type, BlockPos target, Collection<BlockPos> area, Optional<Item> item, long now) {
		return this.post(type, target, area, item, now, Optional.empty());
	}

	/** Like {@link #post(JobType, BlockPos)}, for a job that needs {@code tool}, such as a hoe to till. */
	public Optional<Job> post(JobType type, BlockPos target, Optional<Item> tool) {
		return this.post(type, target, List.of(), Optional.empty(), 0L, tool);
	}

	private Optional<Job> post(JobType type, BlockPos target, Collection<BlockPos> area, Optional<Item> item, long now, Optional<Item> tool) {
		if (this.has(type, target) || this.jobs.stream().anyMatch(job -> job.type() == type && area.stream().anyMatch(job.area()::contains))) {
			return Optional.empty();
		}
		Job job = new Job(UUID.randomUUID(), type, target.immutable(), Optional.empty(), 0L, List.copyOf(area), item, 0, 0L, now, tool, 0);
		this.jobs.add(job);
		return Optional.of(job);
	}

	/** Adds a job with a given id, such as a build plan's, unless a job with that id is posted already. */
	public Optional<Job> post(UUID id, JobType type, BlockPos target) {
		if (this.jobs.stream().anyMatch(job -> job.id().equals(id))) {
			return Optional.empty();
		}
		Job job = new Job(id, type, target.immutable(), Optional.empty(), 0L, List.of(), Optional.empty(), 0, 0L, 0L, Optional.empty(), 0);
		this.jobs.add(job);
		return Optional.of(job);
	}

	/**
	 * Posts tree jobs for the {@code trees} nearest to {@code from} until they and the tree jobs on the
	 * board, claimed or not, cover {@code logs} logs. Given-up jobs bring no logs, so they do not count.
	 */
	public void postTrees(List<TreeScanner.Tree> trees, BlockPos from, int logs) {
		int covered = this.jobs.stream().filter(job -> job.type() == JobType.CHOP_TREE && !job.givenUp()).mapToInt(job -> job.blocks().size()).sum();
		for (TreeScanner.Tree tree : trees.stream().sorted(Comparator.comparingDouble(tree -> tree.base().distSqr(from))).toList()) {
			if (covered >= logs) {
				return;
			}
			// Covering its logs keeps the rest of a tree being felled from being posted as a new tree.
			if (this.post(JobType.CHOP_TREE, tree.base(), tree.logs()).isPresent()) {
				covered += tree.logs().size();
			}
		}
	}

	public boolean has(JobType type, BlockPos target) {
		return this.jobs.stream().anyMatch(job -> job.type() == type && job.target().equals(target));
	}

	/**
	 * Gives the bot its current job, or claims the open job nearest to {@code from}
	 * among the types it can do.
	 */
	public Optional<Job> claim(UUID bot, Set<JobType> types, BlockPos from, long now) {
		return this.claim(bot, types, from, now, job -> true);
	}

	/**
	 * Like {@link #claim(UUID, Set, BlockPos, long)}, but only claims an open job that is
	 * {@code ready} to be worked on, such as a replant job once there is a sapling for it. Jobs to
	 * retry later are not claimed before their time.
	 * The bot's current job is given back whether it is ready or not.
	 */
	public Optional<Job> claim(UUID bot, Set<JobType> types, BlockPos from, long now, Predicate<Job> ready) {
		return this.claim(bot, types, from, now, ready, job -> 0);
	}

	/**
	 * Like {@link #claim(UUID, Set, BlockPos, long, Predicate)}, but claims the open job with the
	 * lowest {@code rank} first, and the nearest of those, such as the first batch of a build plan.
	 */
	public Optional<Job> claim(UUID bot, Set<JobType> types, BlockPos from, long now, Predicate<Job> ready, ToIntFunction<Job> rank) {
		Optional<Job> current = this.jobOf(bot);
		if (current.isPresent()) {
			this.heartbeat(bot, now);
			return this.jobOf(bot);
		}
		Optional<Job> nearest = this.jobs.stream()
			.filter(job -> job.isOpen() && job.notBefore() <= now && types.contains(job.type()) && ready.test(job))
			.min(Comparator.comparingInt(rank).thenComparingDouble(job -> job.target().distSqr(from)));
		nearest.ifPresent(job -> this.replace(job, job.claim(bot, now)));
		return nearest.flatMap(job -> this.jobOf(bot));
	}

	/**
	 * Gives the bot its current job, or claims the {@code ready} open job {@code score} rates lowest,
	 * skipping jobs it cannot take. Jobs to retry later are not claimed before their time.
	 */
	public Optional<Job> claim(UUID bot, long now, Predicate<Job> ready, JobScore score) {
		Optional<Job> current = this.jobOf(bot);
		if (current.isPresent()) {
			this.heartbeat(bot, now);
			return this.jobOf(bot);
		}
		Optional<Job> best = this.best(now, ready, score);
		best.ifPresent(job -> this.replace(job, job.claim(bot, now)));
		return best.flatMap(job -> this.jobOf(bot));
	}

	/** The {@code ready} open job {@code score} rates lowest, left open; see {@link #claim(UUID, long, Predicate, JobScore)}. */
	public Optional<Job> best(long now, Predicate<Job> ready, JobScore score) {
		return this.jobs.stream()
			.filter(job -> job.isOpen() && job.notBefore() <= now && ready.test(job) && score.score(job).isPresent())
			.min(Comparator.comparingDouble(job -> score.score(job).orElseThrow()));
	}

	public Optional<Job> jobOf(UUID bot) {
		return this.jobs.stream().filter(job -> job.claimedBy().filter(bot::equals).isPresent()).findFirst();
	}

	public void heartbeat(UUID bot, long now) {
		this.jobOf(bot).ifPresent(job -> this.replace(job, job.withHeartbeat(now)));
	}

	public void complete(UUID jobId) {
		this.jobs.removeIf(job -> job.id().equals(jobId));
	}

	public void release(UUID bot) {
		this.jobOf(bot).ifPresent(job -> this.replace(job, job.release()));
	}

	/**
	 * Gives the bot's job back to be tried again from {@code notBefore}, such as a tree with logs it could
	 * not reach. After {@code maxTries} tries it is given up instead. Returns whether it will be retried.
	 */
	public boolean retryLater(UUID bot, long notBefore, int maxTries) {
		Optional<Job> job = this.jobOf(bot);
		boolean retry = job.map(j -> j.tries() + 1 < maxTries).orElse(false);
		job.ifPresent(j -> this.replace(j, j.retryLater(retry ? notBefore : Long.MAX_VALUE)));
		return retry;
	}

	/**
	 * Puts given-up jobs back up, such as a tree a player has since opened the way to. They keep their tries,
	 * so a job whose way is still shut is given up again after one more try. Returns how many were reopened.
	 */
	public int reopenGivenUp() {
		int reopened = 0;
		for (Job job : List.copyOf(this.jobs)) {
			if (job.givenUp()) {
				this.replace(job, job.reopen());
				reopened++;
			}
		}
		return reopened;
	}

	/** Frees jobs whose bot has not reported for {@code timeout} ticks. Returns how many were freed. */
	public int releaseStale(long now, long timeout) {
		int released = 0;
		for (Job job : List.copyOf(this.jobs)) {
			if (!job.isOpen() && now - job.heartbeat() > timeout) {
				this.replace(job, job.release());
				released++;
			}
		}
		return released;
	}

	/** Removes unclaimed jobs matching the filter, e.g. trees that no longer exist. */
	public void removeOpenIf(Predicate<Job> filter) {
		this.jobs.removeIf(job -> job.isOpen() && filter.test(job));
	}

	private void replace(Job old, Job updated) {
		int index = this.jobs.indexOf(old);
		if (index >= 0) {
			this.jobs.set(index, updated);
		}
	}
}
