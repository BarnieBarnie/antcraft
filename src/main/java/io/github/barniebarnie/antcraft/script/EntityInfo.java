package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.HostValue;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
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
 * attributes are read when the script asks for them. Equal by UUID. Not plain data, so not for {@code memory}.
 */
public final class EntityInfo implements HostValue {
	public static final String TYPE = "Entity";

	private final Entity entity;

	public EntityInfo(Entity entity) {
		this.entity = entity;
	}

	public Entity entity() {
		return this.entity;
	}

	public boolean valid() {
		return !this.entity.isRemoved();
	}

	@Override
	public String typeName() {
		return TYPE;
	}

	@Override
	public @Nullable Object attribute(String name) {
		return switch (name) {
			case "id" -> this.entity.getStringUUID();
			case "type" -> BuiltInRegistries.ENTITY_TYPE.getKey(this.entity.getType()).toString();
			case "pos" -> pos(this.entity.blockPosition());
			case "health" -> this.entity instanceof LivingEntity living ? (Object) (double) living.getHealth() : PyNone.NONE;
			case "valid" -> this.valid();
			case "item" -> this.entity instanceof ItemEntity item ? SenseApi.stack(item.getItem()) : PyNone.NONE;
			default -> null;
		};
	}

	static Pos pos(BlockPos pos) {
		return new Pos(pos.getX(), pos.getY(), pos.getZ());
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof EntityInfo info && info.entity.getUUID().equals(this.entity.getUUID());
	}

	@Override
	public int hashCode() {
		return this.entity.getUUID().hashCode();
	}

	@Override
	public String toString() {
		return "Entity(" + BuiltInRegistries.ENTITY_TYPE.getKey(this.entity.getType()) + ", " + this.entity.getStringUUID() + ")";
	}
}
