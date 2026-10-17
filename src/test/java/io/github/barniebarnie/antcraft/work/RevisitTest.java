package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RevisitTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void savesTheSpotsAndSaplingKind() {
		BlockPos base = new BlockPos(4, 1, 4);
		List<BlockPos> spots = List.of(base, base.east(), base.south(), base.south().east());
		Revisit revisit = new Revisit(base, 1234L, spots, Optional.of(Items.DARK_OAK_SAPLING));

		var json = Revisit.CODEC.encodeStart(JsonOps.INSTANCE, revisit).getOrThrow();
		Revisit loaded = Revisit.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(revisit, loaded);
	}

	@Test
	void oldSavesWithOnlyTheBaseLoadAsOneSpotAnySapling() {
		JsonObject old = JsonParser.parseString("{\"Pos\": [4, 1, 4], \"Felled\": 1234}").getAsJsonObject();

		Revisit loaded = Revisit.CODEC.parse(JsonOps.INSTANCE, old).getOrThrow();

		BlockPos base = new BlockPos(4, 1, 4);
		assertEquals(new Revisit(base, 1234L, List.of(base), Optional.empty()), loaded);
	}
}
