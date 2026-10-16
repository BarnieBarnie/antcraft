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
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class JobBoardTest {
	private static final UUID ALICE = UUID.randomUUID();
	private static final UUID BOB = UUID.randomUUID();
	private static final Set<JobType> CHOP = Set.of(JobType.CHOP_TREE);

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
}
