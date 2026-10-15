package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.serialization.JsonOps;
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
}
