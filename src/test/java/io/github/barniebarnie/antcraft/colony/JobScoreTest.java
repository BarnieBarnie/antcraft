package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JobScoreTest {
	private static final ToDoubleFunction<Item> NO_FETCH = item -> Double.POSITIVE_INFINITY;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Job job(JobType type, BlockPos target, Optional<Item> tool, int priority) {
		return new Job(UUID.randomUUID(), type, target, Optional.empty(), 0L, List.of(), Optional.empty(), 0, 0L, 0L, tool, priority);
	}

	private static Job job(JobType type, BlockPos target) {
		return job(type, target, Optional.empty(), 0);
	}

	private static JobScore bot(BlockPos pos, List<Item> carried, int freeSlots, Optional<Job> last, ToDoubleFunction<Item> fetch) {
		return new JobScore(pos, carried, freeSlots, last, fetch);
	}

	private static JobScore bot(BlockPos pos) {
		return bot(pos, List.of(), 10, Optional.empty(), NO_FETCH);
	}

	@Test
	void nearerOfTwoEqualJobsWins() {
		JobScore bot = bot(BlockPos.ZERO);
		double near = bot.score(job(JobType.CHOP_TREE, new BlockPos(3, 0, 0))).orElseThrow();
		double far = bot.score(job(JobType.CHOP_TREE, new BlockPos(9, 0, 0))).orElseThrow();
		assertTrue(near < far);
	}

	@Test
	void botCarryingTheToolBeatsANearerBotThatMustFetchIt() {
		Job dig = job(JobType.MINE_BRANCH, new BlockPos(10, 0, 0), Optional.of(Items.STONE_PICKAXE), 0);
		JobScore carrier = bot(new BlockPos(20, 0, 0), List.of(Items.IRON_PICKAXE), 10, Optional.empty(), NO_FETCH);
		JobScore fetcher = bot(new BlockPos(15, 0, 0), List.of(Items.WOODEN_PICKAXE), 10, Optional.empty(), item -> 30);
		assertTrue(carrier.score(dig).orElseThrow() < fetcher.score(dig).orElseThrow());
	}

	@Test
	void jobNobodyCanEquipIsNotClaimable() {
		Job dig = job(JobType.MINE_BRANCH, new BlockPos(10, 0, 0), Optional.of(Items.STONE_PICKAXE), 0);
		assertTrue(bot(BlockPos.ZERO).score(dig).isEmpty());
	}

	@Test
	void sameTypeNextToTheLastJobBeatsASlightlyNearerOtherType() {
		Job finished = job(JobType.CHOP_TREE, new BlockPos(10, 0, 0));
		JobScore bot = bot(BlockPos.ZERO, List.of(), 10, Optional.of(finished), NO_FETCH);
		double sameTree = bot.score(job(JobType.CHOP_TREE, new BlockPos(11, 0, 0))).orElseThrow();
		double otherType = bot.score(job(JobType.HARVEST_CROP, new BlockPos(9, 0, 0))).orElseThrow();
		assertTrue(sameTree < otherType);
	}

	@Test
	void fullBotCannotTakeAJobThatYieldsItems() {
		JobScore full = bot(BlockPos.ZERO, List.of(), 0, Optional.empty(), NO_FETCH);
		assertTrue(full.score(job(JobType.CHOP_TREE, new BlockPos(3, 0, 0))).isEmpty());
		assertTrue(full.score(job(JobType.BUILD, new BlockPos(3, 0, 0))).isPresent());
	}

	@Test
	void higherPriorityBeatsNearerLowerPriority() {
		JobScore bot = bot(BlockPos.ZERO);
		double urgent = bot.score(job(JobType.BUILD, new BlockPos(30, 0, 0), Optional.empty(), 1)).orElseThrow();
		double near = bot.score(job(JobType.BUILD, new BlockPos(2, 0, 0))).orElseThrow();
		assertTrue(urgent < near);
	}

	@Test
	void boardClaimsTheLowestScoringReadyJob() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(2, 0, 0));
		Job build = board.post(JobType.BUILD, new BlockPos(5, 0, 0)).orElseThrow();
		UUID bot = UUID.randomUUID();
		JobScore full = bot(BlockPos.ZERO, List.of(), 0, Optional.empty(), NO_FETCH);

		assertEquals(build.id(), board.claim(bot, 0L, job -> true, full).orElseThrow().id());
	}

	@Test
	void boardShowsTheBestJobWithoutClaimingIt() {
		JobBoard board = new JobBoard();
		Job near = board.post(JobType.CHOP_TREE, new BlockPos(2, 0, 0)).orElseThrow();
		board.post(JobType.CHOP_TREE, new BlockPos(9, 0, 0));

		assertEquals(near.id(), board.best(0L, job -> true, bot(BlockPos.ZERO)).orElseThrow().id());
		assertTrue(board.jobs().stream().allMatch(Job::isOpen));
	}

	@Test
	void oldSavesLoadWithoutToolOrPriority() {
		var json = JsonParser.parseString("[{\"id\": [1, 2, 3, 4], \"type\": \"chop_tree\", \"target\": [2, 0, 0]}]");
		Job loaded = JobBoard.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().jobs().getFirst();

		assertEquals(Optional.empty(), loaded.tool());
		assertEquals(0, loaded.priority());
	}

	/** A tree job's sapling comes from the tree's own drops: saplings in the core add no trip to the core. */
	@Test
	void aTreeJobsSaplingIsNotFetched() {
		Job tree = new Job(UUID.randomUUID(), JobType.CHOP_TREE, new BlockPos(4, 0, 0), Optional.empty(), 0L, List.of(), Optional.of(Items.OAK_SAPLING),
			0, 0L, 0L, Optional.empty(), 0);
		double withSaplings = bot(BlockPos.ZERO, List.of(), 10, Optional.empty(), item -> 30).score(tree).orElseThrow();
		double without = bot(BlockPos.ZERO, List.of(), 10, Optional.empty(), item -> 0).score(tree).orElseThrow();
		assertEquals(without, withSaplings, 1e-9);
	}

	/** A craft job's item is what it makes, which nobody has yet: it costs no fetch. Its station is fetched like a tool. */
	@Test
	void aCraftJobsItemIsItsProductNotANeed() {
		Job craft = new Job(UUID.randomUUID(), JobType.CRAFT, new BlockPos(4, 0, 0), Optional.empty(), 0L, List.of(), Optional.of(Items.COBBLESTONE_STAIRS),
			0, 0L, 0L, Optional.of(Items.CRAFTING_TABLE), 0);
		assertEquals(4.0, bot(BlockPos.ZERO, List.of(Items.CRAFTING_TABLE), 10, Optional.empty(), NO_FETCH).score(craft).orElseThrow(), 1e-9);
		assertTrue(bot(BlockPos.ZERO).score(craft).isEmpty());
	}
}
