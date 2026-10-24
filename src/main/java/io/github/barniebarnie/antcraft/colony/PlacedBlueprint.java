package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A blueprint a player placed for the colony to build (see {@link BlueprintPlacement}), saved with the colony: which
 * blueprint, where and how it is turned, and what it is for with its spots as built, so the colony can use the building
 * once it stands. It stays after it is {@code finished}, for as long as the colony lasts; cancelled, it is forgotten.
 *
 * @param project the colony project that builds it, named after the blueprint
 * @param blueprint the blueprint's id, such as {@code antcraft:starter_house}
 * @param name the lang key of the blueprint's name
 * @param at the lowest corner of the turned structure
 * @param area the box the turned structure fills
 * @param role what the colony uses it for, if anything
 * @param chests where its chests stand, turned with the structure
 * @param workSpots its work spots, turned with the structure
 * @param water where the structure pours water sources, such as a farm's or a generator's, which builders pour back once gone
 * @param lava where the structure pours lava sources, such as a generator's, which builders pour back once gone
 * @param waiting the structure's plan while the colony levels the site first; it is added to the project once the levelling is done
 * @param finished whether the structure is built
 * @param restoreFluids whether builders pour its water and lava back once gone; off once the player cancels or gives up
 *     such a plan (see {@link Colony#restoreFluids}), until {@code colony build restore} turns it on again
 * @param restoring the id of the last plan the colony added to pour its fluids back, so only cancelling that plan, not a
 *     player's own plan at the same spots, turns the restore off
 */
public record PlacedBlueprint(UUID project, Identifier blueprint, String name, BlockPos at, Rotation rotation, Mirror mirror, BoundingBox area,
		Optional<Blueprint.Role> role, List<BlockPos> chests, List<BlockPos> workSpots, List<BlockPos> water, List<BlockPos> lava, Optional<BuildPlan> waiting, boolean finished,
		boolean restoreFluids, Optional<UUID> restoring) {
	public static final Codec<PlacedBlueprint> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.STRING_CODEC.fieldOf("project").forGetter(PlacedBlueprint::project),
		Identifier.CODEC.fieldOf("blueprint").forGetter(PlacedBlueprint::blueprint),
		Codec.STRING.fieldOf("name").forGetter(PlacedBlueprint::name),
		BlockPos.CODEC.fieldOf("at").forGetter(PlacedBlueprint::at),
		Rotation.CODEC.optionalFieldOf("rotation", Rotation.NONE).forGetter(PlacedBlueprint::rotation),
		Mirror.CODEC.optionalFieldOf("mirror", Mirror.NONE).forGetter(PlacedBlueprint::mirror),
		BoundingBox.CODEC.fieldOf("area").forGetter(PlacedBlueprint::area),
		Blueprint.Role.CODEC.optionalFieldOf("role").forGetter(PlacedBlueprint::role),
		BlockPos.CODEC.listOf().optionalFieldOf("chests", List.of()).forGetter(PlacedBlueprint::chests),
		BlockPos.CODEC.listOf().optionalFieldOf("work_spots", List.of()).forGetter(PlacedBlueprint::workSpots),
		BlockPos.CODEC.listOf().optionalFieldOf("water", List.of()).forGetter(PlacedBlueprint::water),
		BlockPos.CODEC.listOf().optionalFieldOf("lava", List.of()).forGetter(PlacedBlueprint::lava),
		BuildPlan.CODEC.optionalFieldOf("waiting").forGetter(PlacedBlueprint::waiting),
		Codec.BOOL.optionalFieldOf("finished", false).forGetter(PlacedBlueprint::finished),
		Codec.BOOL.optionalFieldOf("restore_fluids", true).forGetter(PlacedBlueprint::restoreFluids),
		UUIDUtil.STRING_CODEC.optionalFieldOf("restoring").forGetter(PlacedBlueprint::restoring)
	).apply(i, PlacedBlueprint::new));

	/** A blueprint no plan has poured the fluids of yet. */
	public PlacedBlueprint(UUID project, Identifier blueprint, String name, BlockPos at, Rotation rotation, Mirror mirror, BoundingBox area,
			Optional<Blueprint.Role> role, List<BlockPos> chests, List<BlockPos> workSpots, List<BlockPos> water, List<BlockPos> lava,
			Optional<BuildPlan> waiting, boolean finished, boolean restoreFluids) {
		this(project, blueprint, name, at, rotation, mirror, area, role, chests, workSpots, water, lava, waiting, finished, restoreFluids, Optional.empty());
	}

	public PlacedBlueprint {
		chests = List.copyOf(chests);
		workSpots = List.copyOf(workSpots);
		water = List.copyOf(water);
		lava = List.copyOf(lava);
	}

	/** The blueprint's name, translated. */
	public Component displayName() {
		return Component.translatable(this.name);
	}

	PlacedBlueprint withProject(UUID project, Optional<BuildPlan> waiting) {
		return new PlacedBlueprint(project, this.blueprint, this.name, this.at, this.rotation, this.mirror, this.area, this.role, this.chests,
			this.workSpots, this.water, this.lava, waiting, this.finished, this.restoreFluids, this.restoring);
	}

	/** This with {@code water} and {@code lava} where its structure pours those sources. */
	PlacedBlueprint withFluids(List<BlockPos> water, List<BlockPos> lava) {
		return new PlacedBlueprint(this.project, this.blueprint, this.name, this.at, this.rotation, this.mirror, this.area, this.role, this.chests,
			this.workSpots, water, lava, this.waiting, this.finished, this.restoreFluids, this.restoring);
	}

	/** This with where {@code structure}, its structure's plan, pours water and lava sources. */
	PlacedBlueprint withFluidsOf(BuildPlan structure) {
		return this.withFluids(sources(structure, Blocks.WATER), sources(structure, Blocks.LAVA));
	}

	private static List<BlockPos> sources(BuildPlan structure, Block fluid) {
		return structure.steps().stream().filter(step -> step.state().is(fluid) && step.state().getFluidState().isSource())
			.map(BuildPlan.Step::pos).toList();
	}

	/** This with builders no longer pouring its water and lava back. */
	PlacedBlueprint withoutFluidRestore() {
		return new PlacedBlueprint(this.project, this.blueprint, this.name, this.at, this.rotation, this.mirror, this.area, this.role, this.chests,
			this.workSpots, this.water, this.lava, this.waiting, this.finished, false, Optional.empty());
	}

	/** This with builders pouring its water and lava back again once gone. */
	PlacedBlueprint withFluidRestore() {
		return new PlacedBlueprint(this.project, this.blueprint, this.name, this.at, this.rotation, this.mirror, this.area, this.role, this.chests,
			this.workSpots, this.water, this.lava, this.waiting, this.finished, true, Optional.empty());
	}

	/** This with {@code plan} the plan that pours its fluids back now. */
	PlacedBlueprint restoringWith(UUID plan) {
		return new PlacedBlueprint(this.project, this.blueprint, this.name, this.at, this.rotation, this.mirror, this.area, this.role, this.chests,
			this.workSpots, this.water, this.lava, this.waiting, this.finished, this.restoreFluids, Optional.of(plan));
	}

	/**
	 * Where a plan to pour back its fluids has steps: its water and lava, and for a generator its mining block, which is
	 * mined to pour the lava through.
	 */
	Set<BlockPos> fluidSpots() {
		Set<BlockPos> spots = new HashSet<>(this.water);
		spots.addAll(this.lava);
		if (this.role.equals(Optional.of(Blueprint.Role.GENERATOR)) && !this.workSpots.isEmpty()) {
			spots.add(this.workSpots.getFirst());
		}
		return spots;
	}

	PlacedBlueprint built() {
		return new PlacedBlueprint(this.project, this.blueprint, this.name, this.at, this.rotation, this.mirror, this.area, this.role, this.chests,
			this.workSpots, this.water, this.lava, Optional.empty(), true, this.restoreFluids, this.restoring);
	}
}
