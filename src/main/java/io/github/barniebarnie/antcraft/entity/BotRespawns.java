package io.github.barniebarnie.antcraft.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.script.lang.Code;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Bots of one dimension that died and wait to come back at their home (docs/features/death.md, Idle, home and death), saved
 * with the world so the wait survives a reload. Each waiting bot is kept as it was saved when it died, with what it carried
 * already dropped.
 */
public final class BotRespawns extends SavedData {
	/**
	 * A dead bot waiting to respawn at {@code home} from game time {@code at} on: the bot as saved when it died, and the
	 * script it ran then ({@code script} with {@code args}), to start again from the top. {@code library} says the script
	 * came from the script library, which it is then taken from again. {@code code} is that script's compiled code for one
	 * that did not come from the library, as game tests run; it is not saved.
	 */
	public record Pending(UUID id, BlockPos home, long at, CompoundTag bot, Optional<String> script, List<String> args, boolean library,
		@Nullable Code code) {
		public static final Codec<Pending> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			UUIDUtil.CODEC.fieldOf("Id").forGetter(Pending::id),
			BlockPos.CODEC.fieldOf("Home").forGetter(Pending::home),
			Codec.LONG.fieldOf("At").forGetter(Pending::at),
			CompoundTag.CODEC.fieldOf("Bot").forGetter(Pending::bot),
			Codec.STRING.optionalFieldOf("Script").forGetter(Pending::script),
			Codec.STRING.listOf().optionalFieldOf("Args", List.of()).forGetter(Pending::args),
			Codec.BOOL.optionalFieldOf("Library", false).forGetter(Pending::library)
		).apply(instance, (id, home, at, bot, script, args, library) -> new Pending(id, home, at, bot, script, args, library, null)));
	}

	private static final Codec<BotRespawns> CODEC = Pending.CODEC.listOf().xmap(BotRespawns::new, respawns -> List.copyOf(respawns.pending.values()));

	// The fix type is null: Fabric API skips data fixing for mod saved data.
	public static final SavedDataType<BotRespawns> TYPE = new SavedDataType<>(AntCraft.id("bot_respawns"), BotRespawns::new, CODEC, null);

	private final Map<UUID, Pending> pending = new LinkedHashMap<>();

	public BotRespawns() {
	}

	private BotRespawns(List<Pending> pending) {
		pending.forEach(this::add);
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> server.getAllLevels().forEach(level -> get(level).tick(level)));
	}

	public static BotRespawns get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	/** When a bot that died at game time {@code died} comes back under {@code config}: never with {@code botRespawn} off. */
	public static OptionalLong respawnTime(AntCraftConfig.Values config, long died) {
		return config.botRespawn() ? OptionalLong.of(died + config.botRespawnTicks()) : OptionalLong.empty();
	}

	/** Lets the bot of {@code respawn} wait to come back, in place of any wait it already had. */
	public void add(Pending respawn) {
		this.pending.put(respawn.id(), respawn);
		this.setDirty();
	}

	/** The wait of the bot with {@code id}, or null when it does not wait to respawn. */
	public @Nullable Pending pending(UUID id) {
		return this.pending.get(id);
	}

	/** Drops the waits of the bots whose home matches {@code home}: they stay dead, and leave their colony. */
	public void forget(MinecraftServer server, Predicate<BlockPos> home) {
		for (Pending respawn : List.copyOf(this.pending.values())) {
			if (home.test(respawn.home())) {
				this.pending.remove(respawn.id());
				ColonyManager.leave(server, respawn.id());
				this.setDirty();
			}
		}
	}

	/** Drops the wait of the bot with {@code id}, if any: it stays dead, and leaves its colony. */
	public void forget(MinecraftServer server, UUID id) {
		if (this.pending.remove(id) != null) {
			ColonyManager.leave(server, id);
			this.setDirty();
		}
	}

	/**
	 * Brings back the bots whose time has come, once their home ticks entities, keeping it loaded meanwhile when bots may
	 * load chunks ({@code botsLoadChunks}), and once their dead body is gone (it lies there for a second).
	 */
	private void tick(ServerLevel level) {
		if (this.pending.isEmpty()) {
			return;
		}
		long now = level.getGameTime();
		for (Pending respawn : List.copyOf(this.pending.values())) {
			if (respawn.at() > now || level.getEntity(respawn.id()) != null) {
				continue;
			}
			if (!level.isPositionEntityTicking(respawn.home())) {
				if (AntCraftConfig.botsLoadChunks()) {
					BotChunks.keep(level, ChunkPos.containing(respawn.home()));
				}
				continue;
			}
			if (AntBot.respawn(level, respawn)) {
				this.pending.remove(respawn.id());
				this.setDirty();
			}
		}
	}
}
