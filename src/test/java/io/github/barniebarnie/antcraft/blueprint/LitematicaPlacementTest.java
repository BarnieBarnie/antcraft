package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.blueprint.LitematicaPlacement.Region;
import io.github.barniebarnie.antcraft.network.BlueprintNetworking.PlacementPart;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A Litematica placement the client reads, sent as payload parts (docs/features/blueprints.md, Litematica placements). */
class LitematicaPlacementTest {
	private static final BlockPos ORIGIN = new BlockPos(10, 64, -3);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Region asSaved(String name) {
		return new Region(name, new BlockPos(1, 0, 2), new BlockPos(1, 0, 2), Rotation.NONE, Mirror.NONE, true);
	}

	private static String key(Component message) {
		return ((TranslatableContents) message.getContents()).getKey();
	}

	private static Object arg(Component message, int i) {
		return ((TranslatableContents) message.getContents()).getArgs()[i];
	}

	/** The placement's name, origin, rotation and mirror go with its schematic file, split over parts. */
	@Test
	void sendsTheSchematicWithOriginRotationAndMirror() {
		LitematicaPlacement placement = new LitematicaPlacement("house", ORIGIN, Rotation.CLOCKWISE_90, Mirror.FRONT_BACK,
			List.of(asSaved("main"), asSaved("roof")));
		byte[] file = new byte[PlacementPart.PART_SIZE + 5];
		file[PlacementPart.PART_SIZE + 4] = 7;
		List<PlacementPart> sent = new ArrayList<>();
		Component message = placement.send(file, sent::add);
		assertEquals("message.antcraft.placement.sending", key(message));
		assertEquals("house", arg(message, 0));
		assertEquals(2, sent.size());
		ByteArrayOutputStream joined = new ByteArrayOutputStream();
		for (PlacementPart part : sent) {
			assertEquals("house", part.name());
			assertEquals(ORIGIN, part.origin());
			assertEquals(Rotation.CLOCKWISE_90, part.rotation());
			assertEquals(Mirror.FRONT_BACK, part.mirror());
			joined.writeBytes(part.bytes());
		}
		assertArrayEquals(file, joined.toByteArray());
	}

	/** A sub-region moved, turned, mirrored or switched off on its own is not where the schematic saved it: refused. */
	@Test
	void refusesAPlacementWithChangedSubRegions() {
		List<Region> changed = List.of(
			new Region("moved", new BlockPos(1, 1, 2), new BlockPos(1, 0, 2), Rotation.NONE, Mirror.NONE, true),
			new Region("turned", BlockPos.ZERO, BlockPos.ZERO, Rotation.CLOCKWISE_180, Mirror.NONE, true),
			new Region("mirrored", BlockPos.ZERO, BlockPos.ZERO, Rotation.NONE, Mirror.LEFT_RIGHT, true),
			new Region("off", BlockPos.ZERO, BlockPos.ZERO, Rotation.NONE, Mirror.NONE, false));
		for (Region region : changed) {
			List<PlacementPart> sent = new ArrayList<>();
			Component message = new LitematicaPlacement("house", ORIGIN, Rotation.NONE, Mirror.NONE, List.of(asSaved("main"), region))
				.send(new byte[10], sent::add);
			assertEquals("message.antcraft.placement.refused", key(message), region.name());
			assertTrue(arg(message, 1).toString().contains(region.name()), arg(message, 1).toString());
			assertTrue(sent.isEmpty(), region.name());
		}
	}

	/** A schematic not saved to a file, and a name too long for the payload, are refused before anything is sent. */
	@Test
	void refusesAnUnsavedSchematicAndATooLongName() {
		List<PlacementPart> sent = new ArrayList<>();
		Component unsaved = new LitematicaPlacement("house", ORIGIN, Rotation.NONE, Mirror.NONE, List.of(asSaved("main")))
			.send(null, sent::add);
		assertEquals("message.antcraft.placement.refused", key(unsaved));
		String name = "x".repeat(PlacementPart.NAME_LENGTH + 1);
		Component tooLong = new LitematicaPlacement(name, ORIGIN, Rotation.NONE, Mirror.NONE, List.of(asSaved("main")))
			.send(new byte[10], sent::add);
		assertEquals("message.antcraft.placement.refused", key(tooLong));
		assertTrue(sent.isEmpty());
	}
}
