package cz.rpgcore.boss;

import cz.rpgcore.RPGCore;
import cz.rpgcore.combat.Bolt;
import cz.rpgcore.combat.Telegraph;
import cz.rpgcore.mob.MobManager;
import cz.rpgcore.util.Element;
import cz.rpgcore.util.Fx;
import cz.rpgcore.util.Keys;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Provedení schopností bossů. Každý plošný útok se předem ohlásí kruhem na zemi. */
public final class BossAbilities {
    private BossAbilities() {
    }

    public static boolean validTarget(Player p) {
        return p.isOnline() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    public static List<Player> playersNear(Location center, double radius) {
        List<Player> out = new ArrayList<>();
        if (center.getWorld() == null) {
            return out;
        }
        for (Player p : center.getWorld().getPlayers()) {
            if (validTarget(p) && p.getLocation().distanceSquared(center) <= radius * radius) {
                out.add(p);
            }
        }
        return out;
    }

    private static void hurt(ActiveBoss boss, Player p, double damage, Element element) {
        if (!boss.alive() || !validTarget(p) || p.getWorld() != boss.entity.getWorld()) {
            return;
        }
        p.damage(damage, boss.entity);
        if (element != null && ThreadLocalRandom.current().nextBoolean()) {
            element.applyStatus(p, 3);
        }
    }

    public static void heal(RPGCore plugin, ActiveBoss boss, double virtualAmount) {
        LivingEntity e = boss.entity;
        AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
        if (!boss.alive() || max == null) {
            return;
        }
        e.setHealth(Math.min(max.getValue(), e.getHealth() + virtualAmount / plugin.mobs().hpScale(e)));
        plugin.mobs().updateName(e);
    }

    public static void cast(RPGCore plugin, ActiveBoss boss, BossDef.Ability a, List<Player> targets) {
        if (targets.isEmpty() || !boss.alive()) {
            return;
        }
        LivingEntity e = boss.entity;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Element el = a.element();
        double damage = a.damage() * boss.damageMult;
        Player main = e instanceof Mob mob && mob.getTarget() instanceof Player tp && targets.contains(tp)
                ? tp : targets.get(r.nextInt(targets.size()));

        switch (a.type()) {
            case CHARGE -> {
                Vector v = main.getLocation().toVector().subtract(e.getLocation().toVector());
                double dist = v.length();
                if (dist < 0.5) {
                    v = new Vector(0, 0, 1);
                }
                e.setVelocity(v.normalize().multiply(Math.min(2.6, 0.5 + dist * 0.13)).setY(0.45));
                Fx.sound(e.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.2f, 0.9f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!boss.alive()) {
                        return;
                    }
                    Location at = e.getLocation();
                    Fx.disc(at, 3.2, Element.PHYSICAL);
                    Fx.sound(at, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, 1f, 0.7f);
                    for (Player p : playersNear(at, 3.2)) {
                        hurt(boss, p, damage, null);
                        Vector kb = p.getLocation().toVector().subtract(at.toVector()).setY(0);
                        if (kb.lengthSquared() > 0.01) {
                            p.setVelocity(kb.normalize().multiply(0.9).setY(0.4));
                        }
                    }
                }, 14L);
            }
            case SLAM, NOVA -> {
                Location at = e.getLocation();
                boolean slam = a.type() == BossDef.AbilityType.SLAM;
                Fx.sound(at, slam ? Sound.ENTITY_IRON_GOLEM_ATTACK : Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 0.6f);
                Telegraph.run(plugin, at, a.radius(), a.delay(), el, () -> {
                    if (!boss.alive()) {
                        return;
                    }
                    Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, slam ? 0.6f : 1.0f);
                    for (Player p : playersNear(at, a.radius())) {
                        hurt(boss, p, damage, el);
                        if (slam) {
                            p.setVelocity(p.getVelocity().setY(0.8));
                        }
                    }
                });
            }
            case BOLT -> new BukkitRunnable() {
                int shot;

                @Override
                public void run() {
                    if (shot++ >= a.count() || !boss.alive()) {
                        cancel();
                        return;
                    }
                    List<Player> now = playersNear(e.getLocation(), 40);
                    if (now.isEmpty()) {
                        cancel();
                        return;
                    }
                    Player t = now.get(ThreadLocalRandom.current().nextInt(now.size()));
                    Location from = e.getEyeLocation();
                    Vector dir = t.getLocation().add(0, 1.0, 0).toVector().subtract(from.toVector());
                    Fx.sound(from, el == Element.FIRE ? Sound.ENTITY_BLAZE_SHOOT : Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 0.9f);
                    Bolt.launch(plugin, from, dir, 0.9, 40, el,
                            hit -> hit instanceof Player p && validTarget(p), false,
                            hit -> hurt(boss, (Player) hit, damage, el),
                            end -> el.burst(end, 8, 0.25));
                }
            }.runTaskTimer(plugin, 0L, 5L);
            case METEOR, LIGHTNING -> {
                boolean lightning = a.type() == BossDef.AbilityType.LIGHTNING;
                double radius = lightning ? 2.5 : a.radius();
                Element fx = lightning ? Element.LIGHTNING : el;
                for (int i = 0; i < a.count(); i++) {
                    Player t = targets.get(i % targets.size());
                    Location at = t.getLocation();
                    if (i >= targets.size()) {
                        // víc dopadů než hráčů - zbytek padá náhodně kolem nich
                        at.add(r.nextDouble(-6, 6), 0, r.nextDouble(-6, 6));
                        at = MobManager.ground(at);
                    }
                    Location impact = at;
                    Telegraph.run(plugin, impact, radius, a.delay(), fx, () -> {
                        if (!boss.alive()) {
                            return;
                        }
                        if (lightning) {
                            impact.getWorld().strikeLightningEffect(impact);
                        } else {
                            impact.getWorld().spawnParticle(Particle.EXPLOSION, impact.clone().add(0, 0.5, 0), 2, 0.6, 0.2, 0.6, 0);
                            Fx.sound(impact, Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 1.1f);
                        }
                        for (Player p : playersNear(impact, radius)) {
                            hurt(boss, p, damage, fx);
                        }
                    });
                }
            }
            case TELEPORT -> {
                Player t = targets.get(r.nextInt(targets.size()));
                Vector facing = t.getLocation().getDirection().setY(0);
                Location behind = t.getLocation();
                if (facing.lengthSquared() > 1e-4) {
                    Location c = t.getLocation().subtract(facing.normalize().multiply(1.8));
                    if (c.getBlock().isPassable() && c.clone().add(0, 1, 0).getBlock().isPassable()) {
                        behind = c;
                    }
                }
                el.burst(e.getLocation().add(0, 1, 0), 30, 0.5);
                e.teleport(behind);
                el.burst(behind.clone().add(0, 1, 0), 30, 0.5);
                Fx.sound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.6f);
                if (e instanceof Mob mob) {
                    mob.setTarget(t);
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (boss.alive() && t.getWorld() == e.getWorld() && t.getLocation().distanceSquared(e.getLocation()) <= 3.5 * 3.5) {
                        hurt(boss, t, damage, el);
                        t.getWorld().spawnParticle(Particle.SWEEP_ATTACK, t.getLocation().add(0, 1, 0), 3, 0.4, 0.3, 0.4, 0);
                    }
                }, 10L);
            }
            case PULL -> {
                Location at = e.getLocation();
                Fx.sound(at, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 1.4f);
                for (Player p : playersNear(at, a.radius())) {
                    Vector v = at.toVector().subtract(p.getLocation().toVector());
                    double dist = v.length();
                    if (dist > 2.5) {
                        p.setVelocity(v.normalize().multiply(Math.min(2.4, 0.5 + dist * 0.11)).setY(0.35));
                        Fx.line(p.getLocation().add(0, 1, 0), at.clone().add(0, 1, 0), Element.VOID);
                    }
                }
            }
            case DRAIN -> new BukkitRunnable() {
                int pulse;

                @Override
                public void run() {
                    if (pulse++ >= a.count() || !boss.alive() || !validTarget(main)
                            || main.getWorld() != e.getWorld() || main.getLocation().distanceSquared(e.getLocation()) > 28 * 28) {
                        cancel();
                        return;
                    }
                    Fx.line(e.getEyeLocation(), main.getLocation().add(0, 1.2, 0), Element.POISON);
                    Fx.sound(main.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.4f, 1.8f);
                    hurt(boss, main, damage, null);
                    heal(plugin, boss, damage * 3);
                }
            }.runTaskTimer(plugin, 0L, 10L);
            case SUMMON -> {
                boss.minions.removeIf(uuid -> {
                    Entity m = Bukkit.getEntity(uuid);
                    return m == null || m.isDead() || !m.isValid();
                });
                int room = Math.min(a.count(), a.max() - boss.minions.size());
                for (int i = 0; i < room; i++) {
                    double angle = r.nextDouble(Math.PI * 2);
                    Location at = MobManager.ground(e.getLocation().add(Math.cos(angle) * 2.5, 0, Math.sin(angle) * 2.5));
                    LivingEntity minion = a.mob() == null ? null : plugin.mobs().spawn(a.mob(), at);
                    if (minion == null) {
                        continue;
                    }
                    minion.getPersistentDataContainer().set(Keys.MINION, PersistentDataType.BYTE, (byte) 1);
                    minion.setPersistent(false);
                    boss.minions.add(minion.getUniqueId());
                    if (minion instanceof Mob mob) {
                        mob.setTarget(main);
                    }
                    Element.SHADOW.burst(at.clone().add(0, 1, 0), 20, 0.4);
                }
                Fx.sound(e.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.2f, 0.9f);
            }
            case HEAL -> {
                heal(plugin, boss, boss.def.mob.health * a.percent() / 100.0);
                e.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, e.getLocation().add(0, 1.5, 0), 40, 1, 1.2, 1, 0);
                Fx.sound(e.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 0.7f);
            }
            case CURSE -> {
                Location at = e.getLocation();
                Fx.ring(at, a.radius(), el);
                Fx.sound(at, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.8f);
                int ticks = a.duration() * 20;
                for (Player p : playersNear(at, a.radius())) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 0));
                    switch (el) {
                        case SHADOW -> p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0));
                        case POISON -> p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks, 1));
                        case VOID -> p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, ticks, 1));
                        default -> p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 1));
                    }
                    el.burst(p.getLocation().add(0, 1, 0), 12, 0.4);
                }
            }
        }
    }
}
