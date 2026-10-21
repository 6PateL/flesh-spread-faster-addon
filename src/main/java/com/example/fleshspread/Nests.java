package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Усе, що стосується гнізд (інкубаторів Flesh That Hates).
 * Моди не залежать один від одного на рівні коду: все береться з реєстрів за id.
 */
public final class Nests {
    public static final String BORN = "flesh_spread_born";
    private static final String FTH = "flesh_that_hates";

    private Nests() {}

    // ------------------------------------------------------------ типи

    private static EntityType<?> type(String path) {
        ResourceLocation id = new ResourceLocation(FTH, path);
        return ForgeRegistries.ENTITY_TYPES.containsKey(id) ? ForgeRegistries.ENTITY_TYPES.getValue(id) : null;
    }

    public static EntityType<?> small() { return type("small_scp_6106"); }
    public static EntityType<?> big() { return type("big_scp_6106"); }

    public static boolean isIncubator(Entity e) {
        EntityType<?> t = e.getType();
        return t == small() || t == big();
    }

    public static int count(ServerLevel level, BlockPos center, int radius) {
        AABB box = new AABB(center.getX() - radius, level.getMinBuildHeight(), center.getZ() - radius,
                center.getX() + radius, level.getMaxBuildHeight(), center.getZ() + radius);
        return level.getEntities((Entity) null, box, Nests::isIncubator).size();
    }

    // ------------------------------------------------------------ місце

    /** Поверхня в точці (x, z) або null, якщо там вода/дерево/нічого. */
    public static BlockPos findSurface(ServerLevel level, int x, int z) {
        if (!level.hasChunkAt(new BlockPos(x, 64, z))) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos pos = new BlockPos(x, y, z);
        BlockPos below = pos.below();
        BlockState bs = level.getBlockState(below);
        if (bs.isAir() || bs.is(BlockTags.LOGS) || bs.is(BlockTags.LEAVES)) return null;
        if (!level.getFluidState(below).isEmpty() || !level.getFluidState(pos).isEmpty()) return null;
        return pos;
    }

    // ------------------------------------------------------------ створення

    public static boolean spawnNest(ServerLevel level, BlockPos pos, int patchRadius) {
        EntityType<?> small = small();
        if (small == null) return false;
        Entity e = small.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
        if (e == null) return false;
        e.setDeltaMovement(Vec3.ZERO);
        e.getPersistentData().putLong(BORN, level.getGameTime());
        infectPatch(level, pos, patchRadius);
        playSound(level, pos, "entity.incubator.spawn");
        return true;
    }

    /** Малий -> великий: те саме, що робить гравець, коли годує інкубатор, тільки без гравця. */
    public static void grow(ServerLevel level, Entity small) {
        EntityType<?> big = big();
        if (big == null) return;
        BlockPos pos = small.blockPosition();
        Entity b = big.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
        if (b == null) return;
        b.setYRot(small.getYRot());
        b.setDeltaMovement(Vec3.ZERO);
        b.getPersistentData().putLong(BORN, level.getGameTime());
        small.discard();
        playSound(level, pos, "entity.incubator.grow");
    }

    /** Невелика заражена пляма навколо гнізда, щоб його було видно здалеку. */
    private static void infectPatch(ServerLevel level, BlockPos center, int radius) {
        if (radius <= 0) return;
        Block floor = block("flesh_block");
        Block dirt = block("flesh_dirt");
        Block sand = block("flesh_sand");
        TagKey<Block> tFloor = tag("infectable_floor");
        TagKey<Block> tDirt = tag("infectable_dirt");
        TagKey<Block> tSand = tag("infectable_sand");

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                if (level.random.nextFloat() > 0.75f) continue;
                int x = center.getX() + dx, z = center.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, 64, z))) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos p = new BlockPos(x, y, z);
                BlockState s = level.getBlockState(p);
                if (floor != null && s.is(tFloor)) level.setBlock(p, floor.defaultBlockState(), 3);
                else if (dirt != null && s.is(tDirt)) level.setBlock(p, dirt.defaultBlockState(), 3);
                else if (sand != null && s.is(tSand)) level.setBlock(p, sand.defaultBlockState(), 3);
            }
        }
    }

    private static Block block(String path) {
        ResourceLocation id = new ResourceLocation(FTH, path);
        return ForgeRegistries.BLOCKS.containsKey(id) ? ForgeRegistries.BLOCKS.getValue(id) : null;
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, new ResourceLocation(FTH, path));
    }

    private static void playSound(ServerLevel level, BlockPos pos, String path) {
        ResourceLocation id = new ResourceLocation(FTH, path);
        if (!ForgeRegistries.SOUND_EVENTS.containsKey(id)) return;
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(id);
        if (sound != null) level.playSound(null, pos, sound, SoundSource.HOSTILE, 6.0f, 1.0f);
    }
}
