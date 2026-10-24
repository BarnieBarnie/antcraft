package io.github.barniebarnie.antcraft.network;

import io.github.barniebarnie.antcraft.AntCraft;
import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyActions;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.colony.PlacedBlueprint;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Payloads between the colony status screen and the server. Each payload is a record here,
 * registered in {@link #register()}; the server answers for the nearest colony the player may view.
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

	/** Client asks to place blueprint {@code blueprint} for the colony with its core at {@code core}; see {@link BlueprintPlacement#place}. */
	public record PlaceBlueprint(BlockPos core, Identifier blueprint, BlockPos at, Rotation rotation, Mirror mirror) implements CustomPacketPayload {
		public static final Type<PlaceBlueprint> TYPE = new Type<>(AntCraft.id("place_blueprint"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PlaceBlueprint> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, PlaceBlueprint::core,
			Identifier.STREAM_CODEC, PlaceBlueprint::blueprint,
			BlockPos.STREAM_CODEC, PlaceBlueprint::at,
			ByteBufCodecs.idMapper(i -> Rotation.values()[i], Rotation::ordinal), PlaceBlueprint::rotation,
			ByteBufCodecs.idMapper(i -> Mirror.values()[i], Mirror::ordinal), PlaceBlueprint::mirror,
			PlaceBlueprint::new);

		@Override
		public Type<PlaceBlueprint> type() {
			return TYPE;
		}
	}

	/**
	 * Who sent a request and what they may do: the player and their position, whether they are an operator, whether only
	 * operators may act on colonies, and whether bots are allowed on the server. Built once per received payload.
	 */
	public record Requester(UUID player, BlockPos from, boolean isOp, boolean requireOp, boolean botsAllowed) {
		static Requester of(ServerPlayer player) {
			return new Requester(player.getUUID(), player.blockPosition(), ColonyActions.isOp(player), AntCraftConfig.colonyActionsRequireOp(),
				AntCraftConfig.botsAllowed(player.level().getServer()));
		}

		/** {@link ColonyActions#mayAct} for this requester. */
		public boolean mayAct(Colony colony) {
			return ColonyActions.mayAct(colony, this.player, this.from, this.isOp, this.requireOp);
		}

		/** {@link ColonyActions#claim}s {@code colony} for this requester if it has no owner yet. */
		void claim(Colony colony) {
			ColonyActions.claim(colony, this.player, this.from, this.isOp, this.requireOp);
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(StatusRequest.TYPE, StatusRequest.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EditRequest.TYPE, EditRequest.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PlaceBlueprint.TYPE, PlaceBlueprint.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StatusReply.TYPE, StatusReply.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(StatusRequest.TYPE, (request, context) -> {
			ServerPlayer player = context.player();
			context.responseSender().sendPacket(reply(player.level(), Requester.of(player)));
		});
		ServerPlayNetworking.registerGlobalReceiver(EditRequest.TYPE, (request, context) -> {
			ServerPlayer player = context.player();
			Requester requester = Requester.of(player);
			handle(player.level(), requester, request);
			context.responseSender().sendPacket(reply(player.level(), requester));
		});
		ServerPlayNetworking.registerGlobalReceiver(PlaceBlueprint.TYPE, (request, context) -> {
			ServerPlayer player = context.player();
			Requester requester = Requester.of(player);
			player.sendSystemMessage(handle(player.level(), requester, request).map(
				refusal -> refusal.message().copy().withStyle(ChatFormatting.RED),
				placed -> Component.translatable("commands.antcraft.colony.build.placed", placed.displayName(), placed.at().toShortString(),
					ColonyManager.get(player.level()).byCore(request.core()).map(colony -> colony.builds().size()).orElse(0))));
			context.responseSender().sendPacket(reply(player.level(), requester));
		});
	}

	private static Optional<Colony> colony(ServerLevel level, Requester requester, BlockPos core) {
		Optional<Colony> colony = ColonyManager.get(level).byCore(core);
		colony.ifPresent(requester::claim);
		return colony.filter(requester::mayAct);
	}

	/**
	 * Runs an edit from {@code requester}, unless they may not change the colony, the edit is invalid, or it may
	 * not be made with bots allowed as {@link Requester#botsAllowed} says ({@link ColonyActions#mayEdit}, as for the
	 * {@code /antcraft colony} commands).
	 *
	 * @return whether the colony changed
	 */
	public static boolean handle(ServerLevel level, Requester requester, EditRequest request) {
		if (!ColonyActions.mayEdit(request.kind(), requester.botsAllowed())) {
			return false;
		}
		return colony(level, requester, request.core())
			.map(colony -> ColonyActions.edit(colony, request.kind(), request.target(), request.count()))
			.orElse(false);
	}

	/**
	 * Places a blueprint for {@code requester}, the same way {@code /antcraft colony build place} does
	 * ({@link BlueprintPlacement#place}): refused with the reason unless there is a colony at the request's core, the
	 * requester may act on it, and the site is free. A colony with no owner yet becomes the requester's if they may act on it.
	 */
	public static Either<BlueprintPlacement.Refusal, PlacedBlueprint> handle(ServerLevel level, Requester requester, PlaceBlueprint request) {
		Optional<Colony> colony = ColonyManager.get(level).byCore(request.core());
		if (colony.isEmpty()) {
			return Either.left(BlueprintPlacement.Refusal.NO_COLONY);
		}
		requester.claim(colony.get());
		boolean mayAct = ColonyActions.mayEdit(ColonyActions.Edit.BUILD_PLACE, requester.botsAllowed()) && requester.mayAct(colony.get());
		return BlueprintPlacement.place(level, colony.get(), mayAct, request.blueprint(), request.at(), request.rotation(), request.mirror());
	}

	/**
	 * The reply to a status request from {@code requester}: the nearest colony in range the player may view, or none
	 * if there is none ({@link ColonyActions#mayView}, the same rule as for changes). A colony with no owner yet becomes the
	 * requester's if they may act on it ({@link ColonyActions#claim}).
	 */
	public static StatusReply reply(ServerLevel level, Requester requester) {
		Optional<Colony> nearest = ColonyManager.get(level).nearest(requester.from(), AntCraftCommands.COLONY_RANGE,
			colony -> ColonyActions.mayView(colony, requester.player(), requester.from(), requester.isOp(), requester.requireOp()));
		nearest.ifPresent(requester::claim);
		return new StatusReply(nearest.map(colony -> ColonyStatus.of(level, colony)));
	}
}
