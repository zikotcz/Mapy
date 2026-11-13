package cz.rpgcore.boss;

import cz.rpgcore.item.ItemManager;
import cz.rpgcore.mob.MobDef;
import cz.rpgcore.util.Element;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Definice bosse z bosses.yml. */
public final class BossDef {
    public enum AbilityType {CHARGE, SLAM, NOVA, BOLT, METEOR, LIGHTNING, TELEPORT, PULL, DRAIN, SUMMON, HEAL, CURSE}

    public record Ability(String key, AbilityType type, double damage, double radius, int count, double cooldown,
                          int delay, String mob, int max, Element element, int duration, double percent, String message) {
    }

    public record Phase(double below, List<String> abilities, String message, double speed, double damage) {
    }

    public final String id;
    public final MobDef mob;
    public BossBar.Color barColor = BossBar.Color.RED;
    public double abilityInterval = 4;
    public long coins;
    public final Map<String, Ability> abilities = new LinkedHashMap<>();
    public final List<Phase> phases = new ArrayList<>();
    public final List<MobDef.Drop> loot = new ArrayList<>();

    private BossDef(String id, MobDef mob) {
        this.id = id;
        this.mob = mob;
    }

    public static BossDef parse(String id, ConfigurationSection s) {
        BossDef def = new BossDef(id, MobDef.parse(id, s));
        def.barColor = ItemManager.parseEnum(BossBar.Color.class, s.getString("bar-color"), BossBar.Color.RED);
        def.abilityInterval = Math.max(1, s.getDouble("ability-interval", 4));
        def.coins = s.getLong("coins");
        def.loot.addAll(MobDef.parseDrops(s.getMapList("loot")));

        ConfigurationSection ab = s.getConfigurationSection("abilities");
        if (ab != null) {
            for (String key : ab.getKeys(false)) {
                ConfigurationSection a = ab.getConfigurationSection(key);
                AbilityType type = a == null ? null : ItemManager.parseEnum(AbilityType.class, a.getString("type"), null);
                if (type == null) {
                    throw new IllegalArgumentException("schopnost '" + key + "' má neznámý type");
                }
                def.abilities.put(key, new Ability(key, type,
                        a.getDouble("damage", 0),
                        a.getDouble("radius", 4),
                        Math.max(1, a.getInt("count", 1)),
                        a.getDouble("cooldown", 10),
                        Math.max(8, a.getInt("delay", 30)),
                        a.getString("mob"),
                        a.getInt("max", 6),
                        Element.parse(a.getString("element"), Element.PHYSICAL),
                        a.getInt("duration", 5),
                        a.getDouble("percent", 5),
                        a.getString("message")));
            }
        }
        for (Map<?, ?> m : s.getMapList("phases")) {
            List<String> list = new ArrayList<>();
            if (m.get("abilities") instanceof List<?> raw) {
                for (Object o : raw) {
                    String key = String.valueOf(o);
                    if (!def.abilities.containsKey(key)) {
                        throw new IllegalArgumentException("fáze odkazuje na neznámou schopnost '" + key + "'");
                    }
                    list.add(key);
                }
            }
            def.phases.add(new Phase(
                    m.get("below") instanceof Number n ? n.doubleValue() : 100,
                    list,
                    m.get("message") == null ? null : String.valueOf(m.get("message")),
                    m.get("speed") instanceof Number n ? n.doubleValue() : 1,
                    m.get("damage") instanceof Number n ? n.doubleValue() : 1));
        }
        if (def.phases.isEmpty()) {
            def.phases.add(new Phase(100, new ArrayList<>(def.abilities.keySet()), null, 1, 1));
        }
        def.phases.sort(Comparator.comparingDouble(Phase::below).reversed());
        return def;
    }
}
