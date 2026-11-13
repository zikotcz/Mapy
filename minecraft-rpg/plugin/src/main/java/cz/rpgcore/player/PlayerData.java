package cz.rpgcore.player;

import cz.rpgcore.item.RpgItem;
import cz.rpgcore.item.Stat;
import cz.rpgcore.util.Element;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Vše, co si plugin o hráči pamatuje. */
public final class PlayerData {
    public static final int ACCESSORY_SLOTS = 4;

    public final UUID uuid;

    // ---- ukládá se ----
    public int level = 1;
    public double xp;
    public RpgClass rpgClass;
    public int points;
    public final Map<Stat, Integer> attrs = new EnumMap<>(Stat.class);
    public double mana = 50;
    public long coins;
    public double healthFraction = 1.0;
    /** aktivní úkoly: id -> postup jednotlivých cílů */
    public final Map<String, int[]> quests = new LinkedHashMap<>();
    public final Set<String> completed = new LinkedHashSet<>();
    public final ItemStack[] accessories = new ItemStack[ACCESSORY_SLOTS];

    // ---- jen za běhu ----
    public final Map<Stat, Double> totals = new EnumMap<>(Stat.class);
    public double maxHealth = 20;
    public double maxMana = 50;
    public final Map<String, Long> cooldowns = new HashMap<>();
    /** dočasné bonusy z elixírů a dovedností: stat -> {hodnota, konec v ms} */
    public final Map<Stat, double[]> buffs = new EnumMap<>(Stat.class);
    public Element tempOnHit;
    public long tempOnHitUntil;
    public long guaranteedCritUntil;
    public long hudHoldUntil;
    public long lastClick;
    public long lastCombat;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public double stat(Stat stat) {
        Double v = totals.get(stat);
        return v == null ? 0 : v;
    }

    public int attr(Stat stat) {
        Integer v = attrs.get(stat);
        return v == null ? 0 : v;
    }

    public boolean meets(RpgItem item) {
        return level >= item.level && (item.classes.isEmpty() || (rpgClass != null && item.classes.contains(rpgClass)));
    }

    public double critChance() {
        return Math.min(80, 5 + stat(Stat.CRIT_CHANCE) + stat(Stat.AGILITY) * 0.25);
    }

    public double critMultiplier() {
        return 1.5 + stat(Stat.CRIT_DAMAGE) / 100.0;
    }

    /** Zbývající čekání v sekundách (0 = připraveno). */
    public double cooldownLeft(String key) {
        Long until = cooldowns.get(key);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0) {
            cooldowns.remove(key);
            return 0;
        }
        return left / 1000.0;
    }

    public void setCooldown(String key, double seconds) {
        cooldowns.put(key, System.currentTimeMillis() + (long) (seconds * 1000));
    }

    public void buff(Stat stat, double amount, int seconds) {
        buffs.put(stat, new double[]{amount, System.currentTimeMillis() + seconds * 1000L});
    }
}
