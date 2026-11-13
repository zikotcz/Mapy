package cz.rpgcore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Obchody a recepty kováře (shops.yml). */
public final class Content {
    public record ShopEntry(String item, long price, int amount) {
    }

    public record Shop(String id, String title, List<ShopEntry> entries) {
    }

    public record Recipe(String id, String result, int amount, int level, long coins, Map<String, Integer> ingredients) {
    }

    private final RPGCore plugin;
    private final Map<String, Shop> shops = new LinkedHashMap<>();
    private final Map<String, Recipe> recipes = new LinkedHashMap<>();

    public Content(RPGCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        shops.clear();
        recipes.clear();
        YamlConfiguration cfg = plugin.loadYaml("shops.yml");

        ConfigurationSection shopSec = cfg.getConfigurationSection("shops");
        if (shopSec != null) {
            for (String id : shopSec.getKeys(false)) {
                ConfigurationSection s = shopSec.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                List<ShopEntry> entries = new ArrayList<>();
                for (Map<?, ?> m : s.getMapList("items")) {
                    String item = m.get("item") == null ? null : String.valueOf(m.get("item"));
                    if (item == null || plugin.items().parse(item, 1) == null) {
                        plugin.getLogger().warning("shops.yml: obchod '" + id + "' obsahuje neznámý předmět '" + item + "'.");
                        continue;
                    }
                    entries.add(new ShopEntry(item,
                            m.get("price") instanceof Number n ? n.longValue() : 0,
                            m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1));
                }
                shops.put(id, new Shop(id, s.getString("title", id), entries));
            }
        }
        ConfigurationSection recSec = cfg.getConfigurationSection("recipes");
        if (recSec != null) {
            for (String id : recSec.getKeys(false)) {
                ConfigurationSection s = recSec.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                String result = s.getString("result", id);
                if (plugin.items().parse(result, 1) == null) {
                    plugin.getLogger().warning("shops.yml: recept '" + id + "' má neznámý výsledek '" + result + "'.");
                    continue;
                }
                Map<String, Integer> ingredients = new LinkedHashMap<>();
                ConfigurationSection ing = s.getConfigurationSection("ingredients");
                boolean valid = true;
                if (ing != null) {
                    for (String item : ing.getKeys(false)) {
                        if (plugin.items().parse(item, 1) == null) {
                            plugin.getLogger().warning("shops.yml: recept '" + id + "' má neznámou surovinu '" + item + "'.");
                            valid = false;
                        }
                        ingredients.put(item, Math.max(1, ing.getInt(item, 1)));
                    }
                }
                if (valid) {
                    recipes.put(id, new Recipe(id, result, Math.max(1, s.getInt("amount", 1)), s.getInt("level", 1), s.getLong("coins"), ingredients));
                }
            }
        }
        plugin.getLogger().info("Načteno " + shops.size() + " obchodů a " + recipes.size() + " receptů.");
    }

    public Shop shop(String id) {
        return id == null ? null : shops.get(id);
    }

    public Collection<Recipe> recipes() {
        return recipes.values();
    }
}
