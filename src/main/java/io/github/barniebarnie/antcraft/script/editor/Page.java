package io.github.barniebarnie.antcraft.script.editor;

/**
 * Page {@code index} of {@code count} of a list shown {@code perPage} at a time: its items {@code from} up to
 * {@code to} (docs/features/scripts.md, The bots screen).
 */
public record Page(int index, int count, int from, int to) {
	/** The page {@code wanted} of {@code items} items, or the nearest there is; at least one item to a page, and one page for none. */
	public static Page of(int items, int perPage, int wanted) {
		int size = Math.max(1, perPage);
		int count = Math.max(1, (items + size - 1) / size);
		int index = Math.clamp(wanted, 0, count - 1);
		return new Page(index, count, index * size, Math.min(items, (index + 1) * size));
	}
}
