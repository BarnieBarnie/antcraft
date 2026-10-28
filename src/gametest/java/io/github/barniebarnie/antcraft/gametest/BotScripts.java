package io.github.barniebarnie.antcraft.gametest;

import com.mojang.authlib.GameProfile;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.script.BotLog;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Compiler;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.script.lang.Parser;
import io.github.barniebarnie.antcraft.script.ServerScripts;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

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

	/**
	 * Writes {@code source} as a new script in the world's script folder, named {@code prefix} and a random suffix, and
	 * reloads the script folders. The file is deleted when the test ends.
	 *
	 * @return the script's name
	 */
	public static String libraryScript(GameTestHelper helper, String prefix, String source) {
		String name = prefix + UUID.randomUUID().toString().substring(0, 8);
		Path script = ServerScripts.worldFolder(helper.getLevel().getServer()).resolve(name + ".py");
		GameTests.onEnd(helper, () -> {
			try {
				Files.deleteIfExists(script);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		try {
			Files.createDirectories(script.getParent());
			Files.writeString(script, source);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		command(helper, "antcraft script reload");
		return name;
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
		return command(helper, null, command);
	}

	/** As {@link #command(GameTestHelper, String)}, with {@code as} (if not null) as the entity running it, such as a player. */
	public static List<String> command(GameTestHelper helper, @Nullable Entity as, String command) {
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
		if (as != null) {
			source = source.withEntity(as);
		}
		server.getCommands().performPrefixedCommand(source, command);
		return lines;
	}

	/**
	 * {@code message} as text with the arguments of a translated message spelled out, whether or not the server has the
	 * mod's lang file: {@code key[arg, arg]}.
	 */
	static String text(Component message) {
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
