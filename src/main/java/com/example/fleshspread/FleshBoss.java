package com.example.fleshspread;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;

/**
 * Утроба — фінальний бос виміру плоті: величезний черв із серпом на хвості. Живе в печері-арені під землею, прокидається,
 * коли розбити Серце плоті. Кусає з ривка (перед ривком є мить, щоб утекти), б'є серпом по площі, стає на хвіст і криком
 * кличе інкубатори й заражених, після кожних 5% втраченого здоров'я закопується і виринає в іншому місці. Від рукопашних
 * ударів і гарматних снарядів ухиляється, вогню боїться. У звичайному світі з'являється як «вісник» (mode HERALD): його не
 * можна вбити, він не атакує, лише відкриває три розломи й криком виганяє з них заражених.
 */
public class FleshBoss extends Monster implements GeoEntity {
    public static final float SCALE = 8.0F;
    static final int IDLE = 0, MOVE = 1, BITE_WARN = 2, BITE = 3, TAIL_WARN = 4, TAIL = 5, SUMMON = 6, BURROW = 7, EMERGE = 8, DEATH = 9;
    private static final String[] ANIMS = {"idle", "move", "idle", "attack_bite", "idle", "attack_tail", "summon", "burrow", "emerge", "death"};
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(FleshBoss.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> HERALD = SynchedEntityData.defineId(FleshBoss.class, EntityDataSerializers.BOOLEAN);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final FleshBossPart[] parts;
    // зміщення частин тіла вздовж тулуба (вперед = +) і по висоті, у блоках
    private static final float[][] LAYOUT = {{38.0F, 8.0F, 18.0F, 18.0F}, {20.0F, 9.0F, 16.0F, 16.0F}, {-20.0F, 8.0F, 16.0F, 16.0F},
            {-40.0F, 9.0F, 16.0F, 16.0F}, {-60.0F, 12.0F, 16.0F, 26.0F}};

    private final ServerBossEvent bar = new ServerBossEvent(Component.literal("Утроба"), BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10);
    private int stateTicks;
    private int cooldown = 30;
    private int sinceAttack;
    private int summonCooldown = 300;
    private int alone;
    private float burrowMark = -1.0F;       // здоров'я, після якого треба закопатися
    private Vec3 strike;                    // куди націлено удар
    boolean herald;                         // вісник у звичайному світі
    private int heraldLife;
    BlockPos arena;                         // центр арени (підлога)
    int arenaRadius = 40;

    public FleshBoss(EntityType<? extends FleshBoss> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setPersistenceRequired();
        this.xpReward = 500;
        this.noCulling = true;
        parts = new FleshBossPart[LAYOUT.length];
        for (int i = 0; i < parts.length; i++) parts[i] = new FleshBossPart(this, LAYOUT[i][2], LAYOUT[i][3]);
        this.setId(ENTITY_COUNTER.getAndAdd(parts.length + 1) + 1);
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 600.0D).add(Attributes.ATTACK_DAMAGE, 16.0D)
                .add(Attributes.ARMOR, 6.0D).add(Attributes.KNOCKBACK_RESISTANCE, 1.0D).add(Attributes.FOLLOW_RANGE, 128.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(STATE, IDLE);
        this.entityData.define(HERALD, false);
    }

    int state() {
        return this.entityData.get(STATE);
    }

    private void state(int s) {
        this.entityData.set(STATE, s);
        stateTicks = 0;
    }

    // ------------------------------------------------------------------ частини тіла (хітбокси)

    @Override
    public void setId(int id) {
        super.setId(id);
        if (parts != null) {
            for (int i = 0; i < parts.length; i++) parts[i].setId(id + i + 1);
        }
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return parts;
    }

    private Vec3 forward() {
        float r = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(r), 0.0D, Mth.cos(r));
    }

    private Vec3 along(float dist, float height) {
        Vec3 f = forward();
        return new Vec3(getX() + f.x * dist, getY() + height, getZ() + f.z * dist);
    }

