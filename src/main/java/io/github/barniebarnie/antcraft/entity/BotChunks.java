package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.AntCraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Chunk tickets of bots running scripts (docs/scripting.md, Chunk loading), with config {@code botsLoadChunks} on. A bot
 * keeps the chunk it stands in ticking, as a thrown ender pearl does, and {@code goto} loads the chunks of the next stretch
 * of its way. Both tickets time out, so they need renewing while they are wanted: the bot renews its own every tick, after it
 * moved, which carries the ticket along with it. A bot running the {@code idle.py} it fell back to holds its
 * own only while it walks: it waits at home only while players keep its chunk loaded.
 */
public final class BotChunks {
	/**
	 * A bot's own chunk: entities tick there, the chunks around it are loaded. Not saved; a bot loaded with the world
	 * gets it again on its first tick.
	 */
	public static final TicketType BOT = new TicketType(40L,
		TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
	/** A chunk on a bot's way, loaded (and generated if need be) a while before the bot gets there. */
	public static final TicketType PATH = new TicketType(100L, TicketType.FLAG_LOADING);
	/**
	 * The bot's chunk and its neighbours tick entities, so a bot stepping into the next chunk keeps ticking there (an entity
	 * that enters a chunk that does not tick entities stops, and is not woken when the chunk starts to); the ring past them
	 * ticks blocks, and the next one is loaded.
	 */
	private static final int BOT_RADIUS = 3;

	private BotChunks() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.TICKET_TYPE, AntCraft.id("bot"), BOT);
		Registry.register(BuiltInRegistries.TICKET_TYPE, AntCraft.id("bot_path"), PATH);
	}

	/** Keeps the chunk {@code bot} stands in ticking for a while longer. */
	public static void keep(AntBot bot) {
		if (bot.level() instanceof ServerLevel level) {
			level.getChunkSource().addTicketWithRadius(BOT, bot.chunkPosition(), BOT_RADIUS);
		}
	}

	/** Keeps {@code chunk} ticking for a while, as a bot standing there does: where a dead bot is to respawn. */
	public static void keep(ServerLevel level, ChunkPos chunk) {
		level.getChunkSource().addTicketWithRadius(BOT, chunk, BOT_RADIUS);
	}

	/** Loads the chunk at chunk coordinates {@code x}, {@code z} over the next ticks, generating it if need be, and keeps it a while. */
	public static void load(ServerLevel level, int x, int z) {
		level.getChunkSource().addTicketWithRadius(PATH, new ChunkPos(x, z), 0);
	}

	/** Whether the chunk is loaded, without loading it. */
	public static boolean loaded(ServerLevel level, int x, int z) {
		return level.getChunkSource().hasChunk(x, z);
	}
}
