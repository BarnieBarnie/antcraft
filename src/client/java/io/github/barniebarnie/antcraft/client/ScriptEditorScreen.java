package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.network.ScriptNetworking;
import io.github.barniebarnie.antcraft.script.ScriptEdits;
import io.github.barniebarnie.antcraft.script.editor.ScriptNames;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Shows a script the server sent ({@link ScriptNetworking.OpenScriptEditor}) in a {@link CodeEditBox}, with the
 * last log lines of the bot it was opened from, and for operators a Save button that sends it back
 * ({@link ScriptNetworking.SaveScript}); the server's answer, such as a compile error with its line, shows above the
 * buttons (docs/features/scripts.md, Editing in game).
 */
public class ScriptEditorScreen extends Screen {
	private static final int MARGIN = 10;
	private static final int TOP = 22;
	private static final int LINE_HEIGHT = 10;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_WIDTH = 80;
	private static final int LOG_LINES = 6;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFAAAAAA;
	private static final int RED = 0xFFFF5555;
	private static final int GREEN = 0xFF55FF55;

	private final ScriptNetworking.OpenScriptEditor script;
	private String text;
	private CodeEditBox editor;
	private Component message = Component.empty();
	private int messageColor = GREY;

	public ScriptEditorScreen(ScriptNetworking.OpenScriptEditor script) {
		super(Component.translatable(script.mayEdit() ? "screen.antcraft.script_editor" : "screen.antcraft.script_editor.read_only", script.name()));
		this.script = script;
		this.text = script.source();
	}

	static void open(ScriptNetworking.OpenScriptEditor script) {
		Minecraft.getInstance().gui.setScreen(new ScriptEditorScreen(script));
	}

	/** The server's answer to a save: shown in the editor if it is open, else in chat. */
	static void saved(ScriptNetworking.ScriptSaved reply) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui.screen() instanceof ScriptEditorScreen screen) {
			screen.message = reply.message();
			screen.messageColor = reply.saved() ? GREEN : RED;
			if (reply.line() > 0) {
				screen.editor.markLine(reply.line());
				screen.setFocused(screen.editor);
			}
		} else if (client.player != null) {
			client.player.sendSystemMessage(reply.message());
		}
	}

	private List<String> log() {
		List<String> log = this.script.log();
		return log.subList(Math.max(0, log.size() - LOG_LINES), log.size());
	}

	@Override
	protected void init() {
		int logHeight = this.log().isEmpty() ? 0 : (this.log().size() + 1) * LINE_HEIGHT + 4;
		int bottom = this.height - MARGIN - BUTTON_HEIGHT - LINE_HEIGHT - 4 - logHeight;
		if (this.editor != null) {
			this.text = this.editor.getValue(); // init runs again when the window is resized
		}
		this.editor = new CodeEditBox(this.font, MARGIN, TOP, this.width - 2 * MARGIN, Math.max(LINE_HEIGHT * 3, bottom - TOP), this.title,
			ScriptEdits.MAX_SOURCE_BYTES, ScriptNames.natives());
		this.editor.setValue(this.text);
		this.addRenderableWidget(this.editor);
		int y = this.height - MARGIN - BUTTON_HEIGHT;
		int x = this.width - MARGIN - BUTTON_WIDTH;
		this.addRenderableWidget(Button.builder(Component.translatable("screen.antcraft.script_editor.close"), b -> this.onClose())
			.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		if (this.script.mayEdit()) {
			this.addRenderableWidget(Button.builder(Component.translatable("screen.antcraft.script_editor.save"), b -> this.save())
				.bounds(x - BUTTON_WIDTH - 4, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		}
		this.setInitialFocus(this.editor);
	}

	private void save() {
		this.message = Component.translatable("screen.antcraft.script_editor.saving");
		this.messageColor = GREY;
		ClientPlayNetworking.send(new ScriptNetworking.SaveScript(this.script.name(), this.editor.getValue()));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(this.font, this.title, MARGIN, 8, WHITE);
		int y = this.editor.getY() + this.editor.getHeight() + 4;
		if (!this.log().isEmpty()) {
			graphics.text(this.font, Component.translatable("screen.antcraft.script_editor.log"), MARGIN, y, WHITE);
			for (String line : this.log()) {
				y += LINE_HEIGHT;
				graphics.text(this.font, this.font.plainSubstrByWidth(line, this.width - 2 * MARGIN), MARGIN, y, GREY);
			}
			y += LINE_HEIGHT + 4;
		}
		graphics.text(this.font, this.message, MARGIN, y, this.messageColor);
	}
}
