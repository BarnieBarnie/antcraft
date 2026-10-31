package io.github.barniebarnie.antcraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.barniebarnie.antcraft.network.BotNetworking;
import io.github.barniebarnie.antcraft.script.editor.Completions;
import io.github.barniebarnie.antcraft.script.editor.Page;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * Lists the bots the server says the player may control ({@link BotNetworking.BotList}), a page at a time, each with
 * its script and state and buttons to change its script ({@link PickScript}), see its status ({@link StatusView}), stop
 * it or open its script in the editor; every action is a {@link BotNetworking.BotAction} the server checks as the
 * {@code bot} command it stands for (docs/features/scripts.md, The bots screen).
 */
public class BotsScreen extends Screen {
	private static final int MARGIN = 10;
	private static final int TOP = 34;
	private static final int ROW_HEIGHT = 26;
	private static final int LINE_HEIGHT = 10;
	private static final int BUTTON_HEIGHT = 16;
	private static final int BUTTON_WIDTH = 50;
	private static final int BAR_HEIGHT = 20;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFAAAAAA;

	private BotNetworking.BotList list;
	private int page;

	public BotsScreen(BotNetworking.BotList list) {
		super(Component.translatable("screen.antcraft.bots"));
		this.list = list;
	}

	/** The keybind that asks the server for the list, which opens the screen, and the payloads it gets back. */
	static void register(KeyMapping.Category category) {
		KeyMapping key = KeyMappingHelper.registerKeyMapping(
			new KeyMapping("key.antcraft.bots", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (key.consumeClick()) {
				if (client.player != null && client.gui.screen() == null && ClientPlayNetworking.canSend(BotNetworking.RequestBots.TYPE)) {
					ClientPlayNetworking.send(new BotNetworking.RequestBots());
				}
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(BotNetworking.BotList.TYPE, (list, context) -> receive(list));
		ClientPlayNetworking.registerGlobalReceiver(BotNetworking.BotStatus.TYPE, (status, context) -> receive(status));
	}

	/** Updates the open bots screen, or the one under the script picker or status view, with {@code list}; else opens one. */
	private static void receive(BotNetworking.BotList list) {
		Minecraft client = Minecraft.getInstance();
		Screen open = client.gui.screen();
		BotsScreen screen = open instanceof BotsScreen bots ? bots : open instanceof PickScript pick ? pick.parent
			: open instanceof StatusView view ? view.parent : null;
		if (screen == null) {
			client.gui.setScreen(new BotsScreen(list));
			return;
		}
		screen.list = list;
		if (screen == open) {
			screen.rebuildWidgets();
		}
	}

	/** Shows {@code status}, returning to the bots screen it was asked from. */
	private static void receive(BotNetworking.BotStatus status) {
		Minecraft client = Minecraft.getInstance();
		Screen parent = client.gui.screen() instanceof StatusView view ? view.parent : client.gui.screen();
		client.gui.setScreen(new StatusView(parent instanceof BotsScreen bots ? bots : null, status));
	}

	private static void send(BotNetworking.BotEntry bot, BotNetworking.Action action) {
		ClientPlayNetworking.send(new BotNetworking.BotAction(bot.id(), action, "", ""));
	}

	private Page page() {
		return Page.of(this.list.bots().size(), (this.height - TOP - BAR_HEIGHT - MARGIN) / ROW_HEIGHT, this.page);
	}

	@Override
	protected void init() {
		Page page = this.page();
		this.page = page.index();
		int x = this.width - MARGIN - 4 * (BUTTON_WIDTH + 2);
		for (int i = page.from(); i < page.to(); i++) {
			BotNetworking.BotEntry bot = this.list.bots().get(i);
			int y = TOP + (i - page.from()) * ROW_HEIGHT;
			this.button("screen.antcraft.bots.script", x, y, () -> this.minecraft.gui.setScreen(new PickScript(this, bot)));
			this.button("screen.antcraft.bots.status", x + BUTTON_WIDTH + 2, y, () -> send(bot, BotNetworking.Action.STATUS));
			this.button("screen.antcraft.bots.stop", x + 2 * (BUTTON_WIDTH + 2), y, () -> send(bot, BotNetworking.Action.STOP));
			this.button("screen.antcraft.bots.edit", x + 3 * (BUTTON_WIDTH + 2), y, () -> send(bot, BotNetworking.Action.EDIT));
		}
		int y = this.height - MARGIN - BAR_HEIGHT;
		this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.turn(-1)).bounds(MARGIN, y, 20, BAR_HEIGHT).build()).active = page.index() > 0;
		this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.turn(1)).bounds(MARGIN + 90, y, 20, BAR_HEIGHT).build()).active =
			page.index() < page.count() - 1;
		// The New script button of #476 goes here, left of Refresh.
		this.addRenderableWidget(Button.builder(Component.translatable("screen.antcraft.bots.refresh"),
			b -> ClientPlayNetworking.send(new BotNetworking.RequestBots())).bounds(this.width - MARGIN - 164, y, 80, BAR_HEIGHT).build());
		this.addRenderableWidget(Button.builder(Component.translatable("screen.antcraft.script_editor.close"), b -> this.onClose())
			.bounds(this.width - MARGIN - 80, y, 80, BAR_HEIGHT).build());
	}

	private void button(String key, int x, int y, Runnable action) {
		this.addRenderableWidget(Button.builder(Component.translatable(key), b -> action.run()).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build());
	}

	private void turn(int by) {
		this.page += by;
		this.rebuildWidgets();
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.turn(-(int) Math.signum(scrollY));
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(this.font, Component.translatable("screen.antcraft.bots.title", this.list.bots().size()), MARGIN, 8, WHITE);
		graphics.text(this.font, this.list.message(), MARGIN, 20, GREY);
		Page page = this.page();
		if (this.list.bots().isEmpty()) {
			graphics.text(this.font, Component.translatable("screen.antcraft.bots.empty"), MARGIN, TOP, GREY);
		}
		int textWidth = this.width - 2 * MARGIN - 4 * (BUTTON_WIDTH + 2) - 4;
		for (int i = page.from(); i < page.to(); i++) {
			BotNetworking.BotEntry bot = this.list.bots().get(i);
			int y = TOP + (i - page.from()) * ROW_HEIGHT;
			String script = bot.script().isEmpty() ? "-" : bot.script() + (bot.args().isEmpty() ? "" : " " + bot.args()) + " (" + bot.state() + ")";
			graphics.text(this.font, this.font.plainSubstrByWidth(bot.name() + ": " + script, textWidth), MARGIN, y, WHITE);
			graphics.text(this.font, this.font.plainSubstrByWidth(bot.pos().toShortString() + " " + bot.dimension(), textWidth), MARGIN, y + LINE_HEIGHT, GREY);
		}
		graphics.centeredText(this.font, (page.index() + 1) + "/" + page.count(), MARGIN + 55, this.height - MARGIN - BAR_HEIGHT + 6, WHITE);
	}

	/**
	 * Picks a script for {@code bot} from the library's names, matched as the editor's completion matches them
	 * ({@link Completions#matching}), with optional args; picking one asks the server to run it ({@link BotNetworking.Action#RUN}).
	 */
	static class PickScript extends Screen {
		private final BotsScreen parent;
		private final BotNetworking.BotEntry bot;
		private final List<Button> matches = new ArrayList<>();
		private EditBox search;
		private EditBox args;

		PickScript(BotsScreen parent, BotNetworking.BotEntry bot) {
			super(Component.translatable("screen.antcraft.bots.pick", bot.name()));
			this.parent = parent;
			this.bot = bot;
		}

		@Override
		protected void init() {
			int width = Math.min(300, this.width - 2 * MARGIN);
			this.search = this.addRenderableWidget(new EditBox(this.font, MARGIN, TOP, width, BUTTON_HEIGHT, Component.translatable("screen.antcraft.bots.search")));
			this.search.setHint(Component.translatable("screen.antcraft.bots.search"));
			this.search.setMaxLength(128);
			this.search.setResponder(value -> this.showMatches());
			this.args = this.addRenderableWidget(new EditBox(this.font, MARGIN, TOP + BUTTON_HEIGHT + 4, width, BUTTON_HEIGHT,
				Component.translatable("screen.antcraft.bots.args")));
			this.args.setHint(Component.translatable("screen.antcraft.bots.args"));
			this.args.setMaxLength(BotNetworking.MAX_TEXT);
			this.addRenderableWidget(Button.builder(Component.translatable("screen.antcraft.bots.cancel"), b -> this.onClose())
				.bounds(this.width - MARGIN - 80, this.height - MARGIN - BAR_HEIGHT, 80, BAR_HEIGHT).build());
			this.showMatches();
			this.setInitialFocus(this.search);
		}

		private List<String> found() {
			return Completions.matching(this.search.getValue().trim(), this.parent.list.scripts());
		}

		/** One button per matching script, as many as fit, replacing the last ones without touching the text boxes. */
		private void showMatches() {
			this.matches.forEach(this::removeWidget);
			this.matches.clear();
			int y = TOP + 2 * (BUTTON_HEIGHT + 4);
			int width = Math.min(300, this.width - 2 * MARGIN);
			for (String name : this.found()) {
				if (y + BUTTON_HEIGHT > this.height - MARGIN - BAR_HEIGHT - 4) {
					break;
				}
				this.matches.add(this.addRenderableWidget(Button.builder(Component.literal(name), b -> this.run(name))
					.bounds(MARGIN, y, width, BUTTON_HEIGHT).build()));
				y += BUTTON_HEIGHT + 2;
			}
		}

		private void run(String name) {
			ClientPlayNetworking.send(new BotNetworking.BotAction(this.bot.id(), BotNetworking.Action.RUN, name, this.args.getValue()));
			this.onClose();
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			// Enter runs the first match.
			if (event.isConfirmation() && !this.found().isEmpty()) {
				this.run(this.found().getFirst());
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		public void onClose() {
			this.minecraft.gui.setScreen(this.parent);
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			graphics.text(this.font, this.title, MARGIN, 8, WHITE);
			if (this.found().isEmpty()) {
				graphics.text(this.font, Component.translatable("screen.antcraft.bots.no_match"), MARGIN, TOP + 2 * (BUTTON_HEIGHT + 4), GREY);
			}
		}
	}

	/** A bot's status line, the items it carries with their counts, and its last log lines ({@link BotNetworking.BotStatus}). */
	static class StatusView extends Screen {
		private static final int SLOT = 18;
		private static final int COLUMNS = 9;

		private final BotsScreen parent;
		private final BotNetworking.BotStatus status;

		StatusView(BotsScreen parent, BotNetworking.BotStatus status) {
			super(Component.translatable("screen.antcraft.bots.status"));
			this.parent = parent;
			this.status = status;
		}

		@Override
		protected void init() {
			int y = this.height - MARGIN - BAR_HEIGHT;
			this.addRenderableWidget(Button.builder(Component.translatable("screen.antcraft.bots.refresh"),
				b -> ClientPlayNetworking.send(new BotNetworking.BotAction(this.status.bot(), BotNetworking.Action.STATUS, "", "")))
				.bounds(this.width - MARGIN - 164, y, 80, BAR_HEIGHT).build());
			this.addRenderableWidget(Button.builder(Component.translatable("screen.antcraft.bots.back"), b -> this.onClose())
				.bounds(this.width - MARGIN - 80, y, 80, BAR_HEIGHT).build());
		}

		@Override
		public void onClose() {
			if (this.parent != null) {
				// The list may be out of date after a while here.
				ClientPlayNetworking.send(new BotNetworking.RequestBots());
			}
			this.minecraft.gui.setScreen(this.parent);
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			graphics.text(this.font, this.title, MARGIN, 8, WHITE);
			int y = 22;
			for (FormattedCharSequence line : this.font.split(this.status.status(), this.width - 2 * MARGIN)) {
				graphics.text(this.font, line, MARGIN, y, WHITE);
				y += LINE_HEIGHT;
			}
			y += 4;
			graphics.text(this.font, Component.translatable("screen.antcraft.bots.inventory"), MARGIN, y, WHITE);
			y += LINE_HEIGHT + 2;
			if (this.status.inventory().isEmpty()) {
				graphics.text(this.font, Component.translatable("screen.antcraft.bots.inventory.empty"), MARGIN, y, GREY);
				y += LINE_HEIGHT;
			}
			List<ItemStack> items = this.status.inventory();
			for (int i = 0; i < items.size(); i++) {
				int x = MARGIN + i % COLUMNS * SLOT;
				int top = y + i / COLUMNS * SLOT;
				graphics.item(items.get(i), x, top);
				graphics.itemDecorations(this.font, items.get(i), x, top);
				if (mouseX >= x && mouseX < x + 16 && mouseY >= top && mouseY < top + 16) {
					graphics.setTooltipForNextFrame(this.font, items.get(i), mouseX, mouseY);
				}
			}
			y += (items.size() + COLUMNS - 1) / COLUMNS * SLOT + 4;
			graphics.text(this.font, Component.translatable("screen.antcraft.script_editor.log"), MARGIN, y, WHITE);
			int bottom = this.height - MARGIN - BAR_HEIGHT - 4;
			List<String> log = this.status.log();
			// The last lines that fit.
			int fit = Math.max(0, (bottom - y - LINE_HEIGHT) / LINE_HEIGHT);
			for (String line : log.subList(Math.max(0, log.size() - fit), log.size())) {
				y += LINE_HEIGHT;
				graphics.text(this.font, this.font.plainSubstrByWidth(line, this.width - 2 * MARGIN), MARGIN, y, GREY);
			}
		}
	}
}
