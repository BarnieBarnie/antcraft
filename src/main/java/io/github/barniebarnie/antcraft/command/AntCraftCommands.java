package io.github.barniebarnie.antcraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyActions;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRespawns;
import io.github.barniebarnie.antcraft.item.CoreScript;
import io.github.barniebarnie.antcraft.network.BotNetworking;
import io.github.barniebarnie.antcraft.network.ScriptNetworking;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.registry.ModComponents;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import io.github.barniebarnie.antcraft.script.BotLog;
import io.github.barniebarnie.antcraft.script.PlayerLimits;
import io.github.barniebarnie.antcraft.script.ScriptEdits;
import io.github.barniebarnie.antcraft.script.ScriptLibrary;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.ServerScripts;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * /antcraft status
 * /antcraft profile start|stop|report   (server tick time, per part of the bots' work)
 * /antcraft script list   (also any player)
 * /antcraft script edit &lt;script&gt;   (also any player; opens the script editor, which only operators may save from)
 * /antcraft script reload
 * /antcraft bot [bots] run &lt;script&gt; [args]|stop|remove|join &lt;core pos&gt; [dimension]|leave|status|log [n] [level]|edit   (defaults to the nearest bot; also a
 *     bot's owner, on their own bots, within the limits of {@link PlayerLimits})
 * /antcraft bots   (any player; opens the bots screen with the bots they may control)
 * /antcraft bot remove "&lt;name&gt;"   (a dead bot waiting to respawn, by its name; also its owner)
 * /antcraft core script &lt;script&gt; [args]|clear   (the Bot Core in the player's main hand)
 * /antcraft colony info|radius &lt;blocks&gt;   (nearest colony)
 * /antcraft colony trust|untrust &lt;player&gt; [colony]   (also the colony's owner, not only operators)
 * /antcraft colony trust list [colony]   (also the colony's owner)
 */
public final class AntCraftCommands {
	private static final double NEAREST_RANGE = 64.0;
	public static final int COLONY_RANGE = 64;
	/** How many lines {@code bot log} shows without {@code n}. */
	private static final int DEFAULT_LOG_LINES = 10;
	/** How many of a bot's log lines the script editor shows. */
	public static final int EDITOR_LOG_LINES = 20;

	private AntCraftCommands() {
	}

	private static final Predicate<CommandSourceStack> OPERATOR = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	/** Operators, and any player, who may run the commands an owner may use on their own colony. */
	private static boolean operatorOrPlayer(CommandSourceStack source) {
		return OPERATOR.test(source) || source.isPlayer();
	}

	/** Whether {@code player} is an operator, who may do what the operator-only commands do. */
	public static boolean isOperator(ServerPlayer player) {
		return OPERATOR.test(player.createCommandSourceStack());
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		// Every subcommand is for operators but trust (with trust list) and untrust, which a colony's owner may use too,
		// script list and edit, and bot, which a bot's owner may use on their own bots (checked when run).
		dispatcher.register(Commands.literal("antcraft")
			.requires(AntCraftCommands::operatorOrPlayer)
			.then(Commands.literal("status").requires(OPERATOR).executes(AntCraftCommands::status))
			.then(Commands.literal("profile").requires(OPERATOR)
				.then(Commands.literal("start").executes(ctx -> profile(ctx.getSource(), true)))
				.then(Commands.literal("stop").executes(ctx -> profile(ctx.getSource(), false)))
				.then(Commands.literal("report").executes(ctx -> profileReport(ctx.getSource()))))
			.then(Commands.literal("script").requires(AntCraftCommands::operatorOrPlayer)
				.then(Commands.literal("list").executes(ctx -> listScripts(ctx.getSource())))
				.then(Commands.literal("edit").then(scriptName(Commands.argument("script", StringArgumentType.string()))
					.executes(ctx -> editScript(ctx.getSource(), StringArgumentType.getString(ctx, "script"), List.of()))))
				.then(Commands.literal("reload").requires(OPERATOR).executes(ctx -> reloadScripts(ctx.getSource()))))
			// The bots argument is optional: without it, the nearest bot. Literals win over the selector, so a bot cannot be named "run".
			.then(botCommands(Commands.literal("bot").requires(AntCraftCommands::operatorOrPlayer), ctx -> nearestBot(ctx.getSource()))
				.then(Commands.literal("remove").then(waitingBotName()))
				.then(botCommands(Commands.argument("bots", EntityArgument.entities()), AntCraftCommands::bots)))
			.then(Commands.literal("bots").requires(CommandSourceStack::isPlayer).executes(ctx -> openBots(ctx.getSource())))
			// Literals win over the script argument, so a script named "clear" cannot be set this way.
			.then(Commands.literal("core").requires(OPERATOR)
				.then(Commands.literal("script")
					.then(Commands.literal("clear").executes(ctx -> setCoreScript(ctx.getSource(), null)))
					.then(scriptArgument((ctx, name, args) -> setCoreScript(ctx.getSource(), new CoreScript(name, args))))))
			.then(Commands.literal("colony").requires(AntCraftCommands::operatorOrPlayer)
				.then(Commands.literal("info").requires(OPERATOR).executes(ctx -> colonyInfo(ctx.getSource())))
				.then(Commands.literal("trust").requires(AntCraftCommands::operatorOrPlayer)
					.then(Commands.literal("list")
						.executes(ctx -> listTrusted(ctx.getSource(), Optional.empty()))
						.then(Commands.argument("colony", BlockPosArgument.blockPos())
							.executes(ctx -> listTrusted(ctx.getSource(), Optional.of(BlockPosArgument.getBlockPos(ctx, "colony"))))))
					.then(Commands.argument("players", GameProfileArgument.gameProfile())
						.executes(ctx -> trust(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "players"), Optional.empty(), true))
						.then(Commands.argument("colony", BlockPosArgument.blockPos())
							.executes(ctx -> trust(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "players"),
								Optional.of(BlockPosArgument.getBlockPos(ctx, "colony")), true)))))
				.then(Commands.literal("untrust").requires(AntCraftCommands::operatorOrPlayer)
					.then(Commands.argument("players", GameProfileArgument.gameProfile())
						.executes(ctx -> trust(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "players"), Optional.empty(), false))
						.then(Commands.argument("colony", BlockPosArgument.blockPos())
							.executes(ctx -> trust(ctx.getSource(), GameProfileArgument.getGameProfiles(ctx, "players"),
								Optional.of(BlockPosArgument.getBlockPos(ctx, "colony")), false)))))
				.then(Commands.literal("radius").requires(OPERATOR)
					.then(Commands.argument("blocks", IntegerArgumentType.integer(ColonyActions.MIN_RADIUS, ColonyActions.MAX_RADIUS))
						.executes(ctx -> setRadius(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "blocks")))))));
	}

	private static Optional<Colony> nearestColony(CommandSourceStack source) {
		Optional<Colony> colony = ColonyManager.get(source.getLevel()).nearest(BlockPos.containing(source.getPosition()), COLONY_RANGE);
		if (colony.isEmpty()) {
			source.sendFailure(Component.translatable("commands.antcraft.colony.none", COLONY_RANGE));
		}
		return colony;
	}

	/** Where the nearest colony is, how many members and what radius it has, and who may change it. */
	private static int colonyInfo(CommandSourceStack source) {
		Optional<Colony> found = nearestColony(source);
		if (found.isEmpty()) {
			return 0;
		}
		Colony colony = found.get();
		source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.info", colony.core().toShortString(), colony.members().size(),
			colony.radius()), false);
		source.sendSuccess(() -> access(source.getLevel(), colony), false);
		return colony.members().size();
	}

	/** Who owns {@code colony} and whom it trusts, by name. */
	static Component access(ServerLevel level, Colony colony) {
		if (colony.owner().isEmpty()) {
			return Component.translatable("commands.antcraft.colony.no_owner");
		}
		List<String> trusted = colony.trusted().stream().map(player -> playerName(level, player)).toList();
		Component also = trusted.isEmpty() ? Component.translatable("commands.antcraft.colony.nobody") : Component.literal(String.join(", ", trusted));
		return Component.translatable("commands.antcraft.colony.owner", playerName(level, colony.owner().get()), also);
	}

	/** The name of {@code player} if the server knows it, else its UUID. */
	private static String playerName(ServerLevel level, UUID player) {
		ServerPlayer online = level.getServer().getPlayerList().getPlayer(player);
		if (online != null) {
			return online.getGameProfile().name();
		}
		return level.getServer().services().nameToIdCache().get(player).map(NameAndId::name).orElse(player.toString());
	}

	/**
	 * The colony the source may trust players with ({@link ColonyActions#mayTrust}: its owner, or any for operators): the
	 * one whose core is at {@code named}, or else the nearest such colony, so another player's colony nearer does not get
	 * in the way. Either must lie within {@link #COLONY_RANGE}. A colony the source may not trust is refused, with a
	 * message, as is no colony.
	 */
	private static Optional<Colony> colonyToTrust(CommandSourceStack source, Optional<BlockPos> named) {
		ServerPlayer player = source.getPlayer();
		BlockPos at = BlockPos.containing(source.getPosition());
		ColonyManager colonies = ColonyManager.get(source.getLevel());
		// The console and command blocks run as operators.
		Predicate<Colony> mayTrust = colony -> player == null || ColonyActions.mayTrust(colony, player);
		if (named.isPresent()) {
			Optional<Colony> colony = colonies.byCore(named.get()).filter(found -> found.core().closerThan(at, COLONY_RANGE));
			if (colony.isEmpty()) {
				source.sendFailure(Component.translatable("commands.antcraft.colony.none_at", named.get().toShortString(), COLONY_RANGE));
				return Optional.empty();
			}
			if (!mayTrust.test(colony.get())) {
				source.sendFailure(Component.translatable("commands.antcraft.colony.trust.not_allowed"));
				return Optional.empty();
			}
			return colony;
		}
		Optional<Colony> found = colonies.nearest(at, COLONY_RANGE, mayTrust);
		if (found.isEmpty()) {
			if (colonies.nearest(at, COLONY_RANGE).isPresent()) {
				source.sendFailure(Component.translatable("commands.antcraft.colony.trust.not_allowed"));
			} else {
				source.sendFailure(Component.translatable("commands.antcraft.colony.none", COLONY_RANGE));
			}
		}
		return found;
	}

	/** Names the owner and the trusted players of the colony the source may trust (see {@link #colonyToTrust}). */
	private static int listTrusted(CommandSourceStack source, Optional<BlockPos> named) {
		Optional<Colony> found = colonyToTrust(source, named);
		if (found.isEmpty()) {
			return 0;
		}
		source.sendSuccess(() -> access(source.getLevel(), found.get()), false);
		return found.get().trusted().size();
	}

	/**
	 * Trusts {@code players} with the colony the source may trust (see {@link #colonyToTrust}), or no longer does if not
	 * {@code trust}.
	 */
	private static int trust(CommandSourceStack source, Collection<NameAndId> players, Optional<BlockPos> named, boolean trust) {
		Optional<Colony> found = colonyToTrust(source, named);
		if (found.isEmpty()) {
			return 0;
		}
		int changed = 0;
		for (NameAndId other : players) {
			boolean done = trust ? found.get().trust(other.id()) : found.get().untrust(other.id());
			String key = trust
				? (done ? "commands.antcraft.colony.trust" : "commands.antcraft.colony.trust.unchanged")
				: (done ? "commands.antcraft.colony.untrust" : "commands.antcraft.colony.untrust.unchanged");
			if (done) {
				changed++;
				source.sendSuccess(() -> Component.translatable(key, other.name()), true);
			} else {
				source.sendFailure(Component.translatable(key, other.name()));
			}
		}
		return changed;
	}

	private static int setRadius(CommandSourceStack source, int radius) {
		Optional<Colony> colony = nearestColony(source);
		colony.ifPresent(c -> {
			c.setRadius(radius);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.colony.radius", radius), false);
		});
		return colony.isPresent() ? 1 : 0;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		boolean allowed = AntCraftConfig.botsAllowed(source.getServer());
		int count = AntBot.countIn(source.getLevel());
		source.sendSuccess(() -> Component.translatable(
			"commands.antcraft.status", allowed ? "enabled" : "disabled", count, AntCraftConfig.maxBotsPerLevel()), false);
		return count;
	}

	/** Starts the tick profiler afresh, or stops it, keeping what it measured for the report. */
	private static int profile(CommandSourceStack source, boolean start) {
		if (start) {
			TickProfiler.SERVER.start();
		} else {
			TickProfiler.SERVER.stop();
		}
		source.sendSuccess(() -> Component.translatable(start ? "commands.antcraft.profile.start" : "commands.antcraft.profile.stop"), false);
		return 1;
	}

	/** What the tick profiler measured: the average and worst server tick, then each part, the costliest first. */
	private static int profileReport(CommandSourceStack source) {
		TickProfiler.Report report = TickProfiler.SERVER.report();
		report.lines().forEach(line -> source.sendSuccess(() -> Component.literal(line), false));
		return (int) report.ticks();
	}

	public static int stop(CommandSourceStack source, List<AntBot> bots) {
		if (!checkAny(source, bots)) {
			return 0;
		}
		bots.forEach(AntBot::stopScript);
		source.sendSuccess(() -> Component.translatable("commands.antcraft.stop", bots.size()), false);
		return bots.size();
	}

	/**
	 * Removes {@code bots} for good ({@link AntBot#removeForGood}), or, where a bot given by its UUID is dead with its body
	 * gone, drops the respawn it waits for in every level, which takes it out of its colony (#464).
	 */
	private static int remove(CommandContext<CommandSourceStack> ctx, BotsArgument argument) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		List<AntBot> bots;
		try {
			bots = argument.get(ctx);
		} catch (CommandSyntaxException noEntity) {
			Optional<UUID> id = ctx.getNodes().stream().filter(node -> node.getNode().getName().equals("bots")).findFirst()
				.flatMap(node -> parseUuid(node.getRange().get(ctx.getInput())));
			if (id.isEmpty()) {
				throw noEntity;
			}
			return removeWaiting(source, id.get(), noEntity);
		}
		if (!checkAny(source, bots)) {
			return 0;
		}
		bots.forEach(bot -> bot.removeForGood((ServerLevel) bot.level()));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.bot.remove", bots.size()), false);
		return bots.size();
	}

	/** Drops the respawn the dead bot {@code id} waits for, if the source may control it; {@code noEntity} where none waits. */
	private static int removeWaiting(CommandSourceStack source, UUID id, CommandSyntaxException noEntity) throws CommandSyntaxException {
		MinecraftServer server = source.getServer();
		BotRespawns.Pending waiting = null;
		for (ServerLevel level : server.getAllLevels()) {
			waiting = waiting != null ? waiting : BotRespawns.get(level).pending(id);
		}
		if (waiting == null) {
			throw noEntity;
		}
		if (!mayControl(source, waiting.bot().read("Owner", UUIDUtil.CODEC).orElse(null))) {
			source.sendFailure(Component.translatable("commands.antcraft.bot.not_yours", id.toString()));
			return 0;
		}
		server.getAllLevels().forEach(level -> BotRespawns.get(level).forget(server, id));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.bot.remove", 1), false);
		return 1;
	}

	/**
	 * {@code "<name>"} of {@code bot remove}: removes the dead bot waiting to respawn with that name ({@link #removeWaiting},
	 * #466), suggesting the waiting bots the source may control. The bots argument cannot take a name with a space.
	 */
	private static RequiredArgumentBuilder<CommandSourceStack, String> waitingBotName() {
		return Commands.argument("name", StringArgumentType.string())
			.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(waitingBots(ctx.getSource().getServer())
				.filter(waiting -> mayControl(ctx.getSource(), waiting.bot().read("Owner", UUIDUtil.CODEC).orElse(null)))
				.map(AntCraftCommands::name).filter(name -> !name.isEmpty()).map(StringArgumentType::escapeIfRequired), builder))
			.executes(ctx -> {
				String name = StringArgumentType.getString(ctx, "name");
				// ponytail: the first bot with that name; names carry part of the UUID, so two seldom match
				Optional<BotRespawns.Pending> waiting = waitingBots(ctx.getSource().getServer()).filter(p -> name(p).equals(name)).findFirst();
				if (waiting.isEmpty()) {
					throw EntityArgument.NO_ENTITIES_FOUND.create();
				}
				return removeWaiting(ctx.getSource(), waiting.get().id(), EntityArgument.NO_ENTITIES_FOUND.create());
			});
	}

	private static Stream<BotRespawns.Pending> waitingBots(MinecraftServer server) {
		List<BotRespawns.Pending> waiting = new ArrayList<>();
		server.getAllLevels().forEach(level -> waiting.addAll(BotRespawns.get(level).all()));
		return waiting.stream();
	}

	/** The name of the bot waiting in {@code waiting}, empty when it has none. */
	private static String name(BotRespawns.Pending waiting) {
		return waiting.bot().read("CustomName", ComponentSerialization.CODEC).map(Component::getString).orElse("");
	}

	private static Optional<UUID> parseUuid(String text) {
		try {
			return Optional.of(UUID.fromString(text));
		} catch (IllegalArgumentException notUuid) {
			return Optional.empty();
		}
	}

	/**
	 * Makes {@code bots} members of the colony whose core is at {@code core} in {@code level} (the source's unless the
	 * command names a dimension, #465), leaving the colony they were in ({@link ColonyManager#join}), if the source may
	 * change it ({@link ColonyActions#mayAct}, as a Bot Core checks).
	 */
	private static int join(CommandSourceStack source, List<AntBot> bots, BlockPos core, ServerLevel level) {
		if (!checkAny(source, bots)) {
			return 0;
		}
		ColonyManager colonies = ColonyManager.get(level);
		Optional<Colony> colony = colonies.byCore(core);
		if (colony.isEmpty()) {
			source.sendFailure(Component.translatable("commands.antcraft.bot.join.no_colony", core.toShortString()));
			return 0;
		}
		// The console and command blocks run as operators.
		ServerPlayer player = source.getPlayer();
		if (player != null && !ColonyActions.mayAct(colony.get(), player)) {
			source.sendFailure(Component.translatable("commands.antcraft.bot.join.not_allowed", core.toShortString()));
			return 0;
		}
		bots.forEach(bot -> colonies.join(bot, colony.get()));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.bot.join", bots.size(), core.toShortString()), false);
		return bots.size();
	}

	/** Takes {@code bots} out of their colony; their jobs there go back to the board. */
	private static int leave(CommandSourceStack source, List<AntBot> bots) {
		if (!checkAny(source, bots)) {
			return 0;
		}
		bots.forEach(bot -> ColonyManager.get((ServerLevel) bot.level()).leave(bot));
		source.sendSuccess(() -> Component.translatable("commands.antcraft.bot.leave", bots.size()), false);
		return bots.size();
	}

	/** The bots a {@code bot} subcommand acts on. */
	@FunctionalInterface
	private interface BotsArgument {
		List<AntBot> get(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;
	}

	private interface ScriptCommand {
		int run(CommandContext<CommandSourceStack> ctx, String name, List<String> args) throws CommandSyntaxException;
	}

	/** {@code <script> [args]}, suggesting the library's scripts, running {@code command} with the script's name and args. */
	private static RequiredArgumentBuilder<CommandSourceStack, String> scriptArgument(ScriptCommand command) {
		return scriptName(Commands.argument("script", StringArgumentType.string()))
			.executes(ctx -> command.run(ctx, StringArgumentType.getString(ctx, "script"), List.of()))
			.then(Commands.argument("args", StringArgumentType.greedyString())
				.executes(ctx -> command.run(ctx, StringArgumentType.getString(ctx, "script"),
					List.of(StringArgumentType.getString(ctx, "args").trim().split("\\s+")))));
	}

	/** {@code node}, suggesting the library's scripts. */
	private static RequiredArgumentBuilder<CommandSourceStack, String> scriptName(RequiredArgumentBuilder<CommandSourceStack, String> node) {
		return node.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
			ServerScripts.library().scripts().stream().map(ScriptLibrary.Script::name), builder));
	}

	/**
	 * Opens the script editor on the player's client with the library's script {@code name} and {@code log}, the last lines
	 * of the bot it was opened from (docs/features/scripts.md, Editing in game). Any player may look; only operators may
	 * save, and so only they may open a script the library does not have yet, to write it.
	 */
	private static int editScript(CommandSourceStack source, String name, List<String> log) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!ServerPlayNetworking.canSend(player, ScriptNetworking.OpenScriptEditor.TYPE)) {
			source.sendFailure(Component.translatable("commands.antcraft.script.no_client"));
			return 0;
		}
		boolean operator = isOperator(player);
		Optional<ScriptLibrary.Script> found = ServerScripts.library().get(name);
		if (found.isEmpty() && !operator) {
			source.sendFailure(Component.translatable("commands.antcraft.script.unknown", name));
			return 0;
		}
		String text = found.map(ScriptLibrary.Script::source).orElse("");
		if (text.length() > ScriptEdits.MAX_SOURCE_BYTES) {
			source.sendFailure(Component.translatable("commands.antcraft.script.too_large", name));
			return 0;
		}
		String named = found.map(ScriptLibrary.Script::name).orElse(name);
		ServerPlayNetworking.send(player, new ScriptNetworking.OpenScriptEditor(named, text, log, operator,
			ScriptNetworking.editorNames(ServerScripts.library())));
		return 1;
	}

	/** Opens the bots screen on the player's client with the bots they may control (docs/features/scripts.md, The bots screen). */
	private static int openBots(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (!ServerPlayNetworking.canSend(player, BotNetworking.BotList.TYPE)) {
			source.sendFailure(Component.translatable("commands.antcraft.bots.no_client"));
			return 0;
		}
		BotNetworking.BotList list = BotNetworking.list(player, Component.empty());
		ServerPlayNetworking.send(player, list);
		return list.bots().size();
	}

	/** Opens the script editor on the script the first of {@code bots} runs, with that bot's last log lines. */
	public static int editBotScript(CommandSourceStack source, List<AntBot> bots) throws CommandSyntaxException {
		if (!checkAny(source, bots)) {
			return 0;
		}
		AntBot bot = bots.getFirst();
		ScriptRun run = bot.getScript();
		if (run == null) {
			source.sendFailure(Component.translatable("commands.antcraft.bot.edit.none", bot.getName()));
			return 0;
		}
		List<String> log = bot.getLog().last(EDITOR_LOG_LINES, Output.Level.DEBUG).stream().map(BotLog.Entry::format).toList();
		return editScript(source, run.name(), log);
	}

	/**
	 * The library's script {@code name}, if it exists and compiles, as {@code bot run} and a Bot Core's script need it;
	 * otherwise {@code failure} gets why not.
	 */
	public static Optional<ScriptLibrary.Script> runnable(String name, Consumer<Component> failure) {
		Optional<ScriptLibrary.Script> found = ServerScripts.library().get(name);
		if (found.isEmpty()) {
			failure.accept(Component.translatable("commands.antcraft.script.unknown", name));
		} else if (found.get().code() == null) {
			failure.accept(Component.translatable("commands.antcraft.script.broken", name, found.get().error()));
			return Optional.empty();
		}
		return found;
	}

	/** Sets the script of the Bot Core in the player's main hand, or takes it off with a null {@code script}. */
	private static int setCoreScript(CommandSourceStack source, @Nullable CoreScript script) throws CommandSyntaxException {
		ItemStack stack = source.getPlayerOrException().getMainHandItem();
		if (!stack.is(ModItems.BOT_CORE)) {
			source.sendFailure(Component.translatable("commands.antcraft.core.no_core"));
			return 0;
		}
		if (script == null) {
			stack.remove(ModComponents.CORE_SCRIPT);
			source.sendSuccess(() -> Component.translatable("commands.antcraft.core.cleared"), false);
			return 1;
		}
		if (runnable(script.name(), source::sendFailure).isEmpty()) {
			return 0;
		}
		stack.set(ModComponents.CORE_SCRIPT, script);
		source.sendSuccess(() -> Component.translatable("commands.antcraft.core.script", script.toString()), false);
		return 1;
	}

	/** {@code run <script> [args]|stop|remove|join <core pos> [dimension]|leave|status|log [n] [level]} under {@code node}, acting on the bots {@code bots} gives. */
	private static <T extends ArgumentBuilder<CommandSourceStack, T>> T botCommands(T node, BotsArgument bots) {
		return node
			.then(Commands.literal("run")
				.then(scriptArgument((ctx, name, args) -> runScript(ctx.getSource(), bots.get(ctx), name, args))))
			.then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource(), bots.get(ctx))))
			.then(Commands.literal("remove").executes(ctx -> remove(ctx, bots)))
			.then(Commands.literal("join").then(Commands.argument("core", BlockPosArgument.blockPos())
				.executes(ctx -> join(ctx.getSource(), bots.get(ctx), BlockPosArgument.getBlockPos(ctx, "core"), ctx.getSource().getLevel()))
				.then(Commands.argument("dimension", DimensionArgument.dimension())
					.executes(ctx -> join(ctx.getSource(), bots.get(ctx), BlockPosArgument.getBlockPos(ctx, "core"),
						DimensionArgument.getDimension(ctx, "dimension"))))))
			.then(Commands.literal("leave").executes(ctx -> leave(ctx.getSource(), bots.get(ctx))))
			.then(Commands.literal("edit").executes(ctx -> editBotScript(ctx.getSource(), bots.get(ctx))))
			.then(Commands.literal("status").executes(ctx -> scriptStatus(ctx.getSource(), bots.get(ctx))))
			.then(Commands.literal("log")
				.executes(ctx -> showLog(ctx.getSource(), bots.get(ctx), DEFAULT_LOG_LINES, "debug"))
				.then(Commands.argument("n", IntegerArgumentType.integer(1, BotLog.CAPACITY))
					.executes(ctx -> showLog(ctx.getSource(), bots.get(ctx), IntegerArgumentType.getInteger(ctx, "n"), "debug"))
					.then(Commands.argument("level", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
							Stream.of(Output.Level.values()).map(level -> level.name().toLowerCase(Locale.ROOT)), builder))
						.executes(ctx -> showLog(ctx.getSource(), bots.get(ctx), IntegerArgumentType.getInteger(ctx, "n"),
							StringArgumentType.getString(ctx, "level"))))));
	}

	private static int listScripts(CommandSourceStack source) {
		ScriptLibrary library = ServerScripts.library();
		source.sendSuccess(() -> Component.translatable("commands.antcraft.script.list", library.scripts().size(),
			ServerScripts.configFolder().toString(), ServerScripts.worldFolder(source.getServer()).toString()), false);
		for (ScriptLibrary.Script script : library.scripts()) {
			String named = script.name() + " (" + ServerScripts.origin(source.getServer(), script) + ")";
			Component line = script.error() == null ? Component.literal(named)
				: Component.literal(named + ": " + script.error()).withStyle(ChatFormatting.RED);
			source.sendSuccess(() -> line, false);
		}
		return library.scripts().size();
	}

	private static int reloadScripts(CommandSourceStack source) {
		ServerScripts.Reload reload = ServerScripts.reload(source.getServer());
		ScriptLibrary library = reload.library();
		source.sendSuccess(() -> Component.translatable("commands.antcraft.script.reload", library.scripts().size(), library.errors().size(),
			reload.restarted()), true);
		for (ScriptLibrary.Script script : library.errors()) {
			source.sendFailure(Component.literal(script.error()));
		}
		return library.scripts().size();
	}

	public static int runScript(CommandSourceStack source, List<AntBot> bots, String name, List<String> args) {
		if (!checkAllowed(source) || !checkAny(source, bots)) {
			return 0;
		}
		Optional<ScriptLibrary.Script> found = runnable(name, source::sendFailure);
		if (found.isEmpty()) {
			return 0;
		}
		ScriptLibrary.Script script = found.get();
		ServerPlayer player = source.getPlayer();
		if (player != null && !PlayerLimits.mayStart(PlayerLimits.running(source.getServer(), player.getUUID(), bots), bots.size(),
			AntCraftConfig.maxScriptsPerPlayer(), OPERATOR.test(source) || PlayerLimits.exempt(source.getServer(), player.getUUID()))) {
			source.sendFailure(Component.translatable("commands.antcraft.bot.too_many", AntCraftConfig.maxScriptsPerPlayer()));
			return 0;
		}
		for (AntBot bot : bots) {
			if (player != null) {
				bot.setOwner(player.getUUID());
			}
			bot.runScript(script, args);
		}
		source.sendSuccess(() -> Component.translatable("commands.antcraft.bot.run", name, bots.size()), false);
		return bots.size();
	}

	private static int scriptStatus(CommandSourceStack source, List<AntBot> bots) {
		if (!checkAny(source, bots)) {
			return 0;
		}
		for (AntBot bot : bots) {
			source.sendSuccess(() -> statusLine(bot), false);
		}
		return bots.size();
	}

	/** A bot's script, whether it runs, the line it is at, its current action, its last action's result and how many instructions it ran. */
	public static Component statusLine(AntBot bot) {
		ScriptRun run = bot.getScript();
		if (run == null) {
			return Component.translatable("commands.antcraft.bot.status.none", bot.getName());
		}
		String action = run.action();
		String last = run.lastResult();
		return Component.translatable("commands.antcraft.bot.status", bot.getName(), run.name(), run.state().name().toLowerCase(Locale.ROOT),
			run.line(), action == null ? "-" : action, last == null ? "-" : last, run.executed());
	}

	private static int showLog(CommandSourceStack source, List<AntBot> bots, int n, String levelName) {
		Optional<Output.Level> level = Stream.of(Output.Level.values()).filter(value -> value.name().equalsIgnoreCase(levelName)).findFirst();
		if (level.isEmpty()) {
			source.sendFailure(Component.translatable("commands.antcraft.bot.log.level", levelName));
			return 0;
		}
		if (!checkAny(source, bots)) {
			return 0;
		}
		for (AntBot bot : bots) {
			List<BotLog.Entry> lines = bot.getLog().last(n, level.get());
			source.sendSuccess(() -> Component.translatable("commands.antcraft.bot.log", bot.getName(), lines.size()), false);
			for (BotLog.Entry entry : lines) {
				source.sendSuccess(() -> Component.literal(entry.format()), false);
			}
		}
		return bots.size();
	}

	private static boolean checkAllowed(CommandSourceStack source) {
		if (AntCraftConfig.botsAllowed(source.getServer())) {
			return true;
		}
		source.sendFailure(Component.translatable("message.antcraft.disabled"));
		return false;
	}

	/** Whether there are {@code bots}, all of which the source may control ({@link PlayerLimits#mayControl}); if not, says why. */
	public static boolean checkAny(CommandSourceStack source, List<AntBot> bots) {
		if (bots.isEmpty()) {
			source.sendFailure(Component.translatable("commands.antcraft.no_bots"));
			return false;
		}
		for (AntBot bot : bots) {
			if (!mayControl(source, bot)) {
				source.sendFailure(Component.translatable("commands.antcraft.bot.not_yours", bot.getName()));
				return false;
			}
		}
		return true;
	}

	/** Whether the source may use the {@code bot} commands on {@code bot}: the console and command blocks run as operators. */
	public static boolean mayControl(CommandSourceStack source, AntBot bot) {
		return mayControl(source, bot.getOwner());
	}

	/** Whether the source may use the {@code bot} commands on a bot owned by {@code owner}. */
	private static boolean mayControl(CommandSourceStack source, @Nullable UUID owner) {
		ServerPlayer player = source.getPlayer();
		return player == null || PlayerLimits.mayControl(owner, player.getUUID(), OPERATOR.test(source));
	}

	/** The nearest bot the source may control within {@link #NEAREST_RANGE}, so another player's bot nearer does not get in the way. */
	private static List<AntBot> nearestBot(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		Vec3 origin = source.getPosition();
		return level.getEntities(ModEntities.ANT_BOT, bot -> bot.isAlive() && bot.distanceToSqr(origin) <= NEAREST_RANGE * NEAREST_RANGE
				&& mayControl(source, bot))
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
