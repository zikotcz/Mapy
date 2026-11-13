package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.RpgItem;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Seznam všech předmětů pro adminy (/rpg items). Klik = dostaneš předmět. */
public final class ItemBrowserMenu extends Menu {
    private final int page;

    public ItemBrowserMenu(RPGCore plugin, Player player, int page) {
        super(plugin, player, 6, "<dark_gray>Všechny předměty (" + (page + 1) + ")");
        this.page = page;
    }

    @Override
    protected void draw() {
        List<RpgItem> all = new ArrayList<>(plugin.items().all());
        int from = page * 45;
        for (int i = 0; i < 45 && from + i < all.size(); i++) {
            RpgItem def = all.get(from + i);
            set(i, plugin.items().create(def, 1, 0), e ->
                    plugin.items().give(player, plugin.items().create(def, e.isShiftClick() ? def.type.maxStack : 1, 0)));
        }
        if (page > 0) {
            set(45, icon(Material.ARROW, "<yellow>Předchozí strana"), e -> new ItemBrowserMenu(plugin, player, page - 1).open());
        }
        if (from + 45 < all.size()) {
            set(53, icon(Material.ARROW, "<yellow>Další strana"), e -> new ItemBrowserMenu(plugin, player, page + 1).open());
        }
    }
}
