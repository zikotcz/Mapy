package cz.rpgcore.player;

import cz.rpgcore.item.Stat;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;

import java.util.Map;

public enum RpgClass {
    WARRIOR("Válečník", 0xE05A4A, Material.IRON_SWORD,
            "Bojovník nablízko. Hodně zdraví a obrany."),
    MAGE("Mág", 0x6A9AFF, Material.BREEZE_ROD,
            "Kouzelník. Silná kouzla, hodně many, málo vydrží."),
    ARCHER("Lučištník", 0x6AD06A, Material.BOW,
            "Střelec. Vysoké poškození z dálky a obratnost."),
    ROGUE("Zloděj", 0xB06AE0, Material.IRON_SWORD,
            "Rychlý zabiják. Kritické zásahy a úskoky.");

    public final String label;
    public final TextColor color;
    public final Material icon;
    public final String description;

    RpgClass(String label, int rgb, Material icon, String description) {
        this.label = label;
        this.color = TextColor.color(rgb);
        this.icon = icon;
        this.description = description;
    }

    /** Pasivní bonusy třídy, rostou s úrovní. */
    public void addBonuses(Map<Stat, Double> totals, int level) {
        switch (this) {
            case WARRIOR -> {
                add(totals, Stat.HEALTH, 10 + level * 3.0);
                add(totals, Stat.DEFENSE, 5 + level * 1.0);
                add(totals, Stat.STRENGTH, level * 0.3);
            }
            case MAGE -> {
                add(totals, Stat.MANA, 30 + level * 5.0);
                add(totals, Stat.INTELLIGENCE, 3 + level * 0.5);
                add(totals, Stat.MANA_REGEN, 1 + level * 0.05);
            }
            case ARCHER -> {
                add(totals, Stat.AGILITY, 3 + level * 0.5);
                add(totals, Stat.CRIT_CHANCE, 3);
                add(totals, Stat.HEALTH, level * 1.0);
            }
            case ROGUE -> {
                add(totals, Stat.AGILITY, 2 + level * 0.3);
                add(totals, Stat.STRENGTH, level * 0.2);
                add(totals, Stat.CRIT_CHANCE, 5);
                add(totals, Stat.CRIT_DAMAGE, 20);
                add(totals, Stat.SPEED, 5);
            }
        }
    }

    /** Násobič poškození dovedností podle hlavního atributu třídy. */
    public double skillScale(PlayerData d) {
        return switch (this) {
            case WARRIOR -> 1 + d.stat(Stat.STRENGTH) / 100.0;
            case MAGE -> 1 + d.stat(Stat.INTELLIGENCE) * 1.5 / 100.0;
            case ARCHER -> 1 + d.stat(Stat.AGILITY) / 100.0;
            case ROGUE -> 1 + (d.stat(Stat.AGILITY) * 0.6 + d.stat(Stat.STRENGTH) * 0.6) / 100.0;
        };
    }

    private static void add(Map<Stat, Double> totals, Stat stat, double v) {
        totals.merge(stat, v, Double::sum);
    }
}
