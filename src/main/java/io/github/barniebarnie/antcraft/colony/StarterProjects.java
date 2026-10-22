package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.pathing.WorldView;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.Sites;
import io.github.barniebarnie.antcraft.work.StorageArea;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

/**
 * The projects a newly founded colony starts by itself, so every bot has work from the first minute. In priority
 * order: the starter tool kit, a farm, the mineshaft (mining from it waits until the colony wants what it yields),
 * the storage area, and a small house with beds.
 */
public final class StarterProjects {
	/** The bundled structure the house is built from. */
	public static final Identifier HOUSE = AntCraft.id("starter_house");

	private StarterProjects() {
	}

	/**
	 * Adds the starter projects to {@code colony}, each on a site clear of the ones before it, where every block is
	 * {@code workable}. One whose site cannot be found or that has nothing to do is left out; so is the house without
	 * its {@code house} template.
	 */
	public static void start(Colony colony, Function<BlockPos, BlockState> world, WorldView view, Predicate<BlockPos> workable,
			List<Integer> shaftLevels, @Nullable StructureTemplate house) {
		BlockPos core = colony.core();
		int radius = colony.radius();
		List<Project> started = new ArrayList<>();
		List<BoundingBox> avoid = new ArrayList<>();
		started.add(colony.addProject(Project.TOOLS, DefaultStock.TOOLS));
		Field.site(core, radius, avoid, world, workable).ifPresent(field -> {
			colony.setField(field);
			avoid.add(field.area());
			field.plan(world).ifPresent(plan -> started.add(colony.addBuild(Project.FARM, plan)));
		});
		Mineshaft shaft = Mineshaft.nextTo(core, shaftLevels, avoid, view);
		started.add(colony.setMineshaft(shaft));
		avoid.add(shaft.area());
		StorageArea.site(core, radius, avoid, world, workable).ifPresent(storage -> {
			colony.setStorage(storage);
			avoid.add(storage.area());
			storage.plan(world).ifPresent(plan -> started.add(colony.addBuild(Project.STORAGE, plan)));
		});
		if (house != null) {
			Sites.box(core, radius - house.getSize().getX(), avoid, house.getSize(), workable)
				.flatMap(box -> RegionPlan.load(house, new BlockPos(box.minX(), box.minY(), box.minZ()), Mirror.NONE, Rotation.NONE, 0))
				.ifPresent(plan -> started.add(colony.addBuild(Project.HOUSE, plan)));
		}
		// The order of the list is the priority: tools first.
		for (int i = 0; i < started.size(); i++) {
			colony.setPriority(started.get(i).id(), started.size() - i);
		}
	}
}
