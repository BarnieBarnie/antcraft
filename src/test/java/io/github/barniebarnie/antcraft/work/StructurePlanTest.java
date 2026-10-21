package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StructurePlanTest {
	private final BlockState air = Blocks.AIR.defaultBlockState();
	private final BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
	private final BlockState stairsEast = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static ListTag ints(int... values) {
		ListTag list = new ListTag();
		for (int value : values) {
			list.add(IntTag.valueOf(value));
		}
		return list;
	}

	/** A template as a structure block saves it: positions missing from {@code blocks} are structure void. */
	private static StructureTemplate template(int sizeX, int sizeY, int sizeZ, Map<BlockPos, BlockState> blocks) {
		List<BlockState> palette = new ArrayList<>();
		ListTag paletteTag = new ListTag();
		ListTag blocksTag = new ListTag();
		blocks.forEach((pos, state) -> {
			if (!palette.contains(state)) {
				palette.add(state);
				paletteTag.add(NbtUtils.writeBlockState(state));
			}
			CompoundTag block = new CompoundTag();
			block.put("pos", ints(pos.getX(), pos.getY(), pos.getZ()));
			block.putInt("state", palette.indexOf(state));
			blocksTag.add(block);
		});
		CompoundTag tag = new CompoundTag();
		tag.put("size", ints(sizeX, sizeY, sizeZ));
		tag.put("palette", paletteTag);
		tag.put("blocks", blocksTag);
		tag.put("entities", new ListTag());
		StructureTemplate template = new StructureTemplate();
		template.load(BuiltInRegistries.BLOCK, tag);
		return template;
	}

	private final Map<BlockPos, BlockState> wall = Map.of(
		new BlockPos(0, 0, 0), cobble,
		new BlockPos(1, 0, 0), cobble,
		new BlockPos(0, 1, 0), stairsEast,
		new BlockPos(1, 1, 0), air);

	@Test
	void loadsTheSamePlanAsCopyingTheSameBlocks() {
		BlockPos at = new BlockPos(10, 1, 10);

		BuildPlan loaded = RegionPlan.load(template(2, 2, 1, wall), at, Mirror.NONE, Rotation.NONE).orElseThrow();
		BuildPlan copied = RegionPlan.copy(pos -> wall.getOrDefault(pos, air), BlockPos.ZERO, new BlockPos(1, 1, 0), at).orElseThrow();

		assertEquals(copied.steps(), loaded.steps());
		assertEquals(copied.batches(), loaded.batches());
	}

	@Test
	void aRotatedLoadTurnsPositionsAndFacing() {
		BuildPlan plan = RegionPlan.load(template(2, 2, 1, wall), new BlockPos(10, 1, 10), Mirror.NONE, Rotation.CLOCKWISE_90).orElseThrow();

		// Turned clockwise seen from above, the wall runs south from its lowest corner and the stairs face south.
		assertEquals(List.of(
			new BuildPlan.Step(new BlockPos(10, 1, 10), cobble),
			new BuildPlan.Step(new BlockPos(10, 1, 11), cobble),
			new BuildPlan.Step(new BlockPos(10, 2, 10), stairsEast.setValue(StairBlock.FACING, Direction.SOUTH)),
			BuildPlan.Step.clear(new BlockPos(10, 2, 11))), plan.steps());
	}

	@Test
	void structureVoidLeavesTheBlockThere() {
		// Only the bottom row is saved; the top row is structure void, so it gets no clear step.
		BuildPlan plan = RegionPlan.load(template(2, 2, 1, Map.of(new BlockPos(0, 0, 0), cobble, new BlockPos(1, 0, 0), cobble)),
			BlockPos.ZERO, Mirror.NONE, Rotation.NONE).orElseThrow();

		assertEquals(List.of(new BuildPlan.Step(BlockPos.ZERO, cobble), new BuildPlan.Step(new BlockPos(1, 0, 0), cobble)), plan.steps());
	}

	private static Map<BlockPos, BlockState> placed(BuildPlan plan) {
		Map<BlockPos, BlockState> placed = new HashMap<>();
		plan.steps().forEach(step -> placed.put(step.pos(), step.state()));
		return placed;
	}

	private final BlockState door = Blocks.OAK_DOOR.defaultBlockState()
		.setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
	private final BlockState cornerStairs = stairsEast.setValue(StairBlock.SHAPE, StairsShape.OUTER_LEFT);
	/** Lopsided along both x and z, so every mirror and rotation lands it differently. */
	private final Map<BlockPos, BlockState> lopsided = Map.of(
		new BlockPos(0, 0, 0), cobble,
		new BlockPos(1, 0, 0), cornerStairs,
		new BlockPos(2, 0, 0), air,
		new BlockPos(0, 0, 1), door);

	@Test
	void aMirroredLoadFlipsPositionsFacingHingeAndShape() {
		BuildPlan plan = RegionPlan.load(template(3, 1, 2, lopsided), new BlockPos(10, 1, 10), Mirror.FRONT_BACK, Rotation.NONE).orElseThrow();

		// Front to back flips x: the row runs the other way, east faces west, and a left hinge or outer-left corner turns right.
		assertEquals(Map.of(
			new BlockPos(12, 1, 10), cobble,
			new BlockPos(11, 1, 10), stairsEast.setValue(StairBlock.FACING, Direction.WEST).setValue(StairBlock.SHAPE, StairsShape.OUTER_RIGHT),
			new BlockPos(10, 1, 10), air,
			new BlockPos(12, 1, 11), door.setValue(DoorBlock.FACING, Direction.WEST).setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)),
			placed(plan));
	}

	@Test
	void mirrorAndRotationPlaceLikeAStructureBlock() {
		StructureTemplate template = template(3, 1, 2, lopsided);
		BlockPos at = new BlockPos(10, 1, 10);
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				StructurePlaceSettings settings = new StructurePlaceSettings().setMirror(mirror).setRotation(rotation);
				BoundingBox box = template.getBoundingBox(settings, BlockPos.ZERO);
				BlockPos shift = at.subtract(new BlockPos(box.minX(), box.minY(), box.minZ()));
				Map<BlockPos, BlockState> expected = new HashMap<>();
				// Where and what placeInWorld puts each block of the template, moved so the box starts at at.
				lopsided.forEach((pos, state) -> expected.put(StructureTemplate.calculateRelativePosition(settings, pos).offset(shift),
					state.mirror(mirror).rotate(rotation)));

				assertEquals(expected, placed(RegionPlan.load(template, at, mirror, rotation).orElseThrow()), mirror + " " + rotation);
			}
		}
	}
}
