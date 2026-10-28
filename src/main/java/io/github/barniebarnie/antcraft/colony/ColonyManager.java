package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** All colonies in one dimension, saved with the world. */
public class ColonyManager extends SavedData {
	/** Bot Cores activated this close to a colony core join that colony. */
	public static final int JOIN_DISTANCE = 32;

	private static final Codec<ColonyManager> CODEC = Colony.CODEC.listOf().xmap(ColonyManager::new, m -> List.copyOf(m.colonies.values()));

	// The fix type is null: Fabric API skips data fixing for mod saved data.
	public static final SavedDataType<ColonyManager> TYPE = new SavedDataType<>(AntCraft.id("colonies"), ColonyManager::new, CODEC, null);

	private final Map<UUID, Colony> colonies = new LinkedHashMap<>();

	public ColonyManager() {
	}

	private ColonyManager(List<Colony> colonies) {
		colonies.forEach(colony -> this.colonies.put(colony.id(), colony));
	}

	public static ColonyManager get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	// Colonies are changed in place (members, owner, trust, radius); there are few, so always save rather than track each change.
	@Override
	public boolean isDirty() {
		return true;
	}

	public Colony create(BlockPos core) {
		Optional<Colony> existing = this.byCore(core);
		if (existing.isPresent()) {
			return existing.get();
		}
		Colony colony = new Colony(UUID.randomUUID(), core.immutable(), List.of(), Colony.DEFAULT_RADIUS);
		this.colonies.put(colony.id(), colony);
		return colony;
	}

	public void remove(BlockPos core) {
		this.colonies.values().removeIf(colony -> colony.core().equals(core));
	}

	public Optional<Colony> byId(UUID id) {
		return Optional.ofNullable(this.colonies.get(id));
	}

	public Optional<Colony> byCore(BlockPos core) {
		return this.colonies.values().stream().filter(colony -> colony.core().equals(core)).findFirst();
	}

	public Optional<Colony> nearest(BlockPos pos, int maxDistance) {
		return this.nearest(pos, maxDistance, colony -> true);
	}

	/** The nearest colony within {@code maxDistance} of {@code pos} that passes {@code filter}. */
	public Optional<Colony> nearest(BlockPos pos, int maxDistance, Predicate<Colony> filter) {
		return this.colonies.values().stream()
			.filter(colony -> colony.core().closerThan(pos, maxDistance) && filter.test(colony))
			.min(Comparator.comparingDouble(colony -> colony.core().distSqr(pos)));
	}

	public List<Colony> all() {
		return new ArrayList<>(this.colonies.values());
	}

	/** Makes {@code bot} a member of {@code colony}, leaving the colony it was in. Its home stays where it was. */
	public void join(AntBot bot, Colony colony) {
		this.leave(bot);
		colony.members().add(bot.getUUID());
		bot.setColonyId(colony.id());
	}

	public void leave(AntBot bot) {
		if (bot.getColonyId() == null) {
			return;
		}
		this.byId(bot.getColonyId()).ifPresent(colony -> colony.members().remove(bot.getUUID()));
		bot.setColonyId(null);
	}
}
