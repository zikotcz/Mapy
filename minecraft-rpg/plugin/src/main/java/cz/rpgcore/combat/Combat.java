package cz.rpgcore.combat;

import cz.rpgcore.RPGCore;
import cz.rpgcore.boss.ActiveBoss;
import cz.rpgcore.item.ItemType;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.item.Stat;
import cz.rpgcore.mob.MobDef;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Element;
import cz.rpgcore.util.Keys;
import cz.rpgcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bojový systém.
 * <p>
 * Poškození hráče = poškození zbraně × (1 + atribut %) × kritický zásah.
 * Obrana snižuje příchozí poškození: {@code dmg × 100 / (100 + obrana)}.
 * Moby s víc než 1000 životy mají "virtuální" zdraví (vanilla strop je 1024),
 * poškození se jim proto přepočítává poměrem {@link Keys#HP_SCALE}.
 */
public final class Combat implements Listener {
    private final RPGCore plugin;
    /** Právě probíhá zásah dovedností - poškození je už spočítané. */
    private boolean skillHit;
    private boolean pendingCrit;

    public Combat(RPGCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ pomocné funkce pro dovednosti

    public static boolean roll(double percent) {
        return ThreadLocalRandom.current().nextDouble(100) < percent;
    }

    /** Smí hráč na tuhle entitu útočit dovedností? */
    public boolean isEnemy(Player player, Entity e) {
        if (!(e instanceof LivingEntity living) || e == player || living.isDead() || !e.isValid()) {
            return false;
        }
        if (e instanceof ArmorStand || e instanceof Villager || e instanceof WanderingTrader || plugin.npcs().isNpc(e)) {
            return false;
        }
        if (e instanceof Tameable pet && pet.isTamed()) {
            return false;
        }
        if (e instanceof Player other) {
            return plugin.getConfig().getBoolean("combat.skills-hit-players", false)
                    && player.getWorld().getPVP()
                    && other.getGameMode() != GameMode.CREATIVE && other.getGameMode() != GameMode.SPECTATOR;
        }
        return true;
    }

    public List<LivingEntity> enemiesNear(Player player, Location center, double radius) {
        List<LivingEntity> out = new ArrayList<>();
        if (center.getWorld() == null) {
            return out;
        }
        double r2 = radius * radius;
        for (Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (isEnemy(player, e) && e.getLocation().distanceSquared(center) <= r2 + 1.0) {
                out.add((LivingEntity) e);
            }
        }
        return out;
    }

    public LivingEntity targetInSight(Player player, double range) {
        RayTraceResult hit = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(),
                range, 0.6, e -> isEnemy(player, e));
        return hit != null && hit.getHitEntity() instanceof LivingEntity living ? living : null;
    }

    /** Místo, kam hráč míří (nepřítel, blok, nebo konec dosahu). */
    public Location aimPoint(Player player, double range) {
        LivingEntity target = targetInSight(player, range);
        if (target != null) {
            return target.getLocation();
        }
        RayTraceResult block = player.rayTraceBlocks(range);
        if (block != null) {
            return block.getHitPosition().toLocation(player.getWorld());
        }
        return player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(range));
    }

    /** Základ poškození schopnosti předmětu: poškození zbraně × atribut podle druhu zbraně. */
    public double weaponPower(Player player, PlayerData d) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        RpgItem weapon = plugin.items().get(hand);
        if (weapon == null || !weapon.type.isWeapon() || !d.meets(weapon)) {
            return 1;
        }
        double dmg = Math.max(1, plugin.items().value(hand, weapon, Stat.DAMAGE));
        return dmg * switch (weapon.type.kind) {
            case MAGIC -> 1 + d.stat(Stat.INTELLIGENCE) * 1.5 / 100.0;
            case RANGED -> 1 + d.stat(Stat.AGILITY) / 100.0;
            default -> 1 + d.stat(Stat.STRENGTH) / 100.0;
        };
    }

    /** Základ poškození dovednosti třídy: poškození zbraně × hlavní atribut třídy. */
    public double skillPower(Player player, PlayerData d) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        RpgItem weapon = plugin.items().get(hand);
        double dmg = 2;
        if (weapon != null && weapon.type.isWeapon() && d.meets(weapon)) {
            dmg = Math.max(2, plugin.items().value(hand, weapon, Stat.DAMAGE));
        }
        return dmg * (d.rpgClass == null ? 1 : d.rpgClass.skillScale(d));
    }

    /** Zásah dovedností nebo schopností (už spočítané poškození, hodí se jen kritický zásah). */
    public void hit(Player source, LivingEntity target, double amount, Element element) {
        if (target.isDead() || !target.isValid()) {
            return;
        }
        PlayerData d = plugin.players().get(source);
        boolean crit = d != null && (d.guaranteedCritUntil > System.currentTimeMillis() || roll(d.critChance()));
        if (crit) {
            amount *= d.critMultiplier();
        }
        skillHit = true;
        pendingCrit = crit;
        try {
            target.setNoDamageTicks(0);
            target.damage(amount, source);
        } finally {
            skillHit = false;
        }
        if (element != null && !target.isDead()) {
            element.applyStatus(target, 3);
        }
    }

    // ------------------------------------------------------------------ výpočet poškození

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        if (plugin.npcs().isNpc(victim)) {
            e.setCancelled(true);
            return;
        }
        if (!(victim instanceof LivingEntity target)) {
            return;
        }
        boolean crit = skillHit && pendingCrit;
        long now = System.currentTimeMillis();

        if (e instanceof EntityDamageByEntityEvent by) {
            Entity damager = by.getDamager();
            Entity source = damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter ? shooter : damager;

            if (source instanceof Player p && source != victim) {
                PlayerData d = plugin.players().get(p);
                if (d != null) {
                    d.lastCombat = now;
                    if (!skillHit) {
                        crit = playerAttack(by, p, d, damager, target);
                    }
                }
            } else if (plugin.mobs().isCustom(source)) {
                if (plugin.mobs().isCustom(victim)) {
                    e.setCancelled(true);            // vlastní moby se navzájem nezraňují (šípy, výbuchy)
                    return;
                }
                if (damager != source) {
                    e.setDamage(plugin.mobs().damageOf(source));   // střela - vanilla by počítala podle rychlosti šípu
                }
                MobDef def = plugin.mobs().of(source);
                if (def != null && def.element != null && victim instanceof Player && roll(30)) {
                    def.element.applyStatus(target, 3);
                }
            }
        }

        if (victim instanceof Player p) {
            DamageCause c = e.getCause();
            if (c != DamageCause.VOID && c != DamageCause.KILL && c != DamageCause.SUICIDE
                    && c != DamageCause.STARVATION && c != DamageCause.WORLD_BORDER) {
                PlayerData d = plugin.players().get(p);
                if (d != null) {
                    d.lastCombat = now;
                    double defense = Math.max(0, d.stat(Stat.DEFENSE));
                    e.setDamage(e.getDamage() * 100.0 / (100.0 + defense));
                }
            }
        }

        double scale = plugin.mobs().hpScale(victim);
        if (scale > 1.0) {
            e.setDamage(e.getDamage() / scale);
        }
        pendingCrit = crit;
    }

    /** Útok hráče zbraní nebo šípem. Vrací, zda šlo o kritický zásah. */
    private boolean playerAttack(EntityDamageByEntityEvent e, Player p, PlayerData d, Entity damager, LivingEntity target) {
        double dmg = e.getDamage();
        RpgItem weapon;
        boolean ranged = false;

        if (damager instanceof AbstractArrow arrow) {
            Double tagged = arrow.getPersistentDataContainer().get(Keys.ARROW_DAMAGE, PersistentDataType.DOUBLE);
            if (tagged == null) {
                return false;                         // šíp z obyčejného luku - necháme vanillu
            }
            dmg = tagged;
            weapon = plugin.items().byId(arrow.getPersistentDataContainer().get(Keys.ARROW_ITEM, PersistentDataType.STRING));
            ranged = true;
        } else if (damager == p && (e.getCause() == DamageCause.ENTITY_ATTACK || e.getCause() == DamageCause.ENTITY_SWEEP_ATTACK)) {
            weapon = plugin.items().get(p.getInventory().getItemInMainHand());
            if (weapon != null && !weapon.type.isWeapon()) {
                weapon = null;
            }
            if (weapon != null && !d.meets(weapon)) {
                e.setDamage(1);
                plugin.players().notify(p, "<red>Tuhle zbraň ještě neovládáš <gray>(úroveň " + weapon.level + ")");
                return false;
            }
            if (weapon == null || weapon.type.kind == ItemType.Kind.MELEE) {
                dmg *= 1 + d.stat(Stat.STRENGTH) / 100.0;
            }
        } else {
            return false;
        }

        boolean crit = d.guaranteedCritUntil > System.currentTimeMillis() || roll(d.critChance());
        if (crit) {
            dmg *= d.critMultiplier();
            if (!ranged) {
                d.guaranteedCritUntil = 0;
            }
        }
        e.setDamage(dmg);

        if (weapon != null && weapon.onHit != null && roll(weapon.onHit.chance())) {
            weapon.onHit.element().applyStatus(target, 3);
            weapon.onHit.element().burst(target.getLocation().add(0, 1, 0), 8, 0.3);
        }
        if (d.tempOnHit != null && d.tempOnHitUntil > System.currentTimeMillis()) {
            d.tempOnHit.applyStatus(target, 4);
        }
        return crit;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDone(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity target)) {
            return;
        }
        boolean crit = pendingCrit;
        pendingCrit = false;
        double real = e.getFinalDamage() * plugin.mobs().hpScale(target);
        if (real <= 0) {
            return;
        }
        if (plugin.mobs().isCustom(target)) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.mobs().updateName(target));
        }
        if (!(e instanceof EntityDamageByEntityEvent by)) {
            return;
        }
        Entity damager = by.getDamager();
        Entity source = damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter ? shooter : damager;
        if (!(source instanceof Player p) || target instanceof Player) {
            return;
        }
        indicator(target, real, crit);
        plugin.bosses().recordDamage(target, p, real);
        PlayerData d = plugin.players().get(p);
        if (d != null && d.stat(Stat.LIFESTEAL) > 0) {
            plugin.players().heal(p, real * Math.min(50, d.stat(Stat.LIFESTEAL)) / 100.0);
        }
    }

    private void indicator(LivingEntity target, double amount, boolean crit) {
        if (!plugin.getConfig().getBoolean("combat.damage-indicators", true)) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = target.getLocation().add(r.nextDouble(-0.5, 0.5), target.getHeight() * 0.75 + r.nextDouble(0.3), r.nextDouble(-0.5, 0.5));
        Component text = crit
                ? Component.text("✧ " + Text.num(amount), TextColor.color(0xFFC83A), TextDecoration.BOLD)
                : Component.text(Text.num(amount), NamedTextColor.RED);
        TextDisplay display = target.getWorld().spawn(at, TextDisplay.class, t -> {
            t.text(text);
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setShadowed(true);
            t.setPersistent(false);
            t.setTeleportDuration(14);
            t.getPersistentDataContainer().set(Keys.TEMP, PersistentDataType.BYTE, (byte) 1);
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (display.isValid()) {
                display.teleport(at.clone().add(0, 0.9, 0));
            }
        }, 2L);
        Bukkit.getScheduler().runTaskLater(plugin, display::remove, 18L);
    }

    // ------------------------------------------------------------------ luky

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) {
            return;
        }
        RpgItem bow = plugin.items().get(e.getBow());
        PlayerData d = plugin.players().get(p);
        if (bow == null || bow.type != ItemType.BOW || d == null) {
            return;
        }
        if (!d.meets(bow)) {
            e.setCancelled(true);
            plugin.players().notify(p, "<red>Tenhle luk ještě neovládáš <gray>(úroveň " + bow.level + ")");
            return;
        }
        double dmg = plugin.items().value(e.getBow(), bow, Stat.DAMAGE)
                * (0.25 + 0.75 * e.getForce())
                * (1 + d.stat(Stat.AGILITY) / 100.0);
        tagArrow(e.getProjectile(), dmg, bow.id);
    }

    public void tagArrow(Entity arrow, double damage, String itemId) {
        arrow.getPersistentDataContainer().set(Keys.ARROW_DAMAGE, PersistentDataType.DOUBLE, damage);
        if (itemId != null) {
            arrow.getPersistentDataContainer().set(Keys.ARROW_ITEM, PersistentDataType.STRING, itemId);
        }
    }

    // ------------------------------------------------------------------ smrt

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity entity = e.getEntity();
        if (entity instanceof Player) {
            return;
        }
        ActiveBoss boss = plugin.bosses().byEntity(entity);
        if (boss != null) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            plugin.bosses().onDeath(boss);
            return;
        }
        Player killer = entity.getKiller();
        MobDef def = plugin.mobs().of(entity);
        if (def != null) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            if (killer == null || plugin.mobs().isMinion(entity)) {
                return;
            }
            for (ItemStack drop : def.rollDrops(plugin)) {
                entity.getWorld().dropItemNaturally(entity.getLocation(), drop);
            }
            reward(killer, def.xp, def.rollCoins());
            plugin.quests().onKill(killer, def.id);
        } else if (killer != null && entity instanceof Monster) {
            plugin.players().addXp(killer, plugin.getConfig().getDouble("leveling.vanilla-mob-xp", 3));
        }
    }

    public void reward(Player player, double xp, long coins) {
        plugin.players().addXp(player, xp);
        plugin.players().addCoins(player, coins);
        if (xp > 0 || coins > 0) {
            plugin.players().notify(player, "<green>+" + Text.num(xp) + " XP" + (coins > 0 ? "  <gold>+" + coins + " mincí" : ""));
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        PlayerData d = plugin.players().get(p);
        if (d == null) {
            return;
        }
        long loss = d.coins * plugin.getConfig().getInt("combat.death-coin-loss-percent", 5) / 100;
        if (loss > 0) {
            d.coins -= loss;
            Text.send(p, "<red>Zemřel jsi a ztratil <gold>" + loss + " mincí<red>.");
        }
        d.buffs.clear();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            PlayerData d = plugin.players().get(p);
            if (d == null || !p.isOnline()) {
                return;
            }
            plugin.players().recalc(p);
            d.mana = d.maxMana;
            if (!p.isDead()) {
                p.setHealth(cz.rpgcore.player.PlayerManager.clampHealth(p, d.maxHealth));
            }
        });
    }
}
