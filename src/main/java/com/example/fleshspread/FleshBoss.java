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
    /** Масштаб моделі: у бою — 4 (близько 50 блоків), вісник у звичайному світі вдвічі більший (близько 100). */
    public static final float SCALE = 4.0F;
    static final int IDLE = 0, MOVE = 1, BITE_WARN = 2, BITE = 3, TAIL_WARN = 4, TAIL = 5, SUMMON = 6, BURROW = 7, EMERGE = 8, DEATH = 9,
            REAR = 10, SCREAM = 11, TUNNEL = 12, CHARGE_WARN = 13, CHARGE = 14;
    private static final String[] ANIMS = {"idle", "move", "idle", "attack_bite", "idle", "attack_tail", "summon", "burrow", "emerge", "death",
            "rear_hold", "rear_scream", "burrow", "rear_hold", "move"};
    private static final EntityDataAccessor<Integer> STATE = SynchedEntityData.defineId(FleshBoss.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> HERALD = SynchedEntityData.defineId(FleshBoss.class, EntityDataSerializers.BOOLEAN);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final FleshBossPart[] parts;
    // зміщення частин тіла вздовж тулуба (вперед = +) і по висоті, у блоках
    private static final float[][] LAYOUT = {{19.0F, 4.0F, 9.0F, 9.0F}, {10.0F, 4.5F, 8.0F, 8.0F}, {-10.0F, 4.0F, 8.0F, 8.0F},
            {-20.0F, 4.5F, 8.0F, 8.0F}, {-30.0F, 6.0F, 8.0F, 13.0F}};

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
            Vec3 p = along(LAYOUT[i][0] * k(), LAYOUT[i][1] * k());
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

    /** Множник розміру: вісник удвічі більший за бійця. */
    float k() {
        return isHerald() ? 2.0F : 1.0F;
    }

    boolean isHerald() {
        return this.entityData.get(HERALD);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (HERALD.equals(key) && parts != null) {
            for (int i = 0; i < parts.length; i++) parts[i].resize(LAYOUT[i][2] * k(), LAYOUT[i][3] * k());
        }
    }

    Vec3 headPos() {
        return along(21.0F * k(), 6.0F * k());
    }

    // ------------------------------------------------------------------ шкода

    private boolean hidden() {
        int s = state();
        return s == BURROW || s == TUNNEL || s == EMERGE || s == DEATH;
    }

    boolean hurtPart(DamageSource source, float amount) {
        return this.hurt(source, amount);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return super.hurt(source, amount);
        if (herald || hidden() || source.is(DamageTypeTags.IS_FALL) || source.getEntity() == this
                || source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL) || source.is(net.minecraft.world.damagesource.DamageTypes.CRAMMING)
                || source.is(net.minecraft.world.damagesource.DamageTypes.DROWN)) return false;
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
        double side = random.nextBoolean() ? 7.0D : -7.0D;
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
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }

    /** Діагностика: чому бос зник. */
    @Override
    public void remove(RemovalReason reason) {
        if (!level().isClientSide && reason != RemovalReason.UNLOADED_TO_CHUNK && reason != RemovalReason.UNLOADED_WITH_PLAYER) {
            FleshSpread.LOGGER.info("Утроба{} прибрана: {}, стан {}, тік стану {}, на {}", herald ? " (вісник)" : "", reason, state(),
                    stateTicks, blockPosition().toShortString());
        } else if (!level().isClientSide) {
            FleshSpread.LOGGER.info("Утроба{} вивантажена разом із чанком ({}) на {}", herald ? " (вісник)" : "", reason,
                    blockPosition().toShortString());
        }
        bar.removeAllPlayers();
        super.remove(reason);
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
        if (tickCount % 20 == 0) {
            // смуга боса — у всіх, хто біля арени, навіть якщо модель ще не прогрузилась
            for (ServerPlayer p : level.players()) {
                boolean near = p.distanceToSqr(arena.getX() + 0.5D, p.getY(), arena.getZ() + 0.5D) < (arenaRadius + 60.0D) * (arenaRadius + 60.0D);
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
        }
        if (state() == DEATH) {
            deathTick(level);
            return;
        }
        if (burrowMark < 0.0F) burrowMark = getHealth() - getMaxHealth() * 0.05F;

        ServerPlayer target = target(level);
        if (target == null) {
            // іде, лише коли біля арени взагалі немає гравців (у креативі він вас не атакує, але й не тікає)
            boolean anyone = false;
            for (ServerPlayer p : level.players()) {
                if (p.isAlive() && !p.isSpectator() && p.distanceToSqr(arena.getX() + 0.5D, p.getY(), arena.getZ() + 0.5D)
                        < (arenaRadius + 60.0D) * (arenaRadius + 60.0D)) anyone = true;
            }
            if (anyone) alone = 0;
            if (++alone > 600) leave(level, "гравців біля арени немає 30 с");
            if (state() == MOVE) state(IDLE);
            return;
        }
        alone = 0;
        if (tickCount % 10 == 0 && state() != TUNNEL) breakBlocks(level);

        switch (state()) {
            case EMERGE -> {
                if (stateTicks == 10) {
                    quake(level, headPos(), 9.0D, 10.0F);
                    FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.call", 8.0F, 0.5F);
                }
                if (stateTicks >= 48) attack(level, target, true); // щойно виринув — одразу атакує (без виклику)
            }
            case BURROW -> {
                if (stateTicks >= 48) {
                    tunnelTime = 70 + random.nextInt(50);
                    state(TUNNEL);
                }
            }
            case TUNNEL -> tunnel(level, target);
            case CHARGE_WARN -> {
                // стає дибки й гарчить, а по землі до стіни біжить кривава смуга: туди він зараз влетить
                face(target.position(), 6.0F);
                if (stateTicks <= 18) strike = wallPoint(target.position());
                if (strike != null && stateTicks % 3 == 0) markLine(level, headPos(), strike, 4.0D);
                if (stateTicks % 10 == 1) FleshDim.sound(level, BlockPos.containing(headPos()), "flesh_that_hates:entity.pincher.ambient", 6.0F, 0.4F);
                if (stateTicks % 4 == 0) fx(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, headPos().x, getY() + 2.0D, headPos().z, 6, 2.0D, 1.0D, 2.0D, 0.05D);
                if (stateTicks >= 36) {
                    chargeHit.clear();
                    state(CHARGE);
                    FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.snatcher.ambient", 8.0F, 0.5F);
                }
            }
            case CHARGE -> charge(level);
            case BITE_WARN -> {
                face(target.position(), 10.0F);
                if (stateTicks <= 12) strike = target.position(); // після цього ціль зафіксовано: є мить, щоб відбігти
                if (stateTicks % 2 == 0 && strike != null) mark(level, strike, 4.0D);
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
                        double step = Math.min(len, 3.2D);
                        moveInArena(getX() + d.x / len * step, getZ() + d.z / len * step);
                    }
                }
                if (stateTicks == 9) {
                    Vec3 head = headPos();
                    hit(level, new Vec3(head.x, getY() + 2.0D, head.z), 5.5D, 16.0F, true);
                }
                if (stateTicks >= 24) rest();
            }
            case TAIL_WARN -> {
                face(target.position(), 6.0F);
                // зона удару — там, де гравець; перші пів секунди вона ще йде за ним, далі фіксується
                if (stateTicks <= 10 || strike == null) strike = target.position();
                if (stateTicks % 2 == 0 && strike != null) mark(level, strike, 11.0D);
                if (stateTicks >= 30) {
                    state(TAIL);
                    FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.bulwark.ambient", 6.0F, 0.5F);
                }
            }
            case TAIL -> {
                if (stateTicks == 14 && strike != null) { // серп падає згори: удар по площі
                    quake(level, strike, 11.0D, 18.0F);
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
        if (dist > 12.0D) {
            Vec3 f = forward();
            moveInArena(getX() + f.x * 0.42D, getZ() + f.z * 0.42D);
            if (state() != MOVE) state(MOVE);
        } else if (state() != IDLE) {
            state(IDLE);
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (dist <= 30.0D || sinceAttack > 120) attack(level, target, false);
    }

    /**
     * Вибирає атаку: зблизька — укус; здалеку — ривок через арену до стіни, удар серпом по площі там, де гравець,
     * або (не щойно після виринання) крик, що кличе інкубатори й заражених.
     */
    private void attack(ServerLevel level, ServerPlayer target, boolean fresh) {
        double dist = Math.sqrt(horizontal(headPos(), target.position()));
        int roll = random.nextInt(100);
        if (!fresh && summonCooldown <= 0 && roll < 18) {
            summonCooldown = 500;
            state(SUMMON);
        } else if (dist < 9.0D) {
            strike = target.position();
            state(BITE_WARN);
            FleshDim.sound(level, blockPosition(), "flesh_that_hates:entity.pincher.ambient", 5.0F, 0.5F);
        } else if (roll < 55) {
            strike = null;
            state(TAIL_WARN);
        } else {
            strike = null;
            state(CHARGE_WARN);
        }
    }

    // ------------------------------------------------------------------ ривок і підземний хід

    private final java.util.Set<java.util.UUID> chargeHit = new java.util.HashSet<>();
    private int tunnelTime;

    /** Точка біля стіни арени в бік гравця (куди долетить ривок). */
    private Vec3 wallPoint(Vec3 toward) {
        Vec3 head = headPos();
        Vec3 d = new Vec3(toward.x - getX(), 0.0D, toward.z - getZ());
        if (d.lengthSqr() < 0.01D) d = forward();
        d = d.normalize();
        double max = Math.max(4.0D, arenaRadius - 26.0D);
        double cx = getX() - (arena.getX() + 0.5D), cz = getZ() - (arena.getZ() + 0.5D);
        // перетин променя з колом радіуса max (рух центру тіла обмежено ним)
        double b = cx * d.x + cz * d.z, c = cx * cx + cz * cz - max * max;
        double t = -b + Math.sqrt(Math.max(0.0D, b * b - c));
        t = Math.max(t, 6.0D);
        return new Vec3(head.x + d.x * t, getY(), head.z + d.z * t);
    }

    /** Ривок: летить по прямій до стіни, кожного на шляху голови б'є й відкидає, у стіну врізається з ударом. */
    private void charge(ServerLevel level) {
        if (stateTicks == 1 && strike != null) face(strike, 360.0F);
        Vec3 before = position();
        Vec3 f = forward();
        moveInArena(getX() + f.x * 1.7D, getZ() + f.z * 1.7D);
        Vec3 head = headPos();
        fx(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, head.x, getY() + 0.5D, head.z, 8, 3.0D, 0.5D, 3.0D, 0.03D);
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, new AABB(head, head).inflate(6.0D, 8.0D, 6.0D))) {
            if (horizontal(p.position(), head) > 36.0D || !chargeHit.add(p.getUUID())) continue;
            if (p.hurt(damageSources().mobAttack(this), 18.0F)) {
                p.setDeltaMovement(f.x * 2.2D, 0.8D, f.z * 2.2D);
                p.hurtMarked = true;
                p.addEffect(new MobEffectInstance(MobEffects.WITHER, 100, 1));
            }
        }
        boolean stuck = position().distanceToSqr(before) < 0.25D;
        boolean arrived = strike != null && horizontal(head, strike) < 4.0D;
        if (stuck || arrived || stateTicks > 60) {
            quake(level, head, 8.0D, 12.0F);
            FleshDim.sound(level, BlockPos.containing(head), "minecraft:entity.ravager.stunned", 8.0F, 0.5F);
            cooldown = 40 + random.nextInt(20); // оглушений після удару об стіну — час бити його
            sinceAttack = 0;
            state(IDLE);
        }
    }

    /** Під землею: повзе до гравця, над ним дрижить і тріскається земля; за відведений час виринає там, куди доповз. */
    private void tunnel(ServerLevel level, ServerPlayer target) {
        face(target.position(), 8.0F);
        double d = Math.sqrt(horizontal(position(), target.position()));
        if (d > 10.0D) {
            Vec3 f = forward();
            moveInArena(getX() + f.x * 0.55D, getZ() + f.z * 0.55D);
        }
        BlockState ground = level.getBlockState(BlockPos.containing(getX(), getY() - 1.0D, getZ()));
        if (ground.isAir()) ground = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
        net.minecraft.core.particles.BlockParticleOption dirt = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, ground);
        fx(level, dirt, getX(), getY() + 0.2D, getZ(), 30, 3.5D, 0.3D, 3.5D, 0.25D);
        if (stateTicks % 3 == 0) fx(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, getX(), getY() + 0.3D, getZ(), 3, 2.5D, 0.2D, 2.5D, 0.02D);
        if (stateTicks % 12 == 0) FleshDim.sound(level, blockPosition(), "minecraft:block.rooted_dirt.break", 6.0F, 0.4F);
        if (stateTicks % 20 == 0) FleshDim.sound(level, blockPosition(), "minecraft:entity.warden.dig", 6.0F, 0.6F);
        if (stateTicks >= tunnelTime || (d <= 10.0D && stateTicks > 30)) {
            FleshSpread.LOGGER.info("Утроба виринає після підземного ходу на {} ({} тіків під землею)", blockPosition().toShortString(), stateTicks);
            state(EMERGE);
        }
    }

    /** Частинки, видимі здалеку (звичайні sendParticles видно лише в радіусі 32 блоків). */
    void fx(ServerLevel level, net.minecraft.core.particles.ParticleOptions type, double x, double y, double z, int count,
            double dx, double dy, double dz, double speed) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(x, y, z) < 160.0D * 160.0D) level.sendParticles(p, type, true, x, y, z, count, dx, dy, dz, speed);
        }
    }

    /** Смуга на землі від голови до точки удару — шлях ривка. */
    private void markLine(ServerLevel level, Vec3 from, Vec3 to, double width) {
        net.minecraft.core.particles.DustParticleOptions red = new net.minecraft.core.particles.DustParticleOptions(
                new org.joml.Vector3f(0.9F, 0.05F, 0.05F), 2.0F);
        double len = Math.sqrt(horizontal(from, to));
        double y = arena != null ? arena.getY() + 0.2D : getY() + 0.2D;
        for (double t = 0.0D; t <= len; t += 1.5D) {
            double x = from.x + (to.x - from.x) * t / len, z = from.z + (to.z - from.z) * t / len;
            fx(level, red, x, y, z, 3, width / 3.0D, 0.0D, width / 3.0D, 0.0D);
        }
        fx(level, ParticleTypes.FLAME, to.x, y, to.z, 10, 2.0D, 0.1D, 2.0D, 0.01D);
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
            double max = Math.max(4.0D, arenaRadius - 26.0D); // хвіст тягнеться ~30 блоків назад: не вгризатися в купол
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
            double dist = 18.0D + random.nextDouble() * 12.0D;
            double x = target.getX() + Math.cos(ang) * dist, z = target.getZ() + Math.sin(ang) * dist;
            double dx = x - arena.getX(), dz = z - arena.getZ();
            if (dx * dx + dz * dz < (arenaRadius - 26.0D) * (arenaRadius - 26.0D) && horizontal(position(), new Vec3(x, 0, z)) > 15.0D * 15.0D) {
                setPos(x, arena.getY(), z);
                break;
            }
        }
        face(target.position(), 360.0F);
    }

    /** Зона майбутнього удару: вогняне коло по краю і кривава пляма всередині, видно з усієї арени. */
    private void mark(ServerLevel level, Vec3 at, double radius) {
        double y = arena != null ? arena.getY() + 0.25D : at.y + 0.25D;
        int n = (int) (radius * 6);
        for (int i = 0; i < n; i++) {
            double a = i / (double) n * Math.PI * 2.0D;
            fx(level, ParticleTypes.FLAME, at.x + Math.cos(a) * radius, y, at.z + Math.sin(a) * radius, 1, 0.0D, 0.05D, 0.0D, 0.0D);
        }
        net.minecraft.core.particles.DustParticleOptions red = new net.minecraft.core.particles.DustParticleOptions(
                new org.joml.Vector3f(0.8F, 0.02F, 0.02F), 2.5F);
        fx(level, red, at.x, y, at.z, (int) (radius * radius * 0.8D), radius * 0.55D, 0.0D, radius * 0.55D, 0.0D);
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
        fx(level, ParticleTypes.EXPLOSION, at.x, at.y + 1.0D, at.z, 14, radius / 2.0D, 0.5D, radius / 2.0D, 0.0D);
        fx(level, ParticleTypes.CRIMSON_SPORE, at.x, at.y + 1.0D, at.z, 120, radius / 2.0D, 1.0D, radius / 2.0D, 0.05D);
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
            double dist = 10.0D + random.nextDouble() * Math.max(1.0D, arenaRadius - 22.0D);
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
                        || st.is(FleshDim.HARDENED.get()) || st.is(FleshDim.HARDENED_STAIRS.get())) continue; // купол і тунель не ламає
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
        Vec3 head = headPos();
        float k = k();
        BlockPos under = BlockPos.containing(head.x, getY() - 1.0D, head.z);
        BlockState ground = level.getBlockState(under);
        if (ground.isAir()) ground = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
        net.minecraft.core.particles.BlockParticleOption dirt = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, ground);
        double mouth = 4.5D * k;
        // головне — там, де паща проходить крізь землю: фонтан ґрунту, пилу й крові
        fx(level, dirt, head.x, getY() + 0.5D, head.z, (int) (60 * k), mouth, 1.5D * k, mouth, 0.6D);
        fx(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, head.x, getY() + 0.5D, head.z, (int) (6 * k), mouth, 0.5D, mouth, 0.03D);
        fx(level, ParticleTypes.DAMAGE_INDICATOR, head.x, getY() + 1.5D, head.z, (int) (6 * k), mouth, 1.0D, mouth, 0.15D);
        // уздовж тіла — менше
        if (stateTicks % 2 == 0) {
            for (float i = -30.0F * k; i <= 10.0F * k; i += 5.0F * k) {
                fx(level, dirt, getX() + f.x * i, getY() + 0.3D, getZ() + f.z * i, 6, 2.5D * k, 0.6D, 2.5D * k, 0.3D);
            }
        }
        if (stateTicks % 8 == 0) {
            FleshDim.sound(level, BlockPos.containing(head), "minecraft:block.rooted_dirt.break", 8.0F, 0.5F);
            FleshDim.sound(level, BlockPos.containing(head), "minecraft:entity.ravager.step", 8.0F, 0.5F);
        }
        // яма в землі там, де проходить паща: і коли виринає, і коли закопується
        if (stateTicks == 4 || stateTicks == 16 || stateTicks == 30 || stateTicks == 44) gnaw(level, head, mouth);
    }

    /** Прогризена яма завширшки з пащу там, де голова пробиває землю (без блоків із вмістом, рідин і порталів). */
    private void gnaw(ServerLevel level, Vec3 head, double mouth) {
        int broken = 0;
        int r = (int) Math.ceil(mouth);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d2 = dx * dx + dz * dz;
                if (d2 > mouth * mouth) continue;
                int x = (int) Math.floor(head.x) + dx, z = (int) Math.floor(head.z) + dz;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                // посередині глибше, по краях мілко, краї рвані
                int depth = (int) Math.round((1.0D - Math.sqrt(d2) / mouth) * 4.0D * k()) + random.nextInt(2);
                if (Math.sqrt(d2) > mouth - 1.0D && random.nextBoolean()) continue;
                for (int y = top; y > top - depth; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState st = level.getBlockState(p);
                    if (st.isAir() || st.hasBlockEntity() || st.getDestroySpeed(level, p) < 0.0F || !st.getFluidState().isEmpty()
                            || st.is(FleshDim.PORTAL.get()) || st.is(FleshDim.FRAME.get()) || st.is(FleshDim.HEART.get())) continue;
                    level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    broken++;
                }
            }
        }
        if (broken > 0) FleshSpread.LOGGER.info("Утроба прогризла яму: {} блоків біля {}", broken, BlockPos.containing(head).toShortString());
    }

    // ------------------------------------------------------------------ кінець бою

    /** Гравці втекли або загинули: бос іде під землю, Серце плоті повертається на п'єдестал. */
    void leave(ServerLevel level, String why) {
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
            level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 4.0D, getZ(), 6, 12.0D, 3.0D, 12.0D, 0.0D);
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
    private int riftsOpened;
    private int screams;

    /** Метеорит: летить із неба по похилій і вибухає в точці. */
    private static final class Meteor {
        Vec3 from, to;
        int t, total;
    }

    private final java.util.List<Meteor> falling = new java.util.ArrayList<>();

    /**
     * Метеорити падають довкола вісника — переважно туди, де гравці, — але ніколи не на нього самого (тіло ~100 блоків)
     * і не ближче ніж 10 блоків до його розломів.
     */
    private void callMeteors(ServerLevel level, int count) {
        Vec3 f = forward();
        int made = 0;
        for (int i = 0; i < count * 6 && made < count; i++) {
            Vec3 to;
            Player near = level.getNearestPlayer(this, 140.0D);
            if (near != null && random.nextInt(3) != 0) {
                to = new Vec3(near.getX() + (random.nextDouble() - 0.5D) * 16.0D, 0, near.getZ() + (random.nextDouble() - 0.5D) * 16.0D);
            } else {
                double a = random.nextDouble() * Math.PI * 2.0D, r = 30.0D + random.nextDouble() * 50.0D;
                to = new Vec3(getX() + Math.cos(a) * r, 0, getZ() + Math.sin(a) * r);
            }
            // не по самому віснику: відстань до лінії тіла (від голови до хвоста) більше 14 блоків
            double ax = getX() + f.x * 46.0D, az = getZ() + f.z * 46.0D, bx = getX() - f.x * 60.0D, bz = getZ() - f.z * 60.0D;
            double vx = bx - ax, vz = bz - az, w = ((to.x - ax) * vx + (to.z - az) * vz) / (vx * vx + vz * vz);
            w = Mth.clamp(w, 0.0D, 1.0D);
            double px = ax + vx * w - to.x, pz = az + vz * w - to.z;
            if (px * px + pz * pz < 14.0D * 14.0D) continue;
            if (FleshRifts.riftNear(level, BlockPos.containing(to.x, getY(), to.z), 10.0D)) continue;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(to.x), (int) Math.floor(to.z));
            Meteor m = new Meteor();
            m.to = new Vec3(to.x, y, to.z);
            double a = random.nextDouble() * Math.PI * 2.0D;
            m.from = m.to.add(Math.cos(a) * 45.0D, 80.0D, Math.sin(a) * 45.0D);
            m.total = 30 + random.nextInt(15);
            falling.add(m);
            FleshDim.sound(level, BlockPos.containing(m.from), "minecraft:entity.wither.shoot", 10.0F, 0.4F);
            made++;
        }
        FleshSpread.LOGGER.info("Вісник кличе метеорити: {}", made);
    }

    private void meteors(ServerLevel level) {
        java.util.Iterator<Meteor> it = falling.iterator();
        while (it.hasNext()) {
            Meteor m = it.next();
            m.t++;
            double k = Math.min(1.0D, m.t / (double) m.total);
            Vec3 at = m.from.add(m.to.subtract(m.from).scale(k * k)); // прискорюється
            fx(level, ParticleTypes.FLAME, at.x, at.y, at.z, 25, 1.2D, 1.2D, 1.2D, 0.05D);
            fx(level, ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 12, 1.0D, 1.0D, 1.0D, 0.02D);
            fx(level, ParticleTypes.LAVA, at.x, at.y, at.z, 3, 0.8D, 0.8D, 0.8D, 0.0D);
            if (m.t % 6 == 0) FleshDim.sound(level, BlockPos.containing(at), "minecraft:entity.blaze.burn", 6.0F, 0.5F);
            if (k >= 1.0D) {
                level.explode(this, m.to.x, m.to.y + 0.5D, m.to.z, 3.5F, true, Level.ExplosionInteraction.MOB);
                fx(level, ParticleTypes.EXPLOSION_EMITTER, m.to.x, m.to.y + 1.0D, m.to.z, 2, 1.0D, 0.5D, 1.0D, 0.0D);
                fx(level, ParticleTypes.CRIMSON_SPORE, m.to.x, m.to.y + 1.0D, m.to.z, 80, 4.0D, 1.5D, 4.0D, 0.05D);
                Nests.creep(level, BlockPos.containing(m.to), 6, 12); // метеорит несе плоть
                it.remove();
            }
        }
    }

    void makeHerald(int lifeTicks) {
        herald = true;
        this.entityData.set(HERALD, true);
        heraldLife = lifeTicks;
        for (int i = 0; i < parts.length; i++) parts[i].resize(LAYOUT[i][2] * k(), LAYOUT[i][3] * k());
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
        if (s == EMERGE) {
            if (stateTicks >= 48) {
                // виринув: розломи відкриваються між ним і гравцем, а сам він стає на хвіст
                Player near = level.getNearestPlayer(this, 200.0D);
                BlockPos toward = near == null ? blockPosition()
                        : BlockPos.containing((getX() + near.getX()) / 2.0D, getY(), (getZ() + near.getZ()) / 2.0D);
                int opened = 0;
                for (int i = 0; i < 3; i++) {
                    BlockPos r = FleshRifts.open(level, toward, 6, 28, "вісник", heraldEnd, true);
                    if (r == null) r = FleshRifts.open(level, blockPosition(), 20, 50, "вісник", heraldEnd, true);
                    if (r != null) opened++;
                }
                riftsOpened = opened;
                FleshSpread.LOGGER.info("Вісник (Утроба) виринув на {} і став на хвіст, розломів відкрито: {}", blockPosition().toShortString(), opened);
                state(REAR);
            }
            return;
        }
        if (s == BURROW) {
            if (stateTicks >= 48) {
                FleshSpread.LOGGER.info("Вісник (Утроба) закопався на {}", blockPosition().toShortString());
                discard();
            }
            return;
        }
        if (s != REAR && s != SCREAM) {
            state(REAR);
            return;
        }
        // плоть розповзається, інкубаторів не більше двох
        if (tickCount % 10 == 0) Nests.creep(level, blockPosition(), 30, 16);
        if (nests < 2 && tickCount % 300 == 150) {
            EntityType<?> big = Nests.big();
            BlockPos at = big == null ? null : Nests.findSurface(level, (int) (getX() + (random.nextDouble() - 0.5D) * 40.0D),
                    (int) (getZ() + (random.nextDouble() - 0.5D) * 40.0D));
            Entity e = at == null ? null : big.spawn(level, at, MobSpawnType.MOB_SUMMONED);
            if (e != null) {
                nests++;
                FleshSpread.LOGGER.info("Вісник виростив інкубатор {} на {}", nests, at.toShortString());
            }
        }
        meteors(level);
        if (s == SCREAM) {
            // крик: екран червоніє; перший і кожен другий кличе заражених із розломів, решта — дощ із метеоритів
            if (stateTicks == 8) {
                scream(level, false);
                screams++;
                if (screams % 2 == 1) {
                    FleshRifts.pour(level, blockPosition(), 120.0D);
                } else {
                    callMeteors(level, 3);
                }
            }
            if (stateTicks >= 32) state(REAR);
            return;
        }
        // стоїть на хвості; перший крик — щойно розломи розкриються, далі кожні 30 с
        SpreadConfig cfg = SpreadConfig.get();
        int every = Math.max(100, cfg.heraldScreamSeconds * 20);
        if (stateTicks == 45 || (stateTicks > 45 && (stateTicks - 45) % every == 0)) {
            state(SCREAM);
            return;
        }
        // час минув або всі розломи вже затягнулися — закопується
        boolean riftsGone = riftsOpened > 0 && stateTicks > 100 && FleshRifts.openNear(level, blockPosition(), 120.0D) == 0;
        if (now >= heraldEnd || riftsGone) {
            FleshRifts.closeNear(level, blockPosition(), 120.0D);
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
            RawAnimation anim = s == IDLE || s == MOVE || s == BITE_WARN || s == TAIL_WARN || s == REAR || s == CHARGE_WARN || s == CHARGE
                    ? RawAnimation.begin().thenLoop(name)
                    : s == BURROW || s == TUNNEL || s == DEATH || s == SCREAM ? RawAnimation.begin().thenPlayAndHold(name)
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
