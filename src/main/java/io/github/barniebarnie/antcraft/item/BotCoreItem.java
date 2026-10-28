package io.github.barniebarnie.antcraft.item;

import io.github.barniebarnie.antcraft.colony.BotActivation;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModComponents;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Placing a Bot Core on a block activates a new bot there. A core can carry a script ({@link ModComponents#CORE_SCRIPT},
 * set with {@code /antcraft core script}), which the bot starts at once.
 */
public class BotCoreItem extends Item {
	public BotCoreItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (!(context.getLevel() instanceof ServerLevel level)) {
			return InteractionResult.SUCCESS;
		}

		Player player = context.getPlayer();
		if (!AntCraftConfig.botsAllowed(level.getServer())) {
			message(player, Component.translatable("message.antcraft.disabled"));
			return InteractionResult.FAIL;
		}
		if (AntBot.countIn(level) >= AntCraftConfig.maxBotsPerLevel()) {
			message(player, Component.translatable("message.antcraft.limit", AntCraftConfig.maxBotsPerLevel()));
			return InteractionResult.FAIL;
		}

		BlockPos pos = context.getClickedPos();
		BlockPos spawnPos = level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() ? pos : pos.relative(context.getClickedFace());
		ItemStack stack = context.getItemInHand();
		if (BotActivation.activate(level, spawnPos, player, stack.get(ModComponents.CORE_SCRIPT)) == null) {
			return InteractionResult.FAIL;
		}
		stack.consume(1, player);
		return InteractionResult.SUCCESS;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
		CoreScript script = stack.get(ModComponents.CORE_SCRIPT);
		if (script != null) {
			builder.accept(Component.translatable("item.antcraft.bot_core.script", script.toString()).withStyle(ChatFormatting.GRAY));
		}
	}

	private static void message(Player player, Component text) {
		if (player != null) {
			player.sendOverlayMessage(text);
		}
	}
}
