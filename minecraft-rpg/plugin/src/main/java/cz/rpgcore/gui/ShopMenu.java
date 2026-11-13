package cz.rpgcore.gui;

import cz.rpgcore.Content;
import cz.rpgcore.RPGCore;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Obchod. Nákup klikem nahoře, prodej klikem na předmět ve vlastním inventáři. */
public final class ShopMenu extends Menu {
    private final Content.Shop shop;

    public ShopMenu(RPGCore plugin, Player player, Content.Shop shop) {
        super(plugin, player, 5, "<dark_gray>" + Text.esc(shop.title()));
        this.shop = shop;
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        int slot = 0;
        for (Content.ShopEntry entry : shop.entries()) {
            if (slot >= 36) {
                break;
            }
            ItemStack stack = plugin.items().parse(entry.item(), entry.amount());
            if (stack == null) {
                continue;
            }
            ItemStack shown = withLore(stack, List.of(
                    Text.mm("<gray>Cena: <gold>" + entry.price() + " mincí"),
                    Text.mm(d.coins >= entry.price() ? "<green>Klikni pro koupi" : "<red>Nemáš dost mincí")));
            set(slot++, shown, e -> {
                if (d.coins < entry.price()) {
                    plugin.players().notify(player, "<red>Nemáš dost mincí.");
                    player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
                    return;
                }
                d.coins -= entry.price();
                plugin.items().give(player, plugin.items().parse(entry.item(), entry.amount()));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
                redraw();
            });
        }
        set(40, icon(Material.GOLD_INGOT, "<gold>Tvoje mince: " + d.coins,
                "<gray>Prodej: klikni na předmět ve svém inventáři.",
                "<gray>Shift + klik prodá celý stack."));
        fill();
    }

    @Override
    protected void onPlayerInventoryClick(InventoryClickEvent e) {
        ItemStack stack = e.getCurrentItem();
        RpgItem def = plugin.items().get(stack);
        PlayerData d = plugin.players().get(player);
        if (def == null || d == null || stack == null) {
            return;
        }
        if (def.sell <= 0) {
            plugin.players().notify(player, "<gray>Tohle obchodník nevykupuje.");
            return;
        }
        int amount = e.isShiftClick() ? stack.getAmount() : 1;
        d.coins += def.sell * amount;
        stack.setAmount(stack.getAmount() - amount);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 0.9f);
        plugin.players().notify(player, "<gold>+" + def.sell * amount + " mincí");
        redraw();
    }
}
