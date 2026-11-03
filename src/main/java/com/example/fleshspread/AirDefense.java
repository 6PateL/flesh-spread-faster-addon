package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/**
 * ППО вірусу. Коли поруч із зараженням запалюють ТНТ (зверху), над цим місцем з'являються літаючі істоти Flesh That
 * Hates (SCP-6107). Вони стрибками добираються до висоти підривної установки (роздатчики, поршні, блоки слизу, ТНТ) і
 * ламають усе, до чого дістали.
 */
public final class AirDefense {
    public static final String AA = "fs_aa";
    public static final String AA_END = "fs_aa_end";
    public static final String AA_DEV = "fs_aa_dev";

    private static long lastTrigger = Long.MIN_VALUE / 2;

    private AirDefense() {}

    private static final TagKey<EntityType<?>> FLESH_MOB =
            TagKey.create(Registries.ENTITY_TYPE, new ResourceLocation("flesh_that_hates", "flesh_mob"));

    public static boolean isDevice(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.DISPENSER || b == Blocks.DROPPER || b == Blocks.PISTON || b == Blocks.STICKY_PISTON
                || b == Blocks.SLIME_BLOCK || b == Blocks.TNT;
    }

    // ------------------------------------------------------------------ тригер

    /** Запалений ТНТ поруч зі зараженням (над ним) = спроба підриву. */
    public static void onPrimedTnt(ServerLevel level, PrimedTnt tnt) {
        check(level, tnt.blockPosition(), true);
    }

    /** Поставлений ТНТ / слиз / роздатчик / викинутий молотов поруч зі зараженням: теж тривога (хай навіть хибна). */
    public static void onSuspicious(ServerLevel level, BlockPos pos, boolean needAbove) {
        check(level, pos, needAbove);
    }

    private static void check(ServerLevel level, BlockPos pos, boolean needAbove) {
        SpreadConfig cfg = SpreadConfig.get();
        if (!cfg.enabled || !cfg.aaEnabled || !cfg.dimensions.contains(level.dimension().location().toString())) return;
        long now = level.getGameTime();
        if (now - lastTrigger < cfg.aaCooldownSeconds * 20L) return;

        int r = cfg.aaTriggerRadius;
        AABB box = new AABB(pos).inflate(r, 96, r);
        List<Entity> infected = level.getEntities((Entity) null, box, e -> e.getType().is(FLESH_MOB));
        Entity nearest = null;
        double best = Double.MAX_VALUE;
        for (Entity e : infected) {
            if (needAbove && cfg.aaOnlyFromAbove && pos.getY() < e.getY() + 3.0D) continue;
            double d = e.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        if (nearest == null) return;
        lastTrigger = now;
        spawn(level, cfg, pos);
        spawnGround(level, cfg, nearest.blockPosition());
    }

    /** Охорона: початкові істоти з землі біля цілі (одразу, без «чекання»). */
    private static void spawnGround(ServerLevel level, SpreadConfig cfg, BlockPos at) {
        String[] ids = {"scp_6103", "scp_6101", "scp_6103", "scp_6102"};
        int made = 0;
        for (int i = 0; i < cfg.aaGroundUnits; i++) {
            made += spawnGuards(level, at, 1, ids[i % ids.length]);
        }
        FleshSpread.LOGGER.info("ППО вірусу: {} наземних істот біля {}", made, at.toShortString());
    }

    /** Спавнить n істот типу flesh_that_hates:id навколо точки (на поверхні), робить їх постійними. Повертає скільки вийшло. */
    public static int spawnGuards(ServerLevel level, BlockPos at, int n, String idPath) {
        ResourceLocation id = new ResourceLocation("flesh_that_hates", idPath);
        if (!ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            FleshSpread.LOGGER.warn("Немає істоти {}", id);
            return 0;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        int made = 0;
        for (int i = 0; i < n; i++) {
            for (int attempt = 0; attempt < 12; attempt++) {
                BlockPos p = at.offset(level.random.nextInt(9) - 4, 0, level.random.nextInt(9) - 4);
                p = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p);
                if (!level.hasChunkAt(p)) continue;
                Entity e = type.create(level);
                if (e == null) continue;
                e.moveTo(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
                if (e instanceof Mob m) {
                    m.finalizeSpawn(level, level.getCurrentDifficultyAt(p), MobSpawnType.EVENT, null, null);
                    m.setPersistenceRequired();
                }
                if (level.addFreshEntity(e)) {
                    made++;
                    break;
                }
            }
        }
        return made;
    }

    // ------------------------------------------------------------------ спавн

    /** Створює ППО над точкою. Повертає кількість юнітів. */
    public static int spawn(ServerLevel level, SpreadConfig cfg, BlockPos at) {
        ResourceLocation id = new ResourceLocation("flesh_that_hates", "scp_6107");
        if (!ForgeRegistries.ENTITY_TYPES.containsKey(id)) return 0;
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        List<BlockPos> devices = findDevices(level, at, cfg.aaDeviceRadius);
        double day = level.getGameTime() / 24000.0;
        int count = Math.min(cfg.aaMaxUnits, cfg.aaUnits + (int) (day / 6.0));
        long end = level.getGameTime() + cfg.aaLifetimeSeconds * 20L;
        long[] packed = devices.stream().limit(96).mapToLong(BlockPos::asLong).toArray();

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < 10; attempt++) {
                BlockPos p = at.offset(level.random.nextInt(13) - 6, 8 + level.random.nextInt(9), level.random.nextInt(13) - 6);
                if (!level.hasChunkAt(p) || !level.getBlockState(p).isAir()) continue;
                Entity e = type.spawn(level, p, MobSpawnType.EVENT);
                if (e == null) continue;
                CompoundTag d = e.getPersistentData();
                d.putBoolean(AA, true);
                d.putLong(AA_END, end);
                d.putLongArray(AA_DEV, packed);
                if (e instanceof Mob m) attachGoal(m);
                spawned++;
                break;
            }
        }
        if (spawned > 0) {
            FleshSpread.LOGGER.info("ППО вірусу: {} юнітів над {}, цілей: {}", spawned, at.toShortString(), devices.size());
            Component msg = Component.literal("☣ Вірус викликав повітряну оборону!");
            for (ServerPlayer pl : level.players()) {
                if (pl.distanceToSqr(at.getX(), at.getY(), at.getZ()) < 160 * 160) pl.displayClientMessage(msg, true);
            }
        }
        return spawned;
    }

    /** Установка для підриву: роздатчики / поршні / слиз / ТНТ навколо точки. */
    static List<BlockPos> findDevices(ServerLevel level, BlockPos at, int radius) {
        List<BlockPos> out = new ArrayList<>();
        int minY = Math.max(level.getMinBuildHeight(), at.getY() - 8);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, at.getY() + 24);
        for (BlockPos p : BlockPos.betweenClosed(at.getX() - radius, minY, at.getZ() - radius,
                at.getX() + radius, maxY, at.getZ() + radius)) {
            if (!level.hasChunkAt(p)) continue;
            if (isDevice(level.getBlockState(p))) {
                out.add(p.immutable());
                if (out.size() >= 256) return out;
            }
        }
        return out;
    }

    public static void attachGoal(Mob mob) {
        for (WrappedGoal g : mob.goalSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof AirDefenseGoal) return;
        }
        mob.goalSelector.addGoal(0, new AirDefenseGoal(mob));
    }
}
