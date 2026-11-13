package cz.rpgcore.mob;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.ItemManager;
import cz.rpgcore.util.Element;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Definice moba z mobs.yml (stejný základ používají i bossové). */
public final class MobDef {
    public record Drop(String item, double chance, int min, int max) {
    }

    public final String id;
    public String name;
    public EntityType type = EntityType.ZOMBIE;
    public int level = 1;
    public double health = 20;
    public double damage = 3;
    public double speed = 1;
    public double scale = 1;
    public String costume;
    public String weapon;
    public Element element;
    public double xp;
    public int coinsMin;
    public int coinsMax;
    public boolean natural;
    public boolean aggressive;
    public boolean fireproof;
    public double minDistance;
    public double maxDistance = Double.MAX_VALUE;
    public int weight = 10;
    public final List<Drop> drops = new ArrayList<>();

    public MobDef(String id) {
        this.id = id;
    }

    public static MobDef parse(String id, ConfigurationSection s) {
        MobDef d = new MobDef(id);
        d.name = s.getString("name", id);
        d.type = ItemManager.parseEnum(EntityType.class, s.getString("type"), null);
        if (d.type == null || d.type.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(d.type.getEntityClass())) {
            throw new IllegalArgumentException("neplatný type: " + s.getString("type"));
        }
        d.level = s.getInt("level", 1);
        d.health = Math.max(1, s.getDouble("health", 20));
        d.damage = s.getDouble("damage", 3);
        d.speed = s.getDouble("speed", 1);
        d.scale = s.getDouble("scale", 1);
        d.costume = s.getString("costume");
        d.weapon = s.getString("weapon");
        d.element = Element.parse(s.getString("element"), null);
        d.xp = s.getDouble("xp");
        if (s.isList("coins")) {
            List<Integer> c = s.getIntegerList("coins");
            d.coinsMin = c.isEmpty() ? 0 : c.get(0);
            d.coinsMax = c.size() > 1 ? c.get(1) : d.coinsMin;
        } else {
            d.coinsMin = d.coinsMax = s.getInt("coins");
        }
        d.natural = s.getBoolean("natural");
        d.aggressive = s.getBoolean("aggressive");
        d.fireproof = s.getBoolean("fireproof");
        List<Double> dist = s.getDoubleList("distance");
        if (dist.size() == 2) {
            d.minDistance = dist.get(0);
            d.maxDistance = dist.get(1);
        }
        d.weight = Math.max(1, s.getInt("weight", 10));
        d.drops.addAll(parseDrops(s.getMapList("drops")));
        return d;
    }

    public static List<Drop> parseDrops(List<Map<?, ?>> list) {
        List<Drop> out = new ArrayList<>();
        for (Map<?, ?> m : list) {
            Object item = m.get("item");
            if (item == null) {
                continue;
            }
            int min = num(m.get("min"), 1).intValue();
            int max = Math.max(min, num(m.get("max"), min).intValue());
            out.add(new Drop(String.valueOf(item), num(m.get("chance"), 100).doubleValue(), min, max));
        }
        return out;
    }

    private static Number num(Object o, Number def) {
        return o instanceof Number n ? n : def;
    }

    public static List<ItemStack> roll(RPGCore plugin, List<Drop> drops) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> out = new ArrayList<>();
        for (Drop drop : drops) {
            if (r.nextDouble(100) >= drop.chance()) {
                continue;
            }
            int amount = drop.min() + (drop.max() > drop.min() ? r.nextInt(drop.max() - drop.min() + 1) : 0);
            ItemStack stack = plugin.items().parse(drop.item(), amount);
            if (stack != null) {
                out.add(stack);
            }
        }
        return out;
    }

    public List<ItemStack> rollDrops(RPGCore plugin) {
        return roll(plugin, drops);
    }

    public long rollCoins() {
        return coinsMax <= coinsMin ? coinsMin : ThreadLocalRandom.current().nextInt(coinsMin, coinsMax + 1);
    }
}
