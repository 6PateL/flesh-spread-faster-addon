package com.example.fleshspread;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Розломи: портали плоті, які самі відкриваються поруч із гравцями як подія. Рамка виростає із землі ряд за рядом, рій
 * виє, з розлому виходять заражені; через короткий час розлом так само ряд за рядом затягується і зникає. Увійти в
 * розлом можна лише з артефактом Made in Heaven в інвентарі.
 */
@Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID)
public final class FleshRifts {
    private static final ResourceLocation ARTIFACT = new ResourceLocation("timelesspower", "made_in_heaven");
    private static final int W = 4, H = 5;
    private static final int GROW = 0, OPEN = 1, CLOSE = 2;

    private FleshRifts() {}

    static final class Rift {
        BlockPos base;       // нижній лівий кут рамки (рамка вздовж осі X)
        long expire;
        int phase = GROW;
        int rows;            // скільки рядів рамки зараз стоїть
        int infected;        // скільки блоків заразив (для логу)
        boolean quiet;       // розлом вісника: заражені виходять лише на його крик
        final List<BlockState> saved = new ArrayList<>(); // що було на місці рамки (W*H, ряд за рядом)
    }

    public static class Data extends SavedData {
        final List<Rift> rifts = new ArrayList<>();
        long next;

        static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(Data::load, Data::new, "flesh_spread_rifts");
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            d.next = tag.getLong("next");
            ListTag list = tag.getList("rifts", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                Rift r = new Rift();
                r.base = BlockPos.of(c.getLong("base"));
                r.expire = c.getLong("expire");
                r.phase = c.getInt("phase");
                r.rows = c.getInt("rows");
                r.quiet = c.getBoolean("quiet");
                ListTag st = c.getList("saved", Tag.TAG_COMPOUND);
                for (int k = 0; k < st.size(); k++) {
                    r.saved.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), st.getCompound(k)));
                }
                if (r.saved.size() == W * H) d.rifts.add(r);
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putLong("next", next);
            ListTag list = new ListTag();
            for (Rift r : rifts) {
                CompoundTag c = new CompoundTag();
                c.putLong("base", r.base.asLong());
                c.putLong("expire", r.expire);
                c.putInt("phase", r.phase);
                c.putInt("rows", r.rows);
                c.putBoolean("quiet", r.quiet);
                ListTag st = new ListTag();
                for (BlockState s : r.saved) st.add(NbtUtils.writeBlockState(s));
                c.put("saved", st);
                list.add(c);
            }
            tag.put("rifts", list);
            return tag;
        }
    }

    static boolean hasArtifact(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && ARTIFACT.equals(ForgeRegistries.ITEMS.getKey(s.getItem()))) return true;
        }
        return false;
    }

    private static boolean inside(Rift r, BlockPos p) {
        return p.getZ() == r.base.getZ() && p.getX() >= r.base.getX() && p.getX() < r.base.getX() + W
                && p.getY() >= r.base.getY() && p.getY() < r.base.getY() + H;
    }

    /** Чи належить цей блок (мембрана або рамка) розлому. */
    static boolean isRift(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel sl) || FleshDim.isFlesh(level)) return false;
        for (Rift r : Data.get(sl).rifts) {
            if (inside(r, pos)) return true;
        }
        return false;
    }

    /** Рамку розлому не можна розібрати на блоки: вона зникне сама. */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getState().is(FleshDim.FRAME.get()) && isRift(level, event.getPos())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.literal("Плоть розлому не піддається: він затягнеться сам.")
                    .withStyle(ChatFormatting.DARK_RED), true);
        }
    }

    // ------------------------------------------------------------------ відкриття

    /** Відкриває розлом біля точки (шукає поверхню на відстані min..max). Повертає місце або null. */
    public static BlockPos open(ServerLevel level, BlockPos around, int min, int max, String why) {
        return open(level, around, min, max, why, 0L);
    }

    /** Кількість відкритих (або ще не затягнутих) розломів поруч. */
    static int openNear(ServerLevel level, BlockPos around, double radius) {
        int n = 0;
        for (Rift r : Data.get(level).rifts) {
            if (r.phase != CLOSE && r.base.distSqr(around) <= radius * radius) n++;
        }
        return n;
    }

    /** {@code until} > 0: розлом живе до цього моменту (разом із вісником), інакше — звичайний час із конфігу. */
    public static BlockPos open(ServerLevel level, BlockPos around, int min, int max, String why, long until) {
        return open(level, around, min, max, why, until, false);
    }

    public static BlockPos open(ServerLevel level, BlockPos around, int min, int max, String why, long until, boolean quiet) {
        if (FleshDim.isFlesh(level)) return null;
        SpreadConfig cfg = SpreadConfig.get();
        Data data = Data.get(level);
        for (int attempt = 0; attempt < 12; attempt++) {
            double ang = level.random.nextDouble() * Math.PI * 2.0D;
            double dist = min + level.random.nextDouble() * Math.max(1, max - min);
            int x = (int) Math.floor(around.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(around.getZ() + Math.sin(ang) * dist);
            BlockPos s = Nests.findSurface(level, x + 1, z);
            if (s == null || !level.hasChunkAt(new BlockPos(x, s.getY(), z)) || !level.hasChunkAt(new BlockPos(x + W - 1, s.getY(), z))) continue;
            BlockPos base = new BlockPos(x, s.getY(), z);
            boolean clash = false;
            for (Rift r : data.rifts) {
                if (r.base.distSqr(base) < 12.0D * 12.0D) clash = true;
            }
            // над землею має бути вільно, і жодних блоків із вмістом
            for (int i = 0; i < W && !clash; i++) {
                for (int j = 0; j < H; j++) {
                    BlockPos p = base.offset(i, j, 0);
                    BlockState st = level.getBlockState(p);
                    if (st.hasBlockEntity() || st.getDestroySpeed(level, p) < 0.0F || (j >= 2 && !st.getCollisionShape(level, p).isEmpty())) {
                        clash = true;
                        break;
                    }
                }
            }
            if (clash) continue;
            Rift r = new Rift();
            r.base = base;
            r.quiet = quiet;
            r.expire = until > 0L ? until : level.getGameTime() + 40L + Math.max(10, cfg.riftLifetimeSeconds) * 20L;
            for (int j = 0; j < H; j++) {
                for (int i = 0; i < W; i++) r.saved.add(level.getBlockState(base.offset(i, j, 0)));
            }
            data.rifts.add(r);
            data.setDirty();
            FleshDim.sound(level, base, "flesh_that_hates:entity.incubator.spawn", 4.0F, 0.6F);
            FleshSpread.LOGGER.info("Розлом плоті відкривається на {} ({}), триватиме {} с", base.toShortString(), why, cfg.riftLifetimeSeconds);
            return base;
        }
        FleshSpread.LOGGER.info("Розлом плоті: не знайшлося місця біля {} ({})", around.toShortString(), why);
        return null;
    }

    /**
     * Подія звичайного світу: поки Утробу не вбито, вона виринає неподалік від гравця, відкриває поруч три розломи,
     * час від часу кричить (тоді з розломів виходять заражені) і знову закопується, а розломи зникають. Убити її тут
     * не можна, сама вона не нападає.
     */
    public static BlockPos herald(ServerLevel level, BlockPos around, String why) {
        return herald(level, around, why, false);
    }

    /**
     * Вісник виринає неподалік від гравця. Місце шукає сам (див. {@link #findHeraldSpot}). {@code replace}: дебаг-зонд
     * прибирає вісника, що вже стоїть поруч, і викликає нового.
     */
    public static BlockPos herald(ServerLevel level, BlockPos around, String why, boolean replace) {
        if (FleshDim.isFlesh(level) || FleshData.defeated(level.getServer())) return null;
        List<FleshBoss> old = FleshBoss.near(level, around, 200.0D);
        if (!old.isEmpty()) {
            if (!replace) {
                FleshSpread.LOGGER.info("Вісник: Утроба вже поруч ({})", why);
                return null;
            }
            for (FleshBoss b : old) {
                if (b.herald) {
                    closeNear(level, b.blockPosition(), 90.0D);
                    b.discard();
                }
            }
        }
        SpreadConfig cfg = SpreadConfig.get();
        Spot spot = findHeraldSpot(level, around, cfg);
        if (spot == null) {
            FleshSpread.LOGGER.info("Вісник: біля {} немає жодної завантаженої поверхні ({})", around.toShortString(), why);
            return null;
        }
        BlockPos s = spot.pos();
        FleshBoss boss = FleshDim.BOSS.get().create(level);
        if (boss == null) return null;
        boss.moveTo(s.getX() + 0.5D, s.getY(), s.getZ() + 0.5D, spot.yaw(), 0.0F);
        boss.makeHerald(Math.max(20, cfg.riftLifetimeSeconds) * 20);
        level.addFreshEntity(boss);
        FleshDim.sound(level, s, "flesh_that_hates:entity.call", 10.0F, 0.45F);
        Component msg = Component.literal("☣ Земля здригається: Утроба виринає поруч (" + s.getX() + ", " + s.getY() + ", " + s.getZ() + ").")
                .withStyle(ChatFormatting.DARK_RED);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(s.getX(), s.getY(), s.getZ()) < 160.0D * 160.0D) p.sendSystemMessage(msg);
        }
        FleshSpread.LOGGER.info("Вісник: Утроба виринає на {} ({} блоків від {}, спроба {}, перевірено місць {}) ({}), триватиме {} с",
                s.toShortString(), (int) Math.sqrt(s.distSqr(around)), around.toShortString(), spot.pass(), spot.checked(), why,
                cfg.riftLifetimeSeconds);
        return s;
    }

    private record Spot(BlockPos pos, float yaw, int pass, int checked) {}

    /**
     * Шукає місце сам, кількома спробами від найсуворішої до найм'якшої, тож місце знаходиться завжди, якщо поруч є
     * хоч якась завантажена поверхня:
     * 1) 48–128 блоків, суха земля, голова, середина й хвіст далеко від сіл (тіло можна розвернути в будь-який бік);
     * 2) те саме, але від сіл далеко лише голова (там він прогризає землю);
     * 3) ближче (24–128) і села — лише не в самому центрі.
     */
    private static Spot findHeraldSpot(ServerLevel level, BlockPos around, SpreadConfig cfg) {
        int min = Math.max(24, Math.min(cfg.heraldDistanceMin, cfg.heraldDistanceMax));
        int max = Math.max(min, Math.max(cfg.heraldDistanceMin, cfg.heraldDistanceMax)) + 40;
        double village = cfg.heraldVillageDistance;
        int checked = 0;
        int[][] passes = {{min, max, 0}, {min, max, 1}, {24, max, 2}};
        double start = level.random.nextDouble() * Math.PI * 2.0D;
        for (int pass = 0; pass < passes.length; pass++) {
            for (int dist = passes[pass][0]; dist <= passes[pass][1]; dist += 8) {
                for (int i = 0; i < 24; i++) {
                    double ang = start + i * Math.PI * 2.0D / 24.0D;
                    int x = (int) Math.floor(around.getX() + Math.cos(ang) * dist);
                    int z = (int) Math.floor(around.getZ() + Math.sin(ang) * dist);
                    checked++;
                    if (!level.hasChunkAt(new BlockPos(x, around.getY(), z))) continue;
                    BlockPos s = Nests.findSurface(level, x, z);
                    if (s == null) continue;
                    if (pass < 2 && (!level.getFluidState(s).isEmpty() || !level.getFluidState(s.below()).isEmpty())) continue;
                    double head = pass == 2 ? 24.0D : village;
                    if (RaidTargets.nearVillage(level.getServer(), s, head)) continue;
                    // обличчям до гравця; якщо тіло лягає на село — пробуємо розвернути його в інший бік
                    float face = (float) (Math.toDegrees(Math.atan2(around.getZ() - z, around.getX() - x)) - 90.0D);
                    for (float turn : new float[]{0.0F, 60.0F, -60.0F, 120.0F, -120.0F, 180.0F}) {
                        float yaw = face + turn;
                        if (pass >= 1 || bodyClear(level, x, z, s.getY(), yaw, village)) return new Spot(s, yaw, pass + 1, checked);
                    }
                }
            }
        }
        return null;
    }

    private static boolean bodyClear(ServerLevel level, int x, int z, int y, float yaw, double village) {
        double bx = -Math.sin(Math.toRadians(yaw)), bz = Math.cos(Math.toRadians(yaw));
        for (double along : new double[]{42.0D, 0.0D, -60.0D}) {
            if (RaidTargets.nearVillage(level.getServer(), BlockPos.containing(x + bx * along, y, z + bz * along), village)) return false;
        }
        return true;
    }

    /** Крик вісника: з кожного відкритого розлому поруч виходять заражені. */
    static void pour(ServerLevel level, BlockPos around, double radius) {
        SpreadConfig cfg = SpreadConfig.get();
        int total = 0, rifts = 0;
        for (Rift r : Data.get(level).rifts) {
            if (r.phase != OPEN || r.base.distSqr(around) > radius * radius) continue;
            rifts++;
            total += FleshDim.summon(level, r.base.offset(1, 0, 0), cfg.riftMobs, 1, 3, null);
        }
        FleshSpread.LOGGER.info("Вісник кричить: розломів {}, вийшло заражених {}", rifts, total);
    }

    /** Вісник закопався: розломи поруч затягуються. */
    static void closeNear(ServerLevel level, BlockPos around, double radius) {
        Data data = Data.get(level);
        for (Rift r : data.rifts) {
            if (r.base.distSqr(around) <= radius * radius) r.expire = 0L;
        }
        data.setDirty();
    }

    // ------------------------------------------------------------------ життя розломів

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || FleshDim.isFlesh(level)) return;
        SpreadConfig cfg = SpreadConfig.get();
        if (!cfg.enabled || !cfg.dimensions.contains(level.dimension().location().toString())) return;
        long now = level.getGameTime();
        if (now % 6L != 0L) return;
        boolean won = FleshData.defeated(level.getServer());
        if (won && now % 120L == 0L) { // інфекцію переможено: заражені, що лишилися у світі, зникають
            for (ServerPlayer p : level.players()) {
                for (net.minecraft.world.entity.Entity e : level.getEntities(p, p.getBoundingBox().inflate(128.0D), FleshDim::isInfected)) e.discard();
            }
        }
        Data data = Data.get(level);

        // розклад: часто, але ненадовго
        if (cfg.riftsEnabled && !won && !level.players().isEmpty()) {
            if (data.next == 0L) {
                data.next = now + interval(level, cfg);
                data.setDirty();
            } else if (now >= data.next) {
                List<ServerPlayer> ok = new ArrayList<>();
                for (ServerPlayer p : level.players()) {
                    if (!p.isSpectator()) ok.add(p);
                }
                BlockPos at = ok.isEmpty() ? null : herald(level, ok.get(level.random.nextInt(ok.size())).blockPosition(), "подія");
                data.next = now + (at != null ? interval(level, cfg) : 600L);
                data.setDirty();
            }
        }

        Iterator<Rift> it = data.rifts.iterator();
        while (it.hasNext()) {
            Rift r = it.next();
            if (!level.hasChunkAt(r.base) || !level.hasChunkAt(r.base.offset(W - 1, 0, 0))) continue;
            data.setDirty();
            if (r.phase == GROW) {
                growRow(level, r);
                if (r.rows >= H) openMembrane(level, r, cfg);
            } else if (r.phase == OPEN) {
                if (now >= r.expire || won) {
                    r.phase = CLOSE;
                    FleshDim.sound(level, r.base, "flesh_that_hates:entity.incubator_small.hurt", 4.0F, 0.5F);
                    for (int i = 1; i < W - 1; i++) {
                        for (int j = 1; j < H - 1; j++) level.setBlock(r.base.offset(i, j, 0), Blocks.AIR.defaultBlockState(), 18);
                    }
                    burst(level, r.base.offset(1, 2, 0), 40);
                } else {
                    if (level.random.nextInt(4) == 0) burst(level, r.base.offset(1 + level.random.nextInt(2), 1 + level.random.nextInt(3), 0), 4);
                    // поки розлом відкритий, плоть розповзається довкола нього
                    if (now % 18L == 0L) {
                        int n = Nests.creep(level, r.base.offset(1, 0, 0), 16, 10);
                        r.infected += n;
                    }
                }
            } else {
                closeRow(level, r);
                if (r.rows <= 0) {
                    FleshSpread.LOGGER.info("Розлом плоті на {} затягнувся, встиг заразити блоків: {}", r.base.toShortString(), r.infected);
                    it.remove();
                }
            }
        }
    }

    private static long interval(ServerLevel level, SpreadConfig cfg) {
        int min = Math.max(20, Math.min(cfg.riftIntervalMinSeconds, cfg.riftIntervalMaxSeconds));
        int max = Math.max(min, Math.max(cfg.riftIntervalMinSeconds, cfg.riftIntervalMaxSeconds));
        return (min + level.random.nextInt(max - min + 1)) * 20L;
    }

    private static void burst(ServerLevel level, BlockPos p, int count) {
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D, count, 0.6D, 0.6D, 0.3D, 0.02D);
        level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D, Math.max(1, count / 4), 0.5D, 0.5D, 0.3D, 0.05D);
    }

    /** Анімація появи: рамка виростає із землі, ряд за рядом. */
    private static void growRow(ServerLevel level, Rift r) {
        int j = r.rows;
        for (int i = 0; i < W; i++) {
            BlockPos p = r.base.offset(i, j, 0);
            boolean edge = i == 0 || i == W - 1 || j == 0 || j == H - 1;
            level.setBlock(p, edge ? FleshDim.FRAME.get().defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            if (edge) burst(level, p, 6);
        }
        FleshDim.sound(level, r.base.above(j), "flesh_that_hates:block.flesh_block.place", 2.5F, 0.6F + j * 0.08F);
        r.rows++;
    }

    private static void openMembrane(ServerLevel level, Rift r, SpreadConfig cfg) {
        BlockState membrane = FleshDim.PORTAL.get().defaultBlockState().setValue(FleshPortalBlock.AXIS, Direction.Axis.X)
                .setValue(FleshPortalBlock.ACTIVE, true);
        for (int i = 1; i < W - 1; i++) {
            for (int j = 1; j < H - 1; j++) level.setBlock(r.base.offset(i, j, 0), membrane, 18);
        }
        r.phase = OPEN;
        BlockPos c = r.base.offset(1, 2, 0);
        burst(level, c, 60);
        FleshDim.sound(level, c, "flesh_that_hates:entity.call", 7.0F, 0.6F);
        FleshDim.sound(level, c, "flesh_that_hates:entity.herpad.summon", 4.0F, 0.7F);
        int mobs = r.quiet ? 0 : FleshDim.summon(level, c.below(), cfg.riftMobs, 1, 3, null);
        FleshSpread.LOGGER.info("Розлом плоті на {} відкрито, вийшло мобів: {}", r.base.toShortString(), mobs);
    }

    /** Анімація зникнення: рамка затягується згори донизу, на її місце повертається те, що було. */
    private static void closeRow(ServerLevel level, Rift r) {
        int j = r.rows - 1;
        for (int i = 0; i < W; i++) {
            BlockPos p = r.base.offset(i, j, 0);
            BlockState now = level.getBlockState(p);
            if (now.is(FleshDim.FRAME.get()) || now.is(FleshDim.PORTAL.get()) || now.isAir()) {
                level.setBlock(p, r.saved.get(j * W + i), 3);
            }
            burst(level, p, 5);
        }
        FleshDim.sound(level, r.base.above(j), "flesh_that_hates:block.flesh_block.break", 2.5F, 0.9F - j * 0.08F);
        r.rows--;
    }
}
