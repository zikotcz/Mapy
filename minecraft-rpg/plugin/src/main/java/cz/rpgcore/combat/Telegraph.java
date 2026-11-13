package cz.rpgcore.combat;

import cz.rpgcore.util.Element;
import cz.rpgcore.util.Fx;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

/** Varovný kruh na zemi, po kterém přijde dopad. Dává hráčům čas uhnout. */
public final class Telegraph {
    private Telegraph() {
    }

    public static void run(Plugin plugin, Location center, double radius, int delayTicks, Element element, Runnable impact) {
        new BukkitRunnable() {
            int elapsed;

            @Override
            public void run() {
                if (center.getWorld() == null) {
                    cancel();
                    return;
                }
                if (elapsed >= delayTicks) {
                    cancel();
                    Fx.disc(center, radius, element);
                    impact.run();
                    return;
                }
                Fx.ring(center, radius, element);
                // vnitřní kruh se stahuje - je vidět, kolik času zbývá
                Fx.ring(center, radius * (1.0 - (double) elapsed / delayTicks), element);
                elapsed += 4;
            }
        }.runTaskTimer(plugin, 0L, 4L);
    }
}
