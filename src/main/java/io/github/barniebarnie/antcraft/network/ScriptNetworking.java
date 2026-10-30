package io.github.barniebarnie.antcraft.network;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.script.ScriptEdits;
import io.github.barniebarnie.antcraft.script.ServerScripts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Payloads between the client's script editor and the server (docs/features/scripts.md, Editing in game): the server
 * opens the editor with a script's source and a bot's log, and the client sends a save back, which the server checks
 * like any client input ({@link #save}).
 */
public final class ScriptNetworking {
	private static final StreamCodec<io.netty.buffer.ByteBuf, String> NAME = ByteBufCodecs.stringUtf8(ScriptEdits.MAX_NAME_LENGTH);
	// A character limit; ScriptEdits counts the bytes.
	private static final StreamCodec<io.netty.buffer.ByteBuf, String> SOURCE = ByteBufCodecs.stringUtf8(ScriptEdits.MAX_SOURCE_BYTES);

	private ScriptNetworking() {
	}

	/**
	 * Opens the editor on script {@code name} with its {@code source}, the last lines of the {@code log} of the bot it was
	 * opened from (none from {@code script edit}), and whether the player may save ({@code mayEdit}: operators).
	 */
	public record OpenScriptEditor(String name, String source, List<String> log, boolean mayEdit) implements CustomPacketPayload {
		public static final Type<OpenScriptEditor> TYPE = new Type<>(AntCraft.id("open_script_editor"));
		public static final StreamCodec<RegistryFriendlyByteBuf, OpenScriptEditor> STREAM_CODEC = StreamCodec.composite(
			NAME, OpenScriptEditor::name,
			SOURCE, OpenScriptEditor::source,
			ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenScriptEditor::log,
			ByteBufCodecs.BOOL, OpenScriptEditor::mayEdit,
			OpenScriptEditor::new).cast();

		public OpenScriptEditor {
			log = List.copyOf(log);
		}

		@Override
		public Type<OpenScriptEditor> type() {
			return TYPE;
		}
	}

	/** Client asks to save {@code source} as script {@code name}. */
	public record SaveScript(String name, String source) implements CustomPacketPayload {
		public static final Type<SaveScript> TYPE = new Type<>(AntCraft.id("save_script"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SaveScript> STREAM_CODEC = StreamCodec.composite(
			NAME, SaveScript::name,
			SOURCE, SaveScript::source,
			SaveScript::new).cast();

		@Override
		public Type<SaveScript> type() {
			return TYPE;
		}
	}

	/** The answer to a save: whether the script was {@code saved}, and a message that says so or why not. */
	public record ScriptSaved(boolean saved, Component message) implements CustomPacketPayload {
		public static final Type<ScriptSaved> TYPE = new Type<>(AntCraft.id("script_saved"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ScriptSaved> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, ScriptSaved::saved,
			ComponentSerialization.STREAM_CODEC, ScriptSaved::message,
			ScriptSaved::new);

		@Override
		public Type<ScriptSaved> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(OpenScriptEditor.TYPE, OpenScriptEditor.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SaveScript.TYPE, SaveScript.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ScriptSaved.TYPE, ScriptSaved.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(SaveScript.TYPE, (request, context) ->
			context.responseSender().sendPacket(save(context.player(), request)));
	}

	/**
	 * Saves a script the editor sent, if {@code player} is an operator and {@link ScriptEdits#check} takes its name and
	 * source: writes it to the world script folder, where it wins over a config folder or data pack script of the same
	 * name, and reloads the library, which restarts the bots running it.
	 */
	public static ScriptSaved save(ServerPlayer player, SaveScript request) {
		if (!AntCraftCommands.isOperator(player)) {
			return new ScriptSaved(false, Component.translatable("screen.antcraft.script_editor.not_operator"));
		}
		MinecraftServer server = player.level().getServer();
		Path file;
		try {
			file = ScriptEdits.check(ServerScripts.worldFolder(server), request.name(), request.source());
		} catch (IllegalArgumentException e) {
			return new ScriptSaved(false, Component.literal(e.getMessage()));
		}
		// ponytail: a symbolic link an admin put in the world folder is followed; check toRealPath if that matters.
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, request.source());
		} catch (IOException e) {
			AntCraft.LOGGER.warn("Cannot save the script {} to {}", request.name(), file, e);
			return new ScriptSaved(false, Component.translatable("screen.antcraft.script_editor.cannot_write", e.getMessage()));
		}
		AntCraft.LOGGER.info("{} saved the script {} to {}", player.getGameProfile().name(), request.name(), file);
		int restarted = ServerScripts.reload(server).restarted();
		return new ScriptSaved(true, Component.translatable("screen.antcraft.script_editor.saved", request.name(), restarted));
	}
}
