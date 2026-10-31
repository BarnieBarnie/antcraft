package io.github.barniebarnie.antcraft.network;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.BotLog;
import io.github.barniebarnie.antcraft.script.ScriptEdits;
import io.github.barniebarnie.antcraft.script.ScriptLibrary;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.ServerScripts;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Payloads of the bots screen (docs/features/scripts.md, The bots screen): the client asks for the bots its player may
 * control ({@link RequestBots}, or {@code /antcraft bots}), and acts on one of them ({@link BotAction}). The server
 * treats both as untrusted: an action goes through the same checks as the {@code bot} command it stands for.
 */
public final class BotNetworking {
	/** The most bots {@link BotList} carries; operators on a busy server see the first, by name. */
	public static final int MAX_BOTS = 256;
	/** The most characters of a bot's name, dimension, script args and state. */
	public static final int MAX_TEXT = 64;
	/** The most characters of {@link BotList#message}; a longer one, such as a long compile error, is cut. */
	public static final int MAX_MESSAGE = 256;
	/** The most items {@link BotStatus} carries: a bot's inventory is smaller. */
	private static final int MAX_ITEMS = 64;

	private static final StreamCodec<io.netty.buffer.ByteBuf, String> TEXT = ByteBufCodecs.stringUtf8(MAX_TEXT);
	private static final StreamCodec<io.netty.buffer.ByteBuf, String> NAME = ByteBufCodecs.stringUtf8(ScriptEdits.MAX_NAME_LENGTH);

	private BotNetworking() {
	}

	/**
	 * One bot in the list: its name, UUID, where it is, the script it runs with its args (empty for none) and that
	 * script's state ({@code running}, {@code finished}, {@code failed}, {@code stopped}, or {@code none}).
	 */
	public record BotEntry(String name, UUID id, BlockPos pos, String dimension, String script, String args, String state) {
		public static final StreamCodec<io.netty.buffer.ByteBuf, BotEntry> STREAM_CODEC = StreamCodec.composite(
			TEXT, BotEntry::name,
			UUIDUtil.STREAM_CODEC, BotEntry::id,
			BlockPos.STREAM_CODEC, BotEntry::pos,
			TEXT, BotEntry::dimension,
			NAME, BotEntry::script,
			TEXT, BotEntry::args,
			TEXT, BotEntry::state,
			BotEntry::new);
	}

