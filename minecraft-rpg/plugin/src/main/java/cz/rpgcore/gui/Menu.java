package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Základ všech menu. Kliknutí jsou vždy zrušená, takže z menu nejde nic vytáhnout. */
public abstract class Menu implements InventoryHolder {
    protected final RPGCore plugin;
    protected final Player player;
    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> handlers = new HashMap<>();
    private org.bukkit.scheduler.BukkitTask animation;

    protected Menu(RPGCore plugin, Player player, int rows, String title) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, rows * 9, title(plugin, rows, title));
    }

    /**
     * S resource packem se místo vanilla truhly vykreslí vlastní pozadí: je to znak ve fontu rpg:gui,
     * posunutý záporným odsazením na začátek okna.
     */
    private static Component title(RPGCore plugin, int rows, String title) {
        if (!plugin.getConfig().getBoolean("menus.custom-textures", true) || rows < 3) {
            return Text.mm(title);
        }
        String text = title.replace("<dark_gray>", "<#ffe9a8>");
        return Component.text("\uF801" + (char) (0xE100 + rows - 3) + "\uF802", net.kyori.adventure.text.format.NamedTextColor.WHITE)
                .font(net.kyori.adventure.key.Key.key("rpg", "gui"))
                .append(Text.mm(text).font(net.kyori.adventure.key.Key.key("minecraft", "default")));
    }

    /** Spustí animaci, která běží, dokud je menu otevřené. */
    protected void animate(long periodTicks, Runnable frame) {
        stopAnimation();
        animation = Bukkit.getScheduler().runTaskTimer(plugin, frame, periodTicks, periodTicks);
    }

    protected void stopAnimation() {
        if (animation != null) {
            animation.cancel();
            animation = null;
        }
    }

    final void closed() {
        stopAnimation();
        onClose();
    }

    protected void setRaw(int slot, ItemStack stack) {
        inventory.setItem(slot, stack);
    }

    public static ItemStack pane(Material material) {
        ItemStack pane = new ItemStack(material);
        pane.editMeta(meta -> meta.setHideTooltip(true));
        return pane;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open() {
        redraw();
        player.openInventory(inventory);
    }

    protected void redraw() {
        inventory.clear();
        handlers.clear();
        draw();
    }

    protected abstract void draw();

    protected void set(int slot, ItemStack stack, Consumer<InventoryClickEvent> onClick) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return;
        }
        inventory.setItem(slot, stack);
        if (onClick != null) {
            handlers.put(slot, onClick);
        }
    }

    protected void set(int slot, ItemStack stack) {
        set(slot, stack, null);
    }

    /** Vyplní prázdná místa tmavým sklem. */
    protected void fill() {
        if (plugin.getConfig().getBoolean("menus.custom-textures", true)) {
            return;                       // vlastní pozadí má sloty nakreslené, sklo by ho zakrylo
        }
        ItemStack pane = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, pane);
            }
        }
    }

    void handle(InventoryClickEvent e) {
        Consumer<InventoryClickEvent> handler = handlers.get(e.getRawSlot());
        if (handler != null) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
            handler.accept(e);
        }
    }

    /** Klik do vlastního inventáře hráče, když je menu otevřené. */
    protected void onPlayerInventoryClick(InventoryClickEvent e) {
    }

    protected void onClose() {
    }

    public static ItemStack icon(Material material, String name, String... lore) {
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            lines.add(Text.mm(line));
        }
        return icon(new ItemStack(material), Text.mm(name), lines);
    }

    public static ItemStack icon(ItemStack base, Component name, List<Component> lore) {
        ItemStack stack = base.clone();
        stack.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        });
        return stack;
    }

    /** Přidá řádky pod stávající popis předmětu. */
    public static ItemStack withLore(ItemStack base, List<Component> extra) {
        ItemStack stack = base.clone();
        stack.editMeta(meta -> {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.addAll(extra);
            meta.lore(lore);
        });
        return stack;
    }
}
