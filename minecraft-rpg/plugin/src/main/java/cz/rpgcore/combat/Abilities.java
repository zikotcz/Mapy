package cz.rpgcore.combat;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.item.Stat;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Element;
import cz.rpgcore.util.Fx;
import cz.rpgcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Schopnosti předmětů (pravý klik) a spotřební předměty. */
public final class Abilities {
    private final RPGCore plugin;

    public Abilities(RPGCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ schopnosti zbraní

    public void useAbility(Player p, PlayerData d, RpgItem item) {
        RpgItem.Ability a = item.ability;
        if (a == null) {
            return;
        }
        if (!d.meets(item)) {
            plugin.players().notify(p, "<red>Tuhle zbraň ještě neovládáš <gray>(úroveň " + item.level + ")");
            return;
        }
        String key = "item:" + item.id;
        double left = d.cooldownLeft(key);
        if (left > 0) {
            if (a.cooldown() >= 2) {
                plugin.players().notify(p, "<red>" + a.name() + " <gray>- ještě " + Text.num(left) + " s");
            }
            return;
        }
        if (!plugin.players().spendMana(p, d, a.mana())) {
            return;
        }
        d.setCooldown(key, a.cooldown());
        double damage = plugin.combat().weaponPower(p, d) * a.power();
        switch (a.type()) {
            case BOLT -> bolt(p, damage, a.element(), a.range(), a.radius(), a.pierce(), a.pull(), a.drain());
            case CONE -> cone(p, damage, a.element(), a.range(), a.drain());
            case NOVA -> nova(p, damage, a.element(), a.radius(), a.pull(), false);
            case STRIKE -> strike(p, damage, a.element(), Math.max(1.5, a.radius()));
            case DASH -> dash(p, damage, a.element(), a.range());
            case HEAL -> heal(p, a.power() + d.stat(Stat.INTELLIGENCE) * 0.5, a.radius());
        }
    }

    // ---- stavební kameny, které používají i dovednosti tříd ----

    public void bolt(Player p, double damage, Element el, double range, double radius, boolean pierce, boolean pull, double drain) {
        Combat combat = plugin.combat();
        Vector dir = p.getEyeLocation().getDirection();
        Location start = p.getEyeLocation().add(dir.clone().multiply(0.6)).subtract(0, 0.25, 0);
        Fx.sound(start, el == Element.FIRE ? Sound.ENTITY_BLAZE_SHOOT : Sound.ENTITY_EVOKER_CAST_SPELL, 0.7f, 1.4f);
        Bolt.launch(plugin, start, dir, 1.6, range, el, t -> combat.isEnemy(p, t), pierce,
                target -> {
                    combat.hit(p, target, damage, el);
                    if (drain > 0) {
                        plugin.players().heal(p, damage * drain);
                    }
                },
                at -> {
                    el.burst(at, 10, 0.25);
                    if (radius > 0) {
                        Fx.disc(at, radius, el);
                        Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.3f);
                        for (LivingEntity t : combat.enemiesNear(p, at, radius)) {
                            if (pull) {
                                pullTo(t, at, 0.7);
                            }
                            combat.hit(p, t, damage * 0.6, el);
                        }
                    }
                });
    }

