package com.example.fleshspread;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Кишенькова утроба — трофей за перемогу над Утробою. ПКМ у звичайному світі: із-під землі виростає розлом у кишеньковий
 * вимір; щойно хтось у нього стрибнув, розлом затягується. Кишеньковий вимір — один на всіх: рівна гнила поляна
 * 200x200, без ворожих мобів, де ніхто не отримує шкоди, лише мирна живність. Вихід — так само: ПКМ, розлом, і назад
 * туди, звідки зайшов.
 */
@Mod.EventBusSubscriber(modid = FleshSpread.MOD_ID)
public final class PocketMaw {
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(FleshSpread.MOD_ID, "pocket"));
    static final int HALF = 100; // 200x200
    private static final int W = 4, H = 5;

    public static final RegistryObject<Block> RIFT = FleshDim.BLOCKS.register("pocket_rift", RiftBlock::new);
    public static final RegistryObject<Item> ITEM = ProbeItem.ITEMS.register("pocket_maw",
            () -> new MawItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));

    private PocketMaw() {}

    static boolean isPocket(Level level) {
        return level.dimension() == KEY;
    }

    // ================================================================== предмет

    public static class MawItem extends Item {
        public MawItem(Properties p) {
            super(p);
        }

        @Override
        public InteractionResultHolder<ItemStack> use(Level lvl, Player player, InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            if (lvl.isClientSide || !(lvl instanceof ServerLevel level) || !(player instanceof ServerPlayer sp)) {
                return InteractionResultHolder.success(stack);
            }
            if (FleshDim.isFlesh(level)) {
                sp.displayClientMessage(Component.literal("Утроба мовчить: тут їй тісно від чужої плоті.").withStyle(ChatFormatting.DARK_RED), true);
                return InteractionResultHolder.fail(stack);
            }
            BlockPos at = open(level, sp);
            if (at == null) {
                sp.displayClientMessage(Component.literal("Поруч немає місця для розлому.").withStyle(ChatFormatting.GRAY), true);
                return InteractionResultHolder.fail(stack);
            }
            sp.getCooldowns().addCooldown(this, 100);
            sp.displayClientMessage(Component.literal(isPocket(level) ? "Розлом назад відкривається…" : "Утроба розкриває кишеню…")
                    .withStyle(ChatFormatting.DARK_PURPLE), true);
            return InteractionResultHolder.success(stack);
        }

        @Override
        public void appendHoverText(ItemStack stack, Level level, List<Component> tips, TooltipFlag flag) {
            tips.add(Component.literal("ПКМ — відкрити розлом у кишеньковий вимір (і назад)").withStyle(ChatFormatting.GRAY));
            tips.add(Component.literal("Там тихо: ні ворогів, ні шкоди").withStyle(ChatFormatting.DARK_PURPLE));
        }
    }

    // ================================================================== блок розлому

    public static class RiftBlock extends Block {
        private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 6.0D, 16.0D, 16.0D, 10.0D);

        public RiftBlock() {
            super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).noCollission().strength(-1.0F)
                    .sound(SoundType.SLIME_BLOCK).lightLevel(s -> 10).noLootTable().pushReaction(PushReaction.BLOCK));
        }

        @Override
        public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return SHAPE;
        }

        @Override
        public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
            if (!level.isClientSide && entity instanceof ServerPlayer p && !p.isPassenger() && level instanceof ServerLevel sl) {
                enter(sl, pos, p);
            }
        }

        @Override
        public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource r) {
            if (r.nextInt(2) == 0) {
                level.addParticle(ParticleTypes.REVERSE_PORTAL, pos.getX() + r.nextDouble(), pos.getY() + r.nextDouble(), pos.getZ() + 0.5D,
                        0.0D, 0.03D, 0.0D);
            }
        }

        @Override
        public BlockState updateShape(BlockState state, Direction dir, BlockState n, LevelAccessor level, BlockPos pos, BlockPos np) {
            return state;
        }

        @Override
        public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
            return ItemStack.EMPTY;
        }
    }

    // ================================================================== розломи (виростають і затягуються)

    static final class Rift {
        BlockPos base;
        int phase;      // 0 росте, 1 відкритий, 2 затягується
        int rows;
        long expire;
        final List<BlockState> saved = new ArrayList<>();
    }

    public static class Data extends SavedData {
        final List<Rift> rifts = new ArrayList<>();

        static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(Data::load, Data::new, "flesh_spread_pocket_rifts");
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            ListTag list = tag.getList("r", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                Rift r = new Rift();
                r.base = BlockPos.of(c.getLong("b"));
                r.phase = c.getInt("p");
                r.rows = c.getInt("n");
                r.expire = c.getLong("e");
                ListTag st = c.getList("s", Tag.TAG_COMPOUND);
                for (int k = 0; k < st.size(); k++) r.saved.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), st.getCompound(k)));
                if (r.saved.size() == W * H) d.rifts.add(r);
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (Rift r : rifts) {
                CompoundTag c = new CompoundTag();
                c.putLong("b", r.base.asLong());
                c.putInt("p", r.phase);
                c.putInt("n", r.rows);
                c.putLong("e", r.expire);
                ListTag st = new ListTag();
                for (BlockState s : r.saved) st.add(NbtUtils.writeBlockState(s));
                c.put("s", st);
                list.add(c);
            }
            tag.put("r", list);
            return tag;
        }
    }

    /** Відкриває розлом за 3 блоки перед гравцем (рамка вздовж осі X). */
    static BlockPos open(ServerLevel level, ServerPlayer p) {
        Direction look = p.getDirection();
        for (int dist = 3; dist <= 6; dist++) {
            BlockPos front = p.blockPosition().relative(look, dist);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, front.getX(), front.getZ());
            if (Math.abs(y - p.getBlockY()) > 4) continue;
            BlockPos base = new BlockPos(front.getX() - 1, y, front.getZ());
            boolean ok = true;
            for (int i = 0; i < W && ok; i++) {
                for (int j = 0; j < H; j++) {
                    BlockState st = level.getBlockState(base.offset(i, j, 0));
                    if (st.hasBlockEntity() || st.getDestroySpeed(level, base.offset(i, j, 0)) < 0.0F || st.is(RIFT.get())) {
                        ok = false;
                        break;
                    }
                }
            }
            if (!ok) continue;
            Rift r = new Rift();
            r.base = base;
            r.expire = level.getGameTime() + 40L + 30L * 20L;
            for (int j = 0; j < H; j++) {
                for (int i = 0; i < W; i++) r.saved.add(level.getBlockState(base.offset(i, j, 0)));
            }
            Data data = Data.get(level);
            data.rifts.add(r);
            data.setDirty();
            FleshDim.sound(level, base, "minecraft:block.sculk_shrieker.shriek", 3.0F, 0.6F);
            FleshDim.sound(level, base, "flesh_that_hates:entity.incubator.grow", 3.0F, 0.8F);
            FleshSpread.LOGGER.info("Кишенькова утроба: {} відкрив розлом на {} ({})", p.getName().getString(), base.toShortString(),
                    level.dimension().location());
            return base;
        }
        return null;
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || level.getGameTime() % 5L != 0L) return;
        if (isPocket(level)) pocketTick(level);
        Data data = Data.get(level);
        if (data.rifts.isEmpty()) return;
        long now = level.getGameTime();
        Block frame = FleshDim.FRAME.get();
        Iterator<Rift> it = data.rifts.iterator();
        while (it.hasNext()) {
            Rift r = it.next();
            if (!level.hasChunkAt(r.base)) continue;
            data.setDirty();
            if (r.phase == 0) { // виростає з-під землі ряд за рядом
                int j = r.rows;
                for (int i = 0; i < W; i++) {
                    boolean edge = i == 0 || i == W - 1 || j == 0 || j == H - 1;
                    BlockPos q = r.base.offset(i, j, 0);
                    level.setBlock(q, edge ? frame.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL, q.getX() + 0.5D, q.getY() + 0.5D, q.getZ() + 0.5D, 8, 0.4D, 0.4D, 0.3D, 0.05D);
                }
                FleshDim.sound(level, r.base.above(j), "flesh_that_hates:block.flesh_block.place", 2.0F, 0.7F + j * 0.08F);
                if (++r.rows >= H) {
                    for (int i = 1; i < W - 1; i++) {
                        for (int jj = 1; jj < H - 1; jj++) level.setBlock(r.base.offset(i, jj, 0), RIFT.get().defaultBlockState(), 18);
                    }
                    FleshDim.sound(level, r.base, "minecraft:block.portal.trigger", 1.0F, 0.6F);
                    r.phase = 1;
                }
            } else if (r.phase == 1) {
                if (now >= r.expire) r.phase = 2;
            } else { // затягується згори донизу, повертаючи те, що було
                if (r.rows == H) {
                    for (int i = 1; i < W - 1; i++) {
                        for (int jj = 1; jj < H - 1; jj++) level.setBlock(r.base.offset(i, jj, 0), Blocks.AIR.defaultBlockState(), 18);
                    }
                }
                int j = r.rows - 1;
                for (int i = 0; i < W; i++) {
                    BlockPos q = r.base.offset(i, j, 0);
                    BlockState cur = level.getBlockState(q);
                    if (cur.is(frame) || cur.is(RIFT.get()) || cur.isAir()) level.setBlock(q, r.saved.get(j * W + i), 3);
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL, q.getX() + 0.5D, q.getY() + 0.5D, q.getZ() + 0.5D, 6, 0.4D, 0.4D, 0.3D, 0.05D);
                }
                FleshDim.sound(level, r.base.above(j), "flesh_that_hates:block.flesh_block.break", 2.0F, 0.9F - j * 0.08F);
                if (--r.rows <= 0) it.remove();
            }
        }
    }

    private static Rift riftAt(ServerLevel level, BlockPos pos) {
        for (Rift r : Data.get(level).rifts) {
            if (pos.getZ() == r.base.getZ() && pos.getX() >= r.base.getX() && pos.getX() < r.base.getX() + W
                    && pos.getY() >= r.base.getY() && pos.getY() < r.base.getY() + H) return r;
        }
        return null;
    }

    // ================================================================== перехід

    private static final java.util.Map<java.util.UUID, Long> COOLDOWN = new java.util.HashMap<>();

    static void enter(ServerLevel level, BlockPos pos, ServerPlayer p) {
        long now = level.getServer().getTickCount();
        Long until = COOLDOWN.get(p.getUUID());
        if (until != null && until - now < 400L && now < until) return;
        COOLDOWN.put(p.getUUID(), now + 100L);
        Rift rift = riftAt(level, pos);
        if (rift != null && rift.phase == 1) {
            rift.phase = 2; // щойно хтось стрибнув — розлом затягується
            Data.get(level).setDirty();
        }
        MinecraftServer server = level.getServer();
        CompoundTag root = p.getPersistentData();
        CompoundTag keep = root.getCompound(Player.PERSISTED_NBT_TAG);
        FsNet.sendFx(p, 6);
        if (isPocket(level)) {
            ServerLevel dest = server.overworld();
            BlockPos back = dest.getSharedSpawnPos();
            if (keep.contains("fs_pocket_pos")) {
                back = BlockPos.of(keep.getLong("fs_pocket_pos"));
                ServerLevel saved = server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(keep.getString("fs_pocket_dim"))));
                if (saved != null) dest = saved;
            }
            p.teleportTo(dest, back.getX() + 0.5D, back.getY(), back.getZ() + 0.5D, p.getYRot(), p.getXRot());
            FleshSpread.LOGGER.info("Кишенькова утроба: {} повернувся до {} {}", p.getName().getString(), dest.dimension().location(), back.toShortString());
            return;
        }
        ServerLevel pocket = server.getLevel(KEY);
        if (pocket == null) {
            p.displayClientMessage(Component.literal("Кишеньковий вимір не завантажено (перезапустіть світ із цим модом).").withStyle(ChatFormatting.RED), false);
            return;
        }
        keep.putLong("fs_pocket_pos", p.blockPosition().relative(p.getDirection().getOpposite(), 2).asLong());
        keep.putString("fs_pocket_dim", level.dimension().location().toString());
        root.put(Player.PERSISTED_NBT_TAG, keep);
        double x = (pocket.random.nextDouble() - 0.5D) * 16.0D, z = (pocket.random.nextDouble() - 0.5D) * 16.0D;
        pocket.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        int y = pocket.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z));
        p.teleportTo(pocket, x, y, z, p.getYRot(), 0.0F);
        p.sendSystemMessage(Component.literal("Ви в кишені Утроби. Тут тихо. Щоб вийти — знову розкрийте її.").withStyle(ChatFormatting.DARK_PURPLE));
        FleshSpread.LOGGER.info("Кишенькова утроба: {} увійшов у кишеньковий вимір", p.getName().getString());
    }

    // ================================================================== правила кишенькового виміру

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        COOLDOWN.clear();
        ServerLevel pocket = event.getServer().getLevel(KEY);
        if (pocket != null) {
            pocket.getWorldBorder().setCenter(0.0D, 0.0D);
            pocket.getWorldBorder().setSize(HALF * 2);
        }
    }

    /** Тут ніхто не отримує шкоди. */
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent event) {
        if (isPocket(event.getEntity().level()) && event.getEntity() instanceof Player) event.setCanceled(true);
    }

    /** Ворожих мобів тут немає. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && isPocket(event.getLevel()) && (event.getEntity() instanceof Enemy || FleshDim.isInfected(event.getEntity()))) {
            event.setCanceled(true);
        }
    }

    private static final String[] PASSIVE = {"minecraft:cow", "minecraft:sheep", "minecraft:pig", "minecraft:chicken", "minecraft:rabbit"};

    private static void pocketTick(ServerLevel level) {
        long t = level.getGameTime();
        // межа 200x200 (на випадок, якщо світову межу змінили командою)
        if (t % 20L == 0L) {
            for (ServerPlayer p : level.players()) {
                if (Math.abs(p.getX()) > HALF - 1 || Math.abs(p.getZ()) > HALF - 1) {
                    double x = Math.max(-(HALF - 2), Math.min(HALF - 2, p.getX())), z = Math.max(-(HALF - 2), Math.min(HALF - 2, p.getZ()));
                    p.teleportTo(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(x), (int) Math.floor(z)), z);
                }
                if (p.getY() < level.getMinBuildHeight()) {
                    p.teleportTo(0.5D, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0), 0.5D);
                }
            }
        }
        // мирна живність потроху (до 24 голів)
        if (t % 600L == 0L && !level.players().isEmpty()) {
            int animals = 0;
            for (Entity e : level.getAllEntities()) {
                if (e instanceof net.minecraft.world.entity.animal.Animal) animals++;
            }
            if (animals < 24) {
                ResourceLocation id = new ResourceLocation(PASSIVE[level.random.nextInt(PASSIVE.length)]);
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
                int x = level.random.nextInt(HALF * 2 - 20) - HALF + 10, z = level.random.nextInt(HALF * 2 - 20) - HALF + 10;
                BlockPos at = new BlockPos(x, 0, z);
                if (type != null && level.hasChunkAt(at)) {
                    at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                    for (int i = 0; i < 2; i++) type.spawn(level, at, MobSpawnType.NATURAL);
                }
            }
        }
    }
}
