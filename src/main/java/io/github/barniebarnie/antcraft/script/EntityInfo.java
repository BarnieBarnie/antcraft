package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.HostValue;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import org.jspecify.annotations.Nullable;

/**
 * An entity in a script, the {@code Entity} type (docs/scripting.md, Types): {@code id} (its UUID as a string),
 * {@code type} such as {@code minecraft:cow}, {@code pos} (the block it stands in), {@code health} ({@code None} for an
 * entity without health, such as a dropped item), {@code valid} (false once it is gone: dead, picked up, or unloaded)
 * and, for a dropped item, {@code item}, the {@code Stack} it holds ({@code None} for other entities). Live: its
 * attributes are read when the script asks for them. Equal by UUID. Not plain data, so not for {@code memory}; a saved
 * script keeps it by UUID ({@link #saved}).
 */
public final class EntityInfo implements HostValue {
	public static final String TYPE = "Entity";

	private final UUID uuid;
	private final String type;
	/** Null until a saved one's entity is found. */
	private @Nullable Entity entity;
	/** How a saved one finds its entity; null for one made from its entity. */
	private final @Nullable Function<UUID, @Nullable Entity> lookup;

	public EntityInfo(Entity entity) {
		this.uuid = entity.getUUID();
		this.type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		this.entity = entity;
		this.lookup = null;
	}

	private EntityInfo(UUID uuid, String type, Function<UUID, @Nullable Entity> lookup) {
		this.uuid = uuid;
		this.type = type;
		this.lookup = lookup;
	}

	/**
	 * An entity of a saved script (docs/scripting.md, Save and resume): {@code lookup} finds it by its UUID once the
	 * script uses it. Until it is found it is not valid, and has no position, health or item.
	 */
	public static EntityInfo saved(UUID uuid, String type, Function<UUID, @Nullable Entity> lookup) {
		return new EntityInfo(uuid, type, lookup);
	}

	/** The entity, or null while a saved one is not found. */
	public @Nullable Entity entity() {
		if (this.entity == null && this.lookup != null) {
			this.entity = this.lookup.apply(this.uuid);
		}
		return this.entity;
	}

	public UUID uuid() {
		return this.uuid;
	}

	/** Its type, such as {@code minecraft:cow}. */
	public String type() {
		return this.type;
	}

	public boolean valid() {
		Entity found = this.entity();
		return found != null && !found.isRemoved();
	}

	@Override
	public String typeName() {
		return TYPE;
	}

	@Override
	public @Nullable Object attribute(String name) {
		Entity found = this.entity();
		return switch (name) {
			case "id" -> this.uuid.toString();
			case "type" -> this.type;
			case "pos" -> found == null ? PyNone.NONE : pos(found.blockPosition());
			case "health" -> found instanceof LivingEntity living ? (Object) (double) living.getHealth() : PyNone.NONE;
			case "valid" -> this.valid();
			case "item" -> found instanceof ItemEntity item ? SenseApi.stack(item.getItem()) : PyNone.NONE;
			default -> null;
		};
	}

	static Pos pos(BlockPos pos) {
		return new Pos(pos.getX(), pos.getY(), pos.getZ());
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof EntityInfo info && info.uuid.equals(this.uuid);
	}

	@Override
	public int hashCode() {
		return this.uuid.hashCode();
	}

	@Override
	public String toString() {
		return "Entity(" + this.type + ", " + this.uuid + ")";
	}
}