    private void placeParts() {
        for (int i = 0; i < parts.length; i++) {
            Vec3 p = along(LAYOUT[i][0], LAYOUT[i][1]);
            FleshBossPart part = parts[i];
            part.xo = part.getX();
            part.yo = part.getY();
            part.zo = part.getZ();
            part.xOld = part.xo;
            part.yOld = part.yo;
            part.zOld = part.zo;
            part.setPos(p.x, p.y, p.z);
        }
    }

    Vec3 headPos() {
        return along(42.0F, 12.0F);
    }

    // ------------------------------------------------------------------ шкода

    private boolean hidden() {
        int s = state();
        return s == BURROW || s == EMERGE || s == DEATH;
    }

    boolean hurtPart(DamageSource source, float amount) {
        return this.hurt(source, amount);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurt(source, amount);
        if (herald || hidden() || source.is(DamageTypeTags.IS_FALL) || source.getEntity() == this) return false;
        if (source.getEntity() != null && FleshDim.isInfected(source.getEntity())) return false;
        boolean fire = source.is(DamageTypeTags.IS_FIRE);
        if (fire) {
            amount *= 2.0F; // вогню він боїться найбільше
        } else if (state() == IDLE || state() == MOVE) {
            // ухиляється: від рукопашних ударів інколи, від снарядів і вибухів (гармати жителів) — частіше
            boolean ranged = source.is(DamageTypeTags.IS_PROJECTILE) || source.is(DamageTypeTags.IS_EXPLOSION);
            boolean melee = !ranged && source.getDirectEntity() != null && source.getDirectEntity() == source.getEntity();
            if ((ranged && random.nextFloat() < 0.5F) || (melee && random.nextFloat() < 0.3F)) {
                dodge();
                return false;
            }
        }
        return super.hurt(source, amount);
    }

    private void dodge() {
        Vec3 f = forward();
        double side = random.nextBoolean() ? 12.0D : -12.0D;
        moveInArena(getX() + f.z * side, getZ() - f.x * side);
        FleshDim.sound((ServerLevel) level(), blockPosition(), "flesh_that_hates:entity.scuttler.ambient", 4.0F, 0.5F);
    }

    /** У звичайному світі вісника не можна вбити, тож ні рекрути, ні големи, ні жителі на нього не кидаються. */
    @Override
    public boolean canBeSeenAsEnemy() {
        return !herald && super.canBeSeenAsEnemy();
    }

    @Override
    public boolean isInvulnerable() {
        return herald || super.isInvulnerable();
    }

