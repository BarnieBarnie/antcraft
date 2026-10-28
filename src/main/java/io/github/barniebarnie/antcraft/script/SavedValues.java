package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.VmState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * The bot API's values in a saved script (docs/scripting.md, Save and resume): {@code Pos}, {@code Stack}, {@code Block}
 * and {@code Result} as plain data, and an {@code Entity} by its UUID, which may come back as no longer valid. Each is
 * saved as a tuple that starts with its type name.
 */
public final class SavedValues implements VmState.HostCodec {
	private final Function<UUID, @Nullable Entity> entities;

	/** Values whose entities are looked up, once they are used, with {@code entities}. */
	public SavedValues(Function<UUID, @Nullable Entity> entities) {
		this.entities = entities;
	}

	@Override
	public @Nullable Object save(Object value) {
		return switch (value) {
			case Pos p -> PyTuple.of(Pos.TYPE, p.x(), p.y(), p.z());
			case Stack s -> PyTuple.of(Stack.TYPE, s.item(), s.count());
			case BlockInfo b -> PyTuple.of(BlockInfo.TYPE, b.id(), b.pos(), b.air(), b.fluid(), new PyTuple(new ArrayList<>(b.tags())));
			case Result r -> PyTuple.of(Result.TYPE, r.ok(), r.reason() == null ? PyNone.NONE : r.reason(), r.value());
			case EntityInfo e -> PyTuple.of(EntityInfo.TYPE, e.uuid().toString(), e.type());
			default -> null;
		};
	}

	@Override
	public Object load(Object saved) {
		List<Object> f = ((PyTuple) saved).items();
		return switch ((String) f.getFirst()) {
			case Pos.TYPE -> new Pos((Long) f.get(1), (Long) f.get(2), (Long) f.get(3));
			case Stack.TYPE -> new Stack((String) f.get(1), (Long) f.get(2));
			case BlockInfo.TYPE -> new BlockInfo((String) f.get(1), (Pos) f.get(2), (Boolean) f.get(3), (Boolean) f.get(4),
					((PyTuple) f.get(5)).items().stream().map(String.class::cast).toList());
			case Result.TYPE -> new Result((Boolean) f.get(1), f.get(2) instanceof String reason ? reason : null, f.get(3));
			case EntityInfo.TYPE -> EntityInfo.saved(UUID.fromString((String) f.get(1)), (String) f.get(2), entities);
			default -> throw new IllegalArgumentException("unknown saved value " + f.getFirst());
		};
	}
}
