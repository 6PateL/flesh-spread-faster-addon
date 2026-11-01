package com.example.fleshspread;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Усі види цілей рейду. Чужі моди читаються за id та рефлексією, залежностей на рівні коду немає. */
public final class RaidTargets {
    public static final int PLAYER = 0, VILLAGE = 1, BARBARIAN = 2, PASTURE = 3, INDUSTRY = 4;
    public static final String[] NAMES = {"player", "village", "barbarian", "pasture", "industry"};

    public record Target(int type, String name, BlockPos pos) {}

    private static boolean villagesFailed;
    private static boolean barbFailed;

    private RaidTargets() {}

    // ------------------------------------------------------------------ гравець

    public static List<Target> player(ServerLevel level, ServerPlayer from, boolean force) {
        List<Target> out = new ArrayList<>();
        if (from != null && (force || (!from.isCreative() && !from.isSpectator()))) {
            out.add(new Target(PLAYER, from.getGameProfile().getName(), from.blockPosition()));
        }
        return out;
    }

    // ------------------------------------------------------------------ поселення Recruits Villages

    private record Village(String name, BlockPos center, boolean playerOwned) {}

    private static List<Village> villages(MinecraftServer server) {
        List<Village> out = new ArrayList<>();
        if (villagesFailed || !ModList.get().isLoaded("recruitsvillages")) return out;
        try {
            Class<?> dataClass = Class.forName("com.diego.recruitsvillages.data.LivingVillagesSavedData");
            Object data = dataClass.getMethod("get", MinecraftServer.class).invoke(null, server);
            Collection<?> factions = (Collection<?>) dataClass.getMethod("getFactions").invoke(data);
            Method center = null, name = null, player = null;
            for (Object f : factions) {
                if (center == null) {
                    center = f.getClass().getMethod("getCenterPos");
                    name = f.getClass().getMethod("getName");
                    player = f.getClass().getMethod("isPlayerFaction");
                }
                BlockPos c = (BlockPos) center.invoke(f);
                if (c != null) out.add(new Village(String.valueOf(name.invoke(f)), c, Boolean.TRUE.equals(player.invoke(f))));
            }
        } catch (Throwable t) {
            villagesFailed = true;
            FleshSpread.LOGGER.warn("Не вдалося прочитати села Recruits Villages: {}", t.toString());
        }
        return out;
    }

