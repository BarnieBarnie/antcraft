package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.work.StorageArea;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColonyStorageTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Colony roundTrip(Colony colony) {
		return Colony.CODEC.parse(JsonOps.INSTANCE, Colony.CODEC.encodeStart(JsonOps.INSTANCE, colony).getOrThrow()).getOrThrow();
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), BlockPos.ZERO, List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(), Optional.empty(), DefaultStock.TARGETS, Map.of(), List.of(),
			Optional.empty(), Optional.empty(), Map.of(), List.of());
	}

	@Test
	void storageAreaSurvivesCodecRoundTrip() {
		Colony colony = colony();
		colony.setStorage(new StorageArea(new BlockPos(5, 64, -3)));

		assertEquals(Optional.of(new StorageArea(new BlockPos(5, 64, -3))), roundTrip(colony).storage());
	}

	@Test
	void coloniesWithoutStorageLoad() {
		assertTrue(roundTrip(colony()).storage().isEmpty());
	}
}
