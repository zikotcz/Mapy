package cz.rpgcore.combat;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.Stat;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.player.RpgClass;
import cz.rpgcore.util.Element;
import cz.rpgcore.util.Fx;
import cz.rpgcore.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Dovednosti tříd. Každá třída má 4, odemykají se na úrovních 1 / 8 / 16 / 30.
 * <p>
 * Ovládání (se zbraní v ruce):
 * Shift + pravý klik = 1, Shift + levý klik = 2, F = 3, Shift + F = 4.
 */
public final class SkillManager {
    public record Skill(String id, String name, RpgClass rpgClass, int slot, int level, double mana, double cooldown,
                        Material icon, String description) {
    }

    public static final String[] KEYS = {"Shift + pravý klik", "Shift + levý klik", "F", "Shift + F"};

    private final RPGCore plugin;
    private final List<Skill> skills = new ArrayList<>();

    public SkillManager(RPGCore plugin) {
        this.plugin = plugin;
        // ---- Válečník ----
        add("warrior_slash", "Drtivý úder", RpgClass.WARRIOR, 1, 1, 12, 5, Material.IRON_SWORD,
                "Širokým sekem zasáhne nepřátele před tebou (160 %).");
        add("warrior_cry", "Válečný pokřik", RpgClass.WARRIOR, 2, 8, 25, 30, Material.GOAT_HORN,
                "Na 12 s zvýší tvou sílu a obranu.");
        add("warrior_charge", "Zteč", RpgClass.WARRIOR, 3, 16, 20, 8, Material.IRON_BOOTS,
                "Vyrazíš vpřed a srazíš vše v cestě (190 %).");
        add("warrior_quake", "Zemětřesení", RpgClass.WARRIOR, 4, 30, 45, 18, Material.CRACKED_STONE_BRICKS,
                "Úder do země vyhodí nepřátele do vzduchu (260 %, okruh 6).");
        // ---- Mág ----
        add("mage_fireball", "Ohnivá koule", RpgClass.MAGE, 1, 1, 12, 2.5, Material.FIRE_CHARGE,
                "Střela, která při dopadu vybuchne (170 %).");
        add("mage_nova", "Ledová nova", RpgClass.MAGE, 2, 8, 25, 10, Material.PACKED_ICE,
                "Zmrazí a zpomalí vše kolem tebe (140 %, okruh 6).");
        add("mage_blink", "Mžik", RpgClass.MAGE, 3, 16, 18, 8, Material.ENDER_PEARL,
                "Teleportuje tě až 10 bloků směrem pohledu.");
        add("mage_meteor", "Meteor", RpgClass.MAGE, 4, 30, 60, 20, Material.MAGMA_BLOCK,
                "Na místo, kam míříš, dopadne meteor (420 %, okruh 5).");
        // ---- Lučištník ----
        add("archer_multishot", "Trojitý výstřel", RpgClass.ARCHER, 1, 1, 12, 4, Material.ARROW,
                "Vystřelí tři šípy do vějíře (3× 90 %).");
        add("archer_leap", "Úskok", RpgClass.ARCHER, 2, 8, 15, 8, Material.FEATHER,
                "Odskočíš dozadu a na chvíli zrychlíš.");
        add("archer_explosive", "Výbušný šíp", RpgClass.ARCHER, 3, 16, 25, 10, Material.TNT,
                "Šíp, který při dopadu vybuchne (220 %, okruh 4).");
        add("archer_rain", "Déšť šípů", RpgClass.ARCHER, 4, 30, 50, 20, Material.SPECTRAL_ARROW,
                "Zasype místo, kam míříš, šípy (6× 70 %, okruh 5).");
        // ---- Zloděj ----
        add("rogue_backstab", "Úder ze zálohy", RpgClass.ROGUE, 1, 1, 12, 5, Material.IRON_SWORD,
                "Objevíš se za cílem, na který míříš, a bodneš (200 %).");
        add("rogue_poison", "Otrávená čepel", RpgClass.ROGUE, 2, 8, 20, 18, Material.SPIDER_EYE,
                "Na 12 s tvé zásahy tráví a jsou silnější.");
        add("rogue_vanish", "Zmizení", RpgClass.ROGUE, 3, 16, 25, 20, Material.GRAY_DYE,
                "Zneviditelníš se, nepřátelé tě ztratí. Další zásah je kritický.");
        add("rogue_dance", "Tanec čepelí", RpgClass.ROGUE, 4, 30, 45, 18, Material.NETHER_STAR,
                "Pět bleskových seků do všech okolo (5× 85 %).");
    }