    public void cone(Player p, double damage, Element el, double range, double drain) {
        Combat combat = plugin.combat();
        Location origin = p.getLocation();
        Vector dir = origin.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        for (LivingEntity t : combat.enemiesNear(p, origin, range)) {
            Vector to = t.getLocation().toVector().subtract(origin.toVector()).setY(0);
            if (to.lengthSquared() < 1.0 || to.normalize().dot(dir) > 0.45) {
                combat.hit(p, t, damage, el);
                if (drain > 0) {
                    plugin.players().heal(p, damage * drain);
                }
            }
        }
        for (double dist = 1.2; dist <= range; dist += 1.0) {
            for (int deg = -55; deg <= 55; deg += 11) {
                Vector v = dir.clone().rotateAroundY(Math.toRadians(deg)).multiply(dist);
                el.burst(origin.clone().add(v).add(0, 1.0, 0), 1, 0.08);
            }
        }
        p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, origin.clone().add(dir.clone().multiply(1.5)).add(0, 1, 0), 3, 0.6, 0.2, 0.6, 0);
        Fx.sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 0.8f);
    }

    public void nova(Player p, double damage, Element el, double radius, boolean pull, boolean knockUp) {
        Combat combat = plugin.combat();
        Location center = p.getLocation();
        Fx.disc(center, radius, el);
        Fx.sound(center, el == Element.FROST ? Sound.BLOCK_GLASS_BREAK : Sound.ENTITY_GENERIC_EXPLODE, 0.9f, 0.9f);
        for (LivingEntity t : combat.enemiesNear(p, center, radius)) {
            if (pull) {
                pullTo(t, center, 0.9);
            } else if (knockUp) {
                t.setVelocity(t.getVelocity().setY(0.7));
            }
            combat.hit(p, t, damage, el);
        }
    }

    public void strike(Player p, double damage, Element el, double radius) {
        Combat combat = plugin.combat();
        Location at = combat.aimPoint(p, 24);
        if (el == Element.LIGHTNING) {
            p.getWorld().strikeLightningEffect(at);
        } else {
            Fx.line(at.clone().add(0, 6, 0), at, el);
            Fx.sound(at, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 0.7f);
        }
        Fx.disc(at, radius, el);
        for (LivingEntity t : combat.enemiesNear(p, at, radius)) {
            combat.hit(p, t, damage, el);
        }
    }

    public void dash(Player p, double damage, Element el, double range) {
        Combat combat = plugin.combat();
        Vector dir = p.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) {
            return;
        }
        p.setVelocity(dir.normalize().multiply(Math.min(2.4, 0.6 + range * 0.16)).setY(0.25));
        Fx.sound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8f, 1.2f);
        Set<UUID> hit = new HashSet<>();
        new BukkitRunnable() {
            int ticks;

            @Override
            public void run() {
                if (++ticks > 10 || !p.isOnline() || p.isDead()) {
                    cancel();
                    return;
                }
                el.burst(p.getLocation().add(0, 1, 0), 4, 0.3);
                for (LivingEntity t : combat.enemiesNear(p, p.getLocation(), 2.2)) {
                    if (hit.add(t.getUniqueId())) {
                        combat.hit(p, t, damage, el);
                    }
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void heal(Player p, double amount, double radius) {
        Location center = p.getLocation();
        Fx.ring(center, Math.max(1.5, radius), Element.HOLY);
        Fx.sound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f);
        for (Player other : p.getWorld().getPlayers()) {
            if (other.getLocation().distanceSquared(center) <= radius * radius || other == p) {
                plugin.players().heal(other, amount);
                other.getWorld().spawnParticle(Particle.HEART, other.getLocation().add(0, 2, 0), 5, 0.4, 0.3, 0.4, 0);
            }
        }
    }

    public static void pullTo(LivingEntity target, Location center, double strength) {
        Vector v = center.toVector().subtract(target.getLocation().toVector());
        if (v.lengthSquared() > 0.5) {
            target.setVelocity(v.normalize().multiply(strength).setY(0.25));
        }
    }

    // ------------------------------------------------------------------ spotřební předměty

    public void consume(Player p, PlayerData d, RpgItem item, ItemStack stack) {
        RpgItem.Use use = item.use;
        if (use == null) {
            return;
        }
        if (d.level < item.level) {
            plugin.players().notify(p, "<red>Potřebuješ úroveň " + item.level);
            return;
        }
        if (d.cooldownLeft("consume") > 0) {
            return;
        }
        switch (use.type()) {
            case HEAL -> {
                if (p.getHealth() >= d.maxHealth - 0.5) {
                    plugin.players().notify(p, "<gray>Máš plné zdraví.");
                    return;
                }
                plugin.players().heal(p, use.amount());
                p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 6, 0.4, 0.3, 0.4, 0);
            }
            case MANA -> {
                if (d.mana >= d.maxMana - 0.5) {
                    plugin.players().notify(p, "<gray>Máš plnou manu.");
                    return;
                }
                d.mana = Math.min(d.maxMana, d.mana + use.amount());
                Element.ARCANE.burst(p.getLocation().add(0, 1, 0), 12, 0.4);
            }
            case BUFF -> {
                if (use.stat() == null) {
                    return;
                }
                d.buff(use.stat(), use.amount(), use.duration());
                plugin.players().recalc(p);
                Text.send(p, "<green>+" + Text.num(use.amount()) + " " + use.stat().label + " <gray>na " + use.duration() + " s");
            }
            case FOOD -> {
                p.setFoodLevel(Math.min(20, p.getFoodLevel() + 8));
                p.setSaturation(Math.min(20f, p.getSaturation() + 8f));
                plugin.players().heal(p, use.amount());
            }
            case XP -> plugin.players().addXp(p, use.amount());
            case TELEPORT -> {
                Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
                Element.ARCANE.burst(p.getLocation().add(0, 1, 0), 30, 0.5);
                p.teleport(spawn);
                Fx.sound(spawn, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
            }
            case RESET_STATS -> {
                int refund = 0;
                for (int v : d.attrs.values()) {
                    refund += v;
                }
                if (refund == 0) {
                    plugin.players().notify(p, "<gray>Nemáš rozdělené žádné body.");
                    return;
                }
                d.attrs.clear();
                d.points += refund;
                plugin.players().recalc(p);
                Text.send(p, "<green>Vráceno <yellow>" + refund + "<green> bodů atributů.");
            }
        }
        d.setCooldown("consume", 1.0);
        Fx.sound(p.getLocation(), use.type() == RpgItem.UseType.FOOD ? Sound.ENTITY_PLAYER_BURP : Sound.ENTITY_GENERIC_DRINK, 0.8f, 1f);
        stack.setAmount(stack.getAmount() - 1);
    }
}
