package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.Output.Level;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * A bot's log (docs/scripting.md, Output and logging): its last lines, each with the game time it was written at and its
 * level. Bounded, so a chatty script cannot fill the server's memory. Not saved with the bot.
 */
public final class BotLog {
	/** How many lines a bot keeps. */
	public static final int CAPACITY = 200;

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

	/**
	 * Whether a line at {@code level} goes to the owner's chat with the chat level {@code chatLevel}: {@code print} always
	 * does, as it is above every log level, and other lines from the chat level up.
	 */
	public static boolean showsInChat(Level level, Level chatLevel) {
		return level == Level.PRINT || level.compareTo(chatLevel) >= 0;
	}
}