    private void add(String id, String name, RpgClass c, int slot, int level, double mana, double cooldown, Material icon, String desc) {
        skills.add(new Skill(id, name, c, slot, level, mana, cooldown, icon, desc));
    }

    public List<Skill> of(RpgClass rpgClass) {
        return skills.stream().filter(s -> s.rpgClass() == rpgClass).toList();
    }

    /** @param slot 1-4 */
    public void cast(Player p, int slot) {
        PlayerData d = plugin.players().get(p);
        if (d == null) {
            return;
        }
        if (d.rpgClass == null) {
            plugin.players().notify(p, "<gray>Nejdřív si zvol třídu: <yellow>/class");
            return;
        }
        Skill skill = skills.stream().filter(s -> s.rpgClass() == d.rpgClass && s.slot() == slot).findFirst().orElse(null);
        if (skill == null) {
            return;
        }
        if (d.level < skill.level()) {
            plugin.players().notify(p, "<red>" + skill.name() + " <gray>se odemkne na úrovni " + skill.level());
            return;
        }
        String key = "skill:" + skill.id();
        double left = d.cooldownLeft(key);
        if (left > 0) {
            plugin.players().notify(p, "<red>" + skill.name() + " <gray>- ještě " + Text.num(left) + " s");
            return;
        }
        if (d.mana < skill.mana()) {
            plugin.players().notify(p, "<red>Nedostatek many <gray>(" + Text.num(d.mana) + "/" + Text.num(skill.mana()) + ")");
            return;
        }
        if (!execute(skill, p, d)) {
            return;
        }
        d.mana -= skill.mana();
        d.setCooldown(key, skill.cooldown());
        plugin.players().notify(p, "<gold>✦ " + skill.name());
    }

    /** @return false = dovednost se neprovedla (nestojí manu ani čekání) */
    private boolean execute(Skill skill, Player p, PlayerData d) {
        Combat combat = plugin.combat();
        Abilities ab = plugin.abilities();
        double power = combat.skillPower(p, d);
        long now = System.currentTimeMillis();

        switch (skill.id()) {
            // ------------------------------------------------ Válečník
            case "warrior_slash" -> ab.cone(p, power * 1.6, Element.PHYSICAL, 4.5, 0);
            case "warrior_cry" -> {
                d.buff(Stat.STRENGTH, 20 + d.level * 0.3, 12);
                d.buff(Stat.DEFENSE, 30 + d.level, 12);
                plugin.players().recalc(p);
                Fx.sound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 1.2f);
                Fx.ring(p.getLocation(), 2.5, Element.FIRE);
                p.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, p.getLocation().add(0, 2, 0), 6, 0.5, 0.3, 0.5, 0);
            }
            case "warrior_charge" -> ab.dash(p, power * 1.9, Element.PHYSICAL, 10);
            case "warrior_quake" -> ab.nova(p, power * 2.6, Element.EARTH, 6, false, true);

