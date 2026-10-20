package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OreRevisitTest {
	/** Down to y=6, one branch each side of a 3 long hall, branches 3 long, a torch every 4 blocks. */
	private static final Mineshaft SHAFT = new Mineshaft(new BlockPos(0, 10, 0), List.of(6), 1, 3, 4);
	private static final Mineshaft.Branch BRANCH = SHAFT.branches(6).getFirst();
	private static final Predicate<Item> ANY = item -> true;

	@BeforeAll
	static void loadTags() {
		// Which pickaxe harvests which ore comes from the vanilla tags.
		VanillaData.recipeBook();
	}

	/** A dug branch in stone: its cells are air, with {@code ore} at {@code pos}. */
	private static Function<BlockPos, BlockState> world(BlockPos pos, Block ore) {
		Map<BlockPos, BlockState> blocks = new HashMap<>();
		BRANCH.cells().forEach(cell -> blocks.put(cell, Blocks.AIR.defaultBlockState()));
		blocks.put(pos, ore.defaultBlockState());
		return p -> blocks.getOrDefault(p, Blocks.STONE.defaultBlockState());
	}

	/** A block in the wall beside the branch's first floor cell. */
	private static BlockPos wall() {
		BlockPos cell = BRANCH.cells().getFirst();
		return Direction.Plane.HORIZONTAL.stream().map(cell::relative).filter(side -> !BRANCH.cells().contains(side)).findFirst().orElseThrow();
	}

	private static Set<BlockPos> update(OreRevisit revisit, Item best, Predicate<BlockPos> posted, Function<BlockPos, BlockState> world, Predicate<Item> wanted) {
		return revisit.update(best, posted, () -> List.of(BRANCH), world, pos -> true, () -> wanted);
	}

	@Test
	void branchWithAnIronTierOreInItsWallIsPostedAgainOnceTheColonyHasAnIronPickaxeAndNotBefore() {
		OreRevisit revisit = new OreRevisit();
		Function<BlockPos, BlockState> world = world(wall(), Blocks.DIAMOND_ORE);

		assertEquals(Set.of(), update(revisit, Items.STONE_PICKAXE, pos -> false, world, ANY), "posted again for a stone pickaxe");
		assertEquals(Set.of(BRANCH.start()), update(revisit, Items.IRON_PICKAXE, pos -> false, world, ANY));
	}

	@Test
	void staysPostedUntilItsJobIsDone() {
		OreRevisit revisit = new OreRevisit();
		Function<BlockPos, BlockState> world = world(wall(), Blocks.DIAMOND_ORE);
		update(revisit, Items.IRON_PICKAXE, pos -> false, world, ANY);

		assertEquals(Set.of(BRANCH.start()), update(revisit, Items.IRON_PICKAXE, BRANCH.start()::equals, world, ANY));
		// Its job is gone from the board: the miner finished it, whether or not it got the ore.
		assertEquals(Set.of(), update(revisit, Items.IRON_PICKAXE, pos -> false, world, ANY));
	}

	@Test
	void branchesAreOnlyLookedAtWhenTheBestPickaxeGetsBetter() {
		OreRevisit revisit = new OreRevisit();
		Function<BlockPos, BlockState> world = world(wall(), Blocks.DIAMOND_ORE);
		update(revisit, Items.IRON_PICKAXE, pos -> false, world, ANY);
		Supplier<List<Mineshaft.Branch>> dug = () -> fail("looked at the branches again");
		Supplier<Predicate<Item>> wanted = () -> fail("planned what is wanted again");

		revisit.update(Items.IRON_PICKAXE, pos -> false, dug, world, pos -> true, wanted);
		// Losing the iron pickaxe and getting one back is no new tier.
		revisit.update(Items.STONE_PICKAXE, pos -> false, dug, world, pos -> true, wanted);
		revisit.update(Items.IRON_PICKAXE, pos -> false, dug, world, pos -> true, wanted);
	}

	@Test
	void oresNobodyWantsOrUnderTheFloorAreLeft() {
		assertEquals(Set.of(), update(new OreRevisit(), Items.IRON_PICKAXE, pos -> false, world(wall(), Blocks.DIAMOND_ORE), item -> item != Items.DIAMOND));
		assertEquals(Set.of(), update(new OreRevisit(), Items.IRON_PICKAXE, pos -> false, world(BRANCH.cells().getFirst().below(), Blocks.DIAMOND_ORE), ANY));
		// Deepslate ores count as the ore.
		assertEquals(Set.of(BRANCH.start()),
			update(new OreRevisit(), Items.IRON_PICKAXE, pos -> false, world(wall(), Blocks.DEEPSLATE_DIAMOND_ORE), item -> item == Items.DIAMOND));
	}

	@Test
	void oreAMinerMayNotBreakIsLeft() {
		OreRevisit revisit = new OreRevisit();
		BlockPos wall = wall();
		assertEquals(Set.of(), revisit.update(Items.IRON_PICKAXE, pos -> false, () -> List.of(BRANCH), world(wall, Blocks.DIAMOND_ORE),
			pos -> !pos.equals(wall), () -> ANY));
	}

	/** Every ore of {@code c:ores} counts, not only the overworld ones: the tag is not loaded here, so it stands in. */
	@Test
	void anyOreOfTheConventionalTagIsRevisited() {
		Predicate<BlockState> ores = state -> state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.ANCIENT_DEBRIS) || state.is(Blocks.GILDED_BLACKSTONE);
		BlockPos wall = wall();
		assertEquals(Set.of(BRANCH.start()), new OreRevisit(ores).update(Items.IRON_PICKAXE, pos -> false, () -> List.of(BRANCH),
			world(wall, Blocks.NETHER_QUARTZ_ORE), pos -> true, () -> item -> item == Items.QUARTZ));
		// Ancient debris needs a diamond pickaxe.
		assertEquals(Set.of(), new OreRevisit(ores).update(Items.IRON_PICKAXE, pos -> false, () -> List.of(BRANCH),
			world(wall, Blocks.ANCIENT_DEBRIS), pos -> true, () -> ANY));
		assertEquals(Set.of(BRANCH.start()), new OreRevisit(ores).update(Items.DIAMOND_PICKAXE, pos -> false, () -> List.of(BRANCH),
			world(wall, Blocks.ANCIENT_DEBRIS), pos -> true, () -> item -> item == Items.ANCIENT_DEBRIS));
		// An ore it knows no drop of, such as a modded one, is wanted for its own item.
		assertEquals(Set.of(BRANCH.start()), new OreRevisit(ores).update(Items.IRON_PICKAXE, pos -> false, () -> List.of(BRANCH),
			world(wall, Blocks.GILDED_BLACKSTONE), pos -> true, () -> item -> item == Items.GILDED_BLACKSTONE));
		// Not in the tag: no ore.
		assertEquals(Set.of(), new OreRevisit(ores).update(Items.IRON_PICKAXE, pos -> false, () -> List.of(BRANCH),
			world(wall, Blocks.IRON_ORE), pos -> true, () -> ANY));
	}
}
