package io.github.barniebarnie.antcraft.network;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;

/**
 * Payloads between the colony status screen and the server. Each payload is a record here,
 * registered in {@link #register()}; the server answers for the colony nearest the player.
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

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(StatusRequest.TYPE, StatusRequest.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StatusReply.TYPE, StatusReply.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(StatusRequest.TYPE,
			(request, context) -> context.responseSender().sendPacket(reply(context.player().level(), context.player().blockPosition())));
	}

	/** The reply to a status request from {@code pos}. */
	public static StatusReply reply(ServerLevel level, BlockPos pos) {
		return new StatusReply(ColonyManager.get(level).nearest(pos, AntCraftCommands.COLONY_RANGE).map(colony -> ColonyStatus.of(level, colony)));
	}
}
