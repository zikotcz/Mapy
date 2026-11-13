package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.ItemType;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.player.PlayerData;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/** Sloty na doplňky (prsteny, amulety...). */
public final class AccessoryMenu extends Menu {
    private static final int[] SLOTS = {10, 12, 14, 16};

    public AccessoryMenu(RPGCore plugin, Player player) {
        super(plugin, player, 3, "<dark_gray>Doplňky");
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        for (int i = 0; i < SLOTS.length; i++) {
            int index = i;
            ItemStack acc = d.accessories[i];
            if (acc == null || acc.getType().isAir()) {
                set(SLOTS[i], icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>Prázdný slot", "<dark_gray>Klikni na doplněk ve svém inventáři."));
            } else {
                set(SLOTS[i], acc.clone(), e -> {
                    if (player.getInventory().firstEmpty() < 0) {
                        plugin.players().notify(player, "<red>Máš plný inventář.");
                        return;
                    }
                    player.getInventory().addItem(d.accessories[index]);
                    d.accessories[index] = null;
                    plugin.players().recalc(player);
                    redraw();
                });
            }
        }
        set(18, icon(Material.ARROW, "<gray>Zpět"), e -> new MainMenu(plugin, player).open());
        fill();
    }

    @Override
    protected void onPlayerInventoryClick(InventoryClickEvent e) {
        ItemStack stack = e.getCurrentItem();
        RpgItem def = plugin.items().get(stack);
        PlayerData d = plugin.players().get(player);
        if (def == null || d == null || stack == null || def.type != ItemType.ACCESSORY) {
            return;
        }
        if (!d.meets(def)) {
            plugin.players().notify(player, "<red>Potřebuješ úroveň " + def.level + ".");
            return;
        }
        int free = -1;
        for (int i = 0; i < d.accessories.length; i++) {
            ItemStack acc = d.accessories[i];
            if (acc == null || acc.getType().isAir()) {
                if (free < 0) {
                    free = i;
                }
            } else if (def.id.equals(plugin.items().idOf(acc))) {
                plugin.players().notify(player, "<red>Stejný doplněk už máš nasazený.");
                return;
            }
        }
        if (free < 0) {
            plugin.players().notify(player, "<red>Všechny sloty jsou plné.");
            return;
        }
        ItemStack one = stack.clone();
        one.setAmount(1);
        d.accessories[free] = one;
        stack.setAmount(stack.getAmount() - 1);
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 1.2f);
        plugin.players().recalc(player);
        redraw();
    }
}
