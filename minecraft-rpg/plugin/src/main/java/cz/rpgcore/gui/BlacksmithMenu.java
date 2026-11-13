package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.ItemManager;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/** Kovář: vylepšování předmětu v ruce (+1 až +10) a výroba. */
public final class BlacksmithMenu extends Menu {
    private static final int[] CHANCE = {100, 95, 90, 80, 70, 60, 50, 40, 30, 20};

    public BlacksmithMenu(RPGCore plugin, Player player) {
        super(plugin, player, 3, "<dark_gray>Kovárna");
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        RpgItem def = plugin.items().get(hand);
        boolean upgradable = def != null && (def.type.isWeapon() || def.type.isArmor());
        int level = plugin.items().upgradeOf(hand);

        if (!upgradable) {
            set(11, icon(Material.ANVIL, "<yellow>Vylepšení", "<gray>Vezmi do ruky zbraň nebo kus brnění,", "<gray>který chceš vylepšit, a otevři kovárnu znovu."));
        } else if (level >= ItemManager.MAX_UPGRADE) {
            set(11, icon(Material.ANVIL, "<yellow>Vylepšení", "<green>Tento předmět je na maximu (+10)."));
            set(13, hand.clone());
        } else {
            String stone = level < 5 ? "upgrade_stone" : "greater_upgrade_stone";
            int stones = 1 + level / 2;
            long cost = (level + 1) * 100L * (def.rarity.ordinal() + 1);
            int chance = CHANCE[level];
            int have = plugin.items().count(player, stone);
            boolean ok = have >= stones && d.coins >= cost;
            set(13, hand.clone());
            set(11, icon(Material.ANVIL, "<yellow>Vylepšit na +" + (level + 1),
                    "<gray>Každá úroveň přidá " + Text.num(plugin.getConfig().getDouble("combat.upgrade-bonus-percent", 8)) + " % ke statům.",
                    "",
                    (have >= stones ? "<green>✔ " : "<red>✘ ") + stones + "× " + plugin.items().byId(stone).name + " <dark_gray>(máš " + have + ")",
                    (d.coins >= cost ? "<green>✔ " : "<red>✘ ") + cost + " mincí",
                    "<gray>Šance na úspěch: <yellow>" + chance + " %",
                    "<dark_gray>Při neúspěchu přijdeš jen o suroviny.",
                    "",
                    ok ? "<green>Klikni pro vylepšení" : "<red>Chybí suroviny"), e -> {
                ItemStack now = player.getInventory().getItemInMainHand();
                if (!ok || !now.equals(hand)) {
                    redraw();
                    return;
                }
                plugin.items().take(player, stone, stones);
                d.coins -= cost;
                if (ThreadLocalRandom.current().nextInt(100) < chance) {
                    player.getInventory().setItemInMainHand(plugin.items().create(def, 1, level + 1));
                    player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.2f);
                    Text.send(player, "<green>Vylepšení se povedlo! <white>" + Text.esc(def.name) + " +" + (level + 1));
                } else {
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
                    Text.send(player, "<red>Vylepšení se nepovedlo. <gray>Předmět zůstal, suroviny jsou pryč.");
                }
                plugin.players().recalc(player);
                redraw();
            });
        }
        set(15, icon(Material.CRAFTING_TABLE, "<yellow>Výroba", "<gray>Zbraně, brnění a doplňky ze surovin."), e -> new CraftMenu(plugin, player, 0).open());
        fill();
    }
}
