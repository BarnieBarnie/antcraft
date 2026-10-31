package io.github.barniebarnie.antcraft.network;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.blueprint.BlueprintBlocks;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.blueprint.Placements;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * Payloads between the client's blueprint picker and preview and the server. Each payload is a record here, registered in
 * {@link #register()}. Requests are checked by the server like any client input.
 */
public final class BlueprintNetworking {
	/**
	 * The most bytes the game takes in one custom payload to a client
	 * ({@code ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE}); a larger one disconnects the player.
	 */
	public static final int PAYLOAD_LIMIT = 1_048_576;

	private BlueprintNetworking() {
	}

	/**
	 * Client previewing blueprint {@code blueprint} asks for its blocks, which the list leaves out so it stays small
	 * however many and large the blueprints are.
	 */
	public record BlueprintBlocksRequest(Identifier blueprint) implements CustomPacketPayload {
		public static final Type<BlueprintBlocksRequest> TYPE = new Type<>(AntCraft.id("blueprint_blocks_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintBlocksRequest> STREAM_CODEC =
			Identifier.STREAM_CODEC.map(BlueprintBlocksRequest::new, BlueprintBlocksRequest::blueprint).cast();

		@Override
		public Type<BlueprintBlocksRequest> type() {
			return TYPE;
		}
	}

	/**
	 * Part {@code part} of the {@code parts} the blocks of the blueprint a client asked for come in
	 * ({@link BlueprintBlocks#parts()}): all of them in one for most structures, which the client puts back together; none,
	 * with a zero size, for one the server does not have.
	 */
	public record BlueprintBlocksReply(BlueprintBlocks blocks, int part, int parts) implements CustomPacketPayload {
		public static final Type<BlueprintBlocksReply> TYPE = new Type<>(AntCraft.id("blueprint_blocks"));
		public static final StreamCodec<RegistryFriendlyByteBuf, BlueprintBlocksReply> STREAM_CODEC = StreamCodec.composite(
			BlueprintBlocks.STREAM_CODEC, BlueprintBlocksReply::blocks,
			ByteBufCodecs.VAR_INT, BlueprintBlocksReply::part,
			ByteBufCodecs.VAR_INT, BlueprintBlocksReply::parts,
			BlueprintBlocksReply::new);

		/** The replies that send {@code blocks}, one per part. */
		public static List<BlueprintBlocksReply> of(BlueprintBlocks blocks) {
			List<BlueprintBlocks> parts = blocks.parts();
			return IntStream.range(0, parts.size()).mapToObj(i -> new BlueprintBlocksReply(parts.get(i), i, parts.size())).toList();
		}

		@Override
		public Type<BlueprintBlocksReply> type() {
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
	 * Part {@code part} of the {@code parts} a client sends a Litematica placement in: the bytes of its {@code .litematic}
	 * file, split by {@link #of}, and, the same in each part, the placement's {@code name}, its {@code origin} and its
	 * {@code rotation} and {@code mirror} as Litematica has them (docs/features/blueprints.md, Litematica placements). The
	 * server keeps it for the player's bots ({@link Placements#receive}).
	 */
	public record PlacementPart(String name, BlockPos origin, Rotation rotation, Mirror mirror, int part, int parts, byte[] bytes)
		implements CustomPacketPayload {
		/** The most file bytes in one part, under the 32767 bytes the game takes in a payload from a client. */
		public static final int PART_SIZE = 30_000;
		public static final int NAME_LENGTH = 256;
		public static final Type<PlacementPart> TYPE = new Type<>(AntCraft.id("placement_part"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PlacementPart> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeUtf(payload.name(), NAME_LENGTH);
				buf.writeBlockPos(payload.origin());
				buf.writeEnum(payload.rotation());
				buf.writeEnum(payload.mirror());
				buf.writeVarInt(payload.part());
				buf.writeVarInt(payload.parts());
				buf.writeByteArray(payload.bytes());
			},
			buf -> new PlacementPart(buf.readUtf(NAME_LENGTH), buf.readBlockPos(), buf.readEnum(Rotation.class), buf.readEnum(Mirror.class),
				buf.readVarInt(), buf.readVarInt(), buf.readByteArray(PART_SIZE)));

		/** The parts that send {@code file}, the bytes of a {@code .litematic}, as placement {@code name}. */
		public static List<PlacementPart> of(String name, BlockPos origin, Rotation rotation, Mirror mirror, byte[] file) {
			int parts = Math.max(1, (file.length + PART_SIZE - 1) / PART_SIZE);
			return IntStream.range(0, parts).mapToObj(i -> new PlacementPart(name, origin, rotation, mirror, i, parts,
				Arrays.copyOfRange(file, i * PART_SIZE, Math.min(file.length, (i + 1) * PART_SIZE)))).toList();
		}

		@Override
		public Type<PlacementPart> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(PlacementPart.TYPE, PlacementPart.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(PlacementPart.TYPE, (part, context) -> handle(context.player(), part));
		PayloadTypeRegistry.serverboundPlay().register(ChooseBlueprint.TYPE, ChooseBlueprint.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BlueprintList.TYPE, BlueprintList.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(BlueprintBlocksRequest.TYPE, BlueprintBlocksRequest.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BlueprintBlocksReply.TYPE, BlueprintBlocksReply.STREAM_CODEC);
		// On joining and after every data reload, so the picker lists what the server has loaded now.
		ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> {
			if (ServerPlayNetworking.canSend(player, BlueprintList.TYPE)) {
				ServerPlayNetworking.send(player, BlueprintList.of(player.level().getServer()));
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(ChooseBlueprint.TYPE, (request, context) -> handle(context.player(), request));
		ServerPlayNetworking.registerGlobalReceiver(BlueprintBlocksRequest.TYPE, (request, context) ->
			blocks(context.player().level().getServer(), request).forEach(reply -> context.responseSender().sendPacket(reply)));
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

	/** Takes part of a placement {@code player} sent, in their dimension, and tells them once it is received or refused. */
	public static void handle(ServerPlayer player, PlacementPart part) {
		Component reply = Placements.receive(player.getUUID(), player.level().dimension().identifier().toString(), part);
		if (reply != null) {
			player.sendSystemMessage(reply);
		}
	}

	/**
	 * The replies to a request for a blueprint's blocks: those of its structure as {@code server} has it now, in as many
	 * parts as they take ({@link BlueprintBlocksReply#of}); none for a blueprint or structure it does not have, and none but
	 * the size for one too large to send even in parts. Any player may ask, as any player gets the list.
	 */
	public static List<BlueprintBlocksReply> blocks(MinecraftServer server, BlueprintBlocksRequest request) {
		return BlueprintBlocksReply.of(Blueprints.get(request.blueprint())
			.map(blueprint -> BlueprintBlocks.of(request.blueprint(), Blueprints.template(server, blueprint), blueprint.palette()))
			.orElse(new BlueprintBlocks(request.blueprint(), Vec3i.ZERO, List.of())));
	}
}
