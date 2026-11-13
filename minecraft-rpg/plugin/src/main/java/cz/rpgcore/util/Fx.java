package cz.rpgcore.util;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.util.Vector;

/** Částicové tvary a zvuky. */
public final class Fx {
    private Fx() {
    }

    /** Kruh na zemi - používá se jako varování před útokem. */
    public static void ring(Location center, double radius, Element element) {
        int points = Math.max(12, (int) (radius * 9));
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            element.dot(center.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius));
        }
    }

    /** Vyplněný kruh (dopad). */
    public static void disc(Location center, double radius, Element element) {
        for (double r = radius; r > 0.4; r -= 1.1) {
            ring(center, r, element);
        }
        element.burst(center.clone().add(0, 0.5, 0), (int) (12 + radius * 6), radius * 0.45);
    }

    public static void line(Location from, Location to, Element element) {
        Vector step = to.toVector().subtract(from.toVector());
        double len = step.length();
        if (len < 0.01) {
            return;
        }
        step.normalize().multiply(0.4);
        Location at = from.clone();
        for (double d = 0; d < len; d += 0.4) {
            element.dot(at);
            at.add(step);
        }
    }

    public static void sound(Location at, Sound sound, float volume, float pitch) {
        World w = at.getWorld();
        if (w != null) {
            w.playSound(at, sound, volume, pitch);
        }
    }
}
