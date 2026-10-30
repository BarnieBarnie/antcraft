package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Litematica schematics read as blueprints; the fixtures and how they are laid out are in {@code litematic/make_fixtures.py}. */
class LitematicTest {
	private static final Identifier ID = Identifier.fromNamespaceAndPath("antcraft", "imported");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static CompoundTag fixture(String name) throws IOException {
		try (InputStream in = LitematicTest.class.getResourceAsStream("/litematic/" + name + ".litematic")) {
			return Litematic.read(Objects.requireNonNull(in, name));
		}
	}

	private static BlueprintBlocks blocks(CompoundTag schematic) {
		return BlueprintBlocks.of(ID, Optional.of(Litematic.template(schematic)), 0);
	}

	/** Five states take 3 bits each, so the 22nd entry (3) spans the first long and the second; every entry still reads back. */
	@Test
	void readsEntriesPackedAcrossLongBoundaries() throws IOException {
		BlueprintBlocks blocks = blocks(fixture("packed"));
		assertEquals(new Vec3i(5, 1, 5), blocks.size());
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		List<BlockState> palette = List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
			Blocks.OAK_PLANKS.defaultBlockState(), stairs);
		List<BlueprintSummary.Block> expected = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			if (i * 3 % 5 != 0) {
				expected.add(new BlueprintSummary.Block(new BlockPos(i % 5, 0, i / 5), palette.get(i * 3 % 5)));
			}
		}
		assertEquals(expected, blocks.blocks());
	}

	/** Two regions merge into one blueprint from their lowest corner; one has a negative size, so it runs back from its position. */
	@Test
	void mergesRegionsIncludingOneWithANegativeSize() throws IOException {
		BlueprintBlocks blocks = blocks(fixture("regions"));
		assertEquals(new Vec3i(4, 3, 2), blocks.size());
		assertEquals(List.of(
			new BlueprintSummary.Block(new BlockPos(0, 0, 0), Blocks.STONE.defaultBlockState()),
			new BlueprintSummary.Block(new BlockPos(1, 0, 0), Blocks.DIRT.defaultBlockState()),
			new BlueprintSummary.Block(new BlockPos(2, 2, 1), Blocks.GLASS.defaultBlockState()),
			new BlueprintSummary.Block(new BlockPos(3, 2, 1), Blocks.COBBLESTONE.defaultBlockState())), blocks.blocks());
	}

	/** The blueprint of a schematic builds itself (its structure is its own id) and is named by the schematic's name. */
	@Test
	void theBlueprintIsNamedAfterTheSchematic() throws IOException {
		assertEquals(new Blueprint(ID, "Two regions", Optional.empty(), List.of(), List.of()), Litematic.blueprint(ID, fixture("regions")));
	}
}
