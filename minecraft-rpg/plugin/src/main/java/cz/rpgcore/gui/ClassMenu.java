package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.combat.SkillManager;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.player.RpgClass;
import cz.rpgcore.util.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Výběr třídy. První výběr je zdarma a hráč dostane startovní zbraň. */
public final class ClassMenu extends Menu {
    public ClassMenu(RPGCore plugin, Player player) {
        super(plugin, player, 3, "<dark_gray>Výběr třídy");
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        long cost = plugin.getConfig().getLong("classes.change-cost", 500);
        int[] slots = {10, 12, 14, 16};
        RpgClass[] classes = RpgClass.values();
        for (int i = 0; i < classes.length; i++) {
            RpgClass c = classes[i];
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + c.description);
            lore.add("");
            lore.add("<gold>Dovednosti:");
            for (SkillManager.Skill s : plugin.skills().of(c)) {
                lore.add("<dark_gray> • <white>" + s.name() + " <dark_gray>(úroveň " + s.level() + ")");
            }
            lore.add("");
            if (d.rpgClass == c) {
                lore.add("<green>✔ Tvoje třída");
            } else if (d.rpgClass == null) {
                lore.add("<green>Klikni pro výběr <gray>(zdarma)");
            } else {
                lore.add("<yellow>Shift + klik: <gray>změnit za <gold>" + cost + " mincí");
            }
            ItemStack icon = icon(c.icon, "<bold>" + c.label, lore.toArray(String[]::new));
            icon.editMeta(meta -> {
                meta.displayName(Text.plain(c.label, c.color));
                if (d.rpgClass == c) {
                    meta.setEnchantmentGlintOverride(true);
                }
            });
            set(slots[i], icon, e -> choose(d, c, cost, e.isShiftClick()));
        }
        set(22, icon(Material.ENCHANTED_BOOK, "<yellow>Moje dovednosti", "<gray>Přehled a ovládání."), e -> new SkillsMenu(plugin, player).open());
        fill();
    }

    private void choose(PlayerData d, RpgClass c, long cost, boolean shift) {
        if (d.rpgClass == c) {
            return;
        }
        if (d.rpgClass == null) {
            d.rpgClass = c;
            String starter = switch (c) {
                case WARRIOR -> "training_sword";
                case MAGE -> "apprentice_wand";
                case ARCHER -> "hunter_bow";
                case ROGUE -> "rusty_dagger";
            };
            plugin.items().give(player, plugin.items().create(starter, 1));
            if (c == RpgClass.ARCHER) {
                plugin.items().give(player, new ItemStack(Material.ARROW, 32));
            }
        } else {
            if (!shift) {
                plugin.players().notify(player, "<gray>Pro změnu třídy drž Shift.");
                return;
            }
            if (d.coins < cost) {
                plugin.players().notify(player, "<red>Změna třídy stojí " + cost + " mincí.");
                return;
            }
            d.coins -= cost;
            d.rpgClass = c;
        }
        plugin.players().recalc(player);
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.3f);
        Text.send(player, "<green>Tvá třída je teď <white>" + c.label + "<green>. <gray>Ovládání dovedností najdeš v <yellow>/skills<gray>.");
        redraw();
    }
}
