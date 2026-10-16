package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.colony.ColonyActions;
import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Overview of the nearest colony, asking the server for fresh data while open. Rows have buttons to change a bot's
 * role, cancel an order or edit a stock target; the panel on the right orders items, sets stock targets and the radius.
 */
public class ColonyStatusScreen extends Screen {
	private static final int REFRESH_TICKS = 60;
	private static final int LINE_HEIGHT = 14;
	private static final int BUTTON_HEIGHT = 12;
	private static final int PANEL_WIDTH = 150;
	private static final int SUGGESTIONS = 5;
	private static final int TOP = 30;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFAAAAAA;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFFF55;

	/** A button on a row. */
	private record Act(Component label, Runnable press) {
	}

	private record Line(Component text, int color, List<Act> acts) {
		Line(Component text, int color) {
			this(text, color, List.of());
		}
	}

	/** Empty until the first reply arrives. */
	private Optional<ColonyNetworking.StatusReply> reply = Optional.empty();
	private List<Line> lines = List.of();
	private final List<Button> rowButtons = new ArrayList<>();
	private final List<Button> suggestionButtons = new ArrayList<>();
	private String item = "";
	private String count = "";
	private EditBox itemBox;
	private EditBox countBox;
	private int ticks;
	private int scroll;

	public ColonyStatusScreen() {
		super(Component.translatable("screen.antcraft.colony_status"));
	}

	public static void request() {
		ClientPlayNetworking.send(new ColonyNetworking.StatusRequest());
	}

	public void update(ColonyNetworking.StatusReply reply) {
		this.reply = Optional.of(reply);
		this.lines = this.lines(reply);
		this.scroll = Math.min(this.scroll, this.maxScroll());
		this.refreshRows();
	}

	private int panelX() {
		return this.width - PANEL_WIDTH - 10;
	}

	private int visibleLines() {
		return (this.height - TOP - 10) / LINE_HEIGHT;
	}

	private int maxScroll() {
		return Math.max(0, this.lines.size() - this.visibleLines());
	}

	@Override
	protected void init() {
		int x = this.panelX();
		this.itemBox = new EditBox(this.font, x, TOP, PANEL_WIDTH, 16, Component.translatable("screen.antcraft.colony_status.item"));
		this.itemBox.setHint(Component.translatable("screen.antcraft.colony_status.item"));
		this.itemBox.setMaxLength(128);
		this.itemBox.setValue(this.item);
		this.itemBox.setResponder(value -> {
			this.item = value;
			this.refreshSuggestions();
		});
		this.addRenderableWidget(this.itemBox);

		int y = TOP + 20 + SUGGESTIONS * BUTTON_HEIGHT + 4;
		this.countBox = new EditBox(this.font, x, y, 50, 16, Component.translatable("screen.antcraft.colony_status.count"));
		this.countBox.setHint(Component.translatable("screen.antcraft.colony_status.count"));
		this.countBox.setMaxLength(4);
		this.countBox.setValue(this.count);
		this.countBox.setResponder(value -> this.count = value);
		this.addRenderableWidget(this.countBox);

		int half = PANEL_WIDTH / 2 - 1;
		y += 20;
		this.panelButton("order", x, y, half, () -> this.edit(ColonyActions.Edit.ORDER, this.item.trim(), this.count()));
		this.panelButton("cancel_order", x + half + 2, y, half, () -> this.edit(ColonyActions.Edit.CANCEL, this.item.trim(), 0));
		y += BUTTON_HEIGHT + 2;
		this.panelButton("set_stock", x, y, half, () -> this.edit(ColonyActions.Edit.SET_STOCK, this.item.trim(), this.count()));
		this.panelButton("remove_stock", x + half + 2, y, half, () -> this.edit(ColonyActions.Edit.REMOVE_STOCK, this.item.trim(), 0));
		y += BUTTON_HEIGHT + 2;
		this.panelButton("set_radius", x, y, half, () -> this.edit(ColonyActions.Edit.RADIUS, "", this.count()));

		this.refreshSuggestions();
		this.refreshRows();
	}

