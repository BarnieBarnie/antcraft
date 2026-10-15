package io.github.barniebarnie.antcraft.block;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** The heart of a colony: placing it founds a colony, its storage holds what the bots gather. */
public class ColonyCoreBlock extends BaseEntityBlock {
	public ColonyCoreBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		super.onPlace(state, level, pos, oldState, movedByPiston);
		if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
			ColonyManager.get(serverLevel).create(pos);
		}
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		ColonyManager.get(level).remove(pos);
		Containers.updateNeighboursAfterDestroy(state, level, pos);
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
		if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof ColonyCoreBlockEntity core) {
			player.openMenu(core);
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(BlockPos worldPosition, BlockState blockState) {
		return new ColonyCoreBlockEntity(worldPosition, blockState);
	}
}
