package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.SiteRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PreviewRefusalTest {
	private static final BlockPos AT = new BlockPos(8, 64, 8);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static BlueprintBlocks blueprint() {
		return new BlueprintBlocks(Identifier.fromNamespaceAndPath("antcraft", "step"), new Vec3i(3, 1, 2),
			List.of(new BlueprintSummary.Block(BlockPos.ZERO, Blocks.STONE.defaultBlockState())));
	}

	private static SiteRules rules() {
		return new SiteRules(new BlockPos(0, 64, 0), 32, Optional.empty(), Optional.empty(), List.of(), List.of());
	}

	@Test
	void theRefusalIsWorkedOutOncePerPlacementChange() {
		List<BlueprintGhost> checked = new ArrayList<>();
		PreviewRefusal refusal = new PreviewRefusal((ghost, rules) -> {
			checked.add(ghost);
			return ghost.refusal(rules);
		});
		BlueprintBlocks blueprint = blueprint();
		// The preview makes its ghost anew each frame: an equal ghost is not checked again.
		for (int frame = 0; frame < 3; frame++) {
			assertEquals(Optional.of(BlueprintPlacement.Refusal.NO_COLONY),
				refusal.get(new BlueprintGhost(blueprint, AT, Rotation.NONE, Mirror.NONE), Optional.empty()));
		}
		assertEquals(1, checked.size());
		// Moved, turned, mirrored: checked again, once each.
		BlueprintGhost moved = new BlueprintGhost(blueprint, AT.east(), Rotation.NONE, Mirror.NONE);
		refusal.get(moved, Optional.empty());
		refusal.get(moved, Optional.empty());
		BlueprintGhost turned = new BlueprintGhost(blueprint, AT.east(), Rotation.CLOCKWISE_90, Mirror.NONE);
		refusal.get(turned, Optional.empty());
		BlueprintGhost mirrored = new BlueprintGhost(blueprint, AT.east(), Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT);
		refusal.get(mirrored, Optional.empty());
		assertEquals(4, checked.size());
		// New rules from the server: checked again, but rules sent again unchanged are not.
		assertEquals(Optional.empty(), refusal.get(mirrored, Optional.of(rules())));
		assertEquals(Optional.empty(), refusal.get(mirrored, Optional.of(rules())));
		assertEquals(5, checked.size());
		// Another blueprint at the same spot is checked too.
		BlueprintBlocks wide = new BlueprintBlocks(Identifier.fromNamespaceAndPath("antcraft", "wide"), new Vec3i(40, 1, 1), List.of());
		assertEquals(Optional.of(BlueprintPlacement.Refusal.OUTSIDE),
			refusal.get(new BlueprintGhost(wide, AT.east(), Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT), Optional.of(rules())));
		assertEquals(6, checked.size());
	}
}
