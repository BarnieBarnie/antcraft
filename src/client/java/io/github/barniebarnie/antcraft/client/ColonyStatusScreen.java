package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyActions;
import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Overview of the nearest colony, asking the server for fresh data while open: each bot with the job it holds, and
 * more. Rows have buttons to cancel an order or edit a stock target; the panel on the right orders items, sets stock
 * targets and the radius.
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
	private static final String KEY = "screen.antcraft.colony_status";
	/** Followed by an {@link Colony.OrderPurpose#getSerializedName()}. */
	private static final String PURPOSE_KEY = "screen.antcraft.colony_status.purpose.";

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
		super(Component.translatable(KEY));
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
		this.panelButton("screen.antcraft.colony_status.order", x, y, half, () -> this.edit(ColonyActions.Edit.ORDER, this.item.trim(), this.count()));
		this.panelButton("screen.antcraft.colony_status.cancel_order", x + half + 2, y, half, () -> this.edit(ColonyActions.Edit.CANCEL, this.item.trim(), 0));
		y += BUTTON_HEIGHT + 2;
		this.panelButton("screen.antcraft.colony_status.set_stock", x, y, half, () -> this.edit(ColonyActions.Edit.SET_STOCK, this.item.trim(), this.count()));
		this.panelButton("screen.antcraft.colony_status.remove_stock", x + half + 2, y, half, () -> this.edit(ColonyActions.Edit.REMOVE_STOCK, this.item.trim(), 0));
		y += BUTTON_HEIGHT + 2;
		this.panelButton("screen.antcraft.colony_status.set_radius", x, y, half, () -> this.edit(ColonyActions.Edit.RADIUS, "", this.count()));

		this.refreshSuggestions();
		this.refreshRows();
	}

	private void panelButton(String key, int x, int y, int width, Runnable press) {
		this.addRenderableWidget(Button.builder(Component.translatable(key), b -> press.run())
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
		status.bots().values().forEach(bot -> lines.add(new Line(
			Component.translatable("screen.antcraft.colony_status.bot", bot.name(), JobType.describe(bot.job())), bot.idle() ? GREY : WHITE)));
		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.jobs", status.jobs(), status.claimed()), YELLOW));

		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.stock"), YELLOW));
		for (ColonyStatus.Stock stock : status.stock()) {
			List<Act> acts = List.of(
				new Act(Component.translatable("screen.antcraft.colony_status.edit"), () -> {
					this.itemBox.setValue(stock.name());
					this.countBox.setValue(Integer.toString(stock.want()));
				}),
				new Act(Component.translatable("screen.antcraft.colony_status.remove"), () -> this.edit(ColonyActions.Edit.REMOVE_STOCK, stock.name(), 0)));
			Component text = Component.translatable("screen.antcraft.colony_status.stock_line", stock.have(), stock.want(), stock.name());
			lines.add(new Line(text, stock.isShort() ? RED : GREY, acts));
		}

		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.orders"), YELLOW));
		for (ColonyStatus.Order order : status.orders()) {
			List<Component> by = new ArrayList<>();
			if (order.byPlayer() > 0) {
				by.add(Component.translatable("screen.antcraft.colony_status.order_by_player", order.byPlayer()));
			}
			order.forColony().forEach((purpose, count) ->
				by.add(Component.translatable("screen.antcraft.colony_status.order_by", count, Component.translatable(PURPOSE_KEY + purpose.getSerializedName()))));
			Component text = Component.translatable("screen.antcraft.colony_status.order_line", order.count(), name(order.item()), list(by));
			// Only the player's part can be cancelled; the colony would order its own part again.
			String id = BuiltInRegistries.ITEM.getKey(order.item()).toString();
			List<Act> acts = order.byPlayer() > 0 ? List.of(new Act(Component.translatable("screen.antcraft.colony_status.remove"), () -> this.edit(ColonyActions.Edit.CANCEL, id, 0))) : List.of();
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
			String key = build.mineshaft() ? "screen.antcraft.colony_status.build_mineshaft"
				: build.terraform() ? "screen.antcraft.colony_status.build_terraform" : "screen.antcraft.colony_status.build";
			lines.add(new Line(Component.translatable(key, build.target().toShortString(), build.left(), build.steps()), WHITE));
			// A plan of one batch says it all on its own line.
			for (int i = 0; build.batches().size() > 1 && i < build.batches().size(); i++) {
				ColonyStatus.Batch batch = build.batches().get(i);
				if (batch.blocked()) {
					lines.add(new Line(Component.translatable("screen.antcraft.colony_status.batch_blocked", i + 1, batch.left(), batch.steps()), RED));
				} else {
					lines.add(new Line(Component.translatable("screen.antcraft.colony_status.batch", i + 1, batch.left(), batch.steps()), GREY));
				}
			}
		}
		if (!status.blocked().isEmpty()) {
			Component blocked = list(status.blocked().stream().<Component>map(pos -> Component.literal(pos.toShortString())).toList());
			lines.add(new Line(Component.translatable("commands.antcraft.colony.blocked", blocked), RED));
		}
		for (JobType type : List.of(JobType.CHOP_TREE, JobType.MINE_BRANCH)) {
			List<BlockPos> givenUp = status.givenUp(type);
			if (!givenUp.isEmpty()) {
				Component positions = list(givenUp.stream().<Component>map(pos -> Component.literal(pos.toShortString())).toList());
				lines.add(new Line(Component.translatable(ColonyStatus.givenUpKey(type), positions), RED));
			}
		}
		if (status.room().coreFull()) {
			lines.add(new Line(Component.translatable("commands.antcraft.colony.core_full", Component.translatable(status.room().storage().key())), RED));
		}
		return lines;
	}

	private static Component name(Item item) {
		return new ItemStack(item).getHoverName();
	}

	private static Component items(Map<Item, Integer> items) {
		List<Component> parts = new ArrayList<>();
		items.forEach((item, count) -> parts.add(Component.translatable("screen.antcraft.colony_status.item_count", count, name(item))));
		return list(parts);
	}

	/** The parts with the lang file's separator between them. */
	private static Component list(List<Component> parts) {
		MutableComponent text = Component.empty();
		for (Component part : parts) {
			if (!text.getSiblings().isEmpty()) {
				text.append(Component.translatable("screen.antcraft.colony_status.separator"));
			}
			text.append(part);
		}
		return text;
	}
}
