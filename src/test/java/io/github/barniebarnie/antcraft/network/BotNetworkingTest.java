package io.github.barniebarnie.antcraft.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.ScriptEdits;
import io.github.barniebarnie.antcraft.work.VanillaData;
import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The payloads of the bots screen (docs/features/scripts.md, The bots screen). */
class BotNetworkingTest {
	/** Vanilla's limit on a custom payload the server sends (ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE). */
	private static final int MAX_PAYLOAD_BYTES = 1_048_576;

	@BeforeAll
	static void bootstrap() {
		// Item stacks need their components bound, which happens once the registries are frozen.
		VanillaData.recipeBook();
	}

	private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		codec.encode(buf, value);
		T read = codec.decode(buf);
		assertEquals(0, buf.readableBytes());
		return read;
	}

	@Test
	void thePayloadsRoundTrip() {
		BotNetworking.BotEntry entry = new BotNetworking.BotEntry("Ant 1a2b", UUID.randomUUID(), new BlockPos(1, -60, 3),
			"minecraft:overworld", "miner", "12 deep", "running");
		BotNetworking.BotList list = new BotNetworking.BotList(List.of(entry), List.of("miner", "lib.trees"), Component.literal("Started"));
		assertEquals(list, roundTrip(BotNetworking.BotList.STREAM_CODEC, list));

		for (BotNetworking.Action action : BotNetworking.Action.values()) {
			BotNetworking.BotAction request = new BotNetworking.BotAction(entry.id(), action, "miner", "12 deep");
			assertEquals(request, roundTrip(BotNetworking.BotAction.STREAM_CODEC, request));
		}

		BotNetworking.BotStatus status = new BotNetworking.BotStatus(entry.id(), Component.literal("Ant 1a2b: miner"),
			List.of(new ItemStack(Items.COBBLESTONE, 40), new ItemStack(Items.IRON_PICKAXE)), List.of("[5 print] hi"));
		BotNetworking.BotStatus read = roundTrip(BotNetworking.BotStatus.STREAM_CODEC, status);
		assertEquals(status.status(), read.status());
		assertEquals(status.log(), read.log());
		assertEquals(2, read.inventory().size());
		assertTrue(ItemStack.matches(status.inventory().get(0), read.inventory().get(0)), read.inventory().toString());
		assertTrue(ItemStack.matches(status.inventory().get(1), read.inventory().get(1)), read.inventory().toString());

		assertEquals(new BotNetworking.RequestBots(), roundTrip(BotNetworking.RequestBots.STREAM_CODEC, new BotNetworking.RequestBots()));
	}

	@Test
	void theLargestListFitsTheLimit() {
		// Every string at its longest, in three-byte characters.
		BotNetworking.BotEntry entry = new BotNetworking.BotEntry("€".repeat(BotNetworking.MAX_TEXT), UUID.randomUUID(), BlockPos.ZERO,
			"€".repeat(BotNetworking.MAX_TEXT), "€".repeat(ScriptEdits.MAX_NAME_LENGTH), "€".repeat(BotNetworking.MAX_TEXT), "€".repeat(BotNetworking.MAX_TEXT));
		BotNetworking.BotList largest = new BotNetworking.BotList(Collections.nCopies(BotNetworking.MAX_BOTS, entry),
			Collections.nCopies(ScriptNetworking.MAX_NAMES, "€".repeat(ScriptEdits.MAX_NAME_LENGTH)), Component.literal("€".repeat(BotNetworking.MAX_MESSAGE)));
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		BotNetworking.BotList.STREAM_CODEC.encode(buf, largest);
		assertTrue(buf.readableBytes() < MAX_PAYLOAD_BYTES, buf.readableBytes() + " bytes");
	}

	@Test
	void textIsCutToItsLimit() {
		assertEquals("abc", BotNetworking.cap("abc", 3));
		assertEquals("ab", BotNetworking.cap("abcdef", 2));
	}
}
