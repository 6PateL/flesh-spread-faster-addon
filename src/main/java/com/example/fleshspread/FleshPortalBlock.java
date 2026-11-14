package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Мембрана порталу плоті. Після запалювання рамки вона «спить» (active=false) і нікуди не веде; артефакт Made in Heaven,
 * кинутий у неї, пробуджує портал (active=true).
 */
public class FleshPortalBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    private static final VoxelShape X_SHAPE = Block.box(0.0D, 0.0D, 6.0D, 16.0D, 16.0D, 10.0D);
    private static final VoxelShape Z_SHAPE = Block.box(6.0D, 0.0D, 0.0D, 10.0D, 16.0D, 16.0D);

    public FleshPortalBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).noCollission().strength(-1.0F)
                .sound(SoundType.SLIME_BLOCK).lightLevel(s -> s.getValue(ACTIVE) ? 11 : 3).noLootTable()
                .pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X).setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, ACTIVE);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return state.getValue(AXIS) == Direction.Axis.Z ? Z_SHAPE : X_SHAPE;
    }

    /** Мембрана тримається лише в цілій рамці: щойно поруч (у площині порталу) зникає рамка чи мембрана, вона рветься. */
    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighbor, LevelAccessor level, BlockPos pos,
            BlockPos neighborPos) {
        boolean inPlane = dir.getAxis() == Direction.Axis.Y || dir.getAxis() == state.getValue(AXIS);
        if (inPlane && !neighbor.is(this) && !neighbor.is(FleshDim.FRAME.get())) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, dir, neighbor, level, pos, neighborPos);
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!level.isClientSide) {
            FleshDim.onPortalTouch(level, pos, state, entity);
        }
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(ACTIVE) && random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.CRIMSON_SPORE, pos.getX() + random.nextDouble(), pos.getY() + random.nextDouble(),
                    pos.getZ() + random.nextDouble(), (random.nextDouble() - 0.5D) * 0.2D, 0.02D, (random.nextDouble() - 0.5D) * 0.2D);
        }
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return ItemStack.EMPTY;
    }
}
