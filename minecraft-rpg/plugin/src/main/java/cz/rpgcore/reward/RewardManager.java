package cz.rpgcore.reward;

import cz.rpgcore.RPGCore;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Postupné odměny (rewards.yml):
 * denní série (každý den lepší), odměny za dosažené úrovně a odměna za odehraný čas.
 */
public final class RewardManager implements Listener {
    public record Reward(long coins, double xp, Map<String, Integer> items) {
    }

    private final RPGCore plugin;
    private final List<Reward> daily = new ArrayList<>();
    private final TreeMap<Integer, Reward> levels = new TreeMap<>();
    private Reward playtime;
    private int playtimeMinutes;
    private final Map<UUID, Integer> minutes = new HashMap<>();
    private boolean started;

    public RewardManager(RPGCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        daily.clear();
        levels.clear();
        YamlConfiguration cfg = plugin.loadYaml("rewards.yml");
        for (Map<?, ?> m : cfg.getMapList("daily")) {
            daily.add(parse(m));
        }
        ConfigurationSection lv = cfg.getConfigurationSection("levels");
        if (lv != null) {
            for (String key : lv.getKeys(false)) {
                try {
                    levels.put(Integer.parseInt(key), parse(lv.getConfigurationSection(key).getValues(false)));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("rewards.yml: neplatná úroveň '" + key + "'.");
                }
            }
        }
        ConfigurationSection pt = cfg.getConfigurationSection("playtime");
        playtimeMinutes = pt == null ? 0 : pt.getInt("minutes", 30);
        playtime = pt == null ? null : parse(pt.getValues(false));
        if (!started) {
            started = true;
            Bukkit.getScheduler().runTaskTimer(plugin, this::minute, 1200L, 1200L);
        }
    }

    private Reward parse(Map<?, ?> m) {
        Map<String, Integer> items = new LinkedHashMap<>();
        Object raw = m.get("items");
        Map<?, ?> itemMap = raw instanceof ConfigurationSection cs ? cs.getValues(false) : raw instanceof Map<?, ?> mm ? mm : Map.of();
        itemMap.forEach((k, v) -> {
            String id = String.valueOf(k);
            if (plugin.items().parse(id, 1) == null) {
                plugin.getLogger().warning("rewards.yml: neznámý předmět '" + id + "'.");
            } else {
                items.put(id, v instanceof Number n ? Math.max(1, n.intValue()) : 1);
            }
        });
        return new Reward(m.get("coins") instanceof Number n ? n.longValue() : 0,
                m.get("xp") instanceof Number n ? n.doubleValue() : 0, items);
    }

    public List<Reward> daily() {
        return daily;
    }

    public TreeMap<Integer, Reward> levels() {
        return levels;
    }

    public void give(Player p, Reward r) {
        plugin.players().addCoins(p, r.coins());
        r.items().forEach((id, amount) -> {
            ItemStack stack = plugin.items().parse(id, amount);
            if (stack != null) {
                plugin.items().give(p, stack);
            }
        });
        plugin.players().addXp(p, r.xp());
    }

    public String describe(Reward r) {
        List<String> parts = new ArrayList<>();
        if (r.coins() > 0) {
            parts.add("<gold>" + r.coins() + " mincí");
        }
        if (r.xp() > 0) {
            parts.add("<green>" + Text.num(r.xp()) + " XP");
        }
        r.items().forEach((id, amount) -> {
            var def = plugin.items().byId(id);
            parts.add("<white>" + amount + "× " + Text.esc(def == null ? id : def.name));
        });
        return String.join("<gray>, ", parts);
    }

    // ------------------------------------------------------------------ denní série

    public static long today() {
        return LocalDate.now().toEpochDay();
    }

    public boolean dailyAvailable(PlayerData d) {
        return !daily.isEmpty() && d.dailyDay != today();
    }

    /** Kolikátý den série by hráč dnes vyzvedl (1 = první). */
    public int nextStreak(PlayerData d) {
        return d.dailyDay == today() - 1 ? d.dailyStreak + 1 : 1;
    }

    public void claimDaily(Player p, PlayerData d) {
        if (!dailyAvailable(d)) {
            return;
        }
        int streak = nextStreak(d);
        d.dailyStreak = streak;
        d.dailyDay = today();
        Reward r = daily.get((streak - 1) % daily.size());
        give(p, r);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        Text.send(p, "<green>Denní odměna <gray>(den " + streak + " v řadě): " + describe(r));
    }

    // ------------------------------------------------------------------ odměny za úrovně

    public void onLevelUp(Player p, PlayerData d) {
        for (Map.Entry<Integer, Reward> e : levels.headMap(d.level, true).entrySet()) {
            if (d.levelRewards.add(e.getKey())) {
                give(p, e.getValue());
                Text.send(p, "<gold>Odměna za úroveň " + e.getKey() + ": " + describe(e.getValue()));
            }
        }
    }

    // ------------------------------------------------------------------ odehraný čas

    private void minute() {
        if (playtime == null || playtimeMinutes <= 0) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.players().get(p) == null) {
                continue;
            }
            int m = minutes.merge(p.getUniqueId(), 1, Integer::sum);
            if (m % playtimeMinutes == 0) {
                give(p, playtime);
                Text.send(p, "<aqua>Odměna za hraní <gray>(" + playtimeMinutes + " min): " + describe(playtime));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PlayerData d = plugin.players().get(p);
            if (d != null && p.isOnline() && dailyAvailable(d)) {
                Text.send(p, "<green>Čeká na tebe denní odměna! <gray>Vyzvedni si ji v <yellow>/rewards");
            }
        }, 80L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        minutes.remove(e.getPlayer().getUniqueId());
    }
}
