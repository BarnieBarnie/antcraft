package io.github.barniebarnie.antcraft.item;

import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.blueprint.ChosenBlueprint;
import io.github.barniebarnie.antcraft.registry.ModComponents;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * A Blueprint remembers one of the server's blueprints ({@link ModComponents#CHOSEN_BLUEPRINT}). Right-clicking the air
 * with it opens the picker on the client, which asks the server to set the chosen blueprint
 * ({@code ColonyNetworking.ChooseBlueprint}). Right-clicking a block with no blueprint chosen opens the picker too; with
 * one chosen the client asks the server to place it where its ghost stands ({@link #place}).
 */
public class BlueprintItem extends Item {
	/** Opens the picker for the item in a hand; the client sets it, a dedicated server has none. */
	private static Consumer<InteractionHand> picker = hand -> {
	};

	/** Asks the server to place the chosen blueprint for a right-click on a block; the client sets it, a dedicated server has none. */
	private static Consumer<UseOnContext> placer = context -> {
	};

	public BlueprintItem(Properties properties) {
		super(properties);
	}

	/** Sets what opens the picker on the client. */
	public static void setPicker(Consumer<InteractionHand> opener) {
		picker = opener;
	}

	/** Sets what places the chosen blueprint on the client. */
	public static void setPlacer(Consumer<UseOnContext> sender) {
		placer = sender;
	}

	/** The blueprint {@code stack} is set to, if any. */
	public static Optional<ChosenBlueprint> chosen(ItemStack stack) {
		return Optional.ofNullable(stack.get(ModComponents.CHOSEN_BLUEPRINT));
	}

	/**
	 * Sets {@code stack} to blueprint {@code id} if the server has loaded it.
	 *
	 * @return whether it was set
	 */
	public static boolean choose(ItemStack stack, Identifier id) {
		Optional<Blueprint> blueprint = Blueprints.get(id);
		if (!(stack.getItem() instanceof BlueprintItem) || blueprint.isEmpty()) {
			return false;
		}
		stack.set(ModComponents.CHOSEN_BLUEPRINT, new ChosenBlueprint(id, blueprint.get().name()));
		return true;
	}

	/** The tooltip line for a Blueprint set to {@code chosen}: the blueprint's name, or that none is chosen. */
	public static Component tooltip(Optional<ChosenBlueprint> chosen) {
		return chosen
			.map(blueprint -> Component.translatable("item.antcraft.blueprint.chosen", Component.translatable(blueprint.name())))
			.orElseGet(() -> Component.translatable("item.antcraft.blueprint.none"));
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			picker.accept(hand);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (chosen(context.getItemInHand()).isEmpty()) {
			// Falls through to use, which opens the picker.
			return InteractionResult.PASS;
		}
		return this.place(context);
	}

	/**
	 * Right-click on a block with a blueprint chosen: the client asks the server to place it where its ghost stands, turned
	 * and mirrored as shown ({@code ColonyNetworking.PlaceBlueprint}). The server checks the request and answers on the
	 * action bar; nothing happens to the block here on either side.
	 */
	protected InteractionResult place(UseOnContext context) {
		if (context.getLevel().isClientSide()) {
			placer.accept(context);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		builder.accept(tooltip(chosen(stack)).copy().withStyle(ChatFormatting.GRAY));
	}
}
