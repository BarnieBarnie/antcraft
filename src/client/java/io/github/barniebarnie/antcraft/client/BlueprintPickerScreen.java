package io.github.barniebarnie.antcraft.client;

import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.blueprint.ChosenBlueprint;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import io.github.barniebarnie.antcraft.network.BlueprintNetworking;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;

/**
 * Lists the blueprints the server sent ({@link ClientBlueprints}) with their size, role and materials; choosing one sets
 * the Blueprint item in {@link #hand} to it ({@link BlueprintNetworking.ChooseBlueprint}) and closes the screen.
 */
public class BlueprintPickerScreen extends Screen {
	private static final int TOP = 30;
	private static final int BUTTON_HEIGHT = 14;
	private static final int LINE_HEIGHT = 10;
	private static final int GAP = 6;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GREY = 0xFFAAAAAA;

	private final InteractionHand hand;
	private final Optional<Identifier> chosen;
	private final List<BlueprintSummary> blueprints;
	private int scroll;

	public BlueprintPickerScreen(InteractionHand hand, Optional<ChosenBlueprint> chosen) {
		super(Component.translatable(BlueprintSummary.KEY));
		this.hand = hand;
		this.chosen = chosen.map(ChosenBlueprint::id);
		this.blueprints = ClientBlueprints.all();
	}

	/** Opens the picker for the Blueprint in {@code hand}, if the server takes the choice. */
	static void open(InteractionHand hand) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gui.screen() != null || !ClientPlayNetworking.canSend(BlueprintNetworking.ChooseBlueprint.TYPE)) {
			return;
		}
		client.gui.setScreen(new BlueprintPickerScreen(hand, BlueprintItem.chosen(client.player.getItemInHand(hand))));
	}

	private int entryHeight(BlueprintSummary blueprint) {
		return BUTTON_HEIGHT + blueprint.details().size() * LINE_HEIGHT + GAP;
	}

	private int width() {
		return Math.min(320, this.width - 40);
	}

	@Override
	protected void init() {
		int y = TOP;
		for (int i = this.scroll; i < this.blueprints.size(); i++) {
			BlueprintSummary blueprint = this.blueprints.get(i);
			if (y + this.entryHeight(blueprint) > this.height) {
				break;
			}
			Component label = this.chosen.filter(blueprint.id()::equals).isPresent()
				? Component.translatable("screen.antcraft.blueprint_picker.chosen", blueprint.title())
				: blueprint.title();
			this.addRenderableWidget(Button.builder(label, b -> this.choose(blueprint)).bounds(20, y, this.width(), BUTTON_HEIGHT).build());
			y += this.entryHeight(blueprint);
		}
	}

	private void choose(BlueprintSummary blueprint) {
		ClientPlayNetworking.send(new BlueprintNetworking.ChooseBlueprint(this.hand, blueprint.id()));
		this.onClose();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		this.scroll = Math.clamp(this.scroll - (int) Math.signum(scrollY), 0, Math.max(0, this.blueprints.size() - 1));
		this.rebuildWidgets();
		return true;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.text(this.font, this.title, 20, 10, WHITE);
		if (this.blueprints.isEmpty()) {
			graphics.text(this.font, Component.translatable("screen.antcraft.blueprint_picker.empty"), 20, TOP, GREY);
			return;
		}
		int y = TOP;
		for (int i = this.scroll; i < this.blueprints.size(); i++) {
			BlueprintSummary blueprint = this.blueprints.get(i);
			if (y + this.entryHeight(blueprint) > this.height) {
				break;
			}
			int line = y + BUTTON_HEIGHT + 2;
			for (Component detail : blueprint.details()) {
				graphics.text(this.font, detail, 24, line, GREY);
				line += LINE_HEIGHT;
			}
			y += this.entryHeight(blueprint);
		}
	}
}
