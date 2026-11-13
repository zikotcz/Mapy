package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.Stat;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Hlavní menu postavy (/rpg). */
public final class MainMenu extends Menu {
    public MainMenu(RPGCore plugin, Player player) {
        super(plugin, player, 3, "<dark_gray>Postava");
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Třída: " + (d.rpgClass == null ? "<red>žádná" : "<white>" + d.rpgClass.label));
        lore.add("<gray>Úroveň: <green>" + d.level + " <dark_gray>(" + Text.num(d.xp) + "/" + Text.num(plugin.players().xpNeeded(d.level)) + " XP)");
        lore.add("<gray>Mince: <gold>" + d.coins);
        lore.add("");
        lore.add("<gray>Zdraví: <red>" + Text.num(d.maxHealth) + "   <gray>Mana: <aqua>" + Text.num(d.maxMana));
        for (Stat stat : Stat.values()) {
            if (stat == Stat.HEALTH || stat == Stat.MANA || d.stat(stat) == 0) {
                continue;
            }
            lore.add("<gray>" + stat.label + ": <white>" + Text.num(d.stat(stat)) + (stat.percent ? " %" : ""));
        }
        lore.add("<gray>Šance na kritický zásah celkem: <yellow>" + Text.num(d.critChance()) + " %");
        set(10, icon(Material.NETHER_STAR, "<gold>" + player.getName(), lore.toArray(String[]::new)));

        set(12, icon(Material.BLAZE_POWDER, "<yellow>Atributy",
                "<gray>Rozděl body získané za úrovně.", "", "<gray>Volné body: <green>" + d.points), e -> new StatsMenu(plugin, player).open());
        set(13, icon(Material.ENCHANTED_BOOK, "<yellow>Dovednosti",
                "<gray>Dovednosti tvé třídy a jejich ovládání."), e -> new SkillsMenu(plugin, player).open());
        set(14, icon(Material.WRITABLE_BOOK, "<yellow>Úkoly",
                "<gray>Aktivní úkoly: <white>" + d.quests.size(), "<gray>Splněno: <white>" + d.completed.size()), e -> new QuestMenu(plugin, player, null).open());
        set(15, icon(Material.EMERALD, "<yellow>Doplňky",
                "<gray>Prsteny, amulety a talismany.", "<gray>Až " + PlayerData.ACCESSORY_SLOTS + " najednou."), e -> new AccessoryMenu(plugin, player).open());
        set(16, icon(Material.IRON_CHESTPLATE, "<yellow>Třída",
                "<gray>Výběr nebo změna třídy."), e -> new ClassMenu(plugin, player).open());
        fill();
    }
}
