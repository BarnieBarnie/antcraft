package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

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
		if (this.has(type, target)) {
			return Optional.empty();
		}
		Job job = new Job(UUID.randomUUID(), type, target.immutable(), Optional.empty(), 0L);
		this.jobs.add(job);
		return Optional.of(job);
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
	 * {@code ready} to be worked on, such as a replant job once there is a sapling for it.
	 * The bot's current job is given back whether it is ready or not.
	 */
	public Optional<Job> claim(UUID bot, Set<JobType> types, BlockPos from, long now, Predicate<Job> ready) {
		Optional<Job> current = this.jobOf(bot);
		if (current.isPresent()) {
			this.heartbeat(bot, now);
			return this.jobOf(bot);
		}
		Optional<Job> nearest = this.jobs.stream()
			.filter(job -> job.isOpen() && types.contains(job.type()) && ready.test(job))
			.min(Comparator.comparingDouble(job -> job.target().distSqr(from)));
		nearest.ifPresent(job -> this.replace(job, job.claim(bot, now)));
		return nearest.flatMap(job -> this.jobOf(bot));
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
