package io.github.barniebarnie.antcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import io.github.barniebarnie.antcraft.script.editor.CodeText;
import io.github.barniebarnie.antcraft.script.editor.Highlighter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractTextAreaWidget;
import net.minecraft.client.gui.components.TextCursorUtils;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * The script editor's text box (docs/features/scripts.md, Editing in game): lines do not wrap but scroll sideways, and
 * text is coloured by {@link Highlighter}. The editing itself is {@link CodeText}; this class draws it and maps keys
 * and the mouse to it.
 */
public class CodeEditBox extends AbstractTextAreaWidget {
	private static final int LINE_HEIGHT = 9;
	private static final int CARET_COLOR = 0xFFD0D0D0;
	private static final int SELECTION_COLOR = 0x803060C0;

	private final Font font;
	private final CodeText text;
	private final Set<String> natives;
	/** The text {@link #spans} and {@link #lineStarts} are for: they are worked out once per edit, not per frame. */
	private String coloured;
	private List<Highlighter.Span> spans = List.of();
	private int[] lineStarts = {0};
	private int scrollX;
	private long focusedTime = Util.getMillis();

	public CodeEditBox(Font font, int x, int y, int width, int height, Component title, int maxLength, Set<String> natives) {
		super(x, y, width, height, title, AbstractScrollArea.defaultSettings(LINE_HEIGHT / 2), true, true);
		this.font = font;
		this.text = new CodeText(maxLength);
		this.natives = natives;
	}

	public String getValue() {
		return this.text.text();
	}

	public void setValue(String value) {
		this.text.setText(value);
		this.scrollX = 0;
		this.setScrollAmount(0);
	}

	private void refresh() {
		String value = this.text.text();
		if (value == this.coloured) {
			return;
		}
		this.coloured = value;
		this.spans = Highlighter.spans(value, this.natives);
		List<Integer> starts = new ArrayList<>();
		starts.add(0);
		for (int i = value.indexOf('\n'); i >= 0; i = value.indexOf('\n', i + 1)) {
			starts.add(i + 1);
		}
		this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
	}

	private int lineEnd(int line) {
		return line + 1 < this.lineStarts.length ? this.lineStarts[line + 1] - 1 : this.coloured.length();
	}

	private int lineOf(int pos) {
		int found = java.util.Arrays.binarySearch(this.lineStarts, pos);
		return found >= 0 ? found : -found - 2;
	}

	/** Width of the text area left of the scroll bar. */
	private int textWidth() {
		return this.width - this.totalInnerPadding() - SCROLLBAR_WIDTH;
	}

	@Override
	protected int getInnerHeight() {
		this.refresh();
		return this.lineStarts.length * LINE_HEIGHT;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		this.refresh();
		String value = this.coloured;
		int left = this.getInnerLeft() - this.scrollX;
		int top = this.getInnerTop();
		int first = Math.max(0, (int) ((this.scrollAmount() - this.innerPadding()) / LINE_HEIGHT));
		int last = Math.min(this.lineStarts.length - 1, (int) ((this.scrollAmount() + this.height) / LINE_HEIGHT));
		int selStart = this.text.selectionStart();
		int selEnd = this.text.selectionEnd();
		int span = firstSpanEndingAfter(this.lineStarts[first]);
		for (int line = first; line <= last; line++) {
			int start = this.lineStarts[line];
			int end = this.lineEnd(line);
			int y = top + line * LINE_HEIGHT;
			if (selStart < selEnd && selStart <= end && selEnd > start) {
				int x1 = left + this.font.width(value.substring(start, Math.max(start, selStart)));
				int x2 = left + this.font.width(value.substring(start, Math.min(end, selEnd))) + (selEnd > end ? 3 : 0);
				graphics.fill(x1, y, x2, y + LINE_HEIGHT, SELECTION_COLOR);
			}
			int x = left;
			int pos = start;
			while (pos < end) {
				while (span < this.spans.size() && this.spans.get(span).end() <= pos) {
					span++;
				}
				Highlighter.Span next = span < this.spans.size() ? this.spans.get(span) : null;
				int to;
				int color;
				if (next == null || next.start() > pos) {
					to = next == null ? end : Math.min(end, next.start());
					color = Highlighter.Style.PLAIN.color();
				} else {
					to = Math.min(end, next.end());
					color = next.style().color();
				}
				String piece = value.substring(pos, to);
				graphics.text(this.font, piece, x, y, color, false);
				x += this.font.width(piece);
				pos = to;
			}
		}
		if (this.isFocused() && TextCursorUtils.isCursorVisible(Util.getMillis() - this.focusedTime)) {
			int line = this.lineOf(this.text.caret());
			int x = left + this.font.width(value.substring(this.lineStarts[line], this.text.caret()));
			int y = top + line * LINE_HEIGHT;
			graphics.fill(x, y - 1, x + 1, y + LINE_HEIGHT, CARET_COLOR);
		}
		if (this.isHovered()) {
			graphics.requestCursor(CursorTypes.IBEAM);
		}
	}

