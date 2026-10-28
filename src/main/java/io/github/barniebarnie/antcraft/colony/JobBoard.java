package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;

/**
 * A colony's job board (docs/scripting.md, Jobs, #368): members post jobs, take the oldest open one, and finish or drop
 * it. Natives do the claiming, so two scripts never take the same job, as they could on {@code colony_memory}. Each job
 * keeps its data as a tag ({@code Memory.encode}), a snapshot of the plain data the poster gave.
 */
public final class JobBoard {
	public record Job(long id, String kind, CompoundTag data, UUID poster, Optional<UUID> taker) {
		static final Codec<Job> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.LONG.fieldOf("id").forGetter(Job::id),
			Codec.STRING.fieldOf("kind").forGetter(Job::kind),
			CompoundTag.CODEC.fieldOf("data").forGetter(Job::data),
			UUIDUtil.CODEC.fieldOf("poster").forGetter(Job::poster),
			UUIDUtil.CODEC.optionalFieldOf("taker").forGetter(Job::taker)
		).apply(i, Job::new));

		Job withTaker(Optional<UUID> taker) {
			return new Job(this.id, this.kind, this.data, this.poster, taker);
		}
	}

	public static final Codec<JobBoard> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.LONG.fieldOf("next_id").forGetter(b -> b.nextId),
		Job.CODEC.listOf().fieldOf("jobs").forGetter(b -> List.copyOf(b.jobs))
	).apply(i, JobBoard::new));

	private long nextId;
	/** Oldest first. ponytail: a list scanned per call; a colony has few jobs. */
	private final List<Job> jobs;

	public JobBoard() {
		this(1, List.of());
	}

	private JobBoard(long nextId, List<Job> jobs) {
		this.nextId = nextId;
		this.jobs = new ArrayList<>(jobs);
	}

	/** Whether no job was ever posted: then the board is not saved. */
	boolean unused() {
		return this.nextId == 1;
	}

	/** Adds an open job and returns its id; ids count up and are never reused. */
	public long post(String kind, CompoundTag data, UUID poster) {
		long id = this.nextId++;
		this.jobs.add(new Job(id, kind, data, poster, Optional.empty()));
		return id;
	}

	/** The open and taken jobs of {@code kind}, or of every kind for null, oldest first. */
	public List<Job> jobs(@Nullable String kind) {
		return this.jobs.stream().filter(job -> kind == null || job.kind().equals(kind)).toList();
	}

	/** Claims the oldest open job of {@code kind} (or of any kind for null) for {@code taker}. */
	public Optional<Job> take(@Nullable String kind, UUID taker) {
		for (int i = 0; i < this.jobs.size(); i++) {
			Job job = this.jobs.get(i);
			if (job.taker().isEmpty() && (kind == null || job.kind().equals(kind))) {
				Job taken = job.withTaker(Optional.of(taker));
				this.jobs.set(i, taken);
				return Optional.of(taken);
			}
		}
		return Optional.empty();
	}

	/** Removes job {@code id}; whether it was on the board. */
	public boolean finish(long id) {
		return this.jobs.removeIf(job -> job.id() == id);
	}

	/** Gives taken job {@code id} back to the board; whether it was taken. */
	public boolean drop(long id) {
		for (int i = 0; i < this.jobs.size(); i++) {
			Job job = this.jobs.get(i);
			if (job.id() == id && job.taker().isPresent()) {
				this.jobs.set(i, job.withTaker(Optional.empty()));
				return true;
			}
		}
		return false;
	}

	/** Gives back every job whose taker is {@code gone}, such as one whose script ended. */
	public void release(Predicate<UUID> gone) {
		this.jobs.replaceAll(job -> job.taker().filter(gone).isPresent() ? job.withTaker(Optional.empty()) : job);
	}
}
