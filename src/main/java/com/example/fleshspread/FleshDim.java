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
            .mapColor(MapColor.COLOR_RED).strength(5.0F, 1200.0F).sound(SoundType.SLIME_BLOCK).lightLevel(s -> 13).noLootTable()));

    /** Затверділа плоть: не горить і не ламається вибухами; з неї лігво та вхід у нього. */
    public static final RegistryObject<Block> HARDENED = BLOCKS.register("hardened_flesh", () -> new Block(BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_RED).strength(6.0F, 1200.0F).sound(SoundType.BONE_BLOCK).requiresCorrectToolForDrops()));
    public static final RegistryObject<Block> HARDENED_STAIRS = BLOCKS.register("hardened_flesh_stairs",
            () -> new StairBlock(() -> HARDENED.get().defaultBlockState(), BlockBehaviour.Properties.copy(HARDENED.get())));
    public static final RegistryObject<Item> HARDENED_ITEM = ProbeItem.ITEMS.register("hardened_flesh",
            () -> new BlockItem(HARDENED.get(), new Item.Properties()));
    public static final RegistryObject<Item> HARDENED_STAIRS_ITEM = ProbeItem.ITEMS.register("hardened_flesh_stairs",
            () -> new BlockItem(HARDENED_STAIRS.get(), new Item.Properties()));

    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, FleshSpread.MOD_ID);
    public static final RegistryObject<EntityType<FleshBoss>> BOSS = ENTITIES.register("flesh_boss",
            () -> EntityType.Builder.<FleshBoss>of(FleshBoss::new, net.minecraft.world.entity.MobCategory.MONSTER).sized(8.0F, 10.0F)
                    .clientTrackingRange(16).updateInterval(1).build("flesh_boss"));

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
        if (until != null && until - now > 400L) until = null; // залишок від іншого світу (лічильник тіків почався знову)
        if (until != null && now < until) {
            COOLDOWN.put(player.getUUID(), Math.max(until, now + 40L)); // поки стоїш у порталі, він не спрацює вдруге
            return;
        }
        if (!isFlesh(level) && FleshData.defeated(level.getServer())) {
            COOLDOWN.put(player.getUUID(), now + 60L);
            seal(level, pos);
            player.displayClientMessage(Component.literal("Інфекцію переможено. Портал запечатано назавжди.").withStyle(ChatFormatting.AQUA), true);
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

    /** Запечатує портал: уся з'єднана мембрана стає синьою і більше не працює. */
    static int seal(ServerLevel level, BlockPos pos) {
        Set<BlockPos> done = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(pos);
        done.add(pos);
        int n = 0;
        while (!queue.isEmpty() && done.size() < 600) {
            BlockPos p = queue.poll();
            BlockState st = level.getBlockState(p);
            if (!st.is(PORTAL.get())) continue;
            level.setBlock(p, st.setValue(FleshPortalBlock.SEALED, true).setValue(FleshPortalBlock.ACTIVE, true), 18);
            n++;
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (done.add(q) && level.getBlockState(q).is(PORTAL.get())) queue.add(q);
            }
        }
        return n;
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
            EntityType<?> type = isFlesh(level) ? anyMob(level) : InfectedRaids.pickType(level.random, day);
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

    /** Нова гра чи інший світ: тимчасові списки попереднього світу більше не діють (через них портал «не пускав»). */
    @SubscribeEvent
    public static void onServerStart(net.minecraftforge.event.server.ServerStartingEvent event) {
        COOLDOWN.clear();
        RUINS.clear();
        NESTS.clear();
        MOBS.clear();
        lastForeignLog = 0L;
    }

    // ================================================================== подорож

    private static void travel(ServerLevel from, ServerPlayer player) {
        MinecraftServer server = from.getServer();
        FsNet.sendFx(player, 6); // щупальця плоті затягують екран
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
            List<Entity> squad = squad(from, player);
            player.teleportTo(dest, back.getX() + 0.5D, back.getY(), back.getZ() + 0.5D, player.getYRot(), player.getXRot());
            int moved = moveSquad(squad, dest, back);
            if (FleshData.defeated(server)) {
                // портал, через який заходили, стає синім
                int sealed = 0;
                for (BlockPos q : BlockPos.betweenClosed(back.offset(-3, -3, -3), back.offset(3, 4, 3))) {
                    BlockState st = dest.getBlockState(q);
                    if (st.is(PORTAL.get()) && !st.getValue(FleshPortalBlock.SEALED)) sealed += seal(dest, q.immutable());
                }
                player.sendSystemMessage(Component.literal("Ви повернулися. Інфекцію переможено: вимір плоті закрито назавжди.")
                        .withStyle(ChatFormatting.AQUA));
                FleshSpread.LOGGER.info("Портал плоті: після перемоги запечатано {} блоків мембрани біля {}", sealed, back.toShortString());
            }
            FleshSpread.LOGGER.info("Портал плоті: з гравцем перейшло бійців: {}", moved);
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
        List<Entity> squad = squad(from, player);
        player.teleportTo(flesh, dest.getX() + 0.5D, dest.getY(), dest.getZ() + 0.5D, 180.0F, 0.0F);
        int moved = moveSquad(squad, flesh, dest);
        if (moved > 0) {
            player.sendSystemMessage(Component.literal("З вами пройшло бійців: " + moved).withStyle(ChatFormatting.GRAY));
        }
        FleshSpread.LOGGER.info("Портал плоті: з гравцем перейшло бійців: {}", moved);
        sound(flesh, dest, "flesh_that_hates:entity.call", 3.0F, 0.5F);
        player.sendSystemMessage(Component.literal("Ви у вимірі плоті. Десь тут спить Серце плоті — кривавий компас вкаже шлях.")
                .withStyle(ChatFormatting.DARK_RED));
        FleshSpread.LOGGER.info("Портал плоті: {} увійшов у вимір плоті, прибуття {} (лігво боса: {})", player.getName().getString(),
                dest.toShortString(), FleshData.get(flesh).boss == null ? "?" : FleshData.get(flesh).boss.toShortString());
    }

    /**
     * Підрозділ гравця: його рекрути (мод Recruits) у радіусі 24 блоків від порталу. Вони проходять портал разом із ним
     * (і туди, і назад), тож військо можна привести на бій, зібравши його біля порталу.
     */
    private static List<Entity> squad(ServerLevel level, ServerPlayer player) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : level.getEntities(player, player.getBoundingBox().inflate(24.0D, 10.0D, 24.0D),
                x -> x.isAlive() && x.getClass().getName().startsWith("com.talhanation.recruits."))) {
            try {
                Object owner = e.getClass().getMethod("getOwnerUUID").invoke(e);
                if (player.getUUID().equals(owner) && out.size() < 60) out.add(e);
            } catch (Throwable ignored) {
                // не рекрут або інша версія моду
            }
        }
        return out;
    }

    private static int moveSquad(List<Entity> squad, ServerLevel dest, BlockPos at) {
        int moved = 0;
        for (Entity e : squad) {
            try {
                e.getPersistentData().putBoolean("fs_guest", true);
                double x = at.getX() + 0.5D + (dest.random.nextDouble() - 0.5D) * 6.0D;
                double z = at.getZ() + 1.5D + dest.random.nextDouble() * 5.0D;
                final net.minecraft.world.phys.Vec3 to = new net.minecraft.world.phys.Vec3(x, at.getY(), z);
                Entity moved2 = e.changeDimension(dest, new net.minecraftforge.common.util.ITeleporter() {
                    @Override
                    public net.minecraft.world.level.portal.PortalInfo getPortalInfo(Entity entity, ServerLevel d,
                            java.util.function.Function<ServerLevel, net.minecraft.world.level.portal.PortalInfo> def) {
                        return new net.minecraft.world.level.portal.PortalInfo(to, net.minecraft.world.phys.Vec3.ZERO, entity.getYRot(), entity.getXRot());
                    }

                    @Override
                    public boolean playTeleportSound(ServerPlayer p, ServerLevel a, ServerLevel b) {
                        return false;
                    }
                });
                if (moved2 != null) moved++;
            } catch (Throwable t) {
                FleshSpread.LOGGER.warn("Портал плоті: бійця не вдалося перевести: {}", t.toString());
            }
        }
        return moved;
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
        buildPortal(flesh, base);
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

    /** Будує рамку 4x5 уздовж осі X із пробудженою мембраною, майданчик під нею і вільне місце довкола. */
    static void buildPortal(ServerLevel flesh, BlockPos base) {
        Block floor = fth("rotting_flesh_block");
        BlockState floorState = floor != null ? floor.defaultBlockState() : FRAME.get().defaultBlockState();
        for (int dx = -1; dx <= 4; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                BlockPos f = base.offset(dx, -1, dz);
                if (flesh.getBlockState(f).getCollisionShape(flesh, f).isEmpty() || !flesh.getFluidState(f).isEmpty()) {
                    flesh.setBlock(f, floorState, 3);
                }
                for (int dy = 0; dy <= 5; dy++) flesh.setBlock(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
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
    }

    // ================================================================== усе з плоті: нові чанки виміру

    /** Що робити з блоком звичайного світу. */
    private static final class Rule {
        BlockState target;   // null = лишити як є
        int kind;            // 0 просто замінити, 1 рослина, 2 поверхня (трава), 3 будівельний блок (руйнується клітинами), 4 прибрати завжди
        boolean chest;
    }

    private static final Rule KEEP = new Rule();
    private static final Map<BlockState, Rule> RULES = new IdentityHashMap<>();
    private record PendingNest(int x, int z, long time) {}
    private static final ArrayDeque<PendingNest> NESTS = new ArrayDeque<>();
    /** Відкладена робота над новим чанком: блоки, які треба прибрати «по-справжньому» (з оновленням світла), і скрині. */
    private record Ruin(long chunk, long time, List<BlockPos> remove, List<BlockPos> chests) {}
    private static final ArrayDeque<Ruin> RUINS = new ArrayDeque<>();
    private record PendingMob(BlockPos pos, String type) {}
    private static final ArrayDeque<PendingMob> MOBS = new ArrayDeque<>();

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

    private static int hash(int x, int y, int z) {
        long h = x * 73428767L ^ y * 912931L ^ z * 42317861L;
        h ^= h >>> 13;
        h *= 0x5DEECE66DL;
        h ^= h >>> 17;
        return (int) (h & 0x7FFFFFFF);
    }

    /**
     * Замінює звичайні блоки щойно згенерованого чанка на плоть (каміння, землю, траву, дерева, будинки сіл). Села
     * стають руїнами: частину стін і дахів вибито цілими шматками, дверей, вікон, ліжок і смолоскипів немає.
     */
    private static void fleshify(ServerLevel level, LevelChunk chunk) {
        int changed = 0;
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        List<BlockPos> remove = new ArrayList<>();
        List<BlockPos> chests = new ArrayList<>();
        LevelChunkSection[] sections = chunk.getSections();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) continue;
            int y0 = chunk.getSectionYFromSectionIndex(si) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = sec.getBlockState(x, y, z);
                        if (st.isAir()) continue;
                        Rule rule = RULES.get(st);
                        if (rule == null) {
                            rule = rule(st);
                            RULES.put(st, rule);
                        }
                        if (rule == KEEP) continue;
                        int bx = x0 + x, by = y0 + y, bz = z0 + z;
                        if (rule.chest) chests.add(new BlockPos(bx, by, bz));
                        BlockState target = rule.target;
                        switch (rule.kind) {
                            case 1 -> // трава й квіти зникають, зрідка лишається зубастий кущ
                                    target = target != null && level.random.nextInt(45) == 0 ? target : Blocks.AIR.defaultBlockState();
                            case 2 -> { // поверхня плямами: жива трава, гнила трава, гола плоть, пісок
                                int h = hash(bx >> 3, 0, bz >> 3) % 100;
                                Block alt = h < 45 ? null : fth(h < 70 ? "rotting_flesh_grass" : h < 88 ? "flesh_dirt" : "rotting_flesh_dirt");
                                if (alt != null) target = alt.defaultBlockState();
                            }
                            case 3 -> { // руїни: клітини 3x3x3, третина з них вибита майже повністю
                                boolean broken = hash(Math.floorDiv(bx, 3), Math.floorDiv(by, 3), Math.floorDiv(bz, 3)) % 100 < 36;
                                if (level.random.nextInt(100) < (broken ? 85 : 7)) {
                                    remove.add(new BlockPos(bx, by, bz));
                                    continue;
                                }
                            }
                            case 4 -> {
                                remove.add(new BlockPos(bx, by, bz));
                                continue;
                            }
                            default -> { }
                        }
                        if (target != null && target != st) {
                            sec.setBlockState(x, y, z, target, false);
                            changed++;
                        }
                    }
                }
            }
        }
        if (changed > 0) chunk.setUnsaved(true);
        if (!remove.isEmpty() || !chests.isEmpty()) {
            RUINS.add(new Ruin(chunk.getPos().toLong(), level.getGameTime(), remove, chests));
        }
    }

    private static Rule rule(BlockState st) {
        Block b = st.getBlock();
        String to = null;
        int kind = 0;
        boolean chest = b instanceof net.minecraft.world.level.block.ChestBlock || b instanceof net.minecraft.world.level.block.BarrelBlock;
        if (st.is(BlockTags.LOGS)) to = "flesh_log";
        else if (st.is(BlockTags.LEAVES)) to = "flesh_leaves";
        else if (st.is(BlockTags.PLANKS)) { to = "flesh_planks"; kind = 3; }
        else if (b instanceof StairBlock) { to = "flesh_stair"; kind = 3; }
        else if (b instanceof SlabBlock) { to = "flesh_slab"; kind = 3; }
        else if (b instanceof FenceBlock) { to = "flesh_fence"; kind = 3; }
        else if (b instanceof FenceGateBlock) { to = "flesh_gate"; kind = 3; }
        else if (b instanceof DoorBlock || st.is(BlockTags.BEDS) || b instanceof net.minecraft.world.level.block.TorchBlock
                || b instanceof net.minecraft.world.level.block.LanternBlock || b instanceof net.minecraft.world.level.block.CampfireBlock
                || b instanceof net.minecraft.world.level.block.IronBarsBlock || st.is(BlockTags.WOOL_CARPETS)
                || b instanceof net.minecraft.world.level.block.FlowerPotBlock || b == Blocks.GLASS) kind = 4;
        else if (st.is(BlockTags.WOOL)) kind = 3;
        else if (b instanceof TrapDoorBlock) { to = "flesh_trapdoor"; kind = 3; }
        else if (b == Blocks.GRASS_BLOCK || b == Blocks.PODZOL || b == Blocks.MYCELIUM) { to = "flesh_grass"; kind = 2; }
        else if (b == Blocks.DIRT_PATH || b == Blocks.FARMLAND) to = "rotting_flesh_dirt";
        else if (st.is(BlockTags.DIRT) || b == Blocks.CLAY) to = "flesh_dirt";
        else if (st.is(BlockTags.SAND) || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE) to = "flesh_sand";
        else if (b == Blocks.GRAVEL) to = "rotting_flesh_sand";
        else if (b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE || b == Blocks.STONE_BRICKS || b == Blocks.MOSSY_STONE_BRICKS
                || b == Blocks.HAY_BLOCK || b == Blocks.WHITE_TERRACOTTA || b == Blocks.YELLOW_TERRACOTTA) { to = "rotting_flesh_block"; kind = 3; }
        else if (b == Blocks.DEEPSLATE || b == Blocks.TUFF || b == Blocks.COBBLED_DEEPSLATE) to = "rotting_flesh_block";
        else if (st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(Tags.Blocks.ORES) || b == Blocks.TERRACOTTA
                || b == Blocks.SMOOTH_STONE || b == Blocks.CALCITE) to = "flesh_block";
        else if (st.is(BlockTags.FLOWERS) || b == Blocks.GRASS || b == Blocks.FERN || b == Blocks.TALL_GRASS
                || b == Blocks.LARGE_FERN || b == Blocks.DEAD_BUSH || b instanceof CropBlock || st.is(BlockTags.SAPLINGS)
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.PUMPKIN || b == Blocks.MELON) {
            to = "tooth_bush";
            kind = 1;
        }
        if (to == null && kind == 0 && !chest) return KEEP;
        Rule r = new Rule();
        r.kind = kind;
        r.chest = chest;
        Block target = to == null ? null : fth(to);
        if (target != null) {
            BlockState out = target.defaultBlockState();
            if (kind != 1) {
                for (Property<?> p : st.getProperties()) out = copy(st, out, p);
            }
            r.target = out;
        }
        return r;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> p) {
        return to.hasProperty(p) ? to.setValue(p, from.getValue(p)) : to;
    }

    /** Скрині покинутих сіл: порожні або з плоттю, здобиччю з монстрів чи речами зараження. */
    private static void loot(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity box)) return;
        box.setLootTable(null, 0L);
        box.clearContent();
        if (level.random.nextInt(100) < 35) return; // порожньо
        String[] pool = {"flesh_that_hates:flesh", "flesh_that_hates:flesh", "flesh_that_hates:flesh", "minecraft:rotten_flesh",
                "minecraft:rotten_flesh", "minecraft:bone", "minecraft:bone", "minecraft:string", "minecraft:spider_eye",
                "minecraft:gunpowder", "minecraft:arrow", "flesh_that_hates:flesh_veins", "flesh_that_hates:tooth_bush",
                "flesh_that_hates:flesh_roots", "flesh_that_hates:cooked_flesh", "minecraft:ender_pearl", "minecraft:slime_ball"};
        int stacks = 1 + level.random.nextInt(5);
        for (int i = 0; i < stacks; i++) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(pool[level.random.nextInt(pool.length)]));
            if (item == null || item == Items.AIR) continue;
            int slot = level.random.nextInt(box.getContainerSize());
            if (box.getItem(slot).isEmpty()) box.setItem(slot, new ItemStack(item, 1 + level.random.nextInt(4)));
        }
    }

    private static final String[] ALL_MOBS = {"scp_6101", "scp_6101", "scp_6102", "scp_6102", "scp_6103", "scp_6103", "scp_6104", "scp_6105",
            "scp_6107", "scp_6108", "scp_6109", "scp_61010", "scp_61011", "infected_pillager"};

    /** Будь-який моб зараження (у вимірі плоті живуть усі). */
    static EntityType<?> anyMob(ServerLevel level) {
        for (int i = 0; i < 6; i++) {
            ResourceLocation id = new ResourceLocation("flesh_that_hates", ALL_MOBS[level.random.nextInt(ALL_MOBS.length)]);
            if (ForgeRegistries.ENTITY_TYPES.containsKey(id)) return ForgeRegistries.ENTITY_TYPES.getValue(id);
        }
        return null;
    }

    /**
     * Хто не має права бути у вимірі плоті: жителі, големи, тварини, а також «дикі» рекрути (патрулі мода Recruits, що
     * з'являються самі). Пускаємо лише тих, кого привели через портал або прислали села (тег fs_guest), і рекрутів гравців.
     */
    private static boolean foreign(Entity e) {
        if (e.getPersistentData().getBoolean("fs_guest")) return false;
        if (e.getClass().getName().startsWith("com.talhanation.recruits.")) return !owned(e);
        return e instanceof AbstractVillager || e instanceof IronGolem || (e instanceof Animal && !isInfected(e));
    }

    private static boolean owned(Entity e) {
        try {
            return e.getClass().getMethod("getOwnerUUID").invoke(e) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Покинуті села: ні жителів, ні големів, ні котів чи худоби. Замість жителів селом ходять заражені. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity e = event.getEntity();
        if (!isFlesh(event.getLevel())) {
            // інфекцію переможено: у звичайному світі заражених більше не буває
            if (isInfected(e) && event.getLevel() instanceof ServerLevel sl && FleshData.defeated(sl.getServer())) event.setCanceled(true);
            return;
        }
        if (!foreign(e)) return;
        event.setCanceled(true);
        if (e.getClass().getName().startsWith("com.talhanation.recruits.") && level0Log(e)) {
            FleshSpread.LOGGER.info("Вимір плоті: не пущено чужого рекрута {} на {} (патруль без власника)", e.getType().toShortString(),
                    e.blockPosition().toShortString());
        }
        if (e instanceof AbstractVillager && MOBS.size() < 400) {
            MOBS.add(new PendingMob(e.blockPosition(), e.level().random.nextInt(5) == 0 ? "infected_pillager" : "scp_6101"));
        }
    }

    private static long lastForeignLog;

    private static boolean level0Log(Entity e) {
        long t = e.level().getGameTime();
        if (t - lastForeignLog < 200L && t >= lastForeignLog) return false;
        lastForeignLog = t;
        return true;
    }

    // ================================================================== життя виміру

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || !isFlesh(level)) return;
        long t = level.getGameTime();
        SpreadConfig cfg = SpreadConfig.get();
        lairStep(level);
        if (t % 200L == 0L) heartCheck(level);

        // руїни сіл: вибиті блоки прибираємо звичайним способом, щоб оновилося світло; скрині спорожнюємо
        int budget = 2500;
        while (budget > 0 && !RUINS.isEmpty()) {
            Ruin r = RUINS.peek();
            int cx = net.minecraft.world.level.ChunkPos.getX(r.chunk()), cz = net.minecraft.world.level.ChunkPos.getZ(r.chunk());
            if (!level.hasChunk(cx, cz)) {
                RUINS.poll();
                if (t - r.time() < 6000L) RUINS.add(r); // чанк вивантажився: спробуємо пізніше
                break;
            }
            RUINS.poll();
            for (BlockPos p : r.remove()) level.setBlock(p, Blocks.AIR.defaultBlockState(), 18);
            for (BlockPos p : r.chests()) loot(level, p);
            budget -= r.remove().size() + 50;
            if (r.remove().size() > 40) {
                FleshSpread.LOGGER.info("Вимір плоті: руїни в чанку {} {} — вибито блоків {}, скринь {}", cx, cz, r.remove().size(), r.chests().size());
            }
        }
        // заражені жителі замість звичайних
        for (int i = 0; i < 6 && !MOBS.isEmpty(); i++) {
            PendingMob m = MOBS.poll();
            ResourceLocation id = new ResourceLocation("flesh_that_hates", m.type());
            if (!level.hasChunkAt(m.pos()) || !ForgeRegistries.ENTITY_TYPES.containsKey(id)) continue;
            Entity e = ForgeRegistries.ENTITY_TYPES.getValue(id).spawn(level, m.pos(), MobSpawnType.STRUCTURE);
            if (e instanceof Mob mob) mob.setPersistenceRequired();
        }
        // чужих тут не буває (коти, худоба, жителі, големи)
        if (t % 100L == 0L) {
            for (ServerPlayer p : level.players()) {
                for (Entity e : level.getEntities(p, p.getBoundingBox().inflate(96.0D, 64.0D, 96.0D), FleshDim::foreign)) e.discard();
            }
        }
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
            if (t % 200L == 0L && !p.isCreative() && !FleshData.defeated(level.getServer())) {
                int near = level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(40.0D, 24.0D, 40.0D),
                        m -> isInfected(m) && !Nests.isIncubator(m)).size();
                if (near < cfg.fleshDimMobsNearPlayer) summon(level, p.blockPosition(), 2, 20, 32, null);
            }
            if (data.boss == null || FleshData.defeated(level.getServer())) continue;
            double dx = p.getX() - data.boss.getX(), dz = p.getZ() - data.boss.getZ();
            double d2 = dx * dx + dz * dz;
            if (!data.lairDone && d2 < 150.0D * 150.0D) {
                if (data.lairStage < 0) {
                    data.lairStage = 0;
                    data.setDirty();
                    FleshSpread.LOGGER.info("Вимір плоті: починаю будувати лігво Утроби біля входу {}", data.boss.toShortString());
                }
            }
            if (data.lairDone && data.heart != null && t % 10L == 0L && level.getBlockState(data.heart).is(HEART.get())
                    && p.distanceToSqr(data.heart.getX(), data.heart.getY(), data.heart.getZ()) < 140.0D * 140.0D) {
                // стовп кривавого світла над Серцем, видно через усю арену
                net.minecraft.core.particles.DustParticleOptions blood = new net.minecraft.core.particles.DustParticleOptions(
                        new org.joml.Vector3f(0.85F, 0.05F, 0.05F), 2.5F);
                for (int i = 0; i < 30; i++) {
                    level.sendParticles(p, blood, true, data.heart.getX() + 0.5D, data.heart.getY() + 1.0D + i, data.heart.getZ() + 0.5D,
                            2, 0.25D, 0.4D, 0.25D, 0.0D);
                }
                level.sendParticles(p, net.minecraft.core.particles.ParticleTypes.CRIMSON_SPORE, true, data.heart.getX() + 0.5D,
                        data.heart.getY() + 1.5D, data.heart.getZ() + 0.5D, 20, 3.0D, 2.0D, 3.0D, 0.02D);
            }
            if (data.lairDone && d2 < 20.0D * 20.0D && !p.getPersistentData().getBoolean("fs_lair_seen")) {
                p.getPersistentData().putBoolean("fs_lair_seen", true);
                sound(level, data.boss, "flesh_that_hates:entity.call", 6.0F, 0.4F);
                p.sendSystemMessage(Component.literal("☣ Ви знайшли вхід у лігво. Унизу б'ється Серце плоті: розбийте його, якщо готові до бою.")
                        .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
                FleshSpread.LOGGER.info("Вимір плоті: {} знайшов вхід у лігво на {}", p.getName().getString(), data.boss.toShortString());
            }
        }
    }

    // ================================================================== лігво: печера-арена під землею

    static final int LAIR_RADIUS = 60, LAIR_HEIGHT = 36, LAIR_OFFSET = 42, LAIR_VERSION = 3, SHELL = 4, TUNNEL_W = 5, TUNNEL_H = 8;

    /** Будує лігво поступово (по шару за тик), щоб не підвішувати сервер. Викликається щотику у вимірі плоті. */
    /** Радіус порожнини на висоті dy над підлогою (купол). */
    private static int cavity(int dy) {
        if (dy < 0 || dy > LAIR_HEIGHT) return -1;
        double h = dy / (double) LAIR_HEIGHT;
        return (int) Math.round(LAIR_RADIUS * Math.sqrt(Math.max(0.0D, 1.0D - h * h * 0.85D)));
    }

    private static boolean inCavity(BlockPos c, BlockPos p) {
        int r = cavity(p.getY() - c.getY());
        if (r < 0) return false;
        double dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
        return dx * dx + dz * dz <= r * r;
    }

    private static void lairStep(ServerLevel level) {
        FleshData data = FleshData.get(level);
        if (data.boss != null && data.lairDone && data.lairVersion < LAIR_VERSION) {
            // старе лігво (без купола, затоплене): будуємо наново
            FleshSpread.LOGGER.info("Вимір плоті: лігво старої версії {} — перебудовую з куполом", data.lairVersion);
            data.lairDone = false;
            data.lairStage = 0;
            data.center = null;
            data.heart = null;
            data.setDirty();
        }
        if (data.boss == null || data.lairDone || data.lairStage < 0) return;
        Block log = fth("flesh_log");
        BlockState floor = HARDENED.get().defaultBlockState(); // не горить: оболонка купола, підлога, тунель
        BlockState pillar = log != null ? log.defaultBlockState() : FRAME.get().defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        int ex = data.boss.getX(), ez = data.boss.getZ();       // вхід (колона з драбиною)
        if (data.center == null) {
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ex, ez);
            int fy = Math.max(level.getMinBuildHeight() + 12, Math.min(surface - LAIR_HEIGHT - 22, 8));
            // похилий тунель зі сходами (1:1) від стіни арени до поверхні: вхід там, куди веде компас
            data.center = new BlockPos(ex - (LAIR_RADIUS - 4) - Math.max(0, surface - fy), fy, ez);
            data.boss = new BlockPos(ex, surface, ez);
        }
        BlockPos c = data.center;
        int stage = data.lairStage++;
        data.setDirty();
        int R = LAIR_RADIUS;
        if (stage <= LAIR_HEIGHT + 4) {
            // шари знизу вгору: три шари підлоги, порожнина-купол, навколо неї суцільна оболонка з плоті (щоб не залило
            // водою чи лавою з печер), зверху — дах
            int dy = stage - 3;
            int r = cavity(dy);
            int outer = (dy < 0 ? R : Math.max(r, cavity(Math.min(dy, LAIR_HEIGHT)))) + SHELL;
            if (dy > LAIR_HEIGHT) outer = cavity(LAIR_HEIGHT) + SHELL + 2;
            for (int dx = -outer; dx <= outer; dx++) {
                for (int dz = -outer; dz <= outer; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > outer * outer) continue;
                    BlockPos q = c.offset(dx, dy, dz);
                    level.setBlock(q, r >= 0 && d2 <= r * r ? air : floor, 2);
                }
            }
            return;
        }
        // оздоблення
        for (int i = 0; i < 10; i++) { // стовпи по колу зі світлом
            double ang = i * Math.PI / 5.0D;
            int px = (int) Math.round(Math.cos(ang) * (R - 8)), pz = (int) Math.round(Math.sin(ang) * (R - 8));
            int h = 6 + (i % 3) * 3;
            for (int dy = 0; dy < h; dy++) level.setBlock(c.offset(px, dy, pz), pillar, 2);
            level.setBlock(c.offset(px, h, pz), Blocks.SHROOMLIGHT.defaultBlockState(), 2);
        }
        for (int i = 0; i < 24; i++) { // світло в підлозі
            double ang = i * Math.PI / 12.0D;
            int rr = i % 2 == 0 ? 14 : 30;
            level.setBlock(c.offset((int) Math.round(Math.cos(ang) * rr), -1, (int) Math.round(Math.sin(ang) * rr)), Blocks.SHROOMLIGHT.defaultBlockState(), 2);
        }
        // п'єдестал із Серцем плоті в центрі: ступінчаста піраміда, чотири обеліски і світло довкола
        BlockState flesh = fth("flesh_block") != null ? fth("flesh_block").defaultBlockState() : FRAME.get().defaultBlockState();
        int[] steps = {8, 6, 4, 2};
        BlockState[] stepBlock = {floor, flesh, FRAME.get().defaultBlockState(), FRAME.get().defaultBlockState()};
        for (int lvl = 0; lvl < steps.length; lvl++) {
            int sr = steps[lvl];
            for (int dx = -sr; dx <= sr; dx++) {
                for (int dz = -sr; dz <= sr; dz++) {
                    if (dx * dx + dz * dz <= sr * sr + sr) level.setBlock(c.offset(dx, lvl, dz), stepBlock[lvl], 2);
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            double ang = i * Math.PI / 2.0D + Math.PI / 4.0D;
            int px = (int) Math.round(Math.cos(ang) * 12.0D), pz = (int) Math.round(Math.sin(ang) * 12.0D);
            for (int dy = 0; dy < 16; dy++) {
                for (int ox = 0; ox <= 1; ox++) {
                    for (int oz = 0; oz <= 1; oz++) level.setBlock(c.offset(px + ox, dy, pz + oz), dy % 5 == 4 ? FRAME.get().defaultBlockState() : pillar, 2);
                }
            }
            for (int ox = 0; ox <= 1; ox++) {
                for (int oz = 0; oz <= 1; oz++) level.setBlock(c.offset(px + ox, 16, pz + oz), Blocks.SHROOMLIGHT.defaultBlockState(), 2);
            }
        }
        for (int i = 0; i < 32; i++) {
            double ang = i * Math.PI / 16.0D;
            level.setBlock(c.offset((int) Math.round(Math.cos(ang) * 10.0D), -1, (int) Math.round(Math.sin(ang) * 10.0D)),
                    Blocks.SHROOMLIGHT.defaultBlockState(), 2);
        }
        data.heart = c.above(steps.length);
        level.setBlock(data.heart, HEART.get().defaultBlockState(), 3);
        buildTunnel(level, c, floor);
        EntityType<?> big = Nests.big();
        if (big != null) {
            for (int i = 0; i < 4; i++) {
                double ang = i * Math.PI / 2.0D + Math.PI / 4.0D;
                Entity en = big.spawn(level, c.offset((int) Math.round(Math.cos(ang) * 22.0D), 0, (int) Math.round(Math.sin(ang) * 22.0D)), MobSpawnType.STRUCTURE);
                if (en instanceof Mob m) m.setPersistenceRequired();
            }
        }
        data.lairDone = true;
        data.lairVersion = LAIR_VERSION;
        data.setDirty();
        FleshSpread.LOGGER.info("Вимір плоті: лігво Утроби готове — вхід {}, арена {} (радіус {}), Серце плоті {}", data.boss.toShortString(),
                c.toShortString(), R, data.heart.toShortString());
    }

    /** У звичайному світі ніхто не обирає вісника за ціль: його не можна вбити (у вимірі плоті — можна і треба). */
    @SubscribeEvent
    public static void onChangeTarget(net.minecraftforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getNewTarget() instanceof FleshBoss boss && !isFlesh(boss.level()) && boss.herald) event.setCanceled(true);
    }

    /**
     * Вхід у лігво — широкий похилий тунель («ополонка» в печеру) зі сходами з затверділої плоті, від стіни арени вгору
     * до поверхні. 11 блоків завширшки й 8 заввишки, тож військо проходить строєм. Стіни й стеля — затверділа плоть
     * (не горить), уздовж — світло.
     */
    private static void buildTunnel(ServerLevel level, BlockPos c, BlockState shell) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stairs = HARDENED_STAIRS.get().defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
        int x0 = c.getX() + LAIR_RADIUS - 6;
        int z = c.getZ();
        // висота поверхні над кожним стовпцем — до того, як рити
        int len = 0;
        while (len < 200) {
            int x = x0 + len;
            int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (c.getY() + len > ground + 1) break;
            len++;
        }
        for (int t = 0; t <= len; t++) {
            int x = x0 + t;
            int fy = c.getY() + t - 1; // сходинка
            for (int dz = -TUNNEL_W - 2; dz <= TUNNEL_W + 2; dz++) {
                int zz = z + dz;
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, zz);
                boolean wall = Math.abs(dz) > TUNNEL_W;
                for (int dy = -2; dy <= TUNNEL_H + 2; dy++) {
                    BlockPos q = new BlockPos(x, fy + dy, zz);
                    if (inCavity(c, q)) continue;
                    boolean inside = !wall && dy >= 0 && dy <= TUNNEL_H;
                    if (inside) {
                        level.setBlock(q, dy == 0 ? stairs : air, 2);
                    } else if (q.getY() < ground) {
                        // оболонка лише під землею: нагорі тунель стає відкритою ополонкою
                        boolean light = wall && dy == 4 && t % 6 == 3;
                        level.setBlock(q, light ? Blocks.SHROOMLIGHT.defaultBlockState() : shell, 2);
                    }
                }
            }
        }
        // обеліски біля входу
        int ex = x0 + len;
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < 2; k++) {
                int px = ex - k * 8, pz = z + side * (TUNNEL_W + 4);
                int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
                for (int dy = 0; dy < 9; dy++) {
                    for (int ox = 0; ox <= 1; ox++) {
                        for (int oz = 0; oz <= 1; oz++) {
                            level.setBlock(new BlockPos(px + ox, gy + dy, pz + oz), dy == 8 ? Blocks.SHROOMLIGHT.defaultBlockState() : shell, 2);
                        }
                    }
                }
            }
        }
        FleshSpread.LOGGER.info("Вимір плоті: вхідний тунель у лігво — {} сходинок, вихід на поверхню біля {}, {}", len, ex, z);
    }

    /** Серце плоті розбито: вий, темрява, і з-під землі виринає Утроба. */
    @SubscribeEvent
    public static void onHeartBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !event.getState().is(HEART.get()) || !isFlesh(level)) return;
        FleshData data = FleshData.get(level);
        if (data.heart == null || !data.heart.equals(event.getPos()) || data.center == null) return;
        if (FleshData.defeated(level.getServer()) || !FleshBoss.near(level, data.center, 120.0D).isEmpty()) return;
        startFight(level, data.center, LAIR_RADIUS, event.getPlayer().getName().getString());
    }

    static void startFight(ServerLevel level, BlockPos center, int radius, String who) {
        FleshBoss boss = BOSS.get().create(level);
        if (boss == null) return;
        double ang = level.random.nextDouble() * Math.PI * 2.0D;
        boss.moveTo(center.getX() + 0.5D + Math.cos(ang) * 24.0D, center.getY(), center.getZ() + 0.5D + Math.sin(ang) * 24.0D,
                level.random.nextFloat() * 360.0F, 0.0F);
        boss.arena = center;
        boss.arenaRadius = radius;
        FleshData fd = FleshData.get(level);
        fd.fightActive = true;
        fd.fightGen++;
        fd.fightCenter = center;
        fd.fightRadius = radius;
        fd.setDirty();
        boss.fightGen = fd.fightGen;
        boss.wake();
        level.addFreshEntity(boss);
        sound(level, center, "flesh_that_hates:entity.call", 10.0F, 0.4F);
        sound(level, center, "minecraft:entity.wither.spawn", 6.0F, 0.5F);
        Component msg = Component.literal("☣ Серце плоті розбито. УТРОБА ПРОКИНУЛАСЬ.").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(center.getX(), center.getY(), center.getZ()) > 160.0D * 160.0D) continue;
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.DARKNESS, 140, 0, false, false));
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS, 50, 0, false, false));
            FsNet.sendFx(p, 5);
            p.sendSystemMessage(msg);
        }
        FleshSpread.LOGGER.info("Бій з Утробою почався: арена {} (радіус {}), розбудив {}", center.toShortString(), radius, who);
    }

    /** Серця немає, а бою немає (бос пропав будь-як): Серце повертається на п'єдестал. */
    private static void heartCheck(ServerLevel level) {
        FleshData data = FleshData.get(level);
        if (data.fightActive || !data.lairDone || data.heart == null || data.center == null || FleshData.defeated(level.getServer())) return;
        if (!level.hasChunkAt(data.heart) || level.getBlockState(data.heart).is(HEART.get())) return;
        level.setBlock(data.heart, HEART.get().defaultBlockState(), 3);
        FleshSpread.LOGGER.info("Вимір плоті: бою немає, Серце плоті повернуто на п'єдестал {}", data.heart.toShortString());
    }

    private static final Map<net.minecraft.resources.ResourceKey<Level>, Integer> AWAY = new HashMap<>();

    /**
     * Сторож бою (працює в будь-якому світі, навіть коли сам бос не тікає — він стоїть у чанку, що не оновлюється): якщо
     * біля арени 10 с немає живих гравців, бій завершено — бос зникає (зараз або щойно прогрузиться), Серце повертається.
     */
    @SubscribeEvent
    public static void onAnyLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.getGameTime() % 20L != 0L) return;
        FleshData data = FleshData.get(level);
        if (!data.fightActive || data.fightCenter == null) {
            AWAY.remove(level.dimension());
            return;
        }
        double r = data.fightRadius + 60.0D;
        boolean anyone = false;
        for (ServerPlayer p : level.players()) {
            if (p.isAlive() && !p.isSpectator() && p.distanceToSqr(data.fightCenter.getX() + 0.5D, p.getY(), data.fightCenter.getZ() + 0.5D) < r * r) {
                anyone = true;
            }
        }
        int away = anyone ? 0 : AWAY.getOrDefault(level.dimension(), 0) + 1;
        AWAY.put(level.dimension(), away);
        if (away < 10) return;
        data.fightActive = false;
        data.setDirty();
        AWAY.remove(level.dimension());
        int gone = 0;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof FleshBoss b && !b.herald && b.isAlive()) {
                b.discard();
                gone++;
            }
        }
        if (isFlesh(level)) restoreHeart(level);
        FleshSpread.LOGGER.info("Бій з Утробою скінчився: біля арени {} нікого 10 с; бос прибраний зараз: {} (решта зникне, щойно прогрузиться)",
                data.fightCenter.toShortString(), gone);
    }

    /** Гравці втекли або загинули: Серце плоті знову на п'єдесталі. */
    static void restoreHeart(ServerLevel level) {
        FleshData data = FleshData.get(level);
        if (data.heart != null && level.hasChunkAt(data.heart)) {
            level.setBlock(data.heart, HEART.get().defaultBlockState(), 3);
            sound(level, data.heart, "flesh_that_hates:entity.incubator.spawn", 4.0F, 0.6F);
        }
    }

    /** Утробу вбито: інфекцію переможено. Заражені гинуть, перед гравцем відкривається портал додому. */
    static void victory(ServerLevel level, FleshBoss boss) {
        FleshData global = FleshData.global(level.getServer());
        global.defeated = true;
        global.setDirty();
        FleshData here = FleshData.get(level);
        here.fightActive = false;
        here.setDirty();
        // трофей: Кишенькова утроба
        BlockPos drop = boss.arena != null ? boss.arena.above(6) : boss.blockPosition();
        ItemEntity trophy = new ItemEntity(level, drop.getX() + 0.5D, drop.getY(), drop.getZ() + 0.5D, new ItemStack(PocketMaw.ITEM.get()));
        trophy.setGlowingTag(true);
        trophy.setUnlimitedLifetime();
        level.addFreshEntity(trophy);
        BlockPos center = boss.arena != null ? boss.arena : boss.blockPosition();
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(center).inflate(boss.arenaRadius + 30.0D, 60.0D, boss.arenaRadius + 30.0D),
                FleshDim::isInfected)) m.discard();
        Player near = level.getNearestPlayer(boss, 200.0D);
        BlockPos at = near != null ? near.blockPosition().offset(-2, 0, -6) : center.offset(-2, 0, 8);
        at = new BlockPos(at.getX(), center.getY(), at.getZ());
        buildPortal(level, at);
        sound(level, at, "minecraft:ui.toast.challenge_complete", 4.0F, 1.0F);
        sound(level, at, "minecraft:block.end_portal.spawn", 4.0F, 1.0F);
        Component msg = Component.literal("☣ УТРОБУ ВБИТО. Інфекцію переможено: у звичайному світі більше не буде ні заражених, ні розломів. "
                + "Перед вами портал додому — після повернення вимір плоті закриється назавжди.").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        for (ServerPlayer p : level.getServer().getPlayerList().getPlayers()) p.sendSystemMessage(msg);
        FleshSpread.LOGGER.info("ПЕРЕМОГА: Утробу вбито, інфекцію вимкнено; портал додому на {}", at.toShortString());
    }

    // ================================================================== смерть у вимірі плоті

    /**
     * Гравець загинув у вимірі плоті або вийшов із нього: якщо біля арени більше нікого немає, Утроба одразу йде,
     * а Серце повертається — бій можна почати знову (інакше бос «застрягав» у вивантаженому чанку).
     */
    private static void abandonCheck(ServerLevel flesh, ServerPlayer gone) {
        for (Entity e : flesh.getAllEntities()) {
            if (!(e instanceof FleshBoss boss) || boss.herald || !boss.isAlive() || boss.arena == null) continue;
            boolean anyone = false;
            for (ServerPlayer p : flesh.players()) {
                if (p != gone && p.isAlive() && !p.isSpectator() && p.distanceToSqr(boss.arena.getX(), p.getY(), boss.arena.getZ())
                        < (boss.arenaRadius + 60.0D) * (boss.arenaRadius + 60.0D)) anyone = true;
            }
            if (!anyone) boss.leave(flesh, gone.isAlive() ? "гравець покинув вимір" : "гравець загинув");
        }
    }

    @SubscribeEvent
    public static void onLeaveFlesh(net.minecraftforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getFrom() == KEY) {
            ServerLevel flesh = p.server.getLevel(KEY);
            if (flesh != null) abandonCheck(flesh, p);
        }
    }

    @SubscribeEvent
    public static void onLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && isFlesh(p.level())) abandonCheck((ServerLevel) p.level(), p);
    }

    /** Убитий Утробою втрачає не одне життя, а п'ять (мод Limited Lives). */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onPlayerDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || event.isCanceled()) return;
        COOLDOWN.remove(p.getUUID()); // після смерті портал знову пускає одразу
        if (isFlesh(p.level())) abandonCheck((ServerLevel) p.level(), p);
        Entity killer = event.getSource().getEntity();
        if (!(killer instanceof FleshBoss)) return;
        try {
            Object api = Class.forName("me.ichun.mods.limitedlives.api.LimitedLivesApi").getMethod("getApiImpl").invoke(null);
            Class<?> iface = Class.forName("me.ichun.mods.limitedlives.api.IApi");
            int deaths = (Integer) iface.getMethod("getPlayerDeaths", ServerPlayer.class).invoke(api, p);
            iface.getMethod("setPlayerDeaths", ServerPlayer.class, int.class, boolean.class).invoke(api, p, deaths + 4, true);
            FleshSpread.LOGGER.info("Утроба вбила {}: знято 5 життів (було смертей {}, стало {} + ця)", p.getName().getString(), deaths, deaths + 4);
        } catch (Throwable t) {
            FleshSpread.LOGGER.info("Утроба вбила {} (Limited Lives недоступний: {})", p.getName().getString(), t.toString());
        }
        p.sendSystemMessage(Component.literal("Утроба забрала п'ять життів.").withStyle(ChatFormatting.DARK_RED));
    }

    /** Артефакт Made in Heaven не випадає при смерті у вимірі плоті: він лишається з гравцем. */
    @SubscribeEvent
    public static void onPlayerDrops(net.minecraftforge.event.entity.living.LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !isFlesh(p.level())) return;
        net.minecraft.nbt.ListTag kept = new net.minecraft.nbt.ListTag();
        java.util.Iterator<ItemEntity> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemEntity drop = it.next();
            if (ARTIFACT.equals(ForgeRegistries.ITEMS.getKey(drop.getItem().getItem()))) {
                kept.add(drop.getItem().save(new CompoundTag()));
                it.remove();
            }
        }
        if (kept.isEmpty()) return;
        CompoundTag root = p.getPersistentData();
        CompoundTag keep = root.getCompound(Player.PERSISTED_NBT_TAG);
        keep.put("fs_kept", kept);
        root.put(Player.PERSISTED_NBT_TAG, keep);
    }

    @SubscribeEvent
    public static void onRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag root = p.getPersistentData();
        CompoundTag keep = root.getCompound(Player.PERSISTED_NBT_TAG);
        if (!keep.contains("fs_kept")) return;
        net.minecraft.nbt.ListTag kept = keep.getList("fs_kept", 10);
        keep.remove("fs_kept");
        root.put(Player.PERSISTED_NBT_TAG, keep);
        for (int i = 0; i < kept.size(); i++) {
            ItemStack stack = ItemStack.of(kept.getCompound(i));
            if (!stack.isEmpty() && !p.getInventory().add(stack)) p.drop(stack, false);
        }
        p.sendSystemMessage(Component.literal("Артефакт лишився з вами.").withStyle(ChatFormatting.GOLD));
    }

    static List<Mob> infectedNear(ServerLevel level, Entity around, double radius) {
        return new ArrayList<>(level.getEntitiesOfClass(Mob.class, new AABB(around.blockPosition()).inflate(radius, radius / 2.0D, radius),
                m -> m.isAlive() && isInfected(m) && !Nests.isIncubator(m)));
    }
}