	private int firstSpanEndingAfter(int pos) {
		int low = 0;
		int high = this.spans.size();
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (this.spans.get(mid).end() <= pos) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		return low;
	}

	/** Scrolls so the caret is in view, and restarts its blink so it shows while typing. */
	private void showCaret() {
		this.refresh();
		this.focusedTime = Util.getMillis();
		int line = this.lineOf(this.text.caret());
		int y = line * LINE_HEIGHT;
		int visible = this.height - this.totalInnerPadding();
		if (y < this.scrollAmount()) {
			this.setScrollAmount(y);
		} else if (y + LINE_HEIGHT > this.scrollAmount() + visible) {
			this.setScrollAmount(y + LINE_HEIGHT - visible);
		}
		int x = this.font.width(this.coloured.substring(this.lineStarts[line], this.text.caret()));
		if (x < this.scrollX) {
			this.scrollX = Math.max(0, x - this.textWidth() / 4);
		} else if (x > this.scrollX + this.textWidth() - 4) {
			this.scrollX = x - this.textWidth() + 4;
		}
	}

	/** The text position under the screen point. */
	private int positionAt(double mouseX, double mouseY) {
		this.refresh();
		int line = Math.clamp((int) Math.floor((mouseY - this.getInnerTop() + this.scrollAmount()) / LINE_HEIGHT), 0,
			this.lineStarts.length - 1);
		String lineText = this.coloured.substring(this.lineStarts[line], this.lineEnd(line));
		int x = (int) (mouseX - this.getInnerLeft() + this.scrollX);
		return this.lineStarts[line] + (x <= 0 ? 0 : this.font.plainSubstrByWidth(lineText, x).length());
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (doubleClick) {
			this.text.selectWord(this.positionAt(event.x(), event.y()));
		} else {
			this.text.moveTo(this.positionAt(event.x(), event.y()), event.hasShiftDown());
		}
		this.showCaret();
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		this.text.moveTo(this.positionAt(event.x(), event.y()), true);
		this.showCaret();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollX != 0) {
			this.scrollX = Math.max(0, this.scrollX - (int) (scrollX * 3 * this.font.width(" ")));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!this.edit(event)) {
			return false;
		}
		this.showCaret();
		return true;
	}

	/** Applies a key to the text; false for a key the editor leaves to the screen, such as Escape and Tab. */
	private boolean edit(KeyEvent event) {
		boolean shift = event.hasShiftDown();
		boolean ctrl = event.hasControlDownWithQuirk();
		Minecraft client = Minecraft.getInstance();
		if (event.isSelectAll()) {
			this.text.selectAll();
		} else if (event.isCopy() || event.isCut()) {
			if (this.text.hasSelection()) {
				client.keyboardHandler.setClipboard(this.text.selected());
				if (event.isCut()) {
					this.text.deleteBack(false);
				}
			}
		} else if (event.isPaste()) {
			this.text.insert(client.keyboardHandler.getClipboard());
		} else if (ctrl && event.shortcutKey() == 'z') {
			if (shift) {
				this.text.redo();
			} else {
				this.text.undo();
			}
		} else if (ctrl && event.shortcutKey() == 'y') {
			this.text.redo();
		} else {
			int page = Math.max(1, (this.height - this.totalInnerPadding()) / LINE_HEIGHT - 1);
			switch (event.key()) {
				case InputConstants.KEY_LEFT -> this.text.left(shift, ctrl);
				case InputConstants.KEY_RIGHT -> this.text.right(shift, ctrl);
				case InputConstants.KEY_UP -> this.text.up(shift);
				case InputConstants.KEY_DOWN -> this.text.down(shift);
				case InputConstants.KEY_PAGEUP -> this.text.vertical(-page, shift);
				case InputConstants.KEY_PAGEDOWN -> this.text.vertical(page, shift);
				case InputConstants.KEY_HOME -> this.text.home(shift, ctrl);
				case InputConstants.KEY_END -> this.text.end(shift, ctrl);
				case InputConstants.KEY_BACKSPACE -> this.text.deleteBack(ctrl);
				case InputConstants.KEY_DELETE -> this.text.deleteForward(ctrl);
				case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> this.text.insert("\n");
				default -> {
					return false;
				}
			}
		}
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (!this.visible || !event.isAllowedChatCharacter()) {
			return false;
		}
		this.text.insert(event.codepointAsString());
		this.showCaret();
		return true;
	}

	@Override
	public boolean capturesInput() {
		return this.visible && this.isFocused();
	}

	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
		this.focusedTime = Util.getMillis();
	}

	@Override
	public void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.translatable("gui.narrate.editBox", this.getMessage(), this.getValue()));
	}
}
