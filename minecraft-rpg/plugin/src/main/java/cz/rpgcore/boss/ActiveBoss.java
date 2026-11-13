package cz.rpgcore.boss;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Jeden živý boss ve světě. */
public final class ActiveBoss {
    public final BossDef def;
    public final LivingEntity entity;
    public final BossBar bar;
    public final Location home;
    /** id arény, ve které se boss obnovuje (null = vyvolaný předmětem nebo příkazem) */
    public final String arena;
    public final double baseSpeed;

    public final Map<UUID, Double> damage = new HashMap<>();
    public final Map<String, Long> cooldowns = new HashMap<>();
    public final Set<UUID> minions = new HashSet<>();
    public final Set<UUID> viewers = new HashSet<>();
    public int phase = -1;
    public double damageMult = 1;
    public long nextCast;
    public long lastPlayerSeen = System.currentTimeMillis();

    public ActiveBoss(BossDef def, LivingEntity entity, BossBar bar, Location home, String arena, double baseSpeed) {
        this.def = def;
        this.entity = entity;
        this.bar = bar;
        this.home = home;
        this.arena = arena;
        this.baseSpeed = baseSpeed;
    }

    public boolean alive() {
        return entity.isValid() && !entity.isDead();
    }
}
