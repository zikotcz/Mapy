package cz.rpgcore.crate;

import cz.rpgcore.RPGCore;
import cz.rpgcore.gui.Menu;
import cz.rpgcore.item.Rarity;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Otevírání bedny: pás předmětů projíždí prostředním řádkem, zpomaluje a zastaví na výhře.
 * Výhra je vylosovaná předem, takže zavřením menu o ni hráč nepřijde.
 */
public final class CrateMenu extends Menu {
    private static final Material[] LIGHTS = {Material.YELLOW_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
            Material.MAGENTA_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.LIME_STAINED_GLASS_PANE};
    private static final int STRIP = 44;
    private static final int WIN_INDEX = 38;

    private final RpgItem crateItem;
    private final List<ItemStack> strip = new ArrayList<>();
    private final ItemStack prizeStack;
    private final long prizeCoins;
    private int offset;
    private int wait;
    private int frame;
    private boolean given;

    public CrateMenu(RPGCore plugin, Player player, RpgItem crateItem, CrateManager.Crate crate) {
        super(plugin, player, 3, "<dark_gray>" + Text.esc(crateItem.name));
        this.crateItem = crateItem;
        CrateManager.Prize win = plugin.crates().roll(crate);
        long coins = 0;
        ItemStack won = null;
        for (int i = 0; i < STRIP; i++) {
            CrateManager.Prize p = i == WIN_INDEX ? win : plugin.crates().roll(crate);
            int amount = p.min() + (p.max() > p.min() ? ThreadLocalRandom.current().nextInt(p.max() - p.min() + 1) : 0);
            ItemStack shown;
            if (p.item() == null) {
                shown = icon(Material.GOLD_NUGGET, "<gold>" + amount + " mincí");
                if (i == WIN_INDEX) {
                    coins = amount;
                }
            } else {
                shown = plugin.items().parse(p.item(), amount);
                if (i == WIN_INDEX) {
                    won = shown.clone();
                }
            }
            strip.add(shown);
        }
        this.prizeStack = won;
        this.prizeCoins = coins;
    }

    @Override
    protected void draw() {
        frame();
        animate(1L, this::step);
    }

    private void step() {
        if (given) {
            return;
        }
        if (wait-- > 0) {
            return;
        }
        offset++;
        frame++;
        int left = WIN_INDEX - 4 - offset;           // kolik posunů zbývá
        wait = left > 14 ? 0 : left > 8 ? 1 : left > 4 ? 3 : left > 1 ? 5 : 8;
        frame();
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 0.8f + Math.min(1.2f, offset * 0.03f));
        if (left <= 0) {
            finish(true);
        }
    }

    private void frame() {
        for (int i = 0; i < 9; i++) {
            setRaw(9 + i, strip.get(Math.min(STRIP - 1, offset + i)));
            Material light = LIGHTS[(i + frame) % LIGHTS.length];
            setRaw(i, i == 4 ? icon(Material.HOPPER, "<yellow>▼ Výhra ▼") : pane(light));
            setRaw(18 + i, i == 4 ? icon(Material.HOPPER, "<yellow>▲ Výhra ▲") : pane(light));
        }
    }

    private void finish(boolean animated) {
        if (given) {
            return;
        }
        given = true;
        stopAnimation();
        Component what;
        Rarity rarity = Rarity.COMMON;
        if (prizeStack != null) {
            RpgItem def = plugin.items().get(prizeStack);
            if (def != null) {
                rarity = def.rarity;
            }
            String id = plugin.items().idOf(prizeStack);
            what = Component.text(prizeStack.getAmount() + "× ", NamedTextColor.GRAY)
                    .append(plugin.items().displayName(id != null ? id : prizeStack.getType().name()));
            plugin.items().give(player, prizeStack);
        } else {
            what = Component.text(prizeCoins + " mincí", NamedTextColor.GOLD);
            plugin.players().addCoins(player, prizeCoins);
        }
        player.sendMessage(Text.mm("<dark_gray>[<gold>RPG<dark_gray>] <gray>Z bedny jsi získal: ").append(what));
        boolean rare = rarity.ordinal() >= Rarity.EPIC.ordinal();
        player.playSound(player.getLocation(), rare ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.9f, rare ? 1f : 1.5f);
        player.getWorld().spawnParticle(Particle.FIREWORK, player.getLocation().add(0, 1.2, 0), rare ? 80 : 25, 0.5, 0.6, 0.5, 0.08);
        if (rare) {
            Bukkit.broadcast(Text.mm("<gold>✦ <yellow>" + player.getName() + " <gray>vytáhl z bedny <white>" + Text.esc(crateItem.name) + "<gray>: ").append(what));
        }
        if (animated) {
            // výhra chvíli svítí uprostřed, pak se menu zavře
            for (int i = 0; i < 27; i++) {
                if (i != 13) {
                    setRaw(i, pane(Material.LIME_STAINED_GLASS_PANE));
                }
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.getOpenInventory().getTopInventory().getHolder(false) == this) {
                    player.closeInventory();
                }
            }, 50L);
        }
    }

    @Override
    protected void onClose() {
        finish(false);
    }
}
