package io.github.barniebarnie.antcraft.item;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.gameevent.GameEvent;

/** Placing a Bot Core on a block activates a new bot there. */
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
		AntBot bot = ModEntities.ANT_BOT.spawn(level, stack, player, spawnPos, EntitySpawnReason.SPAWN_ITEM_USE, true, false);
		if (bot == null) {
			return InteractionResult.FAIL;
		}

		bot.setCustomName(Component.literal("Ant " + bot.getUUID().toString().substring(0, 4)));
		stack.consume(1, player);
		level.gameEvent(player, GameEvent.ENTITY_PLACE, spawnPos);
		return InteractionResult.SUCCESS;
	}

	private static void message(Player player, Component text) {
		if (player != null) {
			player.sendOverlayMessage(text);
		}
	}
}
