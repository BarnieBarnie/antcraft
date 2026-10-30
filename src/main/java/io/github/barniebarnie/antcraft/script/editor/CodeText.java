package io.github.barniebarnie.antcraft.script.editor;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The text of the script editor with its caret and selection, and the edits keys make, without rendering
 * (docs/features/scripts.md, Editing in game). The selection runs from the anchor to the caret; lines end with
 * {@code \n}. Positions are character indexes.
 */
public final class CodeText {
	private static final int UNDO_LIMIT = 100;
	/** Spaces in one level of indentation. */
	public static final int INDENT = 4;

	private final int maxLength;
	private String text = "";
	private int caret;
	private int anchor;
	/** The column up and down aim for, or -1 when the caret moved some other way. */
	private int goalColumn = -1;
	/** Whether the last edit typed one character, so the next one joins its undo step. */
	private boolean typing;
	private final Deque<State> undo = new ArrayDeque<>();
	private final Deque<State> redo = new ArrayDeque<>();

	private record State(String text, int caret, int anchor) {
	}

	/** An empty text that holds at most {@code maxLength} characters. */
	public CodeText(int maxLength) {
		this.maxLength = maxLength;
	}

	public String text() {
		return text;
	}

	public int caret() {
		return caret;
	}

	public int anchor() {
		return anchor;
	}

	/** Replaces the text, puts the caret at its start and forgets the undo history. */
	public void setText(String value) {
		text = clean(value);
		if (text.length() > maxLength) {
			text = text.substring(0, maxLength);
		}
		caret = anchor = 0;
		undo.clear();
		redo.clear();
		typing = false;
		goalColumn = -1;
	}

	public boolean hasSelection() {
		return caret != anchor;
	}

	public int selectionStart() {
		return Math.min(caret, anchor);
	}

	public int selectionEnd() {
		return Math.max(caret, anchor);
	}

	public String selected() {
		return text.substring(selectionStart(), selectionEnd());
	}

	// Edits

	/**
	 * Replaces the selection with {@code value}, its line ends made {@code \n}, tabs four spaces and other control
	 * characters dropped, cut to the length limit. The caret goes after it.
	 */
	public void insert(String value) {
		String clean = clean(value);
		boolean oneChar = clean.length() == 1 && clean.charAt(0) != '\n' && !hasSelection();
		replace(selectionStart(), selectionEnd(), clean, oneChar);
	}

	/**
	 * Removes the selection, or the character (with {@code word}, the word) before the caret; in a line's leading
	 * spaces, back to the last multiple of {@link #INDENT}.
	 */
	public void deleteBack(boolean word) {
		if (hasSelection()) {
			replace(selectionStart(), selectionEnd(), "", false);
		} else if (caret > 0) {
			int column = caret - lineStart(lineOf(caret));
			if (!word && column > 0 && text.substring(caret - column, caret).isBlank()) {
				replace(caret - ((column - 1) % INDENT + 1), caret, "", false);
			} else {
				replace(word ? wordLeft(caret) : caret - 1, caret, "", false);
			}
		}
	}

	// Indentation (docs/features/scripts.md, Editing in game)

	/**
	 * Tab: with a selection over several lines, indents each of them by {@link #INDENT} spaces; else puts spaces up to
	 * the next multiple of it in place of the selection.
	 */
	public void indent() {
		if (selected().indexOf('\n') >= 0) {
			changeIndentation(true);
		} else {
			int column = selectionStart() - lineStart(lineOf(selectionStart()));
			insert(" ".repeat(INDENT - column % INDENT));
		}
	}

	/** Shift+Tab: removes up to {@link #INDENT} leading spaces from the caret's line, or from each selected line. */
	public void dedent() {
		changeIndentation(false);
	}

	private void changeIndentation(boolean indent) {
		int first = lineOf(selectionStart());
		int last = lineOf(selectionEnd());
		boolean selection = hasSelection();
		int fromEnd = text.length() - caret;
		int start = lineStart(first);
		int end = lineEnd(last);
		StringBuilder lines = new StringBuilder();
		for (int line = first; line <= last; line++) {
			String content = line(line);
			if (indent) {
				lines.append(" ".repeat(INDENT)).append(content);
			} else {
				int spaces = 0;
				while (spaces < INDENT && spaces < content.length() && content.charAt(spaces) == ' ') {
					spaces++;
				}
				lines.append(content, spaces, content.length());
			}
			if (line < last) {
				lines.append('\n');
			}
		}
		if (lines.toString().equals(text.substring(start, end))) {
			return;
		}
		int lineEndFromEnd = text.length() - end;
		replace(start, end, lines.toString(), false);
		if (selection) {
			moveTo(start, false);
			moveTo(text.length() - lineEndFromEnd, true);
		} else {
			// The caret keeps its place in the line's text, or goes to its start if it was in the removed spaces.
			moveTo(Math.max(start, text.length() - fromEnd), false);
		}
	}

	/**
	 * Enter: a new line with the indentation of the caret's line, one level more after a line ending in {@code :}.
	 */
	public void newline() {
		int start = lineStart(lineOf(selectionStart()));
		String before = text.substring(start, selectionStart());
		int spaces = 0;
		while (spaces < before.length() && before.charAt(spaces) == ' ') {
			spaces++;
		}
		// ponytail: a colon before a trailing comment is not seen; tokenize the line if that matters.
		if (before.stripTrailing().endsWith(":")) {
			spaces += INDENT;
		}
		insert("\n" + " ".repeat(spaces));
	}

	/** Removes the selection, or the character (with {@code word}, the word) after the caret. */
	public void deleteForward(boolean word) {
		if (hasSelection()) {
			replace(selectionStart(), selectionEnd(), "", false);
		} else if (caret < text.length()) {
			replace(caret, word ? wordRight(caret) : caret + 1, "", false);
		}
	}

