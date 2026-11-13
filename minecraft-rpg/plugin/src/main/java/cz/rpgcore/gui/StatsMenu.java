package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.Stat;
import cz.rpgcore.player.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Rozdělování bodů atributů. */
public final class StatsMenu extends Menu {
    public StatsMenu(RPGCore plugin, Player player) {
        super(plugin, player, 3, "<dark_gray>Atributy");
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        attribute(10, Material.IRON_SWORD, Stat.STRENGTH, d, "+1 % poškození zbraněmi nablízko");
        attribute(12, Material.GOLDEN_APPLE, Stat.VITALITY, d, "+3 zdraví, rychlejší regenerace");
        attribute(14, Material.LAPIS_LAZULI, Stat.INTELLIGENCE, d, "+1,5 % poškození kouzel, +4 many");
        attribute(16, Material.FEATHER, Stat.AGILITY, d, "+1 % poškození z luku, +0,25 % krit, rychlost");
        set(22, icon(Material.EXPERIENCE_BOTTLE, "<green>Volné body: " + d.points,
                "<gray>Za každou úroveň dostaneš " + plugin.getConfig().getInt("leveling.points-per-level", 3) + " body.",
                "<gray>Vrátit je umí Svitek zapomnění."));
        set(18, icon(Material.ARROW, "<gray>Zpět"), e -> new MainMenu(plugin, player).open());
        fill();
    }

    private void attribute(int slot, Material material, Stat stat, PlayerData d, String effect) {
        set(slot, icon(material, "<yellow>" + stat.label + " <white>" + d.attr(stat),
                "<gray>" + effect, "", "<green>Klik: <gray>+1", "<green>Shift + klik: <gray>+5"), e -> {
            int amount = Math.min(d.points, e.isShiftClick() ? 5 : 1);
            if (amount <= 0) {
                plugin.players().notify(player, "<red>Nemáš žádné volné body.");
                return;
            }
            d.points -= amount;
            d.attrs.merge(stat, amount, Integer::sum);
            plugin.players().recalc(player);
            redraw();
        });
    }
}
