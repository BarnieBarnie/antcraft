package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import io.github.barniebarnie.antcraft.work.Farming;
import io.github.barniebarnie.antcraft.work.TreeScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JobBoardTest {
	private static final UUID ALICE = UUID.randomUUID();
	private static final UUID BOB = UUID.randomUUID();
	private static final Set<JobType> CHOP = Set.of(JobType.CHOP_TREE);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void postedJobIsOpen() {
		JobBoard board = new JobBoard();
		Job job = board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 5)).orElseThrow();

		assertTrue(job.isOpen());
		assertEquals(1, board.jobs().size());
	}

	@Test
	void sameTargetIsNotPostedTwice() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 5));

		assertTrue(board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 5)).isEmpty());
		assertEquals(1, board.jobs().size());
	}

	@Test
	void postedJobDeclaresTheToolItNeeds() {
		JobBoard board = new JobBoard();
		Job job = board.post(JobType.PLANT_CROP, new BlockPos(5, 1, 5), Optional.of(Items.WOODEN_HOE)).orElseThrow();

		assertEquals(Optional.of(Items.WOODEN_HOE), job.tool());
	}

	/** The survey after tilling posts the plant job again, over farmland now: it needs no hoe any more, claimed or not. */
	@Test
	void aPlantJobOverFarmlandDeclaresNoToolAfterTheSurveyThatFollowsTilling() {
		JobBoard board = new JobBoard();
		BlockPos crop = new BlockPos(5, 1, 5);
		board.post(JobType.PLANT_CROP, crop, Farming.plantTool(Blocks.DIRT.defaultBlockState()));
		board.claim(ALICE, Set.of(JobType.PLANT_CROP), BlockPos.ZERO, 0);

		assertTrue(board.post(JobType.PLANT_CROP, crop, Farming.plantTool(Blocks.FARMLAND.defaultBlockState())).isEmpty());
		assertEquals(1, board.jobs().size());
		Job job = board.jobOf(ALICE).orElseThrow();
		assertEquals(Optional.empty(), job.tool());
	}

	@Test
	void aBuildJobIsPostedWithItsPriorityAndDeclaresWhatItNeedsLater() {
		JobBoard board = new JobBoard();
		UUID batch = UUID.randomUUID();
		Job job = board.post(batch, JobType.BUILD, new BlockPos(5, 1, 5), -2).orElseThrow();
		assertEquals(-2, job.priority());
		board.claim(ALICE, Set.of(JobType.BUILD), BlockPos.ZERO, 0);

		board.declare(batch, Optional.of(Items.COBBLESTONE), Optional.of(Items.WOODEN_PICKAXE));
		Job declared = board.jobOf(ALICE).orElseThrow();
		assertEquals(Optional.of(Items.COBBLESTONE), declared.item());
		assertEquals(Optional.of(Items.WOODEN_PICKAXE), declared.tool());
		assertEquals(-2, declared.priority());
	}

	private static TreeScanner.Tree tree(int x) {
		List<BlockPos> logs = new java.util.ArrayList<>();
		for (int y = 1; y <= 6; y++) {
			logs.add(new BlockPos(x, y, 0));
		}
		return new TreeScanner.Tree(logs.getFirst(), logs);
	}

	@Test
	void noTreeJobsWithoutLogsWanted() {
		JobBoard board = new JobBoard();
		board.postTrees(List.of(tree(3), tree(6)), BlockPos.ZERO, 0, tree -> Optional.empty());
		assertTrue(board.jobs().isEmpty());
	}

	@Test
	void postsTheNearestTreesThatCoverTheLogsWantedCountingThoseOnTheBoard() {
		JobBoard board = new JobBoard();
		// Already posted, and claimed: its 6 logs count.
		board.post(JobType.CHOP_TREE, tree(9).base(), tree(9).logs());
		board.claim(ALICE, CHOP, new BlockPos(9, 1, 0), 0);
		// 20 logs wanted from trees of 6: 4 trees, the nearest ones.
		board.postTrees(List.of(tree(30), tree(15), tree(3), tree(6), tree(9), tree(20)), BlockPos.ZERO, 20, tree -> Optional.empty());
		assertEquals(Set.of(3, 6, 9, 15), board.jobs().stream().map(job -> job.target().getX()).collect(java.util.stream.Collectors.toSet()));
	}

	/** A trunk at x=3 up to y=6, standing on the ground at y=0 while {@code bottom} is 1, else cut below {@code bottom}. */
	private static TreeScanner.TreeView trunk(int bottom) {
		return new TreeScanner.TreeView() {
			@Override
			public boolean isLog(BlockPos pos) {
				return pos.getX() == 3 && pos.getZ() == 0 && pos.getY() >= bottom && pos.getY() <= 6;
			}

			@Override
			public boolean isNaturalLeaves(BlockPos pos) {
				return false;
			}

			@Override
			public boolean isOpen(BlockPos pos) {
				return pos.getY() > 0 && !this.isLog(pos);
			}
		};
	}

	@Test
	void aTreeOfSixLogsGivesSixLogJobsAndOnlyTheReachableOnesAreReady() {
		JobBoard board = new JobBoard();
		board.postTrees(List.of(tree(3)), BlockPos.ZERO, 6, tree -> Optional.of(Items.OAK_SAPLING));

		List<Job> logs = board.jobs().stream().filter(job -> job.type() == JobType.CHOP_LOG).toList();
		assertEquals(tree(3).logs(), logs.stream().map(Job::target).toList());
		assertTrue(logs.stream().allMatch(job -> job.blocks().equals(List.of(job.target()))), "a job of its own for each log");
		// Up to 4 above the ground, within reach of a bot standing on it.
		TreeScanner.TreeView whole = trunk(1);
		assertEquals(List.of(1, 2, 3, 4), logs.stream().filter(job -> TreeScanner.isReachable(whole, job.blocks())).map(job -> job.target().getY()).toList());
		// Once the logs under them are cut, the bottom one of the rest is too: a bot pillars up to it.
		TreeScanner.TreeView cut = trunk(5);
		assertTrue(TreeScanner.isReachable(cut, new BlockPos(3, 5, 0)));
		assertFalse(TreeScanner.isReachable(cut, new BlockPos(3, 6, 0)));
		// One more job for the tree, to pick up the drops and replant it with its own sapling.
		Job tree = board.jobs().stream().filter(job -> job.type() == JobType.CHOP_TREE).findFirst().orElseThrow();
		assertEquals(Optional.of(Items.OAK_SAPLING), tree.item());
		assertEquals(7, board.jobs().size());
	}

	@Test
	void aGiantTrunkGivesAJobPerLayerAndOnlyTheReachableLayersAreReady() {
		// A 2x2 trunk at (3..4, 1..20, 0..1) on the ground at y=0.
		List<BlockPos> trunk = new java.util.ArrayList<>();
		for (int y = 1; y <= 20; y++) {
			for (BlockPos column : List.of(new BlockPos(3, y, 0), new BlockPos(4, y, 0), new BlockPos(3, y, 1), new BlockPos(4, y, 1))) {
				trunk.add(column);
			}
		}
		TreeScanner.TreeView view = new TreeScanner.TreeView() {
			@Override
			public boolean isLog(BlockPos pos) {
				return trunk.contains(pos);
			}

			@Override
			public boolean isNaturalLeaves(BlockPos pos) {
				return false;
			}

			@Override
			public boolean isOpen(BlockPos pos) {
				return pos.getY() > 0 && !this.isLog(pos);
			}
		};
		JobBoard board = new JobBoard();
		assertTrue(board.postTree(new TreeScanner.Tree(trunk.getFirst(), trunk), Optional.empty()));

		List<Job> logs = board.jobs().stream().filter(job -> job.type() == JobType.CHOP_LOG).toList();
		assertEquals(20, logs.size());
		for (Job layer : logs) {
			assertEquals(4, layer.blocks().size());
			assertTrue(layer.blocks().stream().allMatch(log -> log.getY() == layer.target().getY()), "one layer per job: " + layer.blocks());
		}
		assertEquals(List.of(1, 2, 3, 4), logs.stream().filter(job -> TreeScanner.isReachable(view, job.blocks())).map(job -> job.target().getY()).toList());
	}

	@Test
	void theTreeJobIsReadyOnceItsLogJobsAreDoneOrGivenUpOrItHasNone() {
		JobBoard board = new JobBoard();
		board.postTrees(List.of(tree(3)), BlockPos.ZERO, 6, tree -> Optional.empty());
		Job tree = board.jobs().stream().filter(job -> job.type() == JobType.CHOP_TREE).findFirst().orElseThrow();
		List<Job> logs = board.jobs().stream().filter(job -> job.type() == JobType.CHOP_LOG).toList();

		assertFalse(board.treeReady(tree));
		logs.subList(1, 6).forEach(job -> board.complete(job.id()));
		assertFalse(board.treeReady(tree));
		board.claim(ALICE, Set.of(JobType.CHOP_LOG), BlockPos.ZERO, 0);
		board.retryLater(ALICE, 0, 1);
		assertTrue(board.treeReady(tree), "the last log job was given up");
		// A tree posted before log jobs fells its logs itself.
		JobBoard old = new JobBoard();
		Job felledWhole = old.post(JobType.CHOP_TREE, tree(3).base(), tree(3).logs()).orElseThrow();
		assertTrue(old.treeReady(felledWhole));
	}

	@Test
	void claimPicksNearestOpenJob() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(20, 1, 0));
		Job near = board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0)).orElseThrow();

		Job claimed = board.claim(ALICE, CHOP, BlockPos.ZERO, 0).orElseThrow();
		assertEquals(near.id(), claimed.id());
		assertEquals(Optional.of(ALICE), claimed.claimedBy());
	}

	@Test
	void twoBotsGetDifferentJobs() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0));
		board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 0));

		Job a = board.claim(ALICE, CHOP, BlockPos.ZERO, 0).orElseThrow();
		Job b = board.claim(BOB, CHOP, BlockPos.ZERO, 0).orElseThrow();
		assertNotEquals(a.id(), b.id());
	}

	@Test
	void noJobLeftForSecondBot() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0));
		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);

		assertTrue(board.claim(BOB, CHOP, BlockPos.ZERO, 0).isEmpty());
	}

	@Test
	void jobsPostedByIdGoToDifferentBotsEvenAtOneTarget() {
		// Batches of one build plan, each its own job.
		JobBoard board = new JobBoard();
		UUID stairs = UUID.randomUUID();
		UUID hall = UUID.randomUUID();
		board.post(stairs, JobType.BUILD, new BlockPos(3, 1, 0));
		board.post(hall, JobType.BUILD, new BlockPos(3, 1, 0));
		assertTrue(board.post(hall, JobType.BUILD, new BlockPos(5, 1, 0)).isEmpty(), "same batch posted twice");

		Set<UUID> claimed = Set.of(board.claim(ALICE, Set.of(JobType.BUILD), BlockPos.ZERO, 0).orElseThrow().id(),
			board.claim(BOB, Set.of(JobType.BUILD), BlockPos.ZERO, 0).orElseThrow().id());
		assertEquals(Set.of(stairs, hall), claimed);
	}

	@Test
	void claimTakesTheBestRankedJobBeforeTheNearest() {
		JobBoard board = new JobBoard();
		UUID near = UUID.randomUUID();
		UUID first = UUID.randomUUID();
		board.post(near, JobType.BUILD, new BlockPos(1, 1, 0));
		board.post(first, JobType.BUILD, new BlockPos(9, 1, 0));
		Job claimed = board.claim(ALICE, Set.of(JobType.BUILD), BlockPos.ZERO, 0, job -> true, job -> job.id().equals(first) ? 0 : 1).orElseThrow();
		assertEquals(first, claimed.id());
		// Equally ranked, the nearest goes first.
		assertEquals(near, board.claim(BOB, Set.of(JobType.BUILD), BlockPos.ZERO, 0, job -> true, job -> 0).orElseThrow().id());
	}

	@Test
	void claimingAgainReturnsTheSameJob() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0));
		board.post(JobType.CHOP_TREE, new BlockPos(1, 1, 0));
		Job first = board.claim(ALICE, CHOP, new BlockPos(3, 1, 0), 0).orElseThrow();

		// Even though another job is now nearer, the bot keeps its current job.
		Job again = board.claim(ALICE, CHOP, new BlockPos(1, 1, 0), 5).orElseThrow();
		assertEquals(first.id(), again.id());
		assertEquals(1, board.jobs().stream().filter(j -> !j.isOpen()).count());
	}

	@Test
	void ignoresJobTypesTheBotCannotDo() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0));

		assertTrue(board.claim(ALICE, Set.of(), BlockPos.ZERO, 0).isEmpty());
	}

	@Test
	void completedJobIsRemoved() {
		JobBoard board = new JobBoard();
		Job job = board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0)).orElseThrow();
		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);

		board.complete(job.id());
		assertTrue(board.jobs().isEmpty());
		assertTrue(board.jobOf(ALICE).isEmpty());
	}

	@Test
	void releasedJobCanBeClaimedByAnotherBot() {
		JobBoard board = new JobBoard();
		Job job = board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0)).orElseThrow();
		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);

		board.release(ALICE);
		assertEquals(job.id(), board.claim(BOB, CHOP, BlockPos.ZERO, 0).orElseThrow().id());
	}

	@Test
	void staleClaimsAreReleased() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0));
		board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 0));
		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);
		board.claim(BOB, CHOP, BlockPos.ZERO, 0);
		board.heartbeat(BOB, 500);

		assertEquals(1, board.releaseStale(600, 200));
		assertTrue(board.jobOf(ALICE).isEmpty());
		assertTrue(board.jobOf(BOB).isPresent());
	}

	@Test
	void removeOpenJobsWhereTargetIsGone() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0));
		Job claimed = board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 0)).orElseThrow();
		board.claim(ALICE, CHOP, new BlockPos(5, 1, 0), 0);

		board.removeOpenIf(job -> true);
		assertEquals(1, board.jobs().size());
		assertEquals(claimed.id(), board.jobs().getFirst().id());
	}

	@Test
	void survivesSaveAndLoad() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(3, 1, 0));
		board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 0));
		board.claim(ALICE, CHOP, BlockPos.ZERO, 42);

		var json = JobBoard.CODEC.encodeStart(JsonOps.INSTANCE, board).getOrThrow();
		JobBoard loaded = JobBoard.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(board.jobs(), loaded.jobs());
		assertFalse(loaded.jobOf(ALICE).isEmpty());
	}

	@Test
	void replantJobIsClaimedByTheNearerLumberjack() {
		JobBoard board = new JobBoard();
		Set<JobType> lumberjack = Set.of(JobType.CHOP_TREE, JobType.REPLANT);
		Job tree = board.post(JobType.CHOP_TREE, new BlockPos(2, 1, 0)).orElseThrow();
		Job replant = board.post(JobType.REPLANT, new BlockPos(28, 1, 0)).orElseThrow();

		// Alice stands by the tree, Bob by the felled one: each takes the job nearest to them.
		assertEquals(tree.id(), board.claim(ALICE, lumberjack, BlockPos.ZERO, 0).orElseThrow().id());
		assertEquals(replant.id(), board.claim(BOB, lumberjack, new BlockPos(30, 1, 0), 0).orElseThrow().id());
	}

	@Test
	void jobThatIsNotReadyIsNotClaimed() {
		JobBoard board = new JobBoard();
		Set<JobType> lumberjack = Set.of(JobType.CHOP_TREE, JobType.REPLANT);
		board.post(JobType.REPLANT, new BlockPos(1, 1, 0));
		Job tree = board.post(JobType.CHOP_TREE, new BlockPos(9, 1, 0)).orElseThrow();

		// No sapling for the nearer replant job yet, so the bot takes the tree.
		Job claimed = board.claim(ALICE, lumberjack, BlockPos.ZERO, 0, job -> job.type() != JobType.REPLANT).orElseThrow();
		assertEquals(tree.id(), claimed.id());
		assertTrue(board.claim(BOB, lumberjack, BlockPos.ZERO, 0, job -> job.type() != JobType.REPLANT).isEmpty());
	}

	@Test
	void replantJobSurvivesSaveAndLoad() {
		JobBoard board = new JobBoard();
		board.post(JobType.REPLANT, new BlockPos(3, 1, 0));

		var json = JobBoard.CODEC.encodeStart(JsonOps.INSTANCE, board).getOrThrow();
		JobBoard loaded = JobBoard.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(board.jobs(), loaded.jobs());
		assertTrue(loaded.has(JobType.REPLANT, new BlockPos(3, 1, 0)));
	}

	/** A felled tree's lower logs are gone: the rest is scanned as a tree with a new base. */
	@Test
	void treeOverlappingAClaimedTreeIsNotPostedAgain() {
		JobBoard board = new JobBoard();
		List<BlockPos> logs = List.of(new BlockPos(5, 1, 5), new BlockPos(5, 2, 5), new BlockPos(5, 3, 5));
		board.post(JobType.CHOP_TREE, logs.getFirst(), logs);
		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);

		assertTrue(board.post(JobType.CHOP_TREE, logs.get(1), logs.subList(1, 3)).isEmpty());
		assertTrue(board.claim(BOB, CHOP, BlockPos.ZERO, 0).isEmpty());
	}

	/** A tree whose bottom log was cut is rescanned with its base on the ground, under the cut log. */
	@Test
	void rescannedTreeWithItsBottomLogCutIsNotPostedAgain() {
		JobBoard board = new JobBoard();
		List<BlockPos> logs = List.of(new BlockPos(5, 1, 5), new BlockPos(5, 2, 5), new BlockPos(5, 3, 5));
		board.post(JobType.CHOP_TREE, logs.getFirst(), logs);

		assertTrue(board.post(JobType.CHOP_TREE, logs.getFirst(), logs.subList(1, 3)).isEmpty());
		assertEquals(1, board.jobs().size());
	}

	@Test
	void separateTreeIsPosted() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 5), List.of(new BlockPos(5, 1, 5), new BlockPos(5, 2, 5)));
		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);

		assertTrue(board.post(JobType.CHOP_TREE, new BlockPos(9, 1, 5), List.of(new BlockPos(9, 1, 5))).isPresent());
	}

	@Test
	void treeLogsSurviveClaimAndSaveAndLoad() {
		JobBoard board = new JobBoard();
		List<BlockPos> logs = List.of(new BlockPos(5, 1, 5), new BlockPos(5, 2, 5));
		board.post(JobType.CHOP_TREE, logs.getFirst(), logs);
		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);

		var json = JobBoard.CODEC.encodeStart(JsonOps.INSTANCE, board).getOrThrow();
		JobBoard loaded = JobBoard.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(logs, loaded.jobOf(ALICE).orElseThrow().area());
		assertTrue(loaded.post(JobType.CHOP_TREE, logs.get(1), List.of(logs.get(1))).isEmpty());
	}

	/** A tree with logs the bot could not reach goes back on the board, but no one takes it before its time. */
	@Test
	void jobToRetryLaterIsNotClaimedBeforeItsTime() {
		JobBoard board = new JobBoard();
		List<BlockPos> logs = List.of(new BlockPos(5, 1, 5), new BlockPos(5, 2, 5));
		board.post(JobType.CHOP_TREE, logs.getFirst(), logs);
		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);

		assertTrue(board.retryLater(ALICE, 600, 3));
		assertTrue(board.jobOf(ALICE).isEmpty());
		assertTrue(board.claim(BOB, CHOP, BlockPos.ZERO, 599).isEmpty());
		Job retried = board.claim(BOB, CHOP, BlockPos.ZERO, 600).orElseThrow();
		assertEquals(1, retried.tries());
		assertEquals(logs, retried.area());
	}

	/** After the last try the job is given up: never claimed again, but it stays so the tree is not posted anew. */
	@Test
	void jobIsGivenUpAfterItsLastTry() {
		JobBoard board = new JobBoard();
		List<BlockPos> logs = List.of(new BlockPos(5, 1, 5), new BlockPos(5, 2, 5));
		board.post(JobType.CHOP_TREE, logs.getFirst(), logs);

		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);
		assertTrue(board.retryLater(ALICE, 100, 3));
		board.claim(ALICE, CHOP, BlockPos.ZERO, 100);
		assertTrue(board.retryLater(ALICE, 200, 3));
		board.claim(ALICE, CHOP, BlockPos.ZERO, 200);
		assertFalse(board.retryLater(ALICE, 300, 3));

		assertTrue(board.jobs().getFirst().givenUp());
		assertTrue(board.claim(BOB, CHOP, BlockPos.ZERO, Long.MAX_VALUE - 1).isEmpty());
		assertTrue(board.post(JobType.CHOP_TREE, logs.get(1), List.of(logs.get(1))).isEmpty());
	}

	/** Reopened, such as after a player opened the way, a given-up job gets one more try before it is given up again. */
	@Test
	void reopenedGivenUpJobGetsOneMoreTry() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 5));
		board.post(JobType.CHOP_TREE, new BlockPos(9, 1, 9));
		board.claim(ALICE, CHOP, new BlockPos(5, 1, 5), 0);
		assertFalse(board.retryLater(ALICE, 100, 1));

		assertEquals(1, board.reopenGivenUp());
		assertEquals(0, board.reopenGivenUp());
		assertFalse(board.jobs().getFirst().givenUp());
		Job retried = board.claim(BOB, CHOP, new BlockPos(5, 1, 5), 0).orElseThrow();
		assertEquals(new BlockPos(5, 1, 5), retried.target());

		assertFalse(board.retryLater(BOB, 100, 1));
		assertTrue(board.jobs().getFirst().givenUp());
	}

	@Test
	void triesAndRetryTimeSurviveSaveAndLoad() {
		JobBoard board = new JobBoard();
		board.post(JobType.CHOP_TREE, new BlockPos(5, 1, 5));
		board.claim(ALICE, CHOP, BlockPos.ZERO, 0);
		board.retryLater(ALICE, 600, 3);

		var json = JobBoard.CODEC.encodeStart(JsonOps.INSTANCE, board).getOrThrow();
		JobBoard loaded = JobBoard.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(board.jobs(), loaded.jobs());
		assertTrue(loaded.claim(BOB, CHOP, BlockPos.ZERO, 599).isEmpty());
	}

	@Test
	void postedTimeSurvivesSaveAndLoad() {
		JobBoard board = new JobBoard();
		BlockPos base = new BlockPos(3, 1, 0);
		board.post(JobType.REPLANT, base, List.of(base), Optional.empty(), 1234L);

		var json = JobBoard.CODEC.encodeStart(JsonOps.INSTANCE, board).getOrThrow();
		JobBoard loaded = JobBoard.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();

		assertEquals(board.jobs(), loaded.jobs());
		assertEquals(1234L, loaded.jobs().getFirst().posted());
	}

	@Test
	void craftJobsAtOnePlaceAreOnePerProduct() {
		JobBoard board = new JobBoard();
		BlockPos core = new BlockPos(0, 1, 0);

		assertTrue(board.postFor(JobType.CRAFT, core, Items.OAK_PLANKS, Optional.empty()).isPresent());
		assertTrue(board.postFor(JobType.CRAFT, core, Items.STICK, Optional.empty()).isPresent());
		assertTrue(board.postFor(JobType.CRAFT, core, Items.STICK, Optional.empty()).isEmpty());
		Job stairs = board.postFor(JobType.CRAFT, new BlockPos(9, 1, 0), Items.COBBLESTONE_STAIRS, Optional.of(Items.CRAFTING_TABLE)).orElseThrow();

		assertEquals(3, board.jobs().size());
		assertEquals(Optional.of(Items.COBBLESTONE_STAIRS), stairs.item());
		assertEquals(Optional.of(Items.CRAFTING_TABLE), stairs.tool());
	}
}
