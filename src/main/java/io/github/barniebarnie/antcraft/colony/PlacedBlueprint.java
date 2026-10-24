package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * @param water where the structure pours water sources, such as a farm's, which the farmer pours back once gone
 * @param waiting the structure's plan while the colony levels the site first; it is added to the project once the levelling is done
 * @param finished whether the structure is built
 */
public record PlacedBlueprint(UUID project, Identifier blueprint, String name, BlockPos at, Rotation rotation, Mirror mirror, BoundingBox area,
		Optional<Blueprint.Role> role, List<BlockPos> chests, List<BlockPos> workSpots, List<BlockPos> water, Optional<BuildPlan> waiting, boolean finished) {
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
		BuildPlan.CODEC.optionalFieldOf("waiting").forGetter(PlacedBlueprint::waiting),
		Codec.BOOL.optionalFieldOf("finished", false).forGetter(PlacedBlueprint::finished)
	).apply(i, PlacedBlueprint::new));

	public PlacedBlueprint {
		chests = List.copyOf(chests);
		workSpots = List.copyOf(workSpots);
		water = List.copyOf(water);
	}

	/** The blueprint's name, translated. */
	public Component displayName() {
		return Component.translatable(this.name);
	}

	PlacedBlueprint withProject(UUID project, Optional<BuildPlan> waiting) {
		return new PlacedBlueprint(project, this.blueprint, this.name, this.at, this.rotation, this.mirror, this.area, this.role, this.chests,
			this.workSpots, this.water, waiting, this.finished);
	}

	/** This with {@code water} where its structure pours water. */
	PlacedBlueprint withWater(List<BlockPos> water) {
		return new PlacedBlueprint(this.project, this.blueprint, this.name, this.at, this.rotation, this.mirror, this.area, this.role, this.chests,
			this.workSpots, water, this.waiting, this.finished);
	}

	PlacedBlueprint built() {
		return new PlacedBlueprint(this.project, this.blueprint, this.name, this.at, this.rotation, this.mirror, this.area, this.role, this.chests,
			this.workSpots, this.water, Optional.empty(), true);
	}
}
