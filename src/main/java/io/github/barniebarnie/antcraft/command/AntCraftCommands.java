package io.github.barniebarnie.antcraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * /antcraft status
 * /antcraft goto &lt;pos&gt; [bots]   (defaults to the nearest bot)
 * /antcraft stop [bots]
 * /antcraft role &lt;role&gt; [bots]
 * /antcraft home &lt;pos&gt; [bots]
 */
public final class AntCraftCommands {
	private static final double NEAREST_RANGE = 64.0;

	private AntCraftCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> role = Commands.literal("role");
		for (BotRole value : BotRole.values()) {
			role.then(Commands.literal(value.id())
				.executes(ctx -> setRole(ctx.getSource(), nearestBot(ctx.getSource()), value))
				.then(Commands.argument("bots", EntityArgument.entities())
					.executes(ctx -> setRole(ctx.getSource(), bots(ctx), value))));
		}

		dispatcher.register(Commands.literal("antcraft")
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("status").executes(AntCraftCommands::status))
			.then(Commands.literal("goto")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(ctx -> walkTo(ctx, nearestBot(ctx.getSource())))
					.then(Commands.argument("bots", EntityArgument.entities())
						.executes(ctx -> walkTo(ctx, bots(ctx))))))
			.then(Commands.literal("stop")
				.executes(ctx -> stop(ctx.getSource(), nearestBot(ctx.getSource())))
				.then(Commands.argument("bots", EntityArgument.entities())
					.executes(ctx -> stop(ctx.getSource(), bots(ctx)))))
			.then(role)
			.then(Commands.literal("home")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(ctx -> setHome(ctx, nearestBot(ctx.getSource())))
					.then(Commands.argument("bots", EntityArgument.entities())
						.executes(ctx -> setHome(ctx, bots(ctx)))))));
	}

	private static int setRole(CommandSourceStack source, List<AntBot> bots, BotRole role) {
		if (!checkAllowed(source) || !checkAny(source, bots)) {
			return 0;
		}
		bots.forEach(bot -> bot.setRole(role));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.role", bots.size(), role.id()), false);
		return bots.size();
	}

	private static int setHome(CommandContext<CommandSourceStack> ctx, List<AntBot> bots) {
		CommandSourceStack source = ctx.getSource();
		if (!checkAllowed(source) || !checkAny(source, bots)) {
			return 0;
		}
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		bots.forEach(bot -> bot.setHome(pos));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.home", bots.size(), pos.toShortString()), false);
		return bots.size();
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		boolean allowed = AntCraftConfig.botsAllowed(source.getServer());
		int count = AntBot.countIn(source.getLevel());
		source.sendSuccess(() -> Component.translatable(
			"commands.antcraft.status", allowed ? "enabled" : "disabled", count, AntCraftConfig.maxBotsPerLevel()), false);
		return count;
	}

	private static int walkTo(CommandContext<CommandSourceStack> ctx, List<AntBot> bots) {
		CommandSourceStack source = ctx.getSource();
		if (!checkAllowed(source) || !checkAny(source, bots)) {
			return 0;
		}
		BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
		bots.forEach(bot -> bot.setWalkTarget(pos));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.goto", bots.size(), pos.toShortString()), false);
		return bots.size();
	}

	private static int stop(CommandSourceStack source, List<AntBot> bots) {
		if (!checkAny(source, bots)) {
			return 0;
		}
		bots.forEach(bot -> bot.setWalkTarget(null));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.stop", bots.size()), false);
		return bots.size();
	}

	private static boolean checkAllowed(CommandSourceStack source) {
		if (AntCraftConfig.botsAllowed(source.getServer())) {
			return true;
		}
		source.sendFailure(Component.translatable("message.antcraft.disabled"));
		return false;
	}

	private static boolean checkAny(CommandSourceStack source, List<AntBot> bots) {
		if (!bots.isEmpty()) {
			return true;
		}
		source.sendFailure(Component.translatable("commands.antcraft.no_bots"));
		return false;
	}

	private static List<AntBot> nearestBot(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		Vec3 origin = source.getPosition();
		return level.getEntities(ModEntities.ANT_BOT, bot -> bot.isAlive() && bot.distanceToSqr(origin) <= NEAREST_RANGE * NEAREST_RANGE)
			.stream()
			.min(Comparator.comparingDouble(bot -> bot.distanceToSqr(origin)))
			.map(bot -> List.<AntBot>of(bot))
			.orElse(List.of());
	}

	private static List<AntBot> bots(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Collection<? extends Entity> entities = EntityArgument.getEntities(ctx, "bots");
		List<AntBot> bots = new ArrayList<>();
		for (Entity entity : entities) {
			if (entity instanceof AntBot bot) {
				bots.add(bot);
			}
		}
		return bots;
	}
}