	/**
	 * Replaces characters {@code start} to {@code end} with {@code value} (cut to the length limit) as one undo step and
	 * puts the caret after it; {@code typed} joins the step to the last one if that typed one character just before.
	 */
	public void replace(int start, int end, String value, boolean typed) {
		int room = maxLength - (text.length() - (end - start));
		if (value.length() > room) {
			value = value.substring(0, Math.max(0, room));
		}
		if (start == end && value.isEmpty()) {
			return;
		}
		if (!(typed && typing && caret == start)) {
			push(undo);
			redo.clear();
		}
		text = text.substring(0, start) + value + text.substring(end);
		caret = anchor = start + value.length();
		typing = typed;
		goalColumn = -1;
	}

	/** Undoes the last edit; false if there is none. */
	public boolean undo() {
		return restore(undo, redo);
	}

	/** Redoes the last undone edit; false if there is none. */
	public boolean redo() {
		return restore(redo, undo);
	}

	private boolean restore(Deque<State> from, Deque<State> to) {
		if (from.isEmpty()) {
			return false;
		}
		push(to);
		State state = from.pop();
		text = state.text();
		caret = state.caret();
		anchor = state.anchor();
		typing = false;
		goalColumn = -1;
		return true;
	}

	private void push(Deque<State> stack) {
		stack.push(new State(text, caret, anchor));
		if (stack.size() > UNDO_LIMIT) {
			stack.removeLast();
		}
	}

	private static String clean(String value) {
		StringBuilder out = new StringBuilder(value.length());
		String lines = value.replace("\r\n", "\n").replace('\r', '\n');
		for (int i = 0; i < lines.length(); i++) {
			char c = lines.charAt(i);
			if (c == '\t') {
				out.append("    ");
			} else if (c == '\n' || c >= ' ' && c != 0x7F) {
				out.append(c);
			}
		}
		return out.toString();
	}

	// Moves

	/** Moves the caret to {@code pos}; {@code select} keeps the anchor, so the selection grows or shrinks. */
	public void moveTo(int pos, boolean select) {
		caret = Math.clamp(pos, 0, text.length());
		if (!select) {
			anchor = caret;
		}
		typing = false;
		goalColumn = -1;
	}

	public void left(boolean select, boolean word) {
		if (hasSelection() && !select) {
			moveTo(selectionStart(), false);
		} else {
			moveTo(word ? wordLeft(caret) : caret - 1, select);
		}
	}

	public void right(boolean select, boolean word) {
		if (hasSelection() && !select) {
			moveTo(selectionEnd(), false);
		} else {
			moveTo(word ? wordRight(caret) : caret + 1, select);
		}
	}

	public void up(boolean select) {
		vertical(-1, select);
	}

	public void down(boolean select) {
		vertical(1, select);
	}

	/** Moves {@code lines} lines up (negative) or down, keeping the column; past the first or last line, to its end. */
	public void vertical(int lines, boolean select) {
		int line = lineOf(caret);
		int goal = goalColumn >= 0 ? goalColumn : caret - lineStart(line);
		int target = line + lines;
		if (target < 0) {
			moveTo(0, select);
		} else if (target >= lineCount()) {
			moveTo(text.length(), select);
		} else {
			moveTo(Math.min(lineStart(target) + goal, lineEnd(target)), select);
		}
		goalColumn = goal;
	}

	/** To the start of the line, or with {@code all} of the text. */
	public void home(boolean select, boolean all) {
		moveTo(all ? 0 : lineStart(lineOf(caret)), select);
	}

	/** To the end of the line, or with {@code all} of the text. */
	public void end(boolean select, boolean all) {
		moveTo(all ? text.length() : lineEnd(lineOf(caret)), select);
	}

	public void selectAll() {
		anchor = 0;
		caret = text.length();
		typing = false;
	}

	/** Selects the word at {@code pos}, as a double click does. */
	public void selectWord(int pos) {
		int start = pos;
		while (start > 0 && isWord(text.charAt(start - 1))) {
			start--;
		}
		int end = pos;
		while (end < text.length() && isWord(text.charAt(end))) {
			end++;
		}
		moveTo(start, false);
		moveTo(end, true);
	}

	private int wordLeft(int pos) {
		while (pos > 0 && !isWord(text.charAt(pos - 1))) {
			pos--;
		}
		while (pos > 0 && isWord(text.charAt(pos - 1))) {
			pos--;
		}
		return pos;
	}

	private int wordRight(int pos) {
		while (pos < text.length() && !isWord(text.charAt(pos))) {
			pos++;
		}
		while (pos < text.length() && isWord(text.charAt(pos))) {
			pos++;
		}
		return pos;
	}

	static boolean isWord(char c) {
		return c == '_' || Character.isLetterOrDigit(c);
	}

	// Lines

	public int lineCount() {
		int count = 1;
		for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
			count++;
		}
		return count;
	}

	/** The line (from 0) {@code pos} is on. */
	public int lineOf(int pos) {
		int line = 0;
		for (int i = text.indexOf('\n'); i >= 0 && i < pos; i = text.indexOf('\n', i + 1)) {
			line++;
		}
		return line;
	}

	/** Where line {@code line} (from 0) starts. */
	public int lineStart(int line) {
		int pos = 0;
		for (int i = 0; i < line; i++) {
			pos = text.indexOf('\n', pos) + 1;
		}
		return pos;
	}

	/** Where line {@code line} (from 0) ends, before its {@code \n}. */
	public int lineEnd(int line) {
		int end = text.indexOf('\n', lineStart(line));
		return end < 0 ? text.length() : end;
	}

	public String line(int line) {
		return text.substring(lineStart(line), lineEnd(line));
	}
}
