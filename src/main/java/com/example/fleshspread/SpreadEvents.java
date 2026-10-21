package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID)
public final class SpreadEvents {
    private record Pending(ResourceKey<Level> dim, int x, int z, long created) {}

    private static final Queue<Pending> PENDING = new ConcurrentLinkedQueue<>();

    private SpreadEvents() {}

    // ------------------------------------------------------------ нові чанки -> гнізда

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        SpreadConfig cfg = SpreadConfig.get();
        if (!cfg.enabled || !cfg.dimensions.contains(level.dimension().location().toString())) return;
        var pos = event.getChunk().getPos();
        PENDING.add(new Pending(level.dimension(), pos.x, pos.z, level.getGameTime()));
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        SpreadConfig cfg = SpreadConfig.get();
        if (!cfg.enabled || !cfg.dimensions.contains(level.dimension().location().toString())) return;

        processPending(level, cfg);

        long t = level.getGameTime();
        if (cfg.ripenDays > 0 && t % 200 == 0) ripen(level, cfg);
        if (cfg.pressureEnabled && t % Math.max(20, cfg.pressureIntervalTicks) == 0) pressure(level, cfg);
    }

    private static void processPending(ServerLevel level, SpreadConfig cfg) {
        int n = Math.min(PENDING.size(), 16);
        for (int i = 0; i < n; i++) {
            Pending p = PENDING.poll();
            if (p == null) return;
            if (!p.dim().equals(level.dimension())) { PENDING.add(p); continue; }
            if (level.getChunkSource().getChunkNow(p.x(), p.z()) == null) {
                if (level.getGameTime() - p.created() < 200) PENDING.add(p); // чанк ще не готовий
                continue;
            }
            tryChunkNest(level, cfg, p.x(), p.z());
        }
    }

    private static void tryChunkNest(ServerLevel level, SpreadConfig cfg, int cx, int cz) {
        double day = level.getGameTime() / 24000.0;
        double mult = Math.min(cfg.nestMaxMultiplier, 1.0 + day * cfg.nestDayGrowth);
        if (level.random.nextDouble() >= cfg.nestChancePerNewChunk * mult) return;

        int x = (cx << 4) + level.random.nextInt(16);
        int z = (cz << 4) + level.random.nextInt(16);

        BlockPos spawn = level.getSharedSpawnPos();
        double min = cfg.minDistanceFromSpawn;
        if (spawn.distSqr(new BlockPos(x, spawn.getY(), z)) < min * min) return;

        BlockPos pos = Nests.findSurface(level, x, z);
        if (pos == null) return;
        if (Nests.count(level, pos, cfg.minNestSpacing) > 0) return;

        if (Nests.spawnNest(level, pos, cfg.patchRadius)) {
            FleshSpread.LOGGER.info("Нове гніздо (чанк) на {} {} {}", pos.getX(), pos.getY(), pos.getZ());
        }
    }

    // ------------------------------------------------------------ тиск навколо гравця

    private static void pressure(ServerLevel level, SpreadConfig cfg) {
        double day = level.getGameTime() / 24000.0;
        if (day < cfg.pressureStartDay) return;

        int extra = (int) Math.floor((day - cfg.pressureStartDay) / Math.max(0.1, cfg.pressureDaysPerExtraNest));
        int target = Math.min(cfg.pressureMaxNests, cfg.pressureBaseNests + extra);

        for (ServerPlayer player : level.players()) {
            if (player.isCreative() || player.isSpectator()) continue;
            if (Nests.count(level, player.blockPosition(), cfg.pressureCountRadius) >= target) continue;

            for (int attempt = 0; attempt < 8; attempt++) {
                double ang = level.random.nextDouble() * Math.PI * 2;
                double dist = cfg.pressureMinDistance
                        + level.random.nextDouble() * Math.max(1, cfg.pressureMaxDistance - cfg.pressureMinDistance);
                int x = (int) Math.floor(player.getX() + Math.cos(ang) * dist);
                int z = (int) Math.floor(player.getZ() + Math.sin(ang) * dist);

                BlockPos pos = Nests.findSurface(level, x, z);
                if (pos == null) continue;
                if (tooCloseToPlayer(level, pos, cfg.pressureMinDistance * 0.8)) continue;
                if (Nests.count(level, pos, cfg.minNestSpacing) > 0) continue;

                if (Nests.spawnNest(level, pos, cfg.patchRadius)) {
                    FleshSpread.LOGGER.info("Нове гніздо (тиск, день {}) на {} {} {}",
                            String.format("%.1f", day), pos.getX(), pos.getY(), pos.getZ());
                    if (cfg.pressureWarning && !cfg.pressureWarningText.isBlank()) {
                        player.displayClientMessage(Component.literal(cfg.pressureWarningText), true);
                    }
                    break;
                }
            }
        }
    }

    private static boolean tooCloseToPlayer(ServerLevel level, BlockPos pos, double dist) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) < dist * dist) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ дорослішання

    private static void ripen(ServerLevel level, SpreadConfig cfg) {
        EntityType<?> small = Nests.small();
        if (small == null || Nests.big() == null) return;

        long now = level.getGameTime();
        long needed = (long) (cfg.ripenDays * 24000.0);
        List<Entity> ready = new ArrayList<>();

        for (Entity e : level.getAllEntities()) {
            if (e.getType() != small || !e.isAlive()) continue;
            CompoundTag tag = e.getPersistentData();
            if (!tag.contains(Nests.BORN)) { tag.putLong(Nests.BORN, now); continue; } // інкубатор з Flesh That Hates
            if (now - tag.getLong(Nests.BORN) >= needed) ready.add(e);
        }
        for (Entity e : ready) {
            Nests.grow(level, e);
            FleshSpread.LOGGER.info("Інкубатор подорослішав на {}", e.blockPosition().toShortString());
        }
    }
}
