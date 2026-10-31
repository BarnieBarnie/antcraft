package io.github.barniebarnie.antcraft.script.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The pages of the bots screen's list (docs/features/scripts.md, The bots screen). */
class PageTest {
	@Test
	void itemsAreSplitIntoPagesAndAPageOutOfRangeIsTheNearestOne() {
		assertEquals(new Page(0, 3, 0, 4), Page.of(10, 4, 0));
		assertEquals(new Page(1, 3, 4, 8), Page.of(10, 4, 1));
		assertEquals(new Page(2, 3, 8, 10), Page.of(10, 4, 2));
		assertEquals(new Page(2, 3, 8, 10), Page.of(10, 4, 7));
		assertEquals(new Page(0, 3, 0, 4), Page.of(10, 4, -1));
		assertEquals(new Page(0, 1, 0, 4), Page.of(4, 4, 1));
	}

	@Test
	void noItemsIsOneEmptyPageAndAtLeastOneItemFitsAPage() {
		assertEquals(new Page(0, 1, 0, 0), Page.of(0, 4, 3));
		assertEquals(new Page(1, 3, 1, 2), Page.of(3, 0, 1));
	}
}
