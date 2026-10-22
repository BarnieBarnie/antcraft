package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColonyFieldTest {
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
			Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	@Test
	void fieldSurvivesCodecRoundTrip() {
		Colony colony = colony();
		colony.setField(new Field(new BlockPos(4, 63, -7)));

		assertEquals(Optional.of(new Field(new BlockPos(4, 63, -7))), roundTrip(colony).field());
	}

	@Test
	void coloniesWithoutFieldLoad() {
		assertTrue(roundTrip(colony()).field().isEmpty());
	}

	@Test
	void fieldIsNotChosenOverAnUnfinishedPlan() {
		Colony colony = colony();
		// Flat soil at y=-1 everywhere: the nearest field is where a plan-less colony would lay it out.
		Field nearest = Field.choose(BlockPos.ZERO, colony.radius(), colony.occupied(), pos -> pos.getY() == -1, pos -> pos.getY() >= 0).orElseThrow();
		BlockPos at = nearest.area().getCenter();
		colony.addBuild(Project.HOUSE, new BuildPlan(List.of(new BuildPlan.Step(at, Blocks.OAK_PLANKS.defaultBlockState()))));

		Field field = Field.choose(BlockPos.ZERO, colony.radius(), colony.occupied(), pos -> pos.getY() == -1, pos -> pos.getY() >= 0).orElseThrow();

		assertFalse(field.area().isInside(at));
	}
}
