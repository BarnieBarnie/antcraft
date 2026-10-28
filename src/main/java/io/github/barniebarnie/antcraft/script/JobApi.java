package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.colony.JobBoard;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The colony's job board (docs/scripting.md, Jobs, #368): {@code post_job}, {@code jobs}, {@code take_job},
 * {@code finish_job} and {@code drop_job}, on the {@link JobBoard} of the bot's colony. Each is done at once, so a job
 * is claimed in one step however the script's instruction budget falls.
 */
public final class JobApi {
	private JobApi() {
	}

	public static void register(Natives natives, ScriptRun run) {
		natives.register("post_job", 10, call -> {
			Object[] args = Args.bind("post_job", call, 1, "kind", "data");
			String kind = kind(args[0]);
			Object data = Args.absent(args[1]) ? PyNone.NONE : args[1];
			Memory.checkPlain(data);
			AntBot bot = WorkApi.bot(run, call);
			JobBoard board = board(bot).orElseThrow(() -> new ScriptError("post_job() needs the bot to be in a colony"));
			return board.post(kind, Memory.encode(data), bot.getUUID());
		});
		natives.register("jobs", 10, call -> {
			Object[] args = Args.bind("jobs", call, 0, "kind");
			String kind = kindOrAny(args[0]);
			PyList list = new PyList();
			board(WorkApi.bot(run, call)).ifPresent(board -> board.jobs(kind).forEach(job -> list.items.add(job(job))));
			return list;
		});
		natives.register("take_job", 10, call -> {
			Object[] args = Args.bind("take_job", call, 0, "kind");
			String kind = kindOrAny(args[0]);
			AntBot bot = WorkApi.bot(run, call);
			return board(bot).flatMap(board -> board.take(kind, bot.getUUID())).<Object>map(JobApi::job).orElse(PyNone.NONE);
		});
		natives.register("finish_job", 10, call -> {
			long id = Args.integer(Args.bind("finish_job", call, 1, "id")[0]);
			return board(WorkApi.bot(run, call)).map(board -> board.finish(id)).orElse(false);
		});
		natives.register("drop_job", 10, call -> {
			long id = Args.integer(Args.bind("drop_job", call, 1, "id")[0]);
			return board(WorkApi.bot(run, call)).map(board -> board.drop(id)).orElse(false);
		});
	}

	private static Optional<JobBoard> board(AntBot bot) {
		return bot.getColony().map(found -> found.colony().jobs());
	}

	private static String kind(Object value) {
		if (!(value instanceof String kind)) {
			throw new ScriptError("a job's kind is a string, not '" + Values.typeName(value) + "'");
		}
		return kind;
	}

	private static @Nullable String kindOrAny(Object value) {
		return Args.absent(value) || value == PyNone.NONE ? null : kind(value);
	}

	/** A job as plain data, its data a copy of what was posted. */
	private static PyDict job(JobBoard.Job job) {
		PyDict dict = new PyDict();
		dict.put("id", job.id());
		dict.put("kind", job.kind());
		dict.put("data", Memory.decode(job.data()));
		dict.put("taker", job.taker().<Object>map(UUID::toString).orElse(PyNone.NONE));
		dict.put("poster", job.poster().toString());
		return dict;
	}
}
