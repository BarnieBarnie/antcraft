package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.work.LevelTreeView;
import io.github.barniebarnie.antcraft.work.TreeScanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** All colonies in one dimension, saved with the world. */
public class ColonyManager extends SavedData {
	/** Bot Cores activated this close to a colony core join that colony. */
	public static final int JOIN_DISTANCE = 32;
	private static final int SURVEY_INTERVAL = 100;
	/** Jobs whose bot has not reported for this long are freed for others. */
	private static final long CLAIM_TIMEOUT = 600;

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

	// Bots update job heartbeats constantly, so always save rather than tracking every change.
	@Override
	public boolean isDirty() {
		return true;
	}

	public Colony create(BlockPos core) {
		Optional<Colony> existing = this.byCore(core);
		if (existing.isPresent()) {
			return existing.get();
		}
		Colony colony = new Colony(UUID.randomUUID(), core.immutable(), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of());
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
		return this.colonies.values().stream()
			.filter(colony -> colony.core().closerThan(pos, maxDistance))
			.min(Comparator.comparingDouble(colony -> colony.core().distSqr(pos)));
	}

	public List<Colony> all() {
		return new ArrayList<>(this.colonies.values());
	}

	public void join(AntBot bot, Colony colony) {
		this.leave(bot);
		colony.members().add(bot.getUUID());
		bot.setColonyId(colony.id());
		bot.setHome(colony.core());
	}

	public void leave(AntBot bot) {
		if (bot.getColonyId() == null) {
			return;
		}
		this.byId(bot.getColonyId()).ifPresent(colony -> {
			colony.members().remove(bot.getUUID());
			colony.board().release(bot.getUUID());
		});
		bot.setColonyId(null);
	}

	public void tick(ServerLevel level) {
		long now = level.getGameTime();
		for (Colony colony : this.colonies.values()) {
			if (colony.lastSurvey >= 0 && now - colony.lastSurvey < SURVEY_INTERVAL) {
				continue;
			}
			colony.lastSurvey = now;
			colony.board().releaseStale(now, CLAIM_TIMEOUT);
			colony.board().removeOpenIf(job -> job.type() == JobType.CHOP_TREE && !level.getBlockState(job.target()).is(BlockTags.LOGS));
			for (TreeScanner.Tree tree : TreeScanner.findAll(new LevelTreeView(level), colony.core(), colony.radius())) {
				colony.board().post(JobType.CHOP_TREE, tree.base());
			}
		}
	}
}
