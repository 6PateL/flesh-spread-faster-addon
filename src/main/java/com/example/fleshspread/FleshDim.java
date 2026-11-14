package com.example.fleshspread;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Вимір плоті: світ, який цілком поглинуло зараження. Туди веде портал із «живої плоті» (форма як у порталу в Незер),
 * запалений кресалом і пробуджений артефактом Made in Heaven. У вимірі десь далеко спить Серце плоті (майбутній бос);
 * шлях до нього показує кривавий компас.
 */
@Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID)
public final class FleshDim {
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(FleshSpread.MOD_ID, "flesh"));
    private static final ResourceLocation ARTIFACT = new ResourceLocation("timelesspower", "made_in_heaven");
    private static final String FTH_ENTITY = "net.mcreator.fleshthathates.entity.";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, FleshSpread.MOD_ID);
    public static final RegistryObject<Block> FRAME = BLOCKS.register("living_flesh", () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_RED).strength(3.0F, 12.0F).sound(SoundType.SLIME_BLOCK).lightLevel(s -> 4)));
    public static final RegistryObject<Block> PORTAL = BLOCKS.register("flesh_portal", FleshPortalBlock::new);
    public static final RegistryObject<Block> HEART = BLOCKS.register("flesh_heart", () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_RED).strength(-1.0F, 3600000.0F).sound(SoundType.SLIME_BLOCK).lightLevel(s -> 13).noLootTable()));

    public static final RegistryObject<Item> FRAME_ITEM = ProbeItem.ITEMS.register("living_flesh",
            () -> new BlockItem(FRAME.get(), new Item.Properties()));
    public static final RegistryObject<Item> HEART_ITEM = ProbeItem.ITEMS.register("flesh_heart",
            () -> new BlockItem(HEART.get(), new Item.Properties().rarity(Rarity.EPIC)));
    public static final RegistryObject<Item> BLOOD_COMPASS = ProbeItem.ITEMS.register("blood_compass",
            () -> new BloodCompassItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    private FleshDim() {}

    static boolean isFlesh(Level level) {
        return level.dimension() == KEY;
    }

    static boolean isInfected(Entity e) {
        return e.getClass().getName().startsWith(FTH_ENTITY);
    }

    private static Block fth(String path) {
        ResourceLocation id = new ResourceLocation("flesh_that_hates", path);
        return ForgeRegistries.BLOCKS.containsKey(id) ? ForgeRegistries.BLOCKS.getValue(id) : null;
    }

    static void sound(ServerLevel level, BlockPos pos, String id, float volume, float pitch) {
        SoundEvent s = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(id));
        if (s != null) level.playSound(null, pos, s, SoundSource.HOSTILE, volume, pitch);
    }

    // ================================================================== запалювання рамки

    @SubscribeEvent
    public static void onUseOnFrame(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || event.getFace() == null) return;
        ItemStack stack = event.getItemStack();
        if (!stack.is(Items.FLINT_AND_STEEL) && !stack.is(Items.FIRE_CHARGE)) return;
        if (!level.getBlockState(event.getPos()).is(FRAME.get())) return;
        BlockPos inside = event.getPos().relative(event.getFace());
        int n = light(level, inside);
        if (n <= 0) return;
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        Player player = event.getEntity();
        if (stack.is(Items.FLINT_AND_STEEL)) {
            stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(event.getHand()));
        } else if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        sound(level, inside, "minecraft:item.flintandsteel.use", 1.0F, 0.7F);
        sound(level, inside, "flesh_that_hates:entity.incubator.grow", 1.5F, 0.8F);
        player.displayClientMessage(Component.literal("Плоть затягнула рамку, але портал спить. Йому потрібен артефакт…")
                .withStyle(ChatFormatting.DARK_RED), true);
        FleshSpread.LOGGER.info("Портал плоті: рамку запалено біля {} ({} блоків мембрани), гравець {}", inside.toShortString(), n,
                player.getName().getString());
    }

    /** Заповнює замкнену рамку мембраною. Повертає кількість блоків мембрани (0 = рамка не замкнена). */
    private static int light(ServerLevel level, BlockPos start) {
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            Set<BlockPos> region = enclosed(level, start, axis);
            if (region == null) continue;
            BlockState membrane = PORTAL.get().defaultBlockState().setValue(FleshPortalBlock.AXIS, axis)
                    .setValue(FleshPortalBlock.ACTIVE, false);
            for (BlockPos p : region) level.setBlock(p, membrane, 18);
            return region.size();
        }
        return 0;
    }

    private static boolean open(BlockState st) {
        return st.isAir() || st.is(BlockTags.FIRE) || st.canBeReplaced();
    }

    /** Порожнина в площині (вісь + вертикаль), з усіх боків обмежена живою плоттю; мінімум 2x3, максимум 21x21. */
    private static Set<BlockPos> enclosed(ServerLevel level, BlockPos start, Direction.Axis axis) {
        if (!open(level.getBlockState(start))) return null;
        Direction a = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction[] dirs = {Direction.UP, Direction.DOWN, a, a.getOpposite()};
        Set<BlockPos> region = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        region.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();
            for (Direction d : dirs) {
                BlockPos n = p.relative(d);
                if (region.contains(n)) continue;
                BlockState st = level.getBlockState(n);
                if (st.is(FRAME.get())) continue;
                if (!open(st) || Math.abs(n.getX() - start.getX()) > 21 || Math.abs(n.getZ() - start.getZ()) > 21
                        || Math.abs(n.getY() - start.getY()) > 21 || region.size() > 441) {
                    return null; // дірка в рамці або щось стороннє всередині
                }
                region.add(n);
                queue.add(n);
            }
        }
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BlockPos p : region) {
            int h = axis == Direction.Axis.X ? p.getX() : p.getZ();
            minH = Math.min(minH, h);
            maxH = Math.max(maxH, h);
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        return maxH - minH + 1 >= 2 && maxY - minY + 1 >= 3 ? region : null;
    }

    // ================================================================== дотик до мембрани

    private static final Map<UUID, Long> COOLDOWN = new HashMap<>();

    static void onPortalTouch(Level lvl, BlockPos pos, BlockState state, Entity entity) {
        if (!(lvl instanceof ServerLevel level)) return;
        boolean active = state.getValue(FleshPortalBlock.ACTIVE);
        if (entity instanceof ItemEntity item) {
            if (!active && item.isAlive() && ARTIFACT.equals(ForgeRegistries.ITEMS.getKey(item.getItem().getItem()))) {
                awaken(level, pos, item);
            }
            return;
        }
        if (!(entity instanceof ServerPlayer player) || player.isPassenger() || player.isVehicle()) return;
        long now = level.getServer().getTickCount();
        Long until = COOLDOWN.get(player.getUUID());
        if (until != null && now < until) {
            COOLDOWN.put(player.getUUID(), Math.max(until, now + 40L)); // поки стоїш у порталі, він не спрацює вдруге
            return;
        }
        if (!active) {
            COOLDOWN.put(player.getUUID(), now + 60L);
            player.displayClientMessage(Component.literal("Портал спить. Киньте в нього артефакт Made in Heaven.")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return;
        }
        if (FleshRifts.isRift(level, pos) && !FleshRifts.hasArtifact(player)) {
            COOLDOWN.put(player.getUUID(), now + 60L);
            player.displayClientMessage(Component.literal("Розлом не пускає: потрібен артефакт Made in Heaven в інвентарі.")
                    .withStyle(ChatFormatting.DARK_RED), true);
            return;
        }
        COOLDOWN.put(player.getUUID(), now + 200L);
        travel(level, player);
    }

    /** Артефакт пробуджує портал: рев рою, кров на екрані, з порталу вискакують заражені, артефакт повертається власнику. */
    private static void awaken(ServerLevel level, BlockPos pos, ItemEntity item) {
        Set<BlockPos> done = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(pos);
        done.add(pos);
        while (!queue.isEmpty() && done.size() < 600) {
            BlockPos p = queue.poll();
            BlockState st = level.getBlockState(p);
            if (!st.is(PORTAL.get())) continue;
            level.setBlock(p, st.setValue(FleshPortalBlock.ACTIVE, true), 18);
            for (Direction d : Direction.values()) {
                BlockPos n = p.relative(d);
                if (done.add(n) && level.getBlockState(n).is(PORTAL.get())) queue.add(n);
            }
        }

        // артефакт зникає в порталі, але повертається до того, хто його кинув
        ItemStack stack = item.getItem().copy();
        CompoundTag tag = new CompoundTag();
        item.saveWithoutId(tag);
        ServerPlayer owner = tag.hasUUID("Thrower") ? level.getServer().getPlayerList().getPlayer(tag.getUUID("Thrower")) : null;
        if (owner == null) {
            Player near = level.getNearestPlayer(item, 24.0D);
            if (near instanceof ServerPlayer sp) owner = sp;
        }
        item.discard();
        if (owner != null) {
            if (!owner.getInventory().add(stack)) owner.drop(stack, false);
            owner.displayClientMessage(Component.literal("Артефакт повернувся до вас.").withStyle(ChatFormatting.GOLD), false);
        } else {
            level.addFreshEntity(new ItemEntity(level, pos.getX() + 0.5D, pos.getY() + 1.5D, pos.getZ() + 2.5D, stack));
        }

        sound(level, pos, "flesh_that_hates:entity.call", 8.0F, 0.6F);
        sound(level, pos, "flesh_that_hates:entity.herpad.summon", 6.0F, 0.7F);
        sound(level, pos, "minecraft:entity.wither.spawn", 3.0F, 0.5F);
        Component msg = Component.literal("☣ Врата плоті відчинилися. Рій почув вас.").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(pos.getX(), pos.getY(), pos.getZ()) < 96.0D * 96.0D) {
                FsNet.sendFx(p, 2);
                p.sendSystemMessage(msg);
            }
        }
        SpreadConfig cfg = SpreadConfig.get();
        int made = summon(level, pos, cfg.portalAwakenMobs, 1, 3, owner);
        FleshSpread.LOGGER.info("Портал плоті пробуджено біля {} ({} блоків мембрани), вискочило мобів: {}, артефакт повернуто: {}",
                pos.toShortString(), done.size(), made, owner == null ? "нікому (лежить біля порталу)" : owner.getName().getString());
    }

    /** Ставить заражених навколо точки (на відстані min..max блоків) і нацьковує їх на гравця. */
    static int summon(ServerLevel level, BlockPos center, int count, int min, int max, ServerPlayer target) {
        double day = Math.max(6.0D, level.getGameTime() / 24000.0D);
        int made = 0;
        for (int i = 0; i < count; i++) {
            EntityType<?> type = InfectedRaids.pickType(level.random, day);
            if (type == null) break;
            for (int attempt = 0; attempt < 6; attempt++) {
                double ang = level.random.nextDouble() * Math.PI * 2.0D;
                double dist = min + level.random.nextDouble() * Math.max(1, max - min);
                BlockPos p = BlockPos.containing(center.getX() + 0.5D + Math.cos(ang) * dist, center.getY(),
                        center.getZ() + 0.5D + Math.sin(ang) * dist);
                // найближче вільне місце по вертикалі
                BlockPos spot = null;
                for (int dy = 3; dy >= -4; dy--) {
                    BlockPos q = p.above(dy);
                    if (level.getBlockState(q).getCollisionShape(level, q).isEmpty()
                            && level.getBlockState(q.above()).getCollisionShape(level, q.above()).isEmpty()
                            && !level.getBlockState(q.below()).getCollisionShape(level, q.below()).isEmpty()) {
                        spot = q;
                        break;
                    }
                }
                if (spot == null) continue;
                Entity e = type.spawn(level, spot, MobSpawnType.EVENT);
                if (e == null) continue;
                if (e instanceof Mob m && target != null && !target.isCreative() && !target.isSpectator()) m.setTarget(target);
                made++;
                break;
            }
        }
        return made;
    }

    // ================================================================== подорож

    private static void travel(ServerLevel from, ServerPlayer player) {
        MinecraftServer server = from.getServer();
        CompoundTag root = player.getPersistentData();
        CompoundTag keep = root.getCompound(Player.PERSISTED_NBT_TAG);
        if (isFlesh(from)) {
            // назад: туди, звідки гравець зайшов
            ServerLevel dest = server.overworld();
            BlockPos back = dest.getSharedSpawnPos();
            if (keep.contains("fs_return_pos")) {
                back = BlockPos.of(keep.getLong("fs_return_pos"));
                ServerLevel saved = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(keep.getString("fs_return_dim"))));
                if (saved != null) dest = saved;
            }
            player.teleportTo(dest, back.getX() + 0.5D, back.getY(), back.getZ() + 0.5D, player.getYRot(), player.getXRot());
            FleshSpread.LOGGER.info("Портал плоті: {} повернувся з виміру плоті до {} {}", player.getName().getString(),
                    dest.dimension().location(), back.toShortString());
            return;
        }
        ServerLevel flesh = server.getLevel(KEY);
        if (flesh == null) {
            player.displayClientMessage(Component.literal("Вимір плоті не завантажено (перезапустіть світ із цим модом).")
                    .withStyle(ChatFormatting.RED), false);
            FleshSpread.LOGGER.warn("Портал плоті: вимір {} не знайдено", KEY.location());
            return;
        }
        keep.putLong("fs_return_pos", player.blockPosition().asLong());
        keep.putString("fs_return_dim", from.dimension().location().toString());
        root.put(Player.PERSISTED_NBT_TAG, keep);

        BlockPos dest = arrival(flesh, player.getBlockX(), player.getBlockZ());
        player.teleportTo(flesh, dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D, 180.0F, 0.0F);
        sound(flesh, dest, "flesh_that_hates:entity.call", 3.0F, 0.5F);
        player.sendSystemMessage(Component.literal("Ви у вимірі плоті. Десь тут спить Серце плоті — кривавий компас вкаже шлях.")
                .withStyle(ChatFormatting.DARK_RED));
        FleshSpread.LOGGER.info("Портал плоті: {} увійшов у вимір плоті, прибуття {} (лігво боса: {})", player.getName().getString(),
                dest.toShortString(), FleshData.get(flesh).boss == null ? "?" : FleshData.get(flesh).boss.toShortString());
    }

    /** Знаходить портал повернення поруч або будує новий. Повертає місце, де з'явиться гравець. */
    private static BlockPos arrival(ServerLevel flesh, int x, int z) {
        FleshData data = FleshData.get(flesh);
        for (BlockPos p : data.arrivals) {
            double dx = p.getX() - x, dz = p.getZ() - z;
            if (dx * dx + dz * dz <= 48.0D * 48.0D && flesh.getBlockState(p.offset(1, 1, 0)).is(PORTAL.get())) {
                return p.offset(2, 0, 2);
            }
        }
        flesh.getChunk(x >> 4, z >> 4); // згенерувати, якщо ще немає
        int y = Math.max(flesh.getMinBuildHeight() + 6, flesh.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z));
        y = Math.min(y, flesh.getMaxBuildHeight() - 10);
        BlockPos base = new BlockPos(x, y, z);
        Block floor = fth("rotting_flesh_block");
        BlockState floorState = floor != null ? floor.defaultBlockState() : FRAME.get().defaultBlockState();
        // майданчик і вільне місце
        for (int dx = -1; dx <= 4; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                BlockPos f = base.offset(dx, -1, dz);
                if (flesh.getBlockState(f).getCollisionShape(flesh, f).isEmpty() || !flesh.getFluidState(f).isEmpty()) {
                    flesh.setBlock(f, floorState, 3);
                }
                for (int dy = 0; dy <= 5; dy++) flesh.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        // рамка 4x5 уздовж осі X і вже пробуджена мембрана всередині
        BlockState membrane = PORTAL.get().defaultBlockState().setValue(FleshPortalBlock.AXIS, Direction.Axis.X)
                .setValue(FleshPortalBlock.ACTIVE, true);
        for (int i = 0; i <= 3; i++) {
            for (int j = 0; j <= 4; j++) {
                boolean edge = i == 0 || i == 3 || j == 0 || j == 4;
                if (edge) flesh.setBlock(base.offset(i, j, 0), FRAME.get().defaultBlockState(), 3);
            }
        }
        for (int i = 1; i <= 2; i++) {
            for (int j = 1; j <= 3; j++) flesh.setBlock(base.offset(i, j, 0), membrane, 18);
        }
        data.arrivals.add(base);
        if (data.boss == null) {
            SpreadConfig cfg = SpreadConfig.get();
            double ang = flesh.random.nextDouble() * Math.PI * 2.0D;
            int min = Math.max(100, Math.min(cfg.fleshDimBossDistanceMin, cfg.fleshDimBossDistanceMax));
            int max = Math.max(min, Math.max(cfg.fleshDimBossDistanceMin, cfg.fleshDimBossDistanceMax));
            double dist = min + flesh.random.nextDouble() * (max - min);
            data.boss = new BlockPos((int) (x + Math.cos(ang) * dist), 64, (int) (z + Math.sin(ang) * dist));
            FleshSpread.LOGGER.info("Вимір плоті: лігво боса (Серце плоті) призначено на {}", data.boss.toShortString());
        }
        data.setDirty();
        FleshSpread.LOGGER.info("Вимір плоті: збудовано портал повернення на {}", base.toShortString());
        return base.offset(2, 0, 2);
    }

    // ================================================================== усе з плоті: нові чанки виміру

    private static final Object NONE = new Object();
    private static final Map<BlockState, Object> CONVERT = new IdentityHashMap<>();
    private static final Set<BlockState> PLANTS = new HashSet<>();
    private record PendingNest(int x, int z, long time) {}
    private static final ArrayDeque<PendingNest> NESTS = new ArrayDeque<>();

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level) || !isFlesh(level)
                || !(event.getChunk() instanceof LevelChunk chunk)) return;
        try {
            fleshify(level, chunk);
        } catch (Throwable t) {
            FleshSpread.LOGGER.error("Вимір плоті: не вдалося обробити чанк {}: {}", chunk.getPos(), t.toString());
        }
        if (level.random.nextDouble() < SpreadConfig.get().fleshDimSpawnerChance) {
            NESTS.add(new PendingNest(chunk.getPos().getMiddleBlockX(), chunk.getPos().getMiddleBlockZ(), level.getGameTime()));
        }
    }

    /** Замінює звичайні блоки щойно згенерованого чанка на плоть (каміння, землю, траву, дерева, будинки сіл). */
    private static void fleshify(ServerLevel level, LevelChunk chunk) {
        Block bush = fth("tooth_bush");
        int changed = 0;
        for (LevelChunkSection sec : chunk.getSections()) {
            if (sec == null || sec.hasOnlyAir()) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = sec.getBlockState(x, y, z);
                        if (st.isAir()) continue;
                        Object to = CONVERT.get(st);
                        if (to == null) {
                            to = convert(st);
                            CONVERT.put(st, to);
                        }
                        if (to == NONE) continue;
                        BlockState target = (BlockState) to;
                        if (PLANTS.contains(st)) {
                            // трава й квіти: зрідка зубастий кущ, решта просто зникає
                            target = bush != null && target.is(bush) && level.random.nextInt(6) == 0 ? target : Blocks.AIR.defaultBlockState();
                        }
                        sec.setBlockState(x, y, z, target, false);
                        changed++;
                    }
                }
            }
        }
        if (changed > 0) chunk.setUnsaved(true);
    }

    private static Object convert(BlockState st) {
        Block b = st.getBlock();
        String to = null;
        boolean plant = false;
        if (st.is(BlockTags.LOGS)) to = "flesh_log";
        else if (st.is(BlockTags.LEAVES)) to = "flesh_leaves";
        else if (st.is(BlockTags.PLANKS)) to = "flesh_planks";
        else if (b instanceof StairBlock) to = "flesh_stair";
        else if (b instanceof SlabBlock) to = "flesh_slab";
        else if (b instanceof FenceBlock) to = "flesh_fence";
        else if (b instanceof FenceGateBlock) to = "flesh_gate";
        else if (b instanceof DoorBlock) to = "flesh_door";
        else if (b instanceof TrapDoorBlock) to = "flesh_trapdoor";
        else if (b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL || b == Blocks.MYCELIUM || b == Blocks.DIRT_PATH || b == Blocks.FARMLAND) to = "flesh_grass";
        else if (st.is(BlockTags.DIRT) || b == Blocks.CLAY) to = "flesh_dirt";
        else if (st.is(BlockTags.SAND) || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE) to = "flesh_sand";
        else if (b == Blocks.GRAVEL) to = "rotting_flesh_sand";
        else if (b == Blocks.DEEPSLATE || b == Blocks.TUFF || b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE
                || b == Blocks.STONE_BRICKS || b == Blocks.MOSSY_STONE_BRICKS || b == Blocks.HAY_BLOCK
                || b == Blocks.COBBLED_DEEPSLATE) to = "rotting_flesh_block";
        else if (st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Tags.Blocks.ORES) || b == Blocks.TERRACOTTA
                || b == Blocks.SMOOTH_STONE || b == Blocks.CALCITE) to = "flesh_block";
        else if (st.is(BlockTags.FLOWERS) || b == Blocks.GRASS || b == Blocks.FERN || b == Blocks.TALL_GRASS
                || b == Blocks.LARGE_FERN || b == Blocks.DEAD_BUSH || b instanceof CropBlock || st.is(BlockTags.SAPLINGS)) {
            to = "tooth_bush";
            plant = true;
        }
        if (to == null) return NONE;
        Block target = fth(to);
        if (target == null) return NONE;
        BlockState out = target.defaultBlockState();
        if (!plant) {
            for (Property<?> p : st.getProperties()) out = copy(st, out, p);
        } else {
            PLANTS.add(st);
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> p) {
        return to.hasProperty(p) ? to.setValue(p, from.getValue(p)) : to;
    }

    /** Покинуті села: у вимірі плоті немає ні жителів, ні големів, ні худоби. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !isFlesh(event.getLevel())) return;
        Entity e = event.getEntity();
        if (e instanceof AbstractVillager || e instanceof IronGolem || (e instanceof Animal && !isInfected(e) && !e.isVehicle()
                && e.getPersistentData().isEmpty())) {
            event.setCanceled(true);
        }
    }

    // ================================================================== життя виміру

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || !isFlesh(level)) return;
        long t = level.getGameTime();
        SpreadConfig cfg = SpreadConfig.get();

        // великі інкубатори (спавнери 2 рівня) у нових чанках
        for (int i = 0; i < 4 && !NESTS.isEmpty(); i++) {
            PendingNest n = NESTS.poll();
            if (t - n.time() > 1200L || !level.hasChunkAt(new BlockPos(n.x(), 64, n.z()))) continue;
            EntityType<?> big = Nests.big();
            BlockPos at = Nests.findSurface(level, n.x(), n.z());
            if (big == null || at == null) continue;
            Entity e = big.spawn(level, at, MobSpawnType.STRUCTURE);
            if (e instanceof Mob m) m.setPersistenceRequired();
        }
        if (t % 100L != 0L || level.players().isEmpty()) return;

        FleshData data = FleshData.get(level);
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            // заражені довкола гравця
            if (t % 200L == 0L && !p.isCreative()) {
                int near = level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(40.0D, 24.0D, 40.0D),
                        m -> isInfected(m) && !Nests.isIncubator(m)).size();
                if (near < cfg.fleshDimMobsNearPlayer) summon(level, p.blockPosition(), 2, 20, 32, null);
            }
            if (data.boss == null) continue;
            double dx = p.getX() - data.boss.getX(), dz = p.getZ() - data.boss.getZ();
            double d2 = dx * dx + dz * dz;
            if (!data.bossBuilt && d2 < 96.0D * 96.0D && level.hasChunkAt(data.boss)) {
                buildLair(level, data);
            }
            if (data.bossBuilt && d2 < 28.0D * 28.0D && !p.getPersistentData().getBoolean("fs_lair_seen")) {
                p.getPersistentData().putBoolean("fs_lair_seen", true);
                sound(level, data.boss, "flesh_that_hates:entity.call", 6.0F, 0.4F);
                p.sendSystemMessage(Component.literal("☣ Ви знайшли лігво. Серце плоті ще спить… (бос з'явиться в наступному оновленні)")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                FleshSpread.LOGGER.info("Вимір плоті: {} знайшов лігво боса на {}", p.getName().getString(), data.boss.toShortString());
            }
        }
    }

    /** Заглушка лігва боса: кругла арена з гнилої плоті, стовпи, п'єдестал із Серцем плоті та інкубатори довкола. */
    private static void buildLair(ServerLevel level, FleshData data) {
        int x = data.boss.getX(), z = data.boss.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        BlockPos c = new BlockPos(x, y, z);
        Block rot = fth("rotting_flesh_block");
        Block log = fth("flesh_log");
        BlockState floor = rot != null ? rot.defaultBlockState() : FRAME.get().defaultBlockState();
        BlockState pillar = log != null ? log.defaultBlockState() : FRAME.get().defaultBlockState();
        int r = 11;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                for (int dy = -3; dy <= -1; dy++) level.setBlock(c.offset(dx, dy, dz), floor, 2);
                for (int dy = 0; dy <= 12; dy++) level.setBlock(c.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
            }
        }
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0D;
            int px = (int) Math.round(Math.cos(ang) * 9.0D), pz = (int) Math.round(Math.sin(ang) * 9.0D);
            int h = 5 + (i % 2) * 3;
            for (int dy = 0; dy < h; dy++) level.setBlock(c.offset(px, dy, pz), pillar, 2);
            level.setBlock(c.offset(px, h, pz), FRAME.get().defaultBlockState(), 2);
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) level.setBlock(c.offset(dx, 0, dz), FRAME.get().defaultBlockState(), 2);
        }
        level.setBlock(c.above(), HEART.get().defaultBlockState(), 3);
        EntityType<?> big = Nests.big();
        if (big != null) {
            for (int i = 0; i < 4; i++) {
                double ang = i * Math.PI / 2.0D + Math.PI / 4.0D;
                Entity e = big.spawn(level, c.offset((int) Math.round(Math.cos(ang) * 6.0D), 0, (int) Math.round(Math.sin(ang) * 6.0D)),
                        MobSpawnType.STRUCTURE);
                if (e instanceof Mob m) m.setPersistenceRequired();
            }
        }
        data.boss = c;
        data.bossBuilt = true;
        data.setDirty();
        FleshSpread.LOGGER.info("Вимір плоті: збудовано лігво боса (заглушка) на {}", c.toShortString());
    }

    static List<Mob> infectedNear(ServerLevel level, Entity around, double radius) {
        return new ArrayList<>(level.getEntitiesOfClass(Mob.class, new AABB(around.blockPosition()).inflate(radius, radius / 2.0D, radius),
                m -> m.isAlive() && isInfected(m) && !Nests.isIncubator(m)));
    }
}