	/** The client asks for {@link BotList}. */
	public record RequestBots() implements CustomPacketPayload {
		public static final Type<RequestBots> TYPE = new Type<>(AntCraft.id("request_bots"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestBots> STREAM_CODEC = StreamCodec.unit(new RequestBots());

		@Override
		public Type<RequestBots> type() {
			return TYPE;
		}
	}

	/**
	 * Opens the bots screen, or updates it: the {@code bots} the player may control, the library's {@code scripts} to
	 * pick from, and the answer to the last action ({@code message}, empty for none).
	 */
	public record BotList(List<BotEntry> bots, List<String> scripts, Component message) implements CustomPacketPayload {
		public static final Type<BotList> TYPE = new Type<>(AntCraft.id("bot_list"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BotList> STREAM_CODEC = StreamCodec.composite(
			BotEntry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_BOTS)), BotList::bots,
			NAME.apply(ByteBufCodecs.list(ScriptNetworking.MAX_NAMES)), BotList::scripts,
			ComponentSerialization.STREAM_CODEC, BotList::message,
			BotList::new);

		public BotList {
			bots = List.copyOf(bots);
			scripts = List.copyOf(scripts);
		}

		@Override
		public Type<BotList> type() {
			return TYPE;
		}
	}

	/** What the screen asks of one bot, standing for {@code bot run}, {@code stop}, {@code edit} and {@code status}. */
	public enum Action {
		RUN, STOP, EDIT, STATUS
	}

	/** The client asks to do {@code action} on {@code bot}; {@code script} and {@code args} are for {@link Action#RUN}. */
	public record BotAction(UUID bot, Action action, String script, String args) implements CustomPacketPayload {
		public static final Type<BotAction> TYPE = new Type<>(AntCraft.id("bot_action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BotAction> STREAM_CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, BotAction::bot,
			ByteBufCodecs.idMapper(i -> Action.values()[Math.clamp(i, 0, Action.values().length - 1)], Action::ordinal), BotAction::action,
			NAME, BotAction::script,
			TEXT, BotAction::args,
			BotAction::new).cast();

		@Override
		public Type<BotAction> type() {
			return TYPE;
		}
	}

	/** A bot's {@code status} line (as {@code bot status} has it), the items it carries and its last log lines. */
	public record BotStatus(UUID bot, Component status, List<ItemStack> inventory, List<String> log) implements CustomPacketPayload {
		public static final Type<BotStatus> TYPE = new Type<>(AntCraft.id("bot_status"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BotStatus> STREAM_CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, BotStatus::bot,
			ComponentSerialization.STREAM_CODEC, BotStatus::status,
			ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_ITEMS)), BotStatus::inventory,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(AntCraftCommands.EDITOR_LOG_LINES)), BotStatus::log,
			BotStatus::new);

		public BotStatus {
			inventory = List.copyOf(inventory);
			log = List.copyOf(log);
		}

		@Override
		public Type<BotStatus> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(RequestBots.TYPE, RequestBots.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BotList.TYPE, BotList.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BotAction.TYPE, BotAction.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BotStatus.TYPE, BotStatus.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(RequestBots.TYPE, (request, context) ->
			context.responseSender().sendPacket(list(context.player(), Component.empty())));
		ServerPlayNetworking.registerGlobalReceiver(BotAction.TYPE, (request, context) -> {
			CustomPacketPayload reply = handle(context.player(), request);
			if (reply != null) {
				context.responseSender().sendPacket(reply);
			}
		});
	}

	/** {@code text}, cut to at most {@code max} characters, as a codec of that limit takes it. */
	static String cap(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}

	/** The bots {@code player} may control in every level ({@link AntCraftCommands#mayControl}), by name, with {@code message}. */
	public static BotList list(ServerPlayer player, Component message) {
		CommandSourceStack source = player.createCommandSourceStack();
		List<BotEntry> bots = new ArrayList<>();
		for (ServerLevel level : player.level().getServer().getAllLevels()) {
			for (AntBot bot : level.getEntities(ModEntities.ANT_BOT, bot -> bot.isAlive() && AntCraftCommands.mayControl(source, bot))) {
				ScriptRun run = bot.getScript();
				bots.add(new BotEntry(cap(bot.getName().getString(), MAX_TEXT), bot.getUUID(), bot.blockPosition(),
					cap(level.dimension().identifier().toString(), MAX_TEXT),
					run == null ? "" : cap(run.name(), ScriptEdits.MAX_NAME_LENGTH),
					run == null ? "" : cap(String.join(" ", run.args()), MAX_TEXT),
					run == null ? "none" : run.state().name().toLowerCase(Locale.ROOT)));
			}
		}
		List<String> scripts = ServerScripts.library().scripts().stream().map(ScriptLibrary.Script::name)
			.filter(name -> name.length() <= ScriptEdits.MAX_NAME_LENGTH).sorted().limit(ScriptNetworking.MAX_NAMES).toList();
		return new BotList(bots.stream().sorted(Comparator.comparing(BotEntry::name)).limit(MAX_BOTS).toList(), scripts,
			message.getString().length() <= MAX_MESSAGE ? message : Component.literal(cap(message.getString(), MAX_MESSAGE)).withStyle(message.getStyle()));
	}

	/**
	 * Does what {@code request} asks of a bot, as the {@code bot} command it stands for does when {@code player} runs it,
	 * with the same checks and answers: the bot list again with that answer, the bot's status, or nothing where the
	 * script editor opened ({@link Action#EDIT}).
	 */
	public static @Nullable CustomPacketPayload handle(ServerPlayer player, BotAction request) {
		List<Component> said = new ArrayList<>();
		CommandSourceStack source = player.createCommandSourceStack().withSource(recorder(said));
		AntBot bot = find(player.level().getServer(), request.bot());
		List<AntBot> bots = bot == null ? List.of() : List.of(bot);
		switch (request.action()) {
			case RUN -> {
				String args = request.args().trim();
				AntCraftCommands.runScript(source, bots, request.script(), args.isEmpty() ? List.of() : List.of(args.split("\\s+")));
			}
			case STOP -> AntCraftCommands.stop(source, bots);
			case EDIT -> {
				try {
					if (AntCraftCommands.editBotScript(source, bots) > 0) {
						return null;
					}
				} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
					said.add(Component.literal(e.getMessage()));
				}
			}
			case STATUS -> {
				if (AntCraftCommands.checkAny(source, bots)) {
					List<ItemStack> items = bot.getInventory().getItems().stream().filter(stack -> !stack.isEmpty()).limit(MAX_ITEMS)
						.map(ItemStack::copy).toList();
					List<String> log = bot.getLog().last(AntCraftCommands.EDITOR_LOG_LINES, Output.Level.DEBUG).stream()
						.map(BotLog.Entry::format).toList();
					return new BotStatus(bot.getUUID(), AntCraftCommands.statusLine(bot), items, log);
				}
			}
		}
		return list(player, said.isEmpty() ? Component.empty() : said.getLast());
	}

	/** The living bot {@code id} in any level, if there is one. */
	private static @Nullable AntBot find(MinecraftServer server, UUID id) {
		for (ServerLevel level : server.getAllLevels()) {
			Entity entity = level.getEntity(id);
			if (entity instanceof AntBot bot && bot.isAlive()) {
				return bot;
			}
		}
		return null;
	}

	/** A command source that keeps what it is told in {@code said}, to answer the screen with. */
	private static CommandSource recorder(List<Component> said) {
		return new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				said.add(message);
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
	}
}
