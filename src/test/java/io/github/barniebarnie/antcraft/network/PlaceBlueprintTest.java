package io.github.barniebarnie.antcraft.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

class PlaceBlueprintTest {
	private static ColonyNetworking.PlaceBlueprint roundTrip(ColonyNetworking.PlaceBlueprint request) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ColonyNetworking.PlaceBlueprint.STREAM_CODEC.encode(buf, request);
		return ColonyNetworking.PlaceBlueprint.STREAM_CODEC.decode(buf);
	}

	@Test
	void aPlacementRequestCarriesItsPalette() {
		ColonyNetworking.PlaceBlueprint chosen = new ColonyNetworking.PlaceBlueprint(new BlockPos(1, 64, 2), Identifier.parse("antcraft:hut"),
			new BlockPos(5, 64, 5), Rotation.CLOCKWISE_90, Mirror.FRONT_BACK, OptionalInt.of(1));
		assertEquals(chosen, roundTrip(chosen));
		assertEquals(OptionalInt.of(1), roundTrip(chosen).palette());

		// Without one, the blueprint's own palette is built.
		ColonyNetworking.PlaceBlueprint own = new ColonyNetworking.PlaceBlueprint(new BlockPos(1, 64, 2), Identifier.parse("antcraft:hut"),
			new BlockPos(5, 64, 5), Rotation.NONE, Mirror.NONE);
		assertEquals(OptionalInt.empty(), own.palette());
		assertEquals(own, roundTrip(own));
	}
}
