package io.github.barniebarnie.antcraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.GameRecipeBook;
import io.github.barniebarnie.antcraft.work.Gatherables;
import io.github.barniebarnie.antcraft.work.Quarry;
import io.github.barniebarnie.antcraft.work.Storage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * /antcraft status
 * /antcraft goto &lt;pos&gt; [bots]   (defaults to the nearest bot)
 * /antcraft stop [bots]
 * /antcraft role &lt;role&gt; [bots]
 * /antcraft home &lt;pos&gt; [bots]
 * /antcraft colony info|order &lt;item&gt; &lt;count&gt;|cancel &lt;item&gt;|quarry &lt;from&gt; &lt;to&gt;|radius &lt;blocks&gt;   (nearest colony)
 */
public final class AntCraftCommands {
	private static final double NEAREST_RANGE = 64.0;
	private static final int COLONY_RANGE = 64;

	private AntCraftCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher, buildContext));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
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
			.then(Commands.literal("colony")
				.then(Commands.literal("info").executes(ctx -> colonyInfo(ctx.getSource())))
				.then(Commands.literal("order")
					.then(Commands.argument("item", ItemArgument.item(buildContext))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, 4096))
							.executes(ctx -> order(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value(), IntegerArgumentType.getInteger(ctx, "count"))))))
				.then(Commands.literal("quarry")
					.then(Commands.argument("from", BlockPosArgument.blockPos())
						.then(Commands.argument("to", BlockPosArgument.blockPos())
							.executes(ctx -> setQuarry(ctx.getSource(), BlockPosArgument.getBlockPos(ctx, "from"), BlockPosArgument.getBlockPos(ctx, "to"))))))
				.then(Commands.literal("radius")
					.then(Commands.argument("blocks", IntegerArgumentType.integer(4, 128))
						.executes(ctx -> setRadius(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "blocks")))))
				.then(Commands.literal("cancel")
					.then(Commands.argument("item", ItemArgument.item(buildContext))
						.executes(ctx -> cancel(ctx.getSource(), ItemArgument.getItem(ctx, "item").item().value())))))
			.then(Commands.literal("home")
				.then(Commands.argument("pos", BlockPosArgument.blockPos())
					.executes(ctx -> setHome(ctx, nearestBot(ctx.getSource())))
					.then(Commands.argument("bots", EntityArgument.entities())
						.executes(ctx -> setHome(ctx, bots(ctx)))))));
	}

	private static Optional<Colony> nearestColony(CommandSourceStack source) {
		Optional<Colony> colony = ColonyManager.get(source.getLevel()).nearest(BlockPos.containing(source.getPosition()), COLONY_RANGE);
		if (colony.isEmpty()) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.none", COLONY_RANGE));
		}
		return colony;
	}

	private static int colonyInfo(CommandSourceStack source) {
		Optional<Colony> found = nearestColony(source);
		if (found.isEmpty()) {
			return 0;
		}
		Colony colony = found.get();
		ServerLevel level = source.getLevel();
		Map<BotRole, Long> roles = new EnumMap<>(BotRole.class);
		for (UUID member : colony.members()) {
			if (level.getEntity(member) instanceof AntBot bot) {
				roles.merge(bot.getRole(), 1L, Long::sum);
			}
		}
		long claimed = colony.board().jobs().stream().filter(job -> !job.isOpen()).count();
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.info",
			colony.core().toShortString(), colony.members().size(), roles.toString(), colony.board().jobs().size(), claimed, colony.radius()), false);

		Container core = Storage.containerAt(level, colony.core());
		Map<Item, Integer> stock = core == null ? Map.of() : Storage.stock(core);
		for (Map.Entry<Item, Integer> order : colony.orders().entrySet()) {
			Map<Item, Integer> available = new java.util.HashMap<>(stock);
			available.remove(order.getKey());
			TaskPlanner.Plan<Item> plan = TaskPlanner.plan(order.getKey(), order.getValue(), available, GameRecipeBook.of(source.getServer()), Gatherables::isGatherable);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.order_line",
				order.getValue(), itemName(order.getKey()), names(plan.toGather()), names(plan.missing())), false);
		}
		return colony.members().size();
	}

	private static String itemName(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).getPath();
	}

	private static String names(Map<Item, Integer> items) {
		if (items.isEmpty()) {
			return "-";
		}
		StringBuilder text = new StringBuilder();
		items.forEach((item, count) -> text.append(text.isEmpty() ? "" : ", ").append(count).append(' ').append(itemName(item)));
		return text.toString();
	}

	private static int order(CommandSourceStack source, Item item, int count) {
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			c.addOrder(item, count);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.ordered", count, itemName(item)), false);
		});
		return colony.isPresent() ? 1 : 0;
	}

	private static int setQuarry(CommandSourceStack source, BlockPos from, BlockPos to) {
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			Quarry quarry = new Quarry(from, to);
			c.setQuarry(quarry);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.quarry", quarry.min().toShortString(), quarry.max().toShortString()), false);
		});
		return colony.isPresent() ? 1 : 0;
	}

	private static int setRadius(CommandSourceStack source, int radius) {
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			c.setRadius(radius);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.radius", radius), false);
		});
		return colony.isPresent() ? 1 : 0;
	}

	private static int cancel(CommandSourceStack source, Item item) {
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			c.orders().remove(item);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.cancelled", itemName(item)), false);
		});
		return colony.isPresent() ? 1 : 0;
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
