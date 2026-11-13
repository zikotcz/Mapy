package cz.rpgcore.item;

import net.kyori.adventure.text.format.TextColor;

import java.util.Locale;

public enum Stat {
    DAMAGE("Poškození", 0xFF5555, false),
    DEFENSE("Obrana", 0xAAB4C8, false),
    HEALTH("Zdraví", 0xFF6680, false),
    MANA("Mana", 0x55AAFF, false),
    STRENGTH("Síla", 0xFF8844, false),
    VITALITY("Výdrž", 0xFF6680, false),
    INTELLIGENCE("Inteligence", 0x77BBFF, false),
    AGILITY("Obratnost", 0x66DD88, false),
    CRIT_CHANCE("Šance na kritický zásah", 0xFFDD55, true),
    CRIT_DAMAGE("Kritické poškození", 0xFFDD55, true),
    SPEED("Rychlost", 0xFFFFFF, true),
    MANA_REGEN("Regenerace many", 0x55AAFF, false),
    LIFESTEAL("Krádež života", 0xDD4466, true);

    /** Atributy, do kterých hráč rozděluje body. */
    public static final Stat[] ATTRIBUTES = {STRENGTH, VITALITY, INTELLIGENCE, AGILITY};

    public final String label;
    public final TextColor color;
    public final boolean percent;

    Stat(String label, int rgb, boolean percent) {
        this.label = label;
        this.color = TextColor.color(rgb);
        this.percent = percent;
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Stat byKey(String key) {
        try {
            return valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