	private void panelButton(String key, int x, int y, int width, Runnable press) {
		this.addRenderableWidget(Button.builder(Component.translatable("screen.antcraft.colony_status." + key), b -> press.run())
			.bounds(x, y, width, BUTTON_HEIGHT).build());
	}

	private int count() {
		try {
			return Integer.parseInt(this.count);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Items whose id contains what was typed; tags are typed out in full. */
	private void refreshSuggestions() {
		this.suggestionButtons.forEach(this::removeWidget);
		this.suggestionButtons.clear();
		String typed = this.item.trim().toLowerCase(Locale.ROOT);
		if (typed.isEmpty() || typed.startsWith("#")) {
			return;
		}
		// ponytail: linear scan of the item registry per keystroke; fine for a few thousand items.
		List<String> matches = BuiltInRegistries.ITEM.keySet().stream().map(Identifier::toString)
			.filter(id -> id.contains(typed) && !id.equals(typed)).sorted().limit(SUGGESTIONS).toList();
		int y = TOP + 20;
		for (String id : matches) {
			Button button = Button.builder(Component.literal(id), b -> this.itemBox.setValue(id))
				.bounds(this.panelX(), y, PANEL_WIDTH, BUTTON_HEIGHT).build();
			this.suggestionButtons.add(this.addRenderableWidget(button));
			y += BUTTON_HEIGHT;
		}
	}

	/** Places the buttons of the rows in view. */
	private void refreshRows() {
		this.rowButtons.forEach(this::removeWidget);
		this.rowButtons.clear();
		int right = this.panelX() - 10;
		for (int i = this.scroll; i < this.lines.size() && i - this.scroll < this.visibleLines(); i++) {
			int x = right;
			int y = TOP + (i - this.scroll) * LINE_HEIGHT;
			for (Act act : this.lines.get(i).acts().reversed()) {
				int width = this.font.width(act.label()) + 8;
				x -= width + 2;
				this.rowButtons.add(this.addRenderableWidget(Button.builder(act.label(), b -> act.press().run()).bounds(x, y, width, BUTTON_HEIGHT).build()));
			}
		}
	}

	private void edit(ColonyActions.Edit kind, String target, int count) {
		this.reply.flatMap(ColonyNetworking.StatusReply::status)
			.ifPresent(status -> ClientPlayNetworking.send(new ColonyNetworking.EditRequest(status.core(), kind, target, count)));
	}

	private void role(UUID bot, Optional<BotRole> role) {
		this.reply.flatMap(ColonyNetworking.StatusReply::status)
			.ifPresent(status -> ClientPlayNetworking.send(new ColonyNetworking.RoleRequest(status.core(), bot, role)));
	}

	@Override
	public void tick() {
		if (++this.ticks % REFRESH_TICKS == 0) {
			request();
		}
	}

	@Override
	public boolean isPauseScreen() {
		// The data comes from the server, which must keep ticking for it to be live.
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.scroll = Math.clamp(this.scroll - (int) Math.signum(scrollY), 0, this.maxScroll());
		this.refreshRows();
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(this.font, this.title, 20, 10, WHITE);
		if (this.reply.isEmpty()) {
			graphics.text(this.font, Component.translatable("screen.antcraft.colony_status.loading"), 20, TOP, GREY);
			return;
		}
		for (int i = this.scroll; i < this.lines.size() && i - this.scroll < this.visibleLines(); i++) {
			Line line = this.lines.get(i);
			graphics.text(this.font, line.text(), 20, TOP + (i - this.scroll) * LINE_HEIGHT + 2, line.color());
		}
	}

	private List<Line> lines(ColonyNetworking.StatusReply reply) {
		List<Line> lines = new ArrayList<>();
		if (reply.status().isEmpty()) {
			lines.add(new Line(Component.translatable("commands.antcraft.colony.none", AntCraftCommands.COLONY_RANGE), RED));
			return lines;
		}
		ColonyStatus status = reply.status().get();
		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.colony", status.core().toShortString(), status.radius()), WHITE));

		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.bots", status.members(), status.idle()), YELLOW));
		status.bots().forEach((id, bot) -> {
			List<Act> acts = new ArrayList<>();
			BotRole next = BotRole.values()[(bot.role().ordinal() + 1) % BotRole.values().length];
			acts.add(new Act(Component.literal(">"), () -> this.role(id, Optional.of(next))));
			if (bot.setByPlayer()) {
				acts.add(new Act(Component.translatable("screen.antcraft.colony_status.give_back"), () -> this.role(id, Optional.empty())));
			}
			String key = bot.setByPlayer() ? "screen.antcraft.colony_status.bot_by_player" : "screen.antcraft.colony_status.bot";
			lines.add(new Line(Component.translatable(key, bot.name(), bot.role().id()), bot.idle() ? GREY : WHITE, acts));
		});
		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.jobs", status.jobs(), status.claimed()), YELLOW));

		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.stock"), YELLOW));
		for (ColonyStatus.Stock stock : status.stock()) {
			List<Act> acts = List.of(
				new Act(Component.translatable("screen.antcraft.colony_status.edit"), () -> {
					this.itemBox.setValue(stock.name());
					this.countBox.setValue(Integer.toString(stock.want()));
				}),
				new Act(Component.literal("x"), () -> this.edit(ColonyActions.Edit.REMOVE_STOCK, stock.name(), 0)));
			lines.add(new Line(Component.literal("  " + stock.have() + "/" + stock.want() + " " + stock.name()), stock.isShort() ? RED : GREY, acts));
		}

		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.orders"), YELLOW));
		for (ColonyStatus.Order order : status.orders()) {
			MutableComponent text = Component.literal("  " + order.count() + " ").append(name(order.item())).append(" (");
			List<String> by = new ArrayList<>();
			if (order.byPlayer() > 0) {
				by.add(order.byPlayer() + " player");
			}
			order.forColony().forEach((purpose, count) -> by.add(count + " " + purpose.getSerializedName()));
			text.append(String.join(", ", by) + ")");
			// Only the player's part can be cancelled; the colony would order its own part again.
			String id = BuiltInRegistries.ITEM.getKey(order.item()).toString();
			List<Act> acts = order.byPlayer() > 0 ? List.of(new Act(Component.literal("x"), () -> this.edit(ColonyActions.Edit.CANCEL, id, 0))) : List.of();
			lines.add(new Line(text, WHITE, acts));
			if (!order.toGather().isEmpty()) {
				lines.add(new Line(Component.translatable("screen.antcraft.colony_status.gather", items(order.toGather())), GREY));
			}
			if (!order.missing().isEmpty()) {
				lines.add(new Line(Component.translatable("screen.antcraft.colony_status.missing", items(order.missing())), RED));
			}
		}

		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.builds"), YELLOW));
		for (ColonyStatus.Build build : status.builds()) {
			String key = build.mineshaft() ? "screen.antcraft.colony_status.build_mineshaft" : "screen.antcraft.colony_status.build";
			lines.add(new Line(Component.translatable(key, build.target().toShortString(), build.left(), build.steps()), WHITE));
		}
		if (!status.blocked().isEmpty()) {
			String blocked = String.join(", ", status.blocked().stream().map(pos -> pos.toShortString()).toList());
			lines.add(new Line(Component.translatable("commands.antcraft.colony.blocked", blocked), RED));
		}
		return lines;
	}

	private static Component name(Item item) {
		return new ItemStack(item).getHoverName();
	}

	private static Component items(Map<Item, Integer> items) {
		MutableComponent text = Component.empty();
		items.forEach((item, count) -> {
			if (!text.getSiblings().isEmpty()) {
				text.append(", ");
			}
			text.append(count + " ").append(name(item));
		});
		return text;
	}
}
