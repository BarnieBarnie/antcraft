package io.github.barniebarnie.antcraft.network;

import io.github.barniebarnie.antcraft.AntCraft;
import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.BuildGhost;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyActions;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.ColonyStatus;
import io.github.barniebarnie.antcraft.colony.PlacedBlueprint;
import io.github.barniebarnie.antcraft.colony.SiteRules;
import io.github.barniebarnie.antcraft.command.AntCraftCommands;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Payloads between the client (the colony status screen, the blueprint picker) and the server. Each payload is a record here,
 * registered in {@link #register()}; the server answers for the nearest colony the player may view.
 * Changes are checked by the server like any client input, and answered with a fresh status.
 */
public final class ColonyNetworking {
	/** How often, in ticks, the server looks whether a player's ghosts changed. */
	static final int GHOST_INTERVAL = 20;

	/** The ghosts each player was sent last, so they are only sent again once they change. */
	private static final Map<UUID, BuildGhosts> SENT_GHOSTS = new ConcurrentHashMap<>();

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

	/** Client holding a Blueprint asks for the placement rules of the nearest colony it may place for, to tint the ghost. */
	public record SiteRulesRequest() implements CustomPacketPayload {
		public static final Type<SiteRulesRequest> TYPE = new Type<>(AntCraft.id("site_rules_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SiteRulesRequest> STREAM_CODEC = StreamCodec.unit(new SiteRulesRequest());

		@Override
		public Type<SiteRulesRequest> type() {
			return TYPE;
		}
	}

	/** The placement rules of the nearest colony the player may place for, or empty when there is none in range. */
	public record SiteRulesReply(Optional<SiteRules> rules) implements CustomPacketPayload {
		public static final Type<SiteRulesReply> TYPE = new Type<>(AntCraft.id("site_rules"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SiteRulesReply> STREAM_CODEC =
			ByteBufCodecs.optional(SiteRules.STREAM_CODEC).map(SiteRulesReply::new, SiteRulesReply::rules).cast();

		@Override
		public Type<SiteRulesReply> type() {
			return TYPE;
		}
	}

	/**
	 * What is left to build of the player-placed builds of the nearest colony the player may act on, sent while it changes
	 * (see {@link #sendGhosts}); empty when there is none in range.
	 */
	public record BuildGhosts(List<BuildGhost> builds) implements CustomPacketPayload {
		public static final Type<BuildGhosts> TYPE = new Type<>(AntCraft.id("build_ghosts"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BuildGhosts> STREAM_CODEC =
			BuildGhost.STREAM_CODEC.apply(ByteBufCodecs.list()).map(BuildGhosts::new, BuildGhosts::builds);

		public BuildGhosts {
			builds = List.copyOf(builds);
		}

		@Override
		public Type<BuildGhosts> type() {
			return TYPE;
		}
	}

	/** Client asks to cancel the placed build of project {@code project} of the colony with its core at {@code core}, clicked as a ghost. */
	public record CancelBuild(BlockPos core, UUID project) implements CustomPacketPayload {
		public static final Type<CancelBuild> TYPE = new Type<>(AntCraft.id("cancel_build"));
		public static final StreamCodec<RegistryFriendlyByteBuf, CancelBuild> STREAM_CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, CancelBuild::core,
			UUIDUtil.STREAM_CODEC, CancelBuild::project,
			CancelBuild::new);

		@Override
		public Type<CancelBuild> type() {
			return TYPE;
		}
	}

	/** The blueprints the server loaded, sent to each player on joining and after every data reload. */
	public record BlueprintList(List<BlueprintSummary> blueprints) implements CustomPacketPayload {
		public static final Type<BlueprintList> TYPE = new Type<>(AntCraft.id("blueprint_list"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintList> STREAM_CODEC =
			BlueprintSummary.STREAM_CODEC.apply(ByteBufCodecs.list()).map(BlueprintList::new, BlueprintList::blueprints);

		public BlueprintList {
			blueprints = List.copyOf(blueprints);
		}

		/** The blueprints {@code server} has loaded. */
		public static BlueprintList of(MinecraftServer server) {
			return new BlueprintList(BlueprintSummary.all(server));
		}

		@Override
		public Type<BlueprintList> type() {
			return TYPE;
		}
	}

	/** Client asks to set the Blueprint item in {@code hand} to blueprint {@code blueprint}, chosen in the picker. */
	public record ChooseBlueprint(InteractionHand hand, Identifier blueprint) implements CustomPacketPayload {
		public static final Type<ChooseBlueprint> TYPE = new Type<>(AntCraft.id("choose_blueprint"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ChooseBlueprint> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.idMapper(i -> InteractionHand.values()[i], InteractionHand::ordinal), ChooseBlueprint::hand,
			Identifier.STREAM_CODEC, ChooseBlueprint::blueprint,
			ChooseBlueprint::new);

		@Override
		public Type<ChooseBlueprint> type() {
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
		PayloadTypeRegistry.serverboundPlay().register(ChooseBlueprint.TYPE, ChooseBlueprint.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SiteRulesRequest.TYPE, SiteRulesRequest.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SiteRulesReply.TYPE, SiteRulesReply.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(StatusReply.TYPE, StatusReply.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BlueprintList.TYPE, BlueprintList.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BuildGhosts.TYPE, BuildGhosts.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CancelBuild.TYPE, CancelBuild.STREAM_CODEC);
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (server.getTickCount() % GHOST_INTERVAL == 0) {
				server.getPlayerList().getPlayers().forEach(player -> sendGhosts(player, false));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SENT_GHOSTS.remove(handler.getPlayer().getUUID()));
		ServerPlayNetworking.registerGlobalReceiver(CancelBuild.TYPE, (request, context) -> {
			ServerPlayer player = context.player();
			player.sendOverlayMessage(handle(player.level(), Requester.of(player), request).map(
				refusal -> refusal.copy().withStyle(ChatFormatting.RED),
				cancelled -> Component.translatable("commands.antcraft.colony.build.ghost_cancelled", cancelled.displayName())));
			// The ghost goes at once rather than at the next look.
			sendGhosts(player, true);
		});
		// On joining and after every data reload, so the picker lists what the server has loaded now.
		ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> {
			if (ServerPlayNetworking.canSend(player, BlueprintList.TYPE)) {
				ServerPlayNetworking.send(player, BlueprintList.of(player.level().getServer()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ChooseBlueprint.TYPE, (request, context) -> handle(context.player(), request));
		ServerPlayNetworking.registerGlobalReceiver(SiteRulesRequest.TYPE, (request, context) ->
			context.responseSender().sendPacket(siteRules(context.player().level(), Requester.of(context.player()))));
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
			// On the action bar, where the player looks while placing.
			player.sendOverlayMessage(handle(player.level(), requester, request).map(
				refusal -> refusal.message().copy().withStyle(ChatFormatting.RED),
				placed -> Component.translatable("commands.antcraft.colony.build.placed", placed.displayName(), placed.at().toShortString(),
					ColonyManager.get(player.level()).byCore(request.core()).map(colony -> colony.builds().size()).orElse(0))));
			context.responseSender().sendPacket(reply(player.level(), requester));
			// The new build takes room, so the ghost turns red over it at once.
			context.responseSender().sendPacket(siteRules(player.level(), requester));
		});
	}

	private static Optional<Colony> colony(ServerLevel level, Requester requester, BlockPos core) {
		Optional<Colony> colony = ColonyManager.get(level).byCore(core);
		colony.ifPresent(requester::claim);
		return colony.filter(requester::mayAct);
	}

	/**
	 * Sets the Blueprint item in the hand the request names to the chosen blueprint, unless the player holds no Blueprint
	 * there or the server has no blueprint by that id.
	 *
	 * @return whether the item was set
	 */
	public static boolean handle(ServerPlayer player, ChooseBlueprint request) {
		return BlueprintItem.choose(player.getItemInHand(request.hand()), request.blueprint());
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
	 * Sends {@code player} the ghosts of the nearest colony they may act on, if their client takes them and they changed
	 * since the last were sent, or always if {@code force}.
	 */
	private static void sendGhosts(ServerPlayer player, boolean force) {
		if (!ServerPlayNetworking.canSend(player, BuildGhosts.TYPE)) {
			return;
		}
		BuildGhosts ghosts = ghosts(player.level(), Requester.of(player));
		BuildGhosts last = SENT_GHOSTS.put(player.getUUID(), ghosts);
		if (force || !ghosts.equals(last)) {
			ServerPlayNetworking.send(player, ghosts);
		}
	}

	/**
	 * The ghosts for {@code requester}: what is left to build of the player-placed builds of the nearest colony in range
	 * they may act on ({@link ColonyActions#mayAct}, as for {@link #siteRules}), or none.
	 */
	public static BuildGhosts ghosts(ServerLevel level, Requester requester) {
		return new BuildGhosts(ColonyManager.get(level).nearest(requester.from(), AntCraftCommands.COLONY_RANGE, requester::mayAct)
			.map(colony -> BuildGhost.of(colony, level::getBlockState))
			.orElse(List.of()));
	}

	/**
	 * Cancels a placed build for {@code requester}, as {@code /antcraft colony build cancel} does: refused with the reason
	 * unless there is a colony at the request's core, the requester may act on it, and it is still building a blueprint
	 * placed with that project.
	 *
	 * @return the reason it was refused, or the cancelled build
	 */
	public static Either<Component, PlacedBlueprint> handle(ServerLevel level, Requester requester, CancelBuild request) {
		Optional<Colony> colony = ColonyManager.get(level).byCore(request.core());
		if (colony.isEmpty()) {
			return Either.left(BlueprintPlacement.Refusal.NO_COLONY.message());
		}
		if (!ColonyActions.mayEdit(ColonyActions.Edit.BUILD_CANCEL, requester.botsAllowed()) || !requester.mayAct(colony.get())) {
			return Either.left(Component.translatable("commands.antcraft.colony.build.cancel.not_allowed"));
		}
		Optional<PlacedBlueprint> placed = colony.get().blueprints().stream()
			.filter(blueprint -> !blueprint.finished() && blueprint.project().equals(request.project()))
			.findFirst();
		if (placed.isEmpty()) {
			return Either.left(Component.translatable("commands.antcraft.colony.build.cancel.gone"));
		}
		colony.get().cancelProject(request.project());
		return Either.right(placed.get());
	}

	/**
	 * The placement rules for {@code requester}: those of the nearest colony in range they may act on, so may place
	 * blueprints for ({@link ColonyActions#mayAct}), or none.
	 */
	public static SiteRulesReply siteRules(ServerLevel level, Requester requester) {
		return new SiteRulesReply(ColonyManager.get(level).nearest(requester.from(), AntCraftCommands.COLONY_RANGE, requester::mayAct).map(SiteRules::of));
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
