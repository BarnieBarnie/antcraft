package io.github.barniebarnie.antcraft.gametest;

import com.mojang.authlib.GameProfile;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.script.BotLog;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;

/**
 * Running scripts on bots in game tests: a script string on a test bot, an owner whose chat the test can read, and
 * commands whose output it can read. For the tests of each part of the bot API.
 */
public final class BotScripts {
	/** The file name scripts run by {@link #run} have in errors. */
	public static final String FILE = "test.py";

	private BotScripts() {
	}

	/** Compiles {@code source} as {@link #FILE} and starts it on {@code bot}; it runs from the next tick. */
	public static ScriptRun run(AntBot bot, String source, String... args) {
		bot.runScript("test", Compiler.compile(Parser.parse(FILE, source)), List.of(args));
		return Objects.requireNonNull(bot.getScript());
	}

	/** The texts of {@code bot}'s log lines at or above {@code level}. */
	public static List<String> log(AntBot bot, Output.Level level) {
		return bot.getLog().last(BotLog.CAPACITY, level).stream().map(BotLog.Entry::text).toList();
	}

	/** A message a {@link RecordingPlayer} got, at the game time it got it. */
	public record Message(long tick, String text) {
	}

	/** An online player that keeps the system messages it is sent, such as a bot's chat to its owner. */
	public static final class RecordingPlayer extends ServerPlayer {
		private final List<Message> messages = new ArrayList<>();

		RecordingPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation info) {
			super(server, level, profile, info);
		}

		@Override
		public void sendSystemMessage(Component message) {
			messages.add(new Message(this.level().getGameTime(), message.getString()));
		}

		@Override
		public void sendSystemMessage(Component message, boolean overlay) {
			sendSystemMessage(message);
		}

		/** As the game test framework's mock player: creative, so mobs leave it alone. */
		@Override
		public GameType gameMode() {
			return GameType.CREATIVE;
		}

		public List<Message> messages() {
			return messages;
		}
	}

	/**
	 * Logs a {@link RecordingPlayer} in, as the game test framework's mock players are, and makes it {@code bot}'s owner.
	 * It leaves when the test ends.
	 */
	public static RecordingPlayer owner(GameTestHelper helper, AntBot bot) {
		ServerLevel level = helper.getLevel();
		MinecraftServer server = level.getServer();
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "test-owner"), false);
		RecordingPlayer player = new RecordingPlayer(server, level, cookie.gameProfile(), cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		bot.setOwner(player.getUUID());
		GameTests.onEnd(helper, () -> server.getPlayerList().remove(player));
		return player;
	}

	/** Runs {@code command} (without the slash) as an operator in the test's level, and gives every line it answered with. */
	public static List<String> command(GameTestHelper helper, String command) {
		List<String> lines = new ArrayList<>();
		CommandSource recorder = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				lines.add(text(message));
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
		MinecraftServer server = helper.getLevel().getServer();
		CommandSourceStack source = server.createCommandSourceStack().withLevel(helper.getLevel()).withSource(recorder);
		server.getCommands().performPrefixedCommand(source, command);
		return lines;
	}

	/**
	 * {@code message} as text with the arguments of a translated message spelled out, whether or not the server has the
	 * mod's lang file: {@code key[arg, arg]}.
	 */
	private static String text(Component message) {
		if (message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable) {
			List<String> args = new ArrayList<>();
			for (Object arg : translatable.getArgs()) {
				args.add(arg instanceof Component component ? text(component) : String.valueOf(arg));
			}
			return translatable.getKey() + args;
		}
		return message.getString();
	}
}
