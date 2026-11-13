package cz.rpgcore.combat;

import cz.rpgcore.util.Element;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Magická střela - letí rovně, kreslí částice a zasáhne první cíl (nebo všechny při průrazu). */
public final class Bolt extends BukkitRunnable {
    private final Location at;
    private final Vector step;
    private final int stepsPerTick;
    private final Element element;
    private final Predicate<LivingEntity> canHit;
    private final boolean pierce;
    private final Consumer<LivingEntity> onHit;
    private final Consumer<Location> onEnd;
    private final Set<UUID> hit = new HashSet<>();
    private double remaining;

    private Bolt(Location start, Vector direction, double speed, double range, Element element,
                 Predicate<LivingEntity> canHit, boolean pierce,
                 Consumer<LivingEntity> onHit, Consumer<Location> onEnd) {
        this.at = start.clone();
        this.step = direction.clone().normalize().multiply(0.5);
        this.stepsPerTick = Math.max(1, (int) Math.round(speed / 0.5));
        this.remaining = range;
        this.element = element;
        this.canHit = canHit;
        this.pierce = pierce;
        this.onHit = onHit;
        this.onEnd = onEnd;
    }

    /**
     * @param speed  bloků za tick
     * @param onEnd  zavolá se v místě dopadu (zásah, zeď, nebo konec dosahu); může být null
     */
    public static void launch(Plugin plugin, Location start, Vector direction, double speed, double range, Element element,
                              Predicate<LivingEntity> canHit, boolean pierce,
                              Consumer<LivingEntity> onHit, Consumer<Location> onEnd) {
        if (direction.lengthSquared() < 1e-6) {
            return;
        }
        new Bolt(start, direction, speed, range, element, canHit, pierce, onHit, onEnd).runTaskTimer(plugin, 0L, 1L);
    }

    @Override
    public void run() {
        World world = at.getWorld();
        if (world == null) {
            cancel();
            return;
        }
        for (int i = 0; i < stepsPerTick; i++) {
            at.add(step);
            remaining -= 0.5;
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                cancel();
                return;
            }
            element.dot(at);
            if (i % 2 == 0) {
                world.spawnParticle(element.particle, at, 1, 0.05, 0.05, 0.05, 0.0);
            }
            if (remaining <= 0 || !at.getBlock().isPassable()) {
                finish();
                return;
            }
            for (Entity e : world.getNearbyEntities(at, 0.9, 0.9, 0.9)) {
                if (e instanceof LivingEntity target && !target.isDead() && canHit.test(target) && hit.add(target.getUniqueId())) {
                    onHit.accept(target);
                    if (!pierce) {
                        finish();
                        return;
                    }
                }
            }
        }
    }

    private void finish() {
        cancel();
        if (onEnd != null) {
            onEnd.accept(at.clone());
        }
    }
}
