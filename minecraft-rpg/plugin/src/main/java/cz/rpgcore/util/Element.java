package cz.rpgcore.util;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/** Element útoku - určuje částice a vedlejší efekt při zásahu. */
public enum Element {
    PHYSICAL("Fyzický", 0xD0D0D0, Particle.CRIT),
    FIRE("Oheň", 0xFF7A1A, Particle.FLAME),
    FROST("Mráz", 0x9FE0FF, Particle.SNOWFLAKE),
    LIGHTNING("Blesk", 0xF5E85A, Particle.ELECTRIC_SPARK),
    SHADOW("Stín", 0x7A4AB0, Particle.SMOKE),
    HOLY("Světlo", 0xFFF6C0, Particle.END_ROD),
    VOID("Prázdnota", 0xC45AFF, Particle.REVERSE_PORTAL),
    ARCANE("Arkána", 0x5AE0FF, Particle.ENCHANTED_HIT),
    POISON("Jed", 0x7AFF3A, Particle.ITEM_SLIME),
    EARTH("Země", 0x8A6A4A, Particle.CRIT);

    public final String label;
    public final int rgb;
    public final Particle particle;
    private final Particle.DustOptions dust;
    private final Particle.DustOptions bigDust;

    Element(String label, int rgb, Particle particle) {
        this.label = label;
        this.rgb = rgb;
        this.particle = particle;
        this.dust = new Particle.DustOptions(Color.fromRGB(rgb), 1.0f);
        this.bigDust = new Particle.DustOptions(Color.fromRGB(rgb), 1.8f);
    }

    public TextColor color() {
        return TextColor.color(rgb);
    }

    public static Element parse(String s, Element def) {
        if (s == null) {
            return def;
        }
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }

    /** Jedna barevná tečka (na kruhy a čáry). */
    public void dot(Location at) {
        World w = at.getWorld();
        if (w != null) {
            w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, dust);
        }
    }

    /** Výbuch částic. */
    public void burst(Location at, int count, double spread) {
        World w = at.getWorld();
        if (w == null) {
            return;
        }
        w.spawnParticle(particle, at, count, spread, spread, spread, 0.03);
        w.spawnParticle(Particle.DUST, at, Math.max(1, count / 2), spread, spread, spread, 0, bigDust);
    }

    /** Vedlejší efekt elementu na zasaženém cíli. */
    public void applyStatus(LivingEntity target, int seconds) {
        int ticks = seconds * 20;
        switch (this) {
            case FIRE -> target.setFireTicks(Math.max(target.getFireTicks(), ticks));
            case FROST -> {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 1));
                target.setFreezeTicks(Math.min(target.getMaxFreezeTicks(), target.getFreezeTicks() + ticks));
            }
            case LIGHTNING -> target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Math.min(ticks, 30), 3));
            case SHADOW -> target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks, 0));
            case HOLY -> target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks * 2, 0));
            case VOID -> target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, ticks, 1));
            case POISON -> target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, ticks + 20, 1));
            case EARTH -> target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 0));
            default -> {
            }
        }
    }
}
