package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.VmState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The bot API's values in a saved script (docs/scripting.md, Save and resume): {@code Pos}, {@code Stack}, {@code Block}
 * and {@code Result} as plain data, and an {@code Entity} by its UUID, which may come back as no longer valid. Each is
 * saved as a tuple that starts with its type name.
 */
public final class SavedValues implements VmState.HostCodec {
	/** How the colony's {@code colony_memory} is saved: by reference, never a copy of it. */
	private static final String COLONY_MEMORY = "ColonyMemory";

	private final Function<UUID, @Nullable Entity> entities;
	private final Supplier<Object> colonyMemory;

	/** Values whose entities are looked up, once they are used, with {@code entities}; no colony memory. */
	public SavedValues(Function<UUID, @Nullable Entity> entities) {
		this(entities, () -> PyNone.NONE);
	}

	/**
	 * Values whose entities are looked up with {@code entities}, and in which the bot's {@code colony_memory}, as
	 * {@code colonyMemory} gives it, is saved as a reference that loads as the colony's live dict (#365).
	 */
	public SavedValues(Function<UUID, @Nullable Entity> entities, Supplier<Object> colonyMemory) {
		this.entities = entities;
		this.colonyMemory = colonyMemory;
	}

	@Override
	public @Nullable Object save(Object value) {
		return switch (value) {
			case Pos p -> p.dimension() == null ? PyTuple.of(Pos.TYPE, p.x(), p.y(), p.z())
				: PyTuple.of(Pos.TYPE, p.x(), p.y(), p.z(), p.dimension());
			case Stack s -> PyTuple.of(Stack.TYPE, s.item(), s.count());
			case BlockInfo b -> PyTuple.of(BlockInfo.TYPE, b.id(), b.pos(), b.air(), b.fluid(), new PyTuple(new ArrayList<>(b.tags())),
				new PyTuple(b.state().entrySet().stream().<Object>map(e -> PyTuple.of(e.getKey(), e.getValue())).toList()));
			case Result r -> PyTuple.of(Result.TYPE, r.ok(), r.reason() == null ? PyNone.NONE : r.reason(), r.value());
			case EntityInfo e -> PyTuple.of(EntityInfo.TYPE, e.uuid().toString(), e.type());
			case PyDict d when d == colonyMemory.get() -> PyTuple.of(COLONY_MEMORY);
			default -> null;
		};
	}

	/** A block's state saved as (name, value) pairs; a block saved before #369 has none. */
	private static Map<String, Object> state(PyTuple saved) {
		Map<String, Object> state = new HashMap<>();
		for (Object entry : saved.items()) {
			List<Object> pair = ((PyTuple) entry).items();
			state.put((String) pair.get(0), pair.get(1));
		}
		return state;
	}

	@Override
	public Object load(Object saved) {
		List<Object> f = ((PyTuple) saved).items();
		return switch ((String) f.getFirst()) {
			case Pos.TYPE -> new Pos((Long) f.get(1), (Long) f.get(2), (Long) f.get(3), f.size() > 4 ? (String) f.get(4) : null);
			case Stack.TYPE -> new Stack((String) f.get(1), (Long) f.get(2));
			case BlockInfo.TYPE -> new BlockInfo((String) f.get(1), (Pos) f.get(2), (Boolean) f.get(3), (Boolean) f.get(4),
					((PyTuple) f.get(5)).items().stream().map(String.class::cast).toList(), f.size() > 6 ? state((PyTuple) f.get(6)) : Map.of());
			case Result.TYPE -> new Result((Boolean) f.get(1), f.get(2) instanceof String reason ? reason : null, f.get(3));
			case EntityInfo.TYPE -> EntityInfo.saved(UUID.fromString((String) f.get(1)), (String) f.get(2), entities);
			// ponytail: the colony the bot is in now; a bot no longer in one gets an empty dict that nothing shares
			case COLONY_MEMORY -> colonyMemory.get() instanceof PyDict live ? live : Memory.create();
			default -> throw new IllegalArgumentException("unknown saved value " + f.getFirst());
		};
	}
}