    @Override
    public boolean fireImmune() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double d) {
        return false;
    }

    @Override
    public boolean canChangeDimensions() {
        return false;
    }

    @Override
    public void knockback(double a, double b, double c) {
    }

    // ------------------------------------------------------------------ життя

    @Override
    public void aiStep() {
        // власний рух, без звичайного ШІ (super потрібен для плавного руху на клієнті)
        super.aiStep();
        this.setDeltaMovement(Vec3.ZERO);
        placeParts();
        if (level().isClientSide || !(level() instanceof ServerLevel level)) return;
        stateTicks++;
        if (state() == EMERGE || state() == BURROW) dig(level);
        if (herald) {
            heraldTick(level);
            return;
        }
        if (arena == null) arena = blockPosition();
        bar.setProgress(Mth.clamp(getHealth() / getMaxHealth(), 0.0F, 1.0F));
        if (state() == DEATH) {
            deathTick(level);
            return;
        }
        if (burrowMark < 0.0F) burrowMark = getHealth() - getMaxHealth() * 0.05F;

        ServerPlayer target = target(level);
        if (target == null) {
            if (++alone > 300) leave(level, "гравців поруч немає");
            if (state() == MOVE) state(IDLE);
            return;
        }
        alone = 0;
        if (tickCount % 10 == 0) breakBlocks(level);

        switch (state()) {
            case EMERGE -> {
                if (stateTicks == 10) {
                    quake(level, position(), 16.0D, 10.0F);
                    FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.call", 8.0F, 0.5F);
                }
                if (stateTicks >= 48) attack(level, target, true); // щойно виринув — одразу атакує
            }
            case BURROW -> {
                if (stateTicks >= 48) {
                    relocate(level, target);
                    state(EMERGE);
                }
            }
            case BITE_WARN -> {
                face(target.position(), 10.0F);
                if (stateTicks <= 12) strike = target.position(); // після цього ціль зафіксовано: є мить, щоб відбігти
                if (stateTicks % 4 == 0 && strike != null) mark(level, strike, 5.0D);
                if (stateTicks >= 22) {
                    state(BITE);
                    FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.snatcher.ambient", 6.0F, 0.5F);
                }
            }
            case BITE -> {
                if (stateTicks <= 8 && strike != null) { // ривок: голова влітає в зафіксовану точку
                    Vec3 head = headPos();
                    Vec3 d = new Vec3(strike.x - head.x, 0.0D, strike.z - head.z);
                    double len = d.length();
                    if (len > 0.5D) {
                        double step = Math.min(len, 6.0D);
                        moveInArena(getX() + d.x / len * step, getZ() + d.z / len * step);
                    }
                }
                if (stateTicks == 9) {
                    Vec3 head = headPos();
                    hit(level, new Vec3(head.x, getY() + 2.0D, head.z), 9.0D, 16.0F, true);
                }
                if (stateTicks >= 24) rest();
            }
            case TAIL_WARN -> {
                face(target.position(), 6.0F);
                if (stateTicks == 1) strike = along(26.0F, 0.0F);
                if (stateTicks % 4 == 0 && strike != null) mark(level, strike, 16.0D);
                if (stateTicks >= 26) {
                    state(TAIL);
                    FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.bulwark.ambient", 6.0F, 0.5F);
                }
            }
            case TAIL -> {
                if (stateTicks == 14 && strike != null) { // серп падає згори: удар по площі
                    quake(level, strike, 16.0D, 18.0F);
                }
                if (stateTicks >= 28) rest();
            }
            case SUMMON -> {
                if (stateTicks == 22) scream(level, true);
                if (stateTicks >= 64) rest();
            }
            default -> roam(level, target);
        }
    }

    private void rest() {
        cooldown = 25 + random.nextInt(25);
        sinceAttack = 0;
        state(IDLE);
    }

    private ServerPlayer target(ServerLevel level) {
        ServerPlayer best = null;
        double bd = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) {
            if (!p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            double d = p.distanceToSqr(arena.getX() + 0.5D, p.getY(), arena.getZ() + 0.5D);
            if (d > (arenaRadius + 40.0D) * (arenaRadius + 40.0D) || Math.abs(p.getY() - arena.getY()) > 60.0D) continue;
            double me = p.distanceToSqr(this);
            if (me < bd) {
                bd = me;
                best = p;
            }
        }
        return best;
    }

    private void roam(ServerLevel level, ServerPlayer target) {
        // після кожних 5% втраченого здоров'я закопується й переходить в інше місце
        if (getHealth() <= burrowMark) {
            burrowMark = getHealth() - getMaxHealth() * 0.05F;
            state(BURROW);
            FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.incubator.grow", 6.0F, 0.5F);
            return;
        }
        face(target.position(), 5.0F);
        sinceAttack++;
        if (summonCooldown > 0) summonCooldown--;
        double dist = Math.sqrt(horizontal(headPos(), target.position()));
        if (dist > 22.0D) {
            Vec3 f = forward();
            moveInArena(getX() + f.x * 0.6D, getZ() + f.z * 0.6D);
            if (state() != MOVE) state(MOVE);
        } else if (state() != IDLE) {
            state(IDLE);
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (dist <= 50.0D || sinceAttack > 120) attack(level, target, false);
    }

    /** Вибирає атаку: укус із ривка, серп по площі або крик, що кличе інкубатори й заражених. */
    private void attack(ServerLevel level, ServerPlayer target, boolean fresh) {
        double dist = Math.sqrt(horizontal(headPos(), target.position()));
        int roll = random.nextInt(100);
        if (summonCooldown <= 0 && roll < (fresh ? 30 : 18)) {
            summonCooldown = 500;
            state(SUMMON);
        } else if (dist < 30.0D && roll < 60) {
            state(TAIL_WARN);
        } else {
            strike = target.position();
            state(BITE_WARN);
            FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.pincher.ambient", 5.0F, 0.5F);
        }
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private void face(Vec3 at, float maxTurn) {
        float want = (float) (Mth.atan2(at.z - getZ(), at.x - getX()) * Mth.RAD_TO_DEG) - 90.0F;
        float yaw = getYRot() + Mth.clamp(Mth.wrapDegrees(want - getYRot()), -maxTurn, maxTurn);
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        yRotO = yaw;
        yBodyRotO = yaw;
        yHeadRotO = yaw;
    }

    /** Рух без виходу за межі арени. */
    private void moveInArena(double x, double z) {
        if (arena != null && !herald) {
            double dx = x - (arena.getX() + 0.5D), dz = z - (arena.getZ() + 0.5D);
            double max = Math.max(4.0D, arenaRadius - 30.0D);
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > max) {
                x = arena.getX() + 0.5D + dx / len * max;
                z = arena.getZ() + 0.5D + dz / len * max;
            }
        }
        setPos(x, arena != null && !herald ? arena.getY() : getY(), z);
    }

    private void relocate(ServerLevel level, ServerPlayer target) {
        for (int i = 0; i < 12; i++) {
            double ang = random.nextDouble() * Math.PI * 2.0D;
            double dist = 30.0D + random.nextDouble() * 18.0D;
            double x = target.getX() + Math.cos(ang) * dist, z = target.getZ() + Math.sin(ang) * dist;
            double dx = x - arena.getX(), dz = z - arena.getZ();
            if (dx * dx + dz * dz < (arenaRadius - 30.0D) * (arenaRadius - 30.0D) && horizontal(position(), new Vec3(x, 0, z)) > 25.0D * 25.0D) {
                setPos(x, arena.getY(), z);
                break;
            }
        }
        face(target.position(), 360.0F);
    }

    private void mark(ServerLevel level, Vec3 at, double radius) {
        for (int i = 0; i < 28; i++) {
            double a = i / 28.0D * Math.PI * 2.0D;
            level.sendParticles(ParticleTypes.FLAME, at.x + Math.cos(a) * radius, arena != null ? arena.getY() + 0.3D : at.y + 0.3D,
                    at.z + Math.sin(a) * radius, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** Шкода всім гравцям у радіусі; укус ще й заражає. */
    private void hit(ServerLevel level, Vec3 at, double radius, float damage, boolean bite) {
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, new AABB(at, at).inflate(radius, 8.0D, radius))) {
            if (horizontal(p.position(), at) > radius * radius) continue;
            if (p.hurt(damageSources().mobAttack(this), damage)) {
                p.setDeltaMovement(p.getDeltaMovement().add(0.0D, bite ? 0.5D : 0.9D, 0.0D));
                p.hurtMarked = true;
                if (bite) p.addEffect(new MobEffectInstance(MobEffects.WITHER, 100, 1));
            }
        }
    }

    private void quake(ServerLevel level, Vec3 at, double radius, float damage) {
        hit(level, at, radius, damage, false);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 1.0D, at.z, 14, radius / 2.0D, 0.5D, radius / 2.0D, 0.0D);
        level.sendParticles(ParticleTypes.CRIMSON_SPORE, at.x, at.y + 1.0D, at.z, 120, radius / 2.0D, 1.0D, radius / 2.0D, 0.05D);
        FleshDim.sound(level, BlockPos.containing(at), "minecraft:entity.generic.explode", 5.0F, 0.5F);
    }

    /** Крик: екран у гравців червоніє; у бою з'являються інкубатори й заражені, у вісника заражені виходять із розломів. */
    private void scream(ServerLevel level, boolean fight) {
        FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.call", 10.0F, 0.6F);
        FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.herpad.summon", 8.0F, 0.6F);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(this) < 120.0D * 120.0D) FsNet.sendFx(p, 4);
        }
        if (!fight) return;
        EntityType<?> big = Nests.big();
        int nests = 0;
        for (int i = 0; i < 2 && big != null; i++) {
            double ang = random.nextDouble() * Math.PI * 2.0D;
            double dist = 14.0D + random.nextDouble() * Math.max(1.0D, arenaRadius - 30.0D);
            BlockPos at = BlockPos.containing(arena.getX() + Math.cos(ang) * dist, arena.getY(), arena.getZ() + Math.sin(ang) * dist);
            Entity e = big.spawn(level, at, MobSpawnType.MOB_SUMMONED);
            if (e instanceof Mob m) m.setPersistenceRequired();
            if (e != null) nests++;
        }
        ServerPlayer t = target(level);
        int mobs = FleshDim.summon(level, t != null ? t.blockPosition() : arena, 6, 8, 16, t);
        FleshSpread.LOGGER.info("Утроба кричить: інкубаторів {}, заражених {}, здоров'я {}/{}", nests, mobs, (int) getHealth(), (int) getMaxHealth());
    }

    private void breakBlocks(ServerLevel level) {
        int budget = 80;
        for (int i = -1; i < parts.length && budget > 0; i++) {
            AABB box = (i < 0 ? getBoundingBox() : parts[i].getBoundingBox()).deflate(0.5D);
            for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX, Math.max(box.minY, arena.getY()), box.minZ),
                    BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                BlockState st = level.getBlockState(p);
                if (st.isAir() || st.getDestroySpeed(level, p) < 0.0F || st.is(FleshDim.PORTAL.get()) || st.is(FleshDim.HEART.get())
                        || !st.getFluidState().isEmpty() || st.is(net.minecraft.world.level.block.Blocks.LADDER)
                        || st.is(net.minecraft.world.level.block.Blocks.SHROOMLIGHT) || st.is(FleshDim.FRAME.get())
                        || Math.abs(p.getX() - (arena.getX() + FleshDim.LAIR_OFFSET)) <= 3 && Math.abs(p.getZ() - arena.getZ()) <= 3) continue;
                level.destroyBlock(p, false);
                if (--budget <= 0) break;
            }
        }
    }

    /**
     * Виривання з-під землі / закопування: уздовж усього тіла летять грудки землі, пил і кров, земля трясеться,
     * а під тілом лишаються прогризені ями.
     */
    private void dig(ServerLevel level) {
        Vec3 f = forward();
        BlockPos under = BlockPos.containing(getX(), getY() - 1.0D, getZ());
        BlockState ground = level.getBlockState(under);
        if (ground.isAir()) ground = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
        net.minecraft.core.particles.BlockParticleOption dirt = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, ground);
        for (int i = -60; i <= 44; i += 6) {
            double x = getX() + f.x * i, z = getZ() + f.z * i;
            level.sendParticles(dirt, x, getY() + 0.5D, z, 14, 3.5D, 1.2D, 3.5D, 0.4D);
            if (stateTicks % 3 == 0) {
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, getY() + 0.5D, z, 2, 3.0D, 0.3D, 3.0D, 0.02D);
                level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, x, getY() + 1.0D, z, 2, 3.0D, 1.0D, 3.0D, 0.1D);
            }
        }
        if (stateTicks % 8 == 0) {
            FleshDim.sound(level, blockPosition(), "minecraft:block.rooted_dirt.break", 8.0F, 0.5F);
            FleshDim.sound(level, blockPosition(), "minecraft:entity.ravager.step", 8.0F, 0.5F);
        }
        if (stateTicks == 2 || stateTicks == 30) gnaw(level);
    }

    /** Прогризені ями вздовж тіла: вибиває землю плямами (без блоків із вмістом і в межах розумного). */
    private void gnaw(ServerLevel level) {
        Vec3 f = forward();
        int broken = 0;
        for (int i = -56; i <= 44 && broken < 500; i += 3) {
            double cx = getX() + f.x * i, cz = getZ() + f.z * i;
            int r = 2 + random.nextInt(3);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r || random.nextInt(4) == 0) continue;
                    int x = (int) Math.floor(cx) + dx, z = (int) Math.floor(cz) + dz;
                    int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    int depth = 1 + random.nextInt(3);
                    for (int y = top; y > top - depth; y--) {
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState st = level.getBlockState(p);
                        if (st.isAir() || st.hasBlockEntity() || st.getDestroySpeed(level, p) < 0.0F || !st.getFluidState().isEmpty()
                                || st.is(FleshDim.PORTAL.get()) || st.is(FleshDim.FRAME.get())) continue;
                        level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                        broken++;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ кінець бою

    /** Гравці втекли або загинули: бос іде під землю, Серце плоті повертається на п'єдестал. */
    private void leave(ServerLevel level, String why) {
        FleshSpread.LOGGER.info("Утроба зникає ({}), здоров'я {}/{}; Серце плоті повернуто", why, (int) getHealth(), (int) getMaxHealth());
        FleshDim.restoreHeart(level);
        bar.removeAllPlayers();
        discard();
    }

    @Override
    public void die(DamageSource source) {
        if (level().isClientSide || state() == DEATH) return;
        if (herald) {
            setHealth(getMaxHealth());
            return;
        }
        setHealth(1.0F);
        state(DEATH);
        FleshDim.sound((ServerLevel) level(), blockPosition(), "flesh_that_hates:entity.herpad.death", 10.0F, 0.4F);
        FleshSpread.LOGGER.info("Утробу переможено на {}", blockPosition().toShortString());
    }

    private void deathTick(ServerLevel level) {
        if (stateTicks % 5 == 0) {
            level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 8.0D, getZ(), 8, 24.0D, 6.0D, 24.0D, 0.0D);
        }
        if (stateTicks >= 60) {
            bar.removeAllPlayers();
            FleshDim.victory(level, this);
            discard();
        }
    }

    @Override
    protected void tickDeath() {
    }

    // ------------------------------------------------------------------ вісник у звичайному світі

    /** Початок бою: виринає з-під землі. */
    void wake() {
        state(EMERGE);
    }

    private long heraldEnd;
    private int nests;

    void makeHerald(int lifeTicks) {
        herald = true;
        this.entityData.set(HERALD, true);
        heraldLife = lifeTicks;
        heraldEnd = level().getGameTime() + 48 + lifeTicks;
        setInvulnerable(true);
        state(EMERGE);
    }

    /**
     * Вісник у звичайному світі: виринає, одразу стає на хвіст і так стоїть, час від часу кричить (заражені виходять із
     * розломів), а довкола нього розповзається плоть і з'являється до двох інкубаторів. Коли час минає або розломи
     * затягнулися, закопується, а розломи зникають.
     */
    private void heraldTick(ServerLevel level) {
        int s = state();
        long now = level.getGameTime();
        if (s == EMERGE && stateTicks >= 48) {
            int opened = 0;
            for (int i = 0; i < 3; i++) {
                if (FleshRifts.open(level, blockPosition(), 28, 50, "вісник", heraldEnd) != null) opened++;
            }
            FleshSpread.LOGGER.info("Вісник (Утроба) виринув на {}, розломів відкрито: {}", blockPosition().toShortString(), opened);
            state(SUMMON); // одразу стає на хвіст
            scream(level, false);
            return;
        }
        if (s == BURROW) {
            if (stateTicks >= 48) {
                FleshSpread.LOGGER.info("Вісник (Утроба) закопався на {}", blockPosition().toShortString());
                discard();
            }
            return;
        }
        if (s != SUMMON) {
            state(SUMMON);
            return;
        }
        Player near = level.getNearestPlayer(this, 160.0D);
        if (near != null) face(near.position(), 1.5F);
        // плоть розповзається, інкубаторів не більше двох
        if (stateTicks % 10 == 0) Nests.creep(level, blockPosition(), 26, 14);
        if (nests < 2 && stateTicks % 300 == 150) {
            EntityType<?> big = Nests.big();
            BlockPos at = big == null ? null : Nests.findSurface(level, (int) (getX() + (random.nextDouble() - 0.5D) * 40.0D),
                    (int) (getZ() + (random.nextDouble() - 0.5D) * 40.0D));
            Entity e = at == null ? null : big.spawn(level, at, MobSpawnType.MOB_SUMMONED);
            if (e != null) {
                nests++;
                FleshSpread.LOGGER.info("Вісник виростив інкубатор {} на {}", nests, at.toShortString());
            }
        }
        if (stateTicks % 240 == 120) {
            scream(level, false);
            FleshRifts.pour(level, blockPosition(), 80.0D);
        }
        // час минув або всі розломи вже затягнулися — закопується
        boolean riftsGone = stateTicks > 100 && FleshRifts.openNear(level, blockPosition(), 80.0D) == 0;
        if (now >= heraldEnd || riftsGone) {
            FleshRifts.closeNear(level, blockPosition(), 80.0D);
            FleshSpread.LOGGER.info("Вісник закопується на {} ({})", blockPosition().toShortString(), riftsGone ? "розломи затягнулися" : "час минув");
            state(BURROW);
            FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.incubator.grow", 8.0F, 0.5F);
        }
    }

    // ------------------------------------------------------------------ різне

    @Override
    public void startSeenByPlayer(ServerPlayer p) {
        super.startSeenByPlayer(p);
        if (!herald) bar.addPlayer(p);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer p) {
        super.stopSeenByPlayer(p);
        bar.removePlayer(p);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Herald", herald);
        tag.putInt("HeraldLife", heraldLife);
        tag.putLong("HeraldEnd", heraldEnd);
        tag.putInt("Nests", nests);
        tag.putInt("ArenaRadius", arenaRadius);
        if (arena != null) tag.putLong("Arena", arena.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        herald = tag.getBoolean("Herald");
        heraldLife = tag.getInt("HeraldLife");
        heraldEnd = tag.getLong("HeraldEnd");
        nests = tag.getInt("Nests");
        if (herald) {
            setInvulnerable(true);
            this.entityData.set(HERALD, true);
        }
        if (tag.contains("ArenaRadius")) arenaRadius = tag.getInt("ArenaRadius");
        if (tag.contains("Arena")) arena = BlockPos.of(tag.getLong("Arena"));
    }

    // ------------------------------------------------------------------ анімації (GeckoLib)

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 4, st -> {
            int s = Mth.clamp(state(), 0, ANIMS.length - 1);
            String name = "animation.flesh_heart_boss." + ANIMS[s];
            RawAnimation anim = s == IDLE || s == MOVE || s == BITE_WARN || s == TAIL_WARN ? RawAnimation.begin().thenLoop(name)
                    : s == BURROW || s == DEATH || (s == SUMMON && this.entityData.get(HERALD)) ? RawAnimation.begin().thenPlayAndHold(name)
                    : RawAnimation.begin().thenPlay(name);
            return st.setAndContinue(anim);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    static List<FleshBoss> near(ServerLevel level, BlockPos pos, double radius) {
        return level.getEntitiesOfClass(FleshBoss.class, new AABB(pos).inflate(radius), Entity::isAlive);
    }
}