            // ------------------------------------------------ Mág
            case "mage_fireball" -> ab.bolt(p, power * 1.7, Element.FIRE, 28, 3, false, false, 0);
            case "mage_nova" -> ab.nova(p, power * 1.4, Element.FROST, 6, false, false);
            case "mage_blink" -> {
                Vector dir = p.getEyeLocation().getDirection();
                Location dest = null;
                for (double i = 1; i <= 10; i += 0.5) {
                    Location c = p.getLocation().add(dir.clone().multiply(i));
                    if (!c.getBlock().isPassable() || !c.clone().add(0, 1, 0).getBlock().isPassable()) {
                        break;
                    }
                    dest = c;
                }
                if (dest == null) {
                    plugin.players().notify(p, "<gray>Tudy to nejde.");
                    return false;
                }
                Element.ARCANE.burst(p.getLocation().add(0, 1, 0), 20, 0.4);
                dest.setDirection(dir);
                p.setFallDistance(0);
                p.teleport(dest);
                Element.ARCANE.burst(dest.clone().add(0, 1, 0), 20, 0.4);
                Fx.sound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.3f);
            }
            case "mage_meteor" -> {
                Location at = combat.aimPoint(p, 28);
                Fx.sound(at, Sound.ENTITY_WITHER_SHOOT, 0.8f, 0.6f);
                Telegraph.run(plugin, at, 5, 30, Element.FIRE, () -> {
                    at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
                    Fx.sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.7f);
                    if (p.isOnline()) {
                        for (LivingEntity t : combat.enemiesNear(p, at, 5)) {
                            combat.hit(p, t, power * 4.2, Element.FIRE);
                        }
                    }
                });
            }

            // ------------------------------------------------ Lučištník
            case "archer_multishot" -> {
                Vector dir = p.getEyeLocation().getDirection();
                for (int deg : new int[]{-8, 0, 8}) {
                    Vector v = dir.clone().rotateAroundY(Math.toRadians(deg)).multiply(2.8);
                    Arrow arrow = p.launchProjectile(Arrow.class, v);
                    arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    combat.tagArrow(arrow, power * 0.9, null);
                }
                Fx.sound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.2f);
            }
            case "archer_leap" -> {
                Vector back = p.getLocation().getDirection().setY(0);
                if (back.lengthSquared() < 1e-4) {
                    return false;
                }
                p.setVelocity(back.normalize().multiply(-1.3).setY(0.5));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 1));
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.3, 0.1, 0.3, 0.02);
                Fx.sound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.8f, 1.3f);
            }
            case "archer_explosive" -> ab.bolt(p, power * 2.2, Element.FIRE, 40, 4, false, false, 0);
            case "archer_rain" -> {
                Location at = combat.aimPoint(p, 28);
                new BukkitRunnable() {
                    int wave;

                    @Override
                    public void run() {
                        if (++wave > 6 || !p.isOnline() || at.getWorld() == null) {
                            cancel();
                            return;
                        }
                        Fx.ring(at, 5, Element.PHYSICAL);
                        at.getWorld().spawnParticle(Particle.CRIT, at.clone().add(0, 3, 0), 60, 2.5, 1.5, 2.5, 0.3);
                        Fx.sound(at, Sound.ENTITY_ARROW_HIT, 0.7f, 1f);
                        for (LivingEntity t : combat.enemiesNear(p, at, 5)) {
                            combat.hit(p, t, power * 0.7, Element.PHYSICAL);
                        }
                    }
                }.runTaskTimer(plugin, 5L, 10L);
            }

            // ------------------------------------------------ Zloděj
            case "rogue_backstab" -> {
                LivingEntity target = combat.targetInSight(p, 12);
                if (target == null) {
                    plugin.players().notify(p, "<gray>Zamiř na nepřítele.");
                    return false;
                }
                Vector facing = target.getLocation().getDirection().setY(0);
                Location behind = target.getLocation();
                if (facing.lengthSquared() > 1e-4) {
                    Location c = target.getLocation().subtract(facing.normalize().multiply(1.3));
                    if (c.getBlock().isPassable() && c.clone().add(0, 1, 0).getBlock().isPassable()) {
                        behind = c;
                    }
                }
                behind.setDirection(target.getLocation().toVector().subtract(behind.toVector()).setY(0).add(new Vector(0, 0.001, 0)));
                Element.SHADOW.burst(p.getLocation().add(0, 1, 0), 15, 0.3);
                p.teleport(behind);
                Fx.sound(behind, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.6f);
                combat.hit(p, target, power * 2.0, Element.PHYSICAL);
            }
            case "rogue_poison" -> {
                d.tempOnHit = Element.POISON;
                d.tempOnHitUntil = now + 12_000;
                d.buff(Stat.STRENGTH, 15 + d.level * 0.2, 12);
                plugin.players().recalc(p);
                Element.POISON.burst(p.getLocation().add(0, 1, 0), 20, 0.4);
                Fx.sound(p.getLocation(), Sound.ENTITY_SPIDER_AMBIENT, 0.8f, 1.4f);
            }
            case "rogue_vanish" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1));
                d.guaranteedCritUntil = now + 6_000;
                p.getWorld().spawnParticle(Particle.LARGE_SMOKE, p.getLocation().add(0, 1, 0), 40, 0.6, 0.8, 0.6, 0.02);
                Fx.sound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 1.2f);
                for (Entity e : p.getNearbyEntities(16, 8, 16)) {
                    if (e instanceof Mob mob && p.equals(mob.getTarget())) {
                        mob.setTarget(null);
                    }
                }
            }
            case "rogue_dance" -> new BukkitRunnable() {
                int cut;

                @Override
                public void run() {
                    if (++cut > 5 || !p.isOnline() || p.isDead()) {
                        cancel();
                        return;
                    }
                    p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, p.getLocation().add(0, 1, 0), 6, 2, 0.4, 2, 0);
                    Fx.sound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.4f);
                    for (LivingEntity t : combat.enemiesNear(p, p.getLocation(), 4.5)) {
                        combat.hit(p, t, power * 0.85, Element.PHYSICAL);
                    }
                }
            }.runTaskTimer(plugin, 0L, 4L);
            default -> {
                return false;
            }
        }
        return true;
    }
}
