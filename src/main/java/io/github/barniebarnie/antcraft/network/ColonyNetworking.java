package io.github.barniebarnie.antcraft.network;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyActions;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Payloads between the colony status screen and the server. Each payload is a record here,
 * registered in {@link #register()}; the server answers for the colony nearest the player.
 * Changes are checked by the server like any client input, and answered with a fresh status.
 */
public final class ColonyNetworking {
	private ColonyNetworking() {
	}

	/** Client asks for the status of the colony nearest the player. */
	public record StatusRequest() implements CustomPacketPayload {
		public static final Type<StatusRequest> TYPE = new Type<>(AntCraft.id("colony_status_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StatusRequest> STREAM_CODEC = StreamCodec.unit(new StatusRequest());

		@Override
		public Type<StatusRequest> type() {
			return TYPE;
		}
	}

	/** The status of the nearest colony, or empty when there is no colony in range. */
	public record StatusReply(Optional<ColonyStatus> status) implements CustomPacketPayload {
		public static final Type<StatusReply> TYPE = new Type<>(AntCraft.id("colony_status"));
		public static final StreamCodec<RegistryFriendlyByteBuf, StatusReply> STREAM_CODEC =
			ByteBufCodecs.optional(ColonyStatus.STREAM_CODEC).map(StatusReply::new, StatusReply::status);

		@Override
		public Type<StatusReply> type() {
			return TYPE;
		}
	}

	/** Client asks to change the colony with its core at {@code core}; see {@link ColonyActions#edit}. */
	public record EditRequest(BlockPos core, ColonyActions.Edit kind, String target, int count) implements CustomPacketPayload {
		public static final Type<EditRequest> TYPE = new Type<>(AntCraft.id("colony_edit"));
		public static final StreamCodec<RegistryFriendlyByteBuf, EditRequest> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, EditRequest::core,
			ByteBufCodecs.idMapper(i -> ColonyActions.Edit.values()[i], ColonyActions.Edit::ordinal), EditRequest::kind,
			ByteBufCodecs.stringUtf8(256), EditRequest::target,
			ByteBufCodecs.VAR_INT, EditRequest::count,
			EditRequest::new);

		@Override
		public Type<EditRequest> type() {
			return TYPE;
		}
	}

	/** Client asks to give a member bot a role, or with an empty role to hand it back to the colony. */
	public record RoleRequest(BlockPos core, UUID bot, Optional<BotRole> role) implements CustomPacketPayload {
		public static final Type<RoleRequest> TYPE = new Type<>(AntCraft.id("colony_role"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RoleRequest> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, RoleRequest::core,
			UUIDUtil.STREAM_CODEC, RoleRequest::bot,
			ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(32).map(BotRole::byId, BotRole::id)), RoleRequest::role,
			RoleRequest::new);

		@Override
		public Type<RoleRequest> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(StatusRequest.TYPE, StatusRequest.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditRequest.TYPE, EditRequest.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RoleRequest.TYPE, RoleRequest.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StatusReply.TYPE, StatusReply.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(StatusRequest.TYPE,
			(request, context) -> context.responseSender().sendPacket(reply(context.player().level(), context.player().blockPosition())));
		ServerPlayNetworking.registerGlobalReceiver(EditRequest.TYPE, (request, context) -> {
			ServerPlayer player = context.player();
			handle(player.level(), player.blockPosition(), isOp(player), AntCraftConfig.colonyActionsRequireOp(), request);
			context.responseSender().sendPacket(reply(player.level(), player.blockPosition()));
		});
		ServerPlayNetworking.registerGlobalReceiver(RoleRequest.TYPE, (request, context) -> {
			ServerPlayer player = context.player();
			handle(player.level(), player.blockPosition(), isOp(player), AntCraftConfig.colonyActionsRequireOp(),
				AntCraftConfig.botsAllowed(player.level().getServer()), request);
			context.responseSender().sendPacket(reply(player.level(), player.blockPosition()));
		});
	}

	/** The same level the {@code /antcraft} commands need. */
	private static boolean isOp(ServerPlayer player) {
		return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
	}

	private static Optional<Colony> colony(ServerLevel level, BlockPos from, boolean isOp, boolean requireOp, BlockPos core) {
		return ColonyManager.get(level).byCore(core).filter(colony -> ColonyActions.mayAct(colony, from, isOp, requireOp));
	}

	/**
	 * Runs an edit from a player at {@code from}, unless they may not change the colony or the edit is invalid.
	 *
	 * @return whether the colony changed
	 */
	public static boolean handle(ServerLevel level, BlockPos from, boolean isOp, boolean requireOp, EditRequest request) {
		return colony(level, from, isOp, requireOp, request.core())
			.map(colony -> ColonyActions.edit(colony, request.kind(), request.target(), request.count()))
			.orElse(false);
	}

	/**
	 * Sets or releases a member's role for a player at {@code from}, unless they may not change the colony, the bot is not
	 * its member, or bots are not allowed on the server ({@code botsAllowed}, as for {@code /antcraft role}).
	 *
	 * @return whether the bot changed
	 */
	public static boolean handle(ServerLevel level, BlockPos from, boolean isOp, boolean requireOp, boolean botsAllowed, RoleRequest request) {
		Optional<Colony> colony = colony(level, from, isOp, requireOp, request.core());
		if (colony.isEmpty() || !(level.getEntity(request.bot()) instanceof AntBot bot)
				|| !ColonyActions.mayGiveRole(colony.get(), bot.getUUID(), bot.getColonyId(), botsAllowed)) {
			return false;
		}
		request.role().ifPresentOrElse(bot::setRole, bot::releaseRole);
		return true;
	}

	/** The reply to a status request from {@code pos}. */
	public static StatusReply reply(ServerLevel level, BlockPos pos) {
		return new StatusReply(ColonyManager.get(level).nearest(pos, AntCraftCommands.COLONY_RANGE).map(colony -> ColonyStatus.of(level, colony)));
	}
}
