package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.script.Memory;
import io.github.barniebarnie.antcraft.script.Pos;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The colony's job board (#368) as pure logic. */
class JobBoardTest {
	private static final UUID POSTER = new UUID(0, 1);
	private static final UUID A = new UUID(0, 2);
	private static final UUID B = new UUID(0, 3);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static CompoundTag data(Object value) {
		return Memory.encode(value);
	}

	private static List<Long> ids(List<JobBoard.Job> jobs) {
		return jobs.stream().map(JobBoard.Job::id).toList();
	}

	@Test
	void postingGivesIncreasingIdsAndListsByKind() {
		JobBoard board = new JobBoard();
		assertEquals(1, board.post("haul", data(3L), POSTER));
		assertEquals(2, board.post("build", data("farm"), POSTER));
		assertEquals(3, board.post("haul", data(5L), POSTER));
		assertEquals(List.of(1L, 2L, 3L), ids(board.jobs(null)));
		assertEquals(List.of(1L, 3L), ids(board.jobs("haul")));
		JobBoard.Job job = board.jobs("build").getFirst();
		assertEquals("build", job.kind());
		assertEquals("'farm'", Values.repr(Memory.decode(job.data())));
		assertEquals(POSTER, job.poster());
		assertEquals(Optional.empty(), job.taker());
	}

	@Test
	void takeClaimsTheOldestOpenJobAndNeverOneTwice() {
		JobBoard board = new JobBoard();
		board.post("haul", data(1L), POSTER);
		board.post("build", data(2L), POSTER);
		board.post("haul", data(3L), POSTER);
		assertEquals(2, board.take("build", A).orElseThrow().id());
		assertEquals(1, board.take(null, B).orElseThrow().id());
		assertEquals(3, board.take("haul", A).orElseThrow().id());
		assertEquals(Optional.empty(), board.take(null, B));
		assertEquals(Optional.empty(), board.take("mine", B));
		assertEquals(Optional.of(B), board.jobs(null).getFirst().taker());
		assertEquals(3, board.jobs(null).size(), "taken jobs stay listed");
	}

	@Test
	void finishRemovesAndDropGivesBack() {
		JobBoard board = new JobBoard();
		long first = board.post("haul", data(1L), POSTER);
		long second = board.post("haul", data(2L), POSTER);
		board.take(null, A);
		board.take(null, A);
		assertTrue(board.finish(first));
		assertFalse(board.finish(first), "already finished");
		assertEquals(List.of(second), ids(board.jobs(null)));
		assertTrue(board.drop(second));
		assertFalse(board.drop(second), "already open");
		assertFalse(board.drop(99));
		assertEquals(second, board.take(null, B).orElseThrow().id());
		assertEquals(3, board.post("haul", data(3L), POSTER), "ids are not reused");
	}

	@Test
	void releaseGivesBackTheJobsOfATakerWhoseScriptEnded() {
		JobBoard board = new JobBoard();
		board.post("haul", data(1L), POSTER);
		board.post("haul", data(2L), POSTER);
		board.post("haul", data(3L), POSTER);
		board.take(null, A);
		board.take(null, B);
		board.take(null, A);
		board.release(A::equals);
		assertEquals(List.of(Optional.empty(), Optional.of(B), Optional.empty()), board.jobs(null).stream().map(JobBoard.Job::taker).toList());
		assertEquals(1, board.take(null, B).orElseThrow().id(), "the oldest released job comes first");
	}

	/** A colony gives back a job whose taker is no longer a member when its board is read, such as a stale one. */
	@Test
	void aColonyGivesBackTheJobsOfTakersNoLongerMembers() {
		Colony colony = new Colony(UUID.randomUUID(), BlockPos.ZERO, List.of(A, B), Colony.DEFAULT_RADIUS);
		colony.jobs().post("haul", data(1L), POSTER);
		colony.jobs().post("haul", data(2L), POSTER);
		colony.jobs().take(null, A);
		colony.jobs().take(null, B);
		colony.members().remove(A);
		assertEquals(List.of(Optional.empty(), Optional.of(B)), colony.jobs().jobs(null).stream().map(JobBoard.Job::taker).toList());
	}

	@Test
	void theBoardRoundTripsWithTheColony() {
		ColonyManager colonies = new ColonyManager();
		Colony colony = colonies.create(new BlockPos(1, 64, 2));
		colony.members().add(A);
		colony.jobs().post("haul", data(new PyList(new ArrayList<>(List.of(new Pos(1, 2, 3, "minecraft:overworld"), 4L)))), POSTER);
		colony.jobs().post("build", data("farm"), POSTER);
		colony.jobs().finish(colony.jobs().post("gone", data(0L), POSTER));
		colony.jobs().take("build", A);
		Tag saved = ColonyManager.TYPE.codec().encodeStart(NbtOps.INSTANCE, colonies).getOrThrow();
		Colony loaded = ColonyManager.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow().byId(colony.id()).orElseThrow();
		assertEquals(colony.jobs().jobs(null), loaded.jobs().jobs(null));
		PyList haul = (PyList) Memory.decode(loaded.jobs().jobs("haul").getFirst().data());
		assertEquals("[Pos(1, 2, 3), 4]", Values.repr(haul));
		assertEquals("minecraft:overworld", ((Pos) haul.items.getFirst()).dimension());
		assertEquals(4, loaded.jobs().post("next", data(0L), POSTER), "the next id is saved");
	}

	@Test
	void aColonyThatNeverHadAJobSavesNoBoard() {
		Colony colony = new Colony(UUID.randomUUID(), BlockPos.ZERO, List.of(), Colony.DEFAULT_RADIUS);
		Set<String> written = Colony.CODEC.encodeStart(NbtOps.INSTANCE, colony).getOrThrow() instanceof CompoundTag tag ? tag.keySet() : Set.of();
		assertFalse(written.contains("job_board"), "written " + written);
	}
}
