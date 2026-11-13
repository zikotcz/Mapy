package cz.rpgcore;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import cz.rpgcore.gui.AccessoryMenu;
import cz.rpgcore.item.ItemType;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.mob.MobManager;
import cz.rpgcore.player.PlayerData;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Ovládání: schopnosti zbraní, dovednosti tříd, spotřební předměty a ochrana vlastních předmětů. */
public final class InteractListener implements Listener {
    private final RPGCore plugin;

    public InteractListener(RPGCore plugin) {
        this.plugin = plugin;
    }

    private boolean debounce(PlayerData d) {
        long now = System.currentTimeMillis();
        if (now - d.lastClick < 150) {
            return true;
        }
        d.lastClick = now;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() == Action.PHYSICAL) {
            return;
        }
        Player p = e.getPlayer();
        ItemStack hand = e.getItem();
        RpgItem item = plugin.items().get(hand);
        PlayerData d = plugin.players().get(p);
        if (item == null || d == null || hand == null) {
            return;
        }
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        // truhly, dveře a podobně fungují normálně (pokud hráč nedrží Shift)
        if (right && !p.isSneaking() && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable()) {
            return;
        }
        if (item.type.isWeapon()) {
            if (p.isSneaking()) {
                if (right) {
                    e.setCancelled(true);            // jinak by se začal natahovat luk
                }
                if (!debounce(d)) {
                    plugin.skills().cast(p, right ? 1 : 2);
                }
            } else if (right && item.ability != null) {
                e.setCancelled(true);
                if (!debounce(d)) {
                    plugin.abilities().useAbility(p, d, item);
                }
            }
            return;
        }
        if (!right) {
            return;
        }
        e.setCancelled(true);
        if (debounce(d)) {
            return;
        }
        switch (item.type) {
            case CONSUMABLE -> plugin.abilities().consume(p, d, item, hand);
            case SUMMON -> {
                Location at = e.getClickedBlock() != null
                        ? e.getClickedBlock().getLocation().add(0.5, 1, 0.5)
                        : MobManager.ground(p.getLocation().add(p.getLocation().getDirection().setY(0).multiply(4)));
                plugin.bosses().summon(p, item, hand, at);
            }
            case ACCESSORY -> new AccessoryMenu(plugin, p).open();
            case CRATE -> plugin.crates().open(p, item, hand);
            default -> {
            }
        }
    }

    /** F = 3. dovednost, Shift + F = 4. (jen s RPG zbraní v ruce a zvolenou třídou). */
    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        PlayerData d = plugin.players().get(p);
        RpgItem item = plugin.items().get(p.getInventory().getItemInMainHand());
        if (d == null || d.rpgClass == null || item == null || !item.type.isWeapon()) {
            return;
        }
        e.setCancelled(true);
        if (!debounce(d)) {
            plugin.skills().cast(p, p.isSneaking() ? 4 : 3);
        }
    }

    /** Shift + levý klik přímo na nepřítele = 2. dovednost místo obyčejného úderu. */
    @EventHandler(ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking()) {
            return;
        }
        PlayerData d = plugin.players().get(p);
        RpgItem item = plugin.items().get(p.getInventory().getItemInMainHand());
        if (d == null || d.rpgClass == null || item == null || !item.type.isWeapon() || plugin.npcs().isNpc(e.getAttacked())) {
            return;
        }
        e.setCancelled(true);
        if (!debounce(d)) {
            plugin.skills().cast(p, 2);
        }
    }

    // ---- přepočet statů při změně výbavy ----

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.players().recalc(e.getPlayer()));
    }

    @EventHandler
    public void onArmor(PlayerArmorChangeEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.players().recalc(e.getPlayer()));
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.players().recalc(p));
        }
    }

    // ---- vlastní předměty nejdou použít ve vanilla receptech, kovadlině ani očarovat ----

    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack stack : e.getInventory().getMatrix()) {
            if (plugin.items().idOf(stack) != null) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        if (plugin.items().idOf(e.getInventory().getFirstItem()) != null || plugin.items().idOf(e.getInventory().getSecondItem()) != null) {
            e.setResult(null);
        }
    }

    @EventHandler
    public void onEnchant(PrepareItemEnchantEvent e) {
        if (plugin.items().idOf(e.getItem()) != null) {
            e.setCancelled(true);
        }
    }
}
