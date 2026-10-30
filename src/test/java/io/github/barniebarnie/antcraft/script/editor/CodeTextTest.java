package io.github.barniebarnie.antcraft.script.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The editor's text, caret and selection (docs/features/scripts.md, Editing in game). */
class CodeTextTest {
	/** A text with the caret where {@code |} is. */
	private static CodeText at(String marked) {
		CodeText text = new CodeText(1000);
		text.setText(marked.replace("|", ""));
		text.moveTo(marked.indexOf('|'), false);
		return text;
	}

	private static String marked(CodeText text) {
		return text.text().substring(0, text.caret()) + "|" + text.text().substring(text.caret());
	}

	@Test
	void typingInsertsAtTheCaretAndReplacesTheSelection() {
		CodeText text = at("ab|cd");
		text.insert("X");
		assertEquals("abX|cd", marked(text));
		text.moveTo(0, true);
		assertEquals("abX", text.selected());
		text.insert("Y");
		assertEquals("Y|cd", marked(text));
		assertFalse(text.hasSelection());
	}

	@Test
	void pastedLineEndsAndTabsAreNormalised() {
		CodeText text = at("|");
		text.insert("a\r\n\tb\rc\u0007");
		assertEquals("a\n    b\nc|", marked(text));
	}

	@Test
	void theLengthLimitCutsAnInsert() {
		CodeText text = new CodeText(5);
		text.setText("abc");
		text.moveTo(3, false);
		text.insert("defg");
		assertEquals("abcde", text.text());
	}

	@Test
	void backspaceAndDeleteRemoveACharacterOrTheSelectionOrAWord() {
		CodeText text = at("ab|cd");
		text.deleteBack(false);
		assertEquals("a|cd", marked(text));
		text.deleteForward(false);
		assertEquals("a|d", marked(text));
		text = at("foo bar|");
		text.deleteBack(true);
		assertEquals("foo |", marked(text));
		text = at("|foo bar");
		text.deleteForward(true);
		assertEquals("| bar", marked(text));
		text = at("abcd|");
		text.moveTo(1, true);
		text.deleteBack(false);
		assertEquals("a|", marked(text));
	}

	@Test
	void leftAndRightMoveOrCollapseTheSelectionAndJumpWords() {
		CodeText text = at("ab|cd");
		text.left(false, false);
		assertEquals("a|bcd", marked(text));
		text.right(true, false);
		text.right(true, false);
		assertEquals("bc", text.selected());
		text.left(false, false);
		assertEquals("a|bcd", marked(text));
		text = at("|foo.bar baz");
		text.right(false, true);
		assertEquals("foo|.bar baz", marked(text));
		text = at("foo bar|");
		text.left(false, true);
		assertEquals("foo |bar", marked(text));
	}

	@Test
	void upAndDownKeepTheColumn() {
		CodeText text = at("abc|def\nx\nabcdefgh");
		text.down(false);
		assertEquals("abcdef\nx|\nabcdefgh", marked(text));
		text.down(false);
		assertEquals("abcdef\nx\nabc|defgh", marked(text));
		text.up(false);
		text.up(false);
		assertEquals("abc|def\nx\nabcdefgh", marked(text));
		text.up(false);
		assertEquals("|abcdef\nx\nabcdefgh", marked(text));
	}

	@Test
	void homeAndEndGoToTheLineOrTheText() {
		CodeText text = at("ab\n  cd|ef\ngh");
		text.home(false, false);
		assertEquals("ab\n|  cdef\ngh", marked(text));
		text.end(true, false);
		assertEquals("  cdef", text.selected());
		text.end(false, true);
		assertEquals(text.text().length(), text.caret());
		text.home(false, true);
		assertEquals(0, text.caret());
	}

	@Test
	void selectAllAndSelectWord() {
		CodeText text = at("one two|");
		text.selectAll();
		assertEquals("one two", text.selected());
		text.selectWord(5);
		assertEquals("two", text.selected());
	}

	@Test
	void linesAndColumns() {
		CodeText text = at("ab\ncd|e\n");
		assertEquals(3, text.lineCount());
		assertEquals(1, text.lineOf(text.caret()));
		assertEquals(3, text.lineStart(1));
		assertEquals(6, text.lineEnd(1));
		assertEquals("cde", text.line(1));
		assertEquals("", text.line(2));
	}

	@Test
	void undoAndRedoTypingAWordAtOnce() {
		CodeText text = at("x|");
		text.insert("a");
		text.insert("b");
		text.insert("c");
		text.left(false, false);
		text.deleteBack(false);
		assertEquals("xa|c", marked(text));
		assertTrue(text.undo());
		assertEquals("xab|c", marked(text));
		assertTrue(text.undo());
		assertEquals("x|", marked(text));
		assertFalse(text.undo());
		assertTrue(text.redo());
		assertEquals("xab|c", marked(text));
		text.insert("d");
		assertFalse(text.redo());
	}

	/** A text with the selection from {@code [} to the caret at {@code |}. */
	private static CodeText selected(String marked) {
		CodeText text = at(marked.replace("[", ""));
		int anchor = marked.indexOf('[');
		int caret = text.caret();
		text.moveTo(anchor < caret ? anchor : anchor - 1, false);
		text.moveTo(caret, true);
		return text;
	}

	@Test
	void tabIndentsToTheNextMultipleOfFour() {
		CodeText text = at("|x");
		text.indent();
		assertEquals("    |x", marked(text));
		text = at("ab|x");
		text.indent();
		assertEquals("ab  |x", marked(text));
	}

	@Test
	void tabAndShiftTabIndentAndDedentEverySelectedLine() {
		CodeText text = selected("i[f x:\n    pa|ss\ny");
		text.indent();
		assertEquals("    if x:\n        pass\ny", text.text());
		assertEquals("    if x:\n        pass", text.selected());
		text.dedent();
		text.dedent();
		text.dedent();
		assertEquals("if x:\npass\ny", text.text());
	}

	@Test
	void shiftTabOnOneLineRemovesUpToFourSpaces() {
		CodeText text = at("      x|");
		text.dedent();
		assertEquals("  x|", marked(text));
		text.dedent();
		assertEquals("x|", marked(text));
		text.dedent();
		assertEquals("x|", marked(text));
	}

	@Test
	void enterKeepsTheIndentationAndAddsALevelAfterAColon() {
		CodeText text = at("if x:|");
		text.newline();
		assertEquals("if x:\n    |", marked(text));
		text.insert("pass");
		text.newline();
		assertEquals("if x:\n    pass\n    |", marked(text));
		text = at("    while True:  |");
		text.newline();
		assertEquals("    while True:  \n        |", marked(text));
		text = at("x = 1|");
		text.newline();
		assertEquals("x = 1\n|", marked(text));
	}

	@Test
	void backspaceInTheIndentationRemovesBackToTheLastMultipleOfFour() {
		CodeText text = at("x\n      |y");
		text.deleteBack(false);
		assertEquals("x\n    |y", marked(text));
		text.deleteBack(false);
		assertEquals("x\n|y", marked(text));
		text = at("  a |b");
		text.deleteBack(false);
		assertEquals("  a|b", marked(text));
	}
}
