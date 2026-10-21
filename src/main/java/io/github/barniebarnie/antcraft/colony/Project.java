package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;

/**
 * Something the colony wants done, such as "build a hut", made of build plans (by their ids in the colony).
 * Its jobs, and the craft jobs for what it waits for, carry its {@code priority}, higher first (see {@link JobScore}).
 * It is done once its last plan is.
 */
public record Project(UUID id, String name, int priority, List<UUID> plans) {
	public static final Codec<Project> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(Project::id),
		Codec.STRING.fieldOf("name").forGetter(Project::name),
		Codec.INT.optionalFieldOf("priority", 0).forGetter(Project::priority),
		UUIDUtil.STRING_CODEC.listOf().fieldOf("plans").forGetter(Project::plans)
	).apply(i, Project::new));

	public Project {
		plans = List.copyOf(plans);
	}

	Project withPriority(int priority) {
		return new Project(this.id, this.name, priority, this.plans);
	}

	Project without(UUID plan) {
		return new Project(this.id, this.name, this.priority, this.plans.stream().filter(id -> !id.equals(plan)).toList());
	}
}