    /** Найближчі до гравця поселення; поселення гравця (його фракція / підкорені) мають перевагу. */
    public static List<Target> villages(ServerLevel level, ServerPlayer from, int count, double radius) {
        List<Village> near = new ArrayList<>();
        for (Village v : villages(level.getServer())) {
            if (horizontal(v.center(), from.blockPosition()) <= radius * radius && level.hasChunkAt(v.center())) near.add(v);
        }
        near.sort(Comparator.<Village>comparingInt(v -> v.playerOwned() ? 0 : 1)
                .thenComparingDouble(v -> horizontal(v.center(), from.blockPosition())));
        List<Target> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, near.size()); i++) {
            out.add(new Target(VILLAGE, near.get(i).name(), near.get(i).center()));
        }
        return out;
    }

    // ------------------------------------------------------------------ поселення варварів (Barbarian Raids)

    public static List<Target> barbarians(ServerLevel level, ServerPlayer from, double radius) {
        List<Target> out = new ArrayList<>();
        if (barbFailed || !ModList.get().isLoaded("barbarianraids")) return out;
        try {
            Class<?> dataClass = Class.forName("com.barbarianraids.BarbarianData");
            Object data = dataClass.getMethod("get", MinecraftServer.class).invoke(null, level.getServer());
            Map<?, ?> bosses = (Map<?, ?>) dataClass.getField("bosses").get(data);
            for (Map.Entry<?, ?> e : bosses.entrySet()) {
                Object b = e.getValue();
                Class<?> bc = b.getClass();
                boolean dead = bc.getField("dead").getBoolean(b);
                String dim = String.valueOf(bc.getField("dim").get(b));
                BlockPos home = (BlockPos) bc.getField("home").get(b);
                if (dead || home == null || !dim.contains("overworld")) continue;
                if (horizontal(home, from.blockPosition()) <= radius * radius && level.hasChunkAt(home)) {
                    out.add(new Target(BARBARIAN, String.valueOf(bc.getField("structure").get(b)), home));
                }
            }
        } catch (Throwable t) {
            barbFailed = true;
            FleshSpread.LOGGER.warn("Не вдалося прочитати поселення варварів: {}", t.toString());
        }
        out.sort(Comparator.comparingDouble(t -> horizontal(t.pos(), from.blockPosition())));
        return out;
    }

    // ------------------------------------------------------------------ пасовища

    public static boolean isLivestock(Entity e) {
        return e instanceof Animal && !(e instanceof TamableAnimal) && e.isAlive() && e.getType() != null
                && "minecraft".equals(ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).getNamespace());
    }

    /** Місце, де купчаться корисні тварини (кури, вівці, свині, корови...). */
    public static List<Target> pastures(ServerLevel level, ServerPlayer from, double radius, int minAnimals) {
        BlockPos c = from.blockPosition();
        AABB box = new AABB(c.getX() - radius, level.getMinBuildHeight(), c.getZ() - radius,
                c.getX() + radius, level.getMaxBuildHeight(), c.getZ() + radius);
        List<Entity> animals = level.getEntities((Entity) null, box, RaidTargets::isLivestock);
        List<Target> out = new ArrayList<>();
        boolean[] used = new boolean[animals.size()];
        for (int i = 0; i < animals.size() && out.size() < 3; i++) {
            if (used[i]) continue;
            Entity a = animals.get(i);
            double sx = 0, sy = 0, sz = 0;
            int n = 0;
            List<Integer> members = new ArrayList<>();
            for (int j = 0; j < animals.size(); j++) {
                Entity b = animals.get(j);
                if (!used[j] && a.distanceToSqr(b) <= 14 * 14) {
                    sx += b.getX(); sy += b.getY(); sz += b.getZ();
                    members.add(j);
                    n++;
                }
            }
            if (n >= minAnimals) {
                for (int m : members) used[m] = true;
                out.add(new Target(PASTURE, n + " animals", BlockPos.containing(sx / n, sy / n, sz / n)));
            }
        }
        out.sort(Comparator.comparingDouble(t -> horizontal(t.pos(), from.blockPosition())));
        return out;
    }

    // ------------------------------------------------------------------ промисловість Create

    /** Блоки Create (та роздатчики) за блок-ентітями завантажених чанків: без сканування блоків. */
    public static List<Target> industry(ServerLevel level, ServerPlayer from, double radius, int minBlocks) {
        int cr = (int) Math.ceil(radius / 16.0);
        ChunkPos base = from.chunkPosition();
        int size = cr * 2 + 1;
        int[][] count = new int[size][size];
        double[][][] sum = new double[size][size][3];
        for (int dx = -cr; dx <= cr; dx++) {
            for (int dz = -cr; dz <= cr; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(base.x + dx, base.z + dz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    var key = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
                    if (key == null) continue;
                    String ns = key.getNamespace(), path = key.getPath();
                    boolean create = ns.equals("create") || ns.startsWith("create_");
                    boolean dispenser = ns.equals("minecraft") && (path.equals("dispenser") || path.equals("dropper"));
                    if (!create && !dispenser) continue;
                    BlockPos p = be.getBlockPos();
                    count[dx + cr][dz + cr]++;
                    sum[dx + cr][dz + cr][0] += p.getX();
                    sum[dx + cr][dz + cr][1] += p.getY();
                    sum[dx + cr][dz + cr][2] += p.getZ();
                }
            }
        }
        // найщільніший квартал 3x3 чанки
        List<Target> out = new ArrayList<>();
        boolean[][] taken = new boolean[size][size];
        for (int round = 0; round < 3; round++) {
            int best = 0, bx = -1, bz = -1;
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    int total = 0;
                    for (int ax = -1; ax <= 1; ax++) {
                        for (int az = -1; az <= 1; az++) {
                            int xx = x + ax, zz = z + az;
                            if (xx >= 0 && zz >= 0 && xx < size && zz < size && !taken[xx][zz]) total += count[xx][zz];
                        }
                    }
                    if (total > best) { best = total; bx = x; bz = z; }
                }
            }
            if (best < minBlocks) break;
            double sx = 0, sy = 0, sz = 0;
            for (int ax = -1; ax <= 1; ax++) {
                for (int az = -1; az <= 1; az++) {
                    int xx = bx + ax, zz = bz + az;
                    if (xx >= 0 && zz >= 0 && xx < size && zz < size && !taken[xx][zz]) {
                        sx += sum[xx][zz][0]; sy += sum[xx][zz][1]; sz += sum[xx][zz][2];
                        taken[xx][zz] = true;
                    }
                }
            }
            out.add(new Target(INDUSTRY, best + " machines", BlockPos.containing(sx / best, sy / best, sz / best)));
        }
        out.sort(Comparator.comparingDouble(t -> horizontal(t.pos(), from.blockPosition())));
        return out;
    }

    static boolean isCreateBlock(net.minecraft.world.level.block.state.BlockState state) {
        var key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null && (key.getNamespace().equals("create") || key.getNamespace().startsWith("create_"));
    }

    static double horizontal(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
