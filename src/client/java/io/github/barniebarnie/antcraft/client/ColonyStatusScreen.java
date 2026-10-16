package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Read-only overview of the nearest colony; asks the server for fresh data while open. */
public class ColonyStatusScreen extends Screen {
	private static final int REFRESH_TICKS = 60;
	private static final int LINE_HEIGHT = 11;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFAAAAAA;
	private static final int RED = 0xFFFF5555;
	private static final int YELLOW = 0xFFFFFF55;

	private record Line(Component text, int color) {
	}

	/** Empty until the first reply arrives. */
	private Optional<ColonyNetworking.StatusReply> reply = Optional.empty();
	private List<Line> lines = List.of();
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
		this.lines = lines(reply);
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
		int maxScroll = Math.max(0, this.lines.size() - (this.height - 40) / LINE_HEIGHT);
		this.scroll = Math.clamp(this.scroll - (int) Math.signum(scrollY), 0, maxScroll);
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(this.font, this.title, 20, 10, WHITE);
		if (this.reply.isEmpty()) {
			graphics.text(this.font, Component.translatable("screen.antcraft.colony_status.loading"), 20, 30, GREY);
			return;
		}
		int y = 30;
		for (int i = this.scroll; i < this.lines.size() && y < this.height - 10; i++, y += LINE_HEIGHT) {
			Line line = this.lines.get(i);
			graphics.text(this.font, line.text(), 20, y, line.color());
		}
	}

	private static List<Line> lines(ColonyNetworking.StatusReply reply) {
		List<Line> lines = new ArrayList<>();
		if (reply.status().isEmpty()) {
			lines.add(new Line(Component.translatable("commands.antcraft.colony.none", AntCraftCommands.COLONY_RANGE), RED));
			return lines;
		}
		ColonyStatus status = reply.status().get();
		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.colony", status.core().toShortString(), status.radius()), WHITE));

		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.bots", status.members(), status.idle()), YELLOW));
		status.roles().forEach((role, count) -> lines.add(new Line(Component.literal("  " + count + " " + role.id()), WHITE)));
		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.jobs", status.jobs(), status.claimed()), YELLOW));

		lines.add(new Line(Component.translatable("screen.antcraft.colony_status.stock"), YELLOW));
		for (ColonyStatus.Stock stock : status.stock()) {
			lines.add(new Line(Component.literal("  " + stock.have() + "/" + stock.want() + " " + stock.name()), stock.isShort() ? RED : GREY));
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
			lines.add(new Line(text, WHITE));
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
