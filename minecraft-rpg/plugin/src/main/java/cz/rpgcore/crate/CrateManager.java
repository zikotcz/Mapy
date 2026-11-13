package cz.rpgcore.crate;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Bedny s kořistí (crates.yml). Bedna je předmět - pravým klikem se otevře s animací losování. */
public final class CrateManager {
    /** Jedna možná výhra. item == null znamená mince. */
    public record Prize(String item, int min, int max, int weight) {
    }

    public record Crate(String id, List<Prize> prizes, int totalWeight, boolean announce) {
    }

    private final RPGCore plugin;
    private final Map<String, Crate> crates = new LinkedHashMap<>();

    public CrateManager(RPGCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        crates.clear();
        YamlConfiguration cfg = plugin.loadYaml("crates.yml");
        for (String id : cfg.getKeys(false)) {
            ConfigurationSection s = cfg.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            List<Prize> prizes = new ArrayList<>();
            int total = 0;
            for (Map<?, ?> m : s.getMapList("loot")) {
                String item = m.get("item") == null ? null : String.valueOf(m.get("item"));
                if (item != null && plugin.items().parse(item, 1) == null) {
                    plugin.getLogger().warning("crates.yml: bedna '" + id + "' obsahuje neznámý předmět '" + item + "'.");
                    continue;
                }
                int min = m.get("min") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                int max = m.get("max") instanceof Number n ? Math.max(min, n.intValue()) : min;
                int weight = m.get("weight") instanceof Number n ? Math.max(1, n.intValue()) : 10;
                prizes.add(new Prize(item, min, max, weight));
                total += weight;
            }
            if (prizes.isEmpty()) {
                plugin.getLogger().warning("crates.yml: bedna '" + id + "' je prázdná, přeskočeno.");
                continue;
            }
            crates.put(id, new Crate(id, prizes, total, s.getBoolean("announce")));
        }
        plugin.getLogger().info("Načteno " + crates.size() + " beden.");
    }

    public Prize roll(Crate crate) {
        int pick = ThreadLocalRandom.current().nextInt(crate.totalWeight());
        for (Prize prize : crate.prizes()) {
            pick -= prize.weight();
            if (pick < 0) {
                return prize;
            }
        }
        return crate.prizes().get(0);
    }

    public void open(Player p, RpgItem item, ItemStack stack) {
        Crate crate = crates.get(item.id);
        if (crate == null) {
            Text.send(p, "<red>Tahle bedna nemá nastavený obsah (crates.yml).");
            return;
        }
        stack.setAmount(stack.getAmount() - 1);
        new CrateMenu(plugin, p, item, crate).open();
    }
}
