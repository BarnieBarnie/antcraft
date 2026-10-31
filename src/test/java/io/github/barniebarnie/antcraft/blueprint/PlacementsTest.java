package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.network.BlueprintNetworking.PlacementPart;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Litematica placements a client sends (docs/features/blueprints.md, Litematica placements). */
class PlacementsTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static BlockState stairs;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
	}

	private static CompoundTag vec(int x, int y, int z) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("x", x);
		tag.putInt("y", y);
		tag.putInt("z", z);
		return tag;
	}

	/** A region at {@code position} of {@code size}, its entries packed 2 bits each into one long. */
	private static CompoundTag region(BlockPos position, BlockPos size, List<BlockState> palette, int... entries) {
		CompoundTag region = new CompoundTag();
		region.put("Position", vec(position.getX(), position.getY(), position.getZ()));
		region.put("Size", vec(size.getX(), size.getY(), size.getZ()));
		ListTag states = new ListTag();
		palette.forEach(state -> states.add(NbtUtils.writeBlockState(state)));
		region.put("BlockStatePalette", states);
		long packed = 0;
		for (int i = 0; i < entries.length; i++) {
			packed |= (long) entries[i] << (2 * i);
		}
		region.putLongArray("BlockStates", new long[] {packed});
		return region;
	}

	private static byte[] file(CompoundTag... regions) throws IOException {
		CompoundTag root = new CompoundTag();
		root.putInt("MinecraftDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
		CompoundTag named = new CompoundTag();
		for (int i = 0; i < regions.length; i++) {
			named.put("R" + i, regions[i]);
		}
		root.put("Regions", named);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		NbtIo.writeCompressed(root, out);
		return out.toByteArray();
	}

	/**
	 * A region not at the schematic's origin, 2 x 2 x 1 from (1, 0, 2): stone at (1, 0, 2), stairs facing east at (2, 0, 2),
	 * air at (1, 1, 2), stone at (2, 1, 2).
	 */
	private static byte[] step() throws IOException {
		return file(region(new BlockPos(1, 0, 2), new BlockPos(2, 2, 1),
			List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), stairs), 1, 2, 0, 1));
	}

	/** Litematica's {@code PositionUtils.getTransformedBlockPos}: the mirror first, then the rotation, about the schematic's origin. */
	private static BlockPos litematica(BlockPos pos, Mirror mirror, Rotation rotation) {
		int x = pos.getX();
		int y = pos.getY();
		int z = pos.getZ();
		switch (mirror) {
			case LEFT_RIGHT -> z = -z;
			case FRONT_BACK -> x = -x;
			default -> {
			}
		}
		return switch (rotation) {
			case CLOCKWISE_90 -> new BlockPos(-z, y, x);
			case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, -x);
			case CLOCKWISE_180 -> new BlockPos(-x, y, -z);
			default -> new BlockPos(x, y, z);
		};
	}

	private static Set<BuildPlan.Step> steps(BuildPlan plan) {
		return plan.steps().stream().map(step -> new BuildPlan.Step(step.pos(), step.state())).collect(Collectors.toSet());
	}

	/** Every mirror with every rotation puts each block where Litematica does, origin plus its transformed position, mirrored then turned. */
	@Test
	void placesBlocksAsLitematicaDoesForEveryMirrorAndRotation() throws IOException {
		BlockPos origin = new BlockPos(100, 64, -40);
		Map<BlockPos, BlockState> schematic = Map.of(new BlockPos(1, 0, 2), Blocks.STONE.defaultBlockState(), new BlockPos(2, 0, 2), stairs,
			new BlockPos(1, 1, 2), Blocks.AIR.defaultBlockState(), new BlockPos(2, 1, 2), Blocks.STONE.defaultBlockState());
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				Set<BuildPlan.Step> expected = new HashSet<>();
				schematic.forEach((pos, state) ->
					expected.add(new BuildPlan.Step(origin.offset(litematica(pos, mirror, rotation)), state.mirror(mirror).rotate(rotation))));
				Placements.Placement placement = Placements.Placement.of(Litematic.read(new ByteArrayInputStream(step())), origin, mirror, rotation, OVERWORLD);
				assertEquals(expected, steps(placement.plan().orElseThrow()), mirror + " " + rotation);
			}
		}
	}

	private static String key(Component message) {
		return ((TranslatableContents) message.getContents()).getKey();
	}

	/** A file too large for one payload is split over several, which put back together are the file. */
	@Test
	void splitsAFileOverSeveralParts() {
		byte[] file = new byte[PlacementPart.PART_SIZE * 2 + 1];
		new Random(1).nextBytes(file);
		List<PlacementPart> parts = PlacementPart.of("big", BlockPos.ZERO, Rotation.NONE, Mirror.NONE, file);
		assertEquals(3, parts.size());
		ByteArrayOutputStream joined = new ByteArrayOutputStream();
		for (int i = 0; i < parts.size(); i++) {
			assertEquals(i, parts.get(i).part());
			assertEquals(3, parts.get(i).parts());
			joined.writeBytes(parts.get(i).bytes());
		}
		assertArrayEquals(file, joined.toByteArray());
	}

	/** A received placement is the player's alone, under its name, until the player leaves. */
	@Test
	void receivesAPlacementForThePlayerAlone() throws IOException {
		UUID player = UUID.randomUUID();
		Component reply = Placements.receive(player, OVERWORLD,
			PlacementPart.of("house", new BlockPos(5, 6, 7), Rotation.CLOCKWISE_90, Mirror.FRONT_BACK, step()).getFirst());
		assertEquals("message.antcraft.placement.received", key(assertNotNull(reply)));
		assertTrue(Placements.get(player, "house").isPresent());
		assertTrue(Placements.get(UUID.randomUUID(), "house").isEmpty());
		Placements.forget(player);
		assertTrue(Placements.get(player, "house").isEmpty());
	}

	private static Component assertNotNull(Component message) {
		org.junit.jupiter.api.Assertions.assertNotNull(message);
		return message;
	}

	/** The parts before the last are taken in silence; a part out of order is refused. */
	@Test
	void refusesPartsOutOfOrder() throws IOException {
		UUID player = UUID.randomUUID();
		List<PlacementPart> parts = PlacementPart.of("big", BlockPos.ZERO, Rotation.NONE, Mirror.NONE, new byte[PlacementPart.PART_SIZE * 2 + 1]);
		assertNull(Placements.receive(player, OVERWORLD, parts.get(0)));
		assertEquals("message.antcraft.placement.refused", key(Placements.receive(player, OVERWORLD, parts.get(2))));
		assertEquals("message.antcraft.placement.refused", key(Placements.receive(player, OVERWORLD, parts.get(1))));
	}

	/** A file over {@link Placements#MAX_BYTES} is refused as soon as its parts pass the cap, and nothing is kept. */
	@Test
	void refusesAFileOverTheByteCap() {
		UUID player = UUID.randomUUID();
		List<PlacementPart> parts = PlacementPart.of("huge", BlockPos.ZERO, Rotation.NONE, Mirror.NONE, new byte[Placements.MAX_BYTES + 1]);
		Component refused = null;
		for (PlacementPart part : parts) {
			Component reply = Placements.receive(player, OVERWORLD, part);
			if (reply != null) {
				refused = reply;
				break;
			}
		}
		assertEquals("message.antcraft.placement.refused", key(assertNotNull(refused)));
		assertTrue(Placements.get(player, "huge").isEmpty());
	}

	/** A file that is no schematic, one with no blocks, and one whose regions span more than {@link Placements#MAX_VOLUME} are refused. */
	@Test
	void refusesMalformedEmptyAndOversizedSchematics() throws IOException {
		UUID player = UUID.randomUUID();
		byte[] huge = file(region(BlockPos.ZERO, new BlockPos(1000, 1000, 1000), List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState())));
		byte[] apart = file(region(BlockPos.ZERO, new BlockPos(1, 1, 1), List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()), 1),
			region(new BlockPos(100_000, 0, 100_000), new BlockPos(1, 1, 1), List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState()), 1));
		for (byte[] bytes : List.of("not a schematic".getBytes(), file(), huge, apart)) {
			Component reply = Placements.receive(player, OVERWORLD, PlacementPart.of("bad", BlockPos.ZERO, Rotation.NONE, Mirror.NONE, bytes).getFirst());
			assertEquals("message.antcraft.placement.refused", key(assertNotNull(reply)));
			assertTrue(Placements.get(player, "bad").isEmpty());
		}
	}
}
