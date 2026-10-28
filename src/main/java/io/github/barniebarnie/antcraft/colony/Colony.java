package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/**
 * A colony: its core block (storage), member bots, radius and the players who may change it. What a colony does with its
 * members comes back as scripts in milestone 7 of docs/scripting.md; the goal-driven colony's jobs, stock, projects and
 * builds are gone. A colony saved with them loads without them: their fields are ignored, and not written again.
 */
public class Colony {
	public static final int DEFAULT_RADIUS = 32;

	public static final Codec<Colony> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(Colony::id),
		BlockPos.CODEC.fieldOf("core").forGetter(Colony::core),
		UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(c -> List.copyOf(c.members)),
		Codec.INT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(Colony::radius),
		UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Colony::owner),
		UUIDUtil.CODEC.listOf().optionalFieldOf("trusted", List.of()).forGetter(c -> List.copyOf(c.trusted))
	).apply(i, Colony::new));

	private final UUID id;
	private final BlockPos core;
	private final Set<UUID> members;
	/** The player who placed the core, if known; see {@link ColonyActions#mayAct}. */
	private Optional<UUID> owner;
	/** Players the owner lets change the colony, besides the owner. */
	private final Set<UUID> trusted = new LinkedHashSet<>();
	private int radius;

	public Colony(UUID id, BlockPos core, List<UUID> members, int radius) {
		this(id, core, members, radius, Optional.empty(), List.of());
	}

	private Colony(UUID id, BlockPos core, List<UUID> members, int radius, Optional<UUID> owner, List<UUID> trusted) {
		this.id = id;
		this.core = core;
		this.members = new LinkedHashSet<>(members);
		this.radius = radius;
		this.owner = owner;
		this.trusted.addAll(trusted);
		owner.ifPresent(this.trusted::remove);
	}

	/** The player who placed the core, or empty for a colony founded otherwise or saved before owners. */
	public Optional<UUID> owner() {
		return this.owner;
	}

	public void setOwner(UUID owner) {
		this.owner = Optional.of(owner);
		this.trusted.remove(owner);
	}

	/** The players besides the owner who may change the colony. */
	public Set<UUID> trusted() {
		return Collections.unmodifiableSet(this.trusted);
	}

	/** Whether {@code player} is the owner or trusted. */
	public boolean isOwnerOrTrusted(UUID player) {
		return this.owner.filter(player::equals).isPresent() || this.trusted.contains(player);
	}

	/** @return whether {@code player} was not trusted before; the owner never is */
	public boolean trust(UUID player) {
		return this.owner.filter(player::equals).isEmpty() && this.trusted.add(player);
	}

	/** @return whether {@code player} was trusted */
	public boolean untrust(UUID player) {
		return this.trusted.remove(player);
	}

	public UUID id() {
		return this.id;
	}

	public BlockPos core() {
		return this.core;
	}

	public Set<UUID> members() {
		return this.members;
	}

	public int radius() {
		return this.radius;
	}

	public void setRadius(int radius) {
		this.radius = radius;
	}
}
