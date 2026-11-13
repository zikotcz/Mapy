package cz.rpgcore.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Klíče pro ukládání dat přímo na předměty a entity (PersistentDataContainer). */
public final class Keys {
    /** Jmenný prostor resource packu (assets/rpg/...). */
    public static final String PACK = "rpg";

    public static NamespacedKey ITEM;        // id předmětu
    public static NamespacedKey UPGRADE;     // úroveň vylepšení +N
    public static NamespacedKey MOB;         // id vlastního moba
    public static NamespacedKey MOB_DAMAGE;  // poškození moba (pro střely)
    public static NamespacedKey HP_SCALE;    // poměr "virtuálního" zdraví k reálnému
    public static NamespacedKey BOSS;        // id bosse
    public static NamespacedKey MINION;      // poskok bosse (žádný loot)
    public static NamespacedKey NPC;         // id umístěného NPC
    public static NamespacedKey ARROW_DAMAGE;
    public static NamespacedKey ARROW_ITEM;
    public static NamespacedKey TEMP;        // dočasná entita (ukazatel poškození)

    private Keys() {
    }

    public static void init(Plugin plugin) {
        ITEM = new NamespacedKey(plugin, "item");
        UPGRADE = new NamespacedKey(plugin, "upgrade");
        MOB = new NamespacedKey(plugin, "mob");
        MOB_DAMAGE = new NamespacedKey(plugin, "mob_damage");
        HP_SCALE = new NamespacedKey(plugin, "hp_scale");
        BOSS = new NamespacedKey(plugin, "boss");
        MINION = new NamespacedKey(plugin, "minion");
        NPC = new NamespacedKey(plugin, "npc");
        ARROW_DAMAGE = new NamespacedKey(plugin, "arrow_damage");
        ARROW_ITEM = new NamespacedKey(plugin, "arrow_item");
        TEMP = new NamespacedKey(plugin, "temp");
    }

    public static NamespacedKey pack(String path) {
        return new NamespacedKey(PACK, path);
    }
}
