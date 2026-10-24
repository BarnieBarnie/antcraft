package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.pathing.WorldView;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import java.util.List;

/**
 * The projects a newly founded colony starts by itself, so every bot has work from the first minute: the starter tool
 * kit, then the mineshaft (mining from it waits until the colony wants what it yields). The player decides what else
 * the colony builds, such as a farm or a warehouse, by placing blueprints.
 */
public final class StarterProjects {
	private StarterProjects() {
	}

	/** Adds the starter projects to {@code colony}, the tool kit first, with its mineshaft's levels at {@code shaftLevels}. */
	public static void start(Colony colony, WorldView view, List<Integer> shaftLevels) {
		List<Project> started = List.of(
			colony.addProject(Project.TOOLS, DefaultStock.TOOLS, true),
			colony.setMineshaft(Mineshaft.nextTo(colony.core(), shaftLevels, List.of(), view)));
		// The order of the list is the priority: tools first.
		for (int i = 0; i < started.size(); i++) {
			colony.setPriority(started.get(i).id(), started.size() - i);
		}
	}
}
