package io.github.barniebarnie.antcraft.script;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A bot's log (docs/scripting.md, Output and logging): its last lines, each with the game time it was written at and its
 * level. Bounded, so a chatty script cannot fill the server's memory. Saved with the bot ({@link #save}).
 */
public final class BotLog {
	/** How many lines a bot keeps. */
	public static final int CAPACITY = 200;
	/** The key the log is saved under. */
	public static final String KEY = "Log";

	/** A level by its lower-case name; a name this version does not know reads as {@link Level#INFO}. */
	private static final Codec<Level> LEVEL_CODEC = Codec.STRING.xmap(
		name -> Stream.of(Level.values()).filter(level -> level.name().equalsIgnoreCase(name)).findFirst().orElse(Level.INFO),
		level -> level.name().toLowerCase(Locale.ROOT));
	private static final Codec<Entry> ENTRY_CODEC = RecordCodecBuilder.create(entry -> entry.group(
		Codec.LONG.fieldOf("tick").forGetter(Entry::tick),
		LEVEL_CODEC.fieldOf("level").forGetter(Entry::level),
		Codec.STRING.fieldOf("text").forGetter(Entry::text)
	).apply(entry, Entry::new));

	public record Entry(long tick, Level level, String text) {
		/** As {@code /antcraft bot log} shows it: {@code [tick level] text}. */
		public String format() {
			return "[" + tick + " " + level.name().toLowerCase(Locale.ROOT) + "] " + text;
		}
	}

	private final int capacity;
	private final Deque<Entry> entries = new ArrayDeque<>();

	public BotLog() {
		this(CAPACITY);
	}

	public BotLog(int capacity) {
		this.capacity = capacity;
	}

	public void add(long tick, Level level, String text) {
		if (entries.size() == capacity) {
			entries.removeFirst();
		}
		entries.addLast(new Entry(tick, level, text));
	}

	/** The last {@code n} lines at or above {@code level}, oldest first. */
	public List<Entry> last(int n, Level level) {
		List<Entry> found = new ArrayList<>();
		for (Iterator<Entry> it = entries.descendingIterator(); it.hasNext() && found.size() < n;) {
			Entry entry = it.next();
			if (entry.level().compareTo(level) >= 0) {
				found.add(entry);
			}
		}
		return found.reversed();
	}

	/** Saves the lines with the bot, under {@link #KEY}; an empty log is left out. */
	public void save(ValueOutput output) {
		if (!entries.isEmpty()) {
			output.store(KEY, ENTRY_CODEC.listOf(), List.copyOf(entries));
		}
	}

	/** Replaces the lines with those saved with the bot, keeping the last {@link #capacity}; none when there are none. */
	public void load(ValueInput input) {
		entries.clear();
		input.read(KEY, ENTRY_CODEC.listOf()).ifPresent(saved -> saved.forEach(entry -> add(entry.tick(), entry.level(), entry.text())));
	}

	/**
	 * Whether a line at {@code level} goes to the owner's chat with the chat level {@code chatLevel}: {@code print} always
	 * does, as it is above every log level, and other lines from the chat level up.
	 */
	public static boolean showsInChat(Level level, Level chatLevel) {
		return level == Level.PRINT || level.compareTo(chatLevel) >= 0;
	}
}
