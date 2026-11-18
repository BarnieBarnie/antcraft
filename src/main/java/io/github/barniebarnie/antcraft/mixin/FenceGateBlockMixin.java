package io.github.barniebarnie.antcraft.mixin;

import io.github.barniebarnie.antcraft.entity.BotTemptGoal;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A fence gate a bot opened to walk through stays shut to animals (#863): walking out of a pen through animals crowded
 * at its gate, the bot shoved one out ahead of it, or the crowd pressed one through behind it. A gate opened otherwise,
 * such as by jobs.ranch's {@code use} to lure an animal in, is open to them as usual, but an animal following a bot
 * that holds its food ({@link BotTemptGoal}) goes through it only the way it swung open, away from whoever opened it
 * (#879): a lured animal follows the bot in, a penned one does not follow it out. Where it was opened by hand, a
 * player's or a bot's use, and it is a pen's gate, the way in counts, not the swing ({@link PathFollower#usedOpen},
 * #884, #905): opened from inside, it swings out.
 * <p>
 * Shut to an animal, it is {@link PathFollower#shutTo}'s panel.
 */
@Mixin(FenceGateBlock.class)
abstract class FenceGateBlockMixin {
	@Inject(method = "getCollisionShape", at = @At("HEAD"), cancellable = true)
	private void antcraft$shutToAnimals(BlockState state, BlockGetter getter, BlockPos pos, CollisionContext context,
		CallbackInfoReturnable<VoxelShape> cir) {
		if (state.getValue(BlockStateProperties.OPEN) && context instanceof EntityCollisionContext entityContext
			&& entityContext.getEntity() instanceof Animal animal
			&& (PathFollower.openedByBot(animal.level(), pos) || BotTemptGoal.leads(animal) && keptIn(state, pos, animal))) {
			cir.setReturnValue(PathFollower.shutTo(animal, state, getter, pos));
		}
	}

	/** Has {@link PathFollower#usedOpen} note the pen's side of a gate opened or shut by hand (#905). */
	@Inject(method = "useWithoutItem", at = @At("RETURN"))
	private void antcraft$noteInward(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit,
		CallbackInfoReturnable<InteractionResult> cir) {
		if (level instanceof ServerLevel server) {
			PathFollower.usedOpen(server, pos);
		}
	}

	/**
	 * Whether the animal is on the pen's side of a gate opened by hand ({@link PathFollower#usedOpen}, #884), or,
	 * where that found no pen, on the side the open gate swung to, which vanilla turns away from the one who opened it.
	 */
	private static boolean keptIn(BlockState state, BlockPos pos, Animal animal) {
		Direction inward = PathFollower.inward(animal.level(), pos);
		return PathFollower.inside(animal, pos, inward != null ? inward : state.getValue(BlockStateProperties.HORIZONTAL_FACING));
	}
}
