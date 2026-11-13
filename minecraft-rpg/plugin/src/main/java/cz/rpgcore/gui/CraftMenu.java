package cz.rpgcore.gui;

import cz.rpgcore.Content;
import cz.rpgcore.RPGCore;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Výroba u kováře podle receptů ze shops.yml. */
public final class CraftMenu extends Menu {
    private final int page;

    public CraftMenu(RPGCore plugin, Player player, int page) {
        super(plugin, player, 6, "<dark_gray>Výroba");
        this.page = page;
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        List<Content.Recipe> recipes = new ArrayList<>(plugin.content().recipes());
        int from = page * 45;
        for (int i = 0; i < 45 && from + i < recipes.size(); i++) {
            Content.Recipe r = recipes.get(from + i);
            ItemStack result = plugin.items().parse(r.result(), r.amount());
            if (result == null) {
                continue;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(Text.mm("<gold>Suroviny:"));
            boolean ok = d.level >= r.level() && d.coins >= r.coins();
            for (Map.Entry<String, Integer> ing : r.ingredients().entrySet()) {
                int have = plugin.items().count(player, ing.getKey());
                boolean enough = have >= ing.getValue();
                ok &= enough;
                lore.add(Component.text(enough ? " ✔ " : " ✘ ", enough ? NamedTextColor.GREEN : NamedTextColor.RED)
                        .append(Text.plain(ing.getValue() + "× ", NamedTextColor.GRAY))
                        .append(plugin.items().displayName(ing.getKey()))
                        .append(Text.plain(" (" + have + ")", NamedTextColor.DARK_GRAY)));
            }
            lore.add(Text.mm((d.coins >= r.coins() ? "<green> ✔ " : "<red> ✘ ") + "<gray>" + r.coins() + " mincí"));
            if (r.level() > 1) {
                lore.add(Text.mm((d.level >= r.level() ? "<green> ✔ " : "<red> ✘ ") + "<gray>Úroveň " + r.level()));
            }
            lore.add(Text.mm(ok ? "<green>Klikni pro výrobu" : "<red>Něco ti chybí"));
            boolean can = ok;
            set(i, withLore(result, lore), e -> {
                if (!can) {
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
                    return;
                }
                // znovu ověříme - inventář se mohl mezitím změnit
                for (Map.Entry<String, Integer> ing : r.ingredients().entrySet()) {
                    if (plugin.items().count(player, ing.getKey()) < ing.getValue()) {
                        redraw();
                        return;
                    }
                }
                if (d.coins < r.coins()) {
                    redraw();
                    return;
                }
                r.ingredients().forEach((item, amount) -> plugin.items().take(player, item, amount));
                d.coins -= r.coins();
                plugin.items().give(player, plugin.items().parse(r.result(), r.amount()));
                player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.3f);
                redraw();
            });
        }
        if (page > 0) {
            set(45, icon(Material.ARROW, "<yellow>Předchozí strana"), e -> new CraftMenu(plugin, player, page - 1).open());
        }
        if (from + 45 < recipes.size()) {
            set(53, icon(Material.ARROW, "<yellow>Další strana"), e -> new CraftMenu(plugin, player, page + 1).open());
        }
        set(49, icon(Material.ANVIL, "<gray>Zpět do kovárny", "<gray>Mince: <gold>" + d.coins), e -> new BlacksmithMenu(plugin, player).open());
        fill();
    }
}
