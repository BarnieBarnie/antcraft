package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The client's copy of a colony's placement rules refuses what the server refuses, for the same reason. */
class SiteRulesTest {
	private static final Identifier HUT = Identifier.fromNamespaceAndPath("antcraft", "hut");
	private static final Blueprint BLUEPRINT = new Blueprint(HUT, "blueprint.antcraft.hut", Optional.empty(), List.of(), List.of());
	private static final Vec3i SIZE = new Vec3i(3, 2, 4);
	private static final BlockPos AT = new BlockPos(8, 64, 8);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	private static BoundingBox area(BlockPos at) {
		return new BoundingBox(at.getX(), at.getY(), at.getZ(), at.getX() + SIZE.getX() - 1, at.getY() + SIZE.getY() - 1, at.getZ() + SIZE.getZ() - 1);
	}

	/** Checks the hut at {@code at} on a colony set up by {@code setUp}, by the rules and by the server, and returns the rules' answer. */
	private static Optional<BlueprintPlacement.Refusal> check(Consumer<Colony> setUp, BlockPos at) {
		Colony colony = colony();
		setUp.accept(colony);
		Optional<BlueprintPlacement.Refusal> server = BlueprintPlacement.site(colony, HUT, Optional.of(BLUEPRINT), Optional.of(SIZE), at, Rotation.NONE, Mirror.NONE).left();
		Optional<BlueprintPlacement.Refusal> rules = SiteRules.of(colony).check(area(at));
		assertEquals(server, rules, "the client and the server disagree");
		return rules;
	}

	@Test
	void aFreeSiteIsAllowed() {
		assertEquals(Optional.empty(), check(colony -> {
		}, AT));
	}

	@Test
	void aSiteOutsideTheRadiusIsRefused() {
		assertEquals(Optional.of(BlueprintPlacement.Refusal.OUTSIDE), check(colony -> {
		}, new BlockPos(Colony.DEFAULT_RADIUS - 1, 64, 0)));
	}

	@Test
	void aSiteOverTheCoreIsRefused() {
		assertEquals(Optional.of(BlueprintPlacement.Refusal.OVERLAPS_CORE), check(colony -> {
		}, new BlockPos(-1, 60, -1)));
	}

	@Test
	void aSiteOverTheMineshaftIsRefused() {
		assertEquals(Optional.of(BlueprintPlacement.Refusal.OVERLAPS_MINESHAFT), check(colony -> colony.setMineshaft(
			new Mineshaft(new BlockPos(10, 64, 10), List.of(60), 1, 3, 4, Mineshaft.DEFAULT_ROUNDS, Mineshaft.Layout.STAIRCASE)), AT));
	}

	@Test
	void aSiteOverABuildOrBlocksBuildersPlacedIsRefused() {
		assertEquals(Optional.of(BlueprintPlacement.Refusal.OVERLAPS_BUILD), check(colony ->
			colony.addBuild(new BuildPlan(List.of(new BuildPlan.Step(AT.offset(2, 5, 3), Blocks.STONE.defaultBlockState())))), AT));
		assertEquals(Optional.of(BlueprintPlacement.Refusal.OVERLAPS_BUILD), check(colony -> colony.protect(AT.offset(1, -9, 1), Blocks.OAK_PLANKS), AT));
	}

	@Test
	void theRulesTravelToTheClient() {
		Colony colony = colony();
		colony.setMineshaft(new Mineshaft(new BlockPos(10, 64, 10), List.of(60), 1, 3, 4, Mineshaft.DEFAULT_ROUNDS, Mineshaft.Layout.STAIRCASE));
		colony.addBuild(new BuildPlan(List.of(new BuildPlan.Step(AT.offset(2, 5, 3), Blocks.STONE.defaultBlockState()))));
		colony.protect(AT.offset(1, 0, 1), Blocks.OAK_PLANKS);
		colony.protect(AT.offset(1, 3, 1), Blocks.OAK_PLANKS);
		SiteRules rules = SiteRules.of(colony);
		// Blocks builders placed matter by their column only.
		assertEquals(List.of(new BlockPos(9, 0, 9)), rules.placedColumns());

		ByteBuf buf = Unpooled.buffer();
		SiteRules.STREAM_CODEC.encode(buf, rules);
		assertEquals(rules, SiteRules.STREAM_CODEC.decode(buf));
	}
}
