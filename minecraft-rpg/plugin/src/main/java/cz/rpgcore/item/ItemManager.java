package cz.rpgcore.item;

import cz.rpgcore.RPGCore;
import cz.rpgcore.player.RpgClass;
import cz.rpgcore.util.Element;
import cz.rpgcore.util.Keys;
import cz.rpgcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Načítá předměty z items.yml a vyrábí z nich ItemStacky. */
public final class ItemManager {
    /** Set brnění (i kostým moba). {@code name} je ve 2. pádu: "Helma rytíře". */
    public record ArmorSet(String id, String name, String style, boolean costume, Map<Stat, Double> fullSet) {
    }

    private static final ItemType[] PIECES = {ItemType.HELMET, ItemType.CHESTPLATE, ItemType.LEGGINGS, ItemType.BOOTS};
    private static final String[] PIECE_IDS = {"helmet", "chestplate", "leggings", "boots"};
    private static final double[] PIECE_SHARE = {0.20, 0.40, 0.25, 0.15};
    public static final int MAX_UPGRADE = 10;

    private final RPGCore plugin;
    private final Map<String, RpgItem> items = new LinkedHashMap<>();
    private final Map<String, ArmorSet> sets = new LinkedHashMap<>();
    private double upgradeBonus = 0.08;

    public ItemManager(RPGCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ načítání

    public void load() {
        items.clear();
        sets.clear();
        upgradeBonus = plugin.getConfig().getDouble("combat.upgrade-bonus-percent", 8) / 100.0;
        YamlConfiguration cfg = plugin.loadYaml("items.yml");

        ConfigurationSection setSec = cfg.getConfigurationSection("sets");
        if (setSec != null) {
            for (String id : setSec.getKeys(false)) {
                ConfigurationSection s = setSec.getConfigurationSection(id);
                if (s != null) {
                    loadSet(id, s);
                }
            }
        }
        ConfigurationSection itemSec = cfg.getConfigurationSection("items");
        if (itemSec != null) {
            for (String id : itemSec.getKeys(false)) {
                ConfigurationSection s = itemSec.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                try {
                    items.put(id, loadItem(id, s));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("items.yml: předmět '" + id + "' přeskočen - " + ex.getMessage());
                }
            }
        }
        plugin.getLogger().info("Načteno " + items.size() + " předmětů a " + sets.size() + " setů.");
    }

    private void loadSet(String id, ConfigurationSection s) {
        boolean costume = s.getBoolean("costume");
        String style = s.getString("style", "plate");
        String name = s.getString("name", id);
        sets.put(id, new ArmorSet(id, name, style, costume, readStats(s.getConfigurationSection("full-set"))));
        if (costume) {
            return;
        }
        Map<Stat, Double> total = readStats(s.getConfigurationSection("stats"));
        Rarity rarity = parseEnum(Rarity.class, s.getString("rarity"), Rarity.COMMON);
        int level = s.getInt("level", 1);
        String[] labels = {
                style.equals("robe") ? "Kápě" : "Helma",
                style.equals("robe") ? "Róba" : (style.equals("leather") ? "Kabátec" : "Zbroj"),
                "Kalhoty", "Boty"};
        for (int i = 0; i < PIECES.length; i++) {
            RpgItem item = new RpgItem(id + "_" + PIECE_IDS[i]);
            item.name = labels[i] + " " + name;
            item.type = PIECES[i];
            item.rarity = rarity;
            item.level = level;
            item.setId = id;
            readClasses(s, item);
            double share = PIECE_SHARE[i];
            total.forEach((stat, v) -> item.stats.put(stat, (double) Math.max(1, Math.round(v * share))));
            item.sell = Math.round(level * 8.0 * (rarity.ordinal() + 1) * share * 4);
            items.put(item.id, item);
        }
    }

    private RpgItem loadItem(String id, ConfigurationSection s) {
        RpgItem item = new RpgItem(id);
        item.name = s.getString("name", id);
        item.type = parseEnum(ItemType.class, s.getString("type"), null);
        if (item.type == null) {
            throw new IllegalArgumentException("neznámý type: " + s.getString("type"));
        }
        item.rarity = parseEnum(Rarity.class, s.getString("rarity"), Rarity.COMMON);
        item.level = s.getInt("level", 1);
        item.glint = s.getBoolean("glint");
        item.price = s.getLong("price");
        item.sell = s.getLong("sell");
        item.boss = s.getString("boss");
        item.lore.addAll(s.getStringList("lore"));
        item.stats.putAll(readStats(s.getConfigurationSection("stats")));
        readClasses(s, item);

        ConfigurationSection a = s.getConfigurationSection("ability");
        if (a != null) {
            RpgItem.AbilityType type = parseEnum(RpgItem.AbilityType.class, a.getString("type"), null);
            if (type == null) {
                throw new IllegalArgumentException("neznámý typ schopnosti: " + a.getString("type"));
            }
            item.ability = new RpgItem.Ability(type,
                    Element.parse(a.getString("element"), Element.PHYSICAL),
                    a.getString("name", "Schopnost"),
                    a.getDouble("power", 1.0),
                    a.getDouble("range", type == RpgItem.AbilityType.BOLT ? 24 : 6),
                    a.getDouble("radius", 0),
                    a.getDouble("mana", 0),
                    a.getDouble("cooldown", 1),
                    a.getBoolean("pull"),
                    a.getBoolean("pierce"),
                    a.getDouble("drain", 0));
        }
        ConfigurationSection h = s.getConfigurationSection("on-hit");
        if (h != null) {
            item.onHit = new RpgItem.OnHit(Element.parse(h.getString("element"), Element.PHYSICAL), h.getDouble("chance", 100));
        }
        ConfigurationSection u = s.getConfigurationSection("use");
        if (u != null) {
            RpgItem.UseType type = parseEnum(RpgItem.UseType.class, u.getString("type"), null);
            if (type == null) {
                throw new IllegalArgumentException("neznámý typ použití: " + u.getString("type"));
            }
            Stat stat = u.isString("stat") ? Stat.byKey(u.getString("stat", "")) : null;
            item.use = new RpgItem.Use(type, stat, u.getDouble("amount", 0), u.getInt("duration", 0));
        }
        return item;
    }

    private static void readClasses(ConfigurationSection s, RpgItem item) {
        for (String c : s.getStringList("classes")) {
            RpgClass rc = parseEnum(RpgClass.class, c, null);
            if (rc != null) {
                item.classes.add(rc);
            }
        }
    }

    public static Map<Stat, Double> readStats(ConfigurationSection s) {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        if (s != null) {
            for (String key : s.getKeys(false)) {
                Stat stat = Stat.byKey(key);
                if (stat != null) {
                    out.put(stat, s.getDouble(key));
                }
            }
        }
        return out;
    }

    public static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E def) {
        if (value == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return def;
        }
    }

    // ------------------------------------------------------------------ dotazy

    public RpgItem byId(String id) {
        return id == null ? null : items.get(id);
    }

    public Collection<RpgItem> all() {
        return items.values();
    }

    public ArmorSet set(String id) {
        return id == null ? null : sets.get(id);
    }

    public String idOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        return stack.getPersistentDataContainer().get(Keys.ITEM, PersistentDataType.STRING);
    }

    public RpgItem get(ItemStack stack) {
        return byId(idOf(stack));
    }

    public int upgradeOf(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return 0;
        }
        Integer v = stack.getPersistentDataContainer().get(Keys.UPGRADE, PersistentDataType.INTEGER);
        return v == null ? 0 : v;
    }

    /** Hodnota statu včetně bonusu za vylepšení. */
    public double value(RpgItem def, Stat stat, int upgrade) {
        Double base = def.stats.get(stat);
        return base == null ? 0 : base * (1 + upgradeBonus * upgrade);
    }

    public double value(ItemStack stack, RpgItem def, Stat stat) {
        return value(def, stat, upgradeOf(stack));
    }

    public void addStats(Map<Stat, Double> totals, ItemStack stack, RpgItem def) {
        int upgrade = upgradeOf(stack);
        for (Stat stat : def.stats.keySet()) {
            totals.merge(stat, value(def, stat, upgrade), Double::sum);
        }
    }

    // ------------------------------------------------------------------ výroba ItemStacků

    public ItemStack create(String id, int amount) {
        RpgItem def = items.get(id);
        return def == null ? null : create(def, amount, 0);
    }

    public ItemStack create(RpgItem def, int amount, int upgrade) {
        ItemStack stack = new ItemStack(def.type.material, Math.max(1, amount));
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer().set(Keys.ITEM, PersistentDataType.STRING, def.id);
            if (upgrade > 0) {
                meta.getPersistentDataContainer().set(Keys.UPGRADE, PersistentDataType.INTEGER, upgrade);
            }
            String suffix = upgrade > 0 ? " +" + upgrade : "";
            meta.itemName(Text.plain(def.name + suffix, def.rarity.color));
            meta.lore(lore(def, upgrade));
            meta.setItemModel(Keys.pack(def.id));
            meta.setMaxStackSize(def.type.maxStack);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
            if (def.glint || upgrade >= 7) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (def.type.isWeapon()) {
                meta.setUnbreakable(true);
                if (def.type != ItemType.BOW) {
                    // Útok pěstí má základ 1, proto -1. Hole a hůlky nablízko skoro nezraní.
                    double dmg = def.type.kind == ItemType.Kind.MELEE ? value(def, Stat.DAMAGE, upgrade) : 2.0;
                    meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                            key("weapon_damage"), dmg - 1.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
                    meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                            key("weapon_speed"), def.type.attackSpeed, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
                }
            } else if (def.type.isArmor()) {
                meta.setUnbreakable(true);
                dressAsArmor(meta, def.type.slot(), def.setId);
            }
        });
        return stack;
    }

    /** Kus kostýmu pro moba/bosse - jen vzhled, žádné staty. */
    public ItemStack costume(String setId, EquipmentSlot slot) {
        Material material = switch (slot) {
            case HEAD -> Material.IRON_HELMET;
            case CHEST -> Material.IRON_CHESTPLATE;
            case LEGS -> Material.IRON_LEGGINGS;
            default -> Material.IRON_BOOTS;
        };
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.setUnbreakable(true);
            dressAsArmor(meta, slot, setId);
        });
        return stack;
    }

    private void dressAsArmor(ItemMeta meta, EquipmentSlot slot, String setId) {
        // Nulový modifikátor přepíše vanilla brnění železa - obranu počítá plugin sám.
        meta.addAttributeModifier(Attribute.ARMOR, new AttributeModifier(
                key("armor_" + slot.name().toLowerCase(Locale.ROOT)), 0, AttributeModifier.Operation.ADD_NUMBER, slot.getGroup()));
        if (setId != null) {
            EquippableComponent eq = meta.getEquippable();
            eq.setSlot(slot);
            eq.setModel(Keys.pack(setId));
            meta.setEquippable(eq);
        }
    }

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }

    // ------------------------------------------------------------------ popisky

    private static final TextColor GRAY = NamedTextColor.GRAY;
    private static final TextColor DARK = NamedTextColor.DARK_GRAY;

    public List<Component> lore(RpgItem def, int upgrade) {
        List<Component> out = new ArrayList<>();
        out.add(Text.plain(def.type.label + " • " + def.rarity.label, DARK));

        if (!def.stats.isEmpty()) {
            out.add(Component.empty());
            for (Stat stat : Stat.values()) {
                if (!def.stats.containsKey(stat)) {
                    continue;
                }
                double v = value(def, stat, upgrade);
                String sign = v >= 0 ? "+" : "";
                out.add(Text.plain(stat.label + ": ", GRAY)
                        .append(Text.plain(sign + Text.num(v) + (stat.percent ? " %" : ""), stat.color)));
            }
        }
        if (def.onHit != null) {
            out.add(Component.empty());
            out.add(Text.plain("Při zásahu: ", GRAY)
                    .append(Text.plain(def.onHit.element().label + " (" + Text.num(def.onHit.chance()) + " %)", def.onHit.element().color())));
        }
        if (def.ability != null) {
            RpgItem.Ability a = def.ability;
            out.add(Component.empty());
            out.add(Text.plain("Schopnost: ", NamedTextColor.GOLD)
                    .append(Text.plain(a.name(), a.element().color()))
                    .append(Text.plain("  PRAVÝ KLIK", NamedTextColor.YELLOW)));
            out.add(Text.plain(describe(a), GRAY));
            out.add(Text.plain("Mana: " + Text.num(a.mana()) + " • Čekání: " + Text.num(a.cooldown()) + " s", DARK));
        }
        if (def.use != null) {
            out.add(Component.empty());
            out.add(Text.plain(describe(def.use), NamedTextColor.GREEN));
            out.add(Text.plain("Pravý klik pro použití", DARK));
        }
        if (def.type == ItemType.SUMMON) {
            out.add(Component.empty());
            out.add(Text.plain("Pravým klikem na zem vyvoláš bosse.", NamedTextColor.RED));
        }
        if (def.type == ItemType.CRATE) {
            out.add(Component.empty());
            out.add(Text.plain("Pravým klikem otevřeš.", NamedTextColor.YELLOW));
        }
        if (def.type == ItemType.ACCESSORY) {
            out.add(Component.empty());
            out.add(Text.plain("Nasaď v /rpg → Doplňky (nebo pravý klik)", DARK));
        }
        ArmorSet set = sets.get(def.setId);
        if (set != null && !set.fullSet().isEmpty()) {
            out.add(Component.empty());
            out.add(Text.plain("Bonus celého setu (4 kusy):", NamedTextColor.GOLD));
            set.fullSet().forEach((stat, v) -> out.add(
                    Text.plain(" +" + Text.num(v) + (stat.percent ? " % " : " ") + stat.label, stat.color)));
        }
        if (def.level > 1 || !def.classes.isEmpty()) {
            out.add(Component.empty());
            if (def.level > 1) {
                out.add(Text.plain("Vyžaduje úroveň ", GRAY).append(Text.plain(String.valueOf(def.level), NamedTextColor.WHITE)));
            }
            if (!def.classes.isEmpty()) {
                List<String> names = def.classes.stream().map(c -> c.label).toList();
                out.add(Text.plain("Třída: ", GRAY).append(Text.plain(String.join(", ", names), NamedTextColor.WHITE)));
            }
        }
        if (!def.lore.isEmpty()) {
            out.add(Component.empty());
            for (String line : def.lore) {
                out.add(Component.text(line, TextColor.color(0x7788AA)));   // kurzíva = "příběhový" text
            }
        }
        return out;
    }

    private static String describe(RpgItem.Ability a) {
        String pct = Text.num(a.power() * 100) + " %";
        return switch (a.type()) {
            case BOLT -> "Střela za " + pct + " poškození"
                    + (a.radius() > 0 ? ", výbuch " + Text.num(a.radius()) + " bloků" : "")
                    + (a.pierce() ? ", prochází nepřáteli" : "")
                    + (a.drain() > 0 ? ", léčí tě" : "") + ".";
            case CONE -> "Vlna před tebou za " + pct + " poškození" + (a.drain() > 0 ? ", léčí tě" : "") + ".";
            case NOVA -> "Zasáhne vše do " + Text.num(a.radius()) + " bloků za " + pct + (a.pull() ? ", přitáhne nepřátele" : "") + ".";
            case STRIKE -> "Udeří na cíl, na který míříš, za " + pct + ".";
            case DASH -> "Výpad vpřed, zraní nepřátele v cestě za " + pct + ".";
            case HEAL -> "Vyléčí tebe i spojence v okolí o " + Text.num(a.power()) + " zdraví.";
        };
    }

    private static String describe(RpgItem.Use u) {
        return switch (u.type()) {
            case HEAL -> "Obnoví " + Text.num(u.amount()) + " zdraví";
            case MANA -> "Obnoví " + Text.num(u.amount()) + " many";
            case BUFF -> "+" + Text.num(u.amount()) + " " + (u.stat() == null ? "" : u.stat().label) + " na " + u.duration() + " s";
            case FOOD -> "Zasytí a obnoví " + Text.num(u.amount()) + " zdraví";
            case XP -> "Přidá " + Text.num(u.amount()) + " zkušeností";
            case TELEPORT -> "Teleportuje tě na spawn";
            case RESET_STATS -> "Vrátí rozdělené body atributů";
        };
    }

    // ------------------------------------------------------------------ práce s inventářem
    //  "spec" = id vlastního předmětu, nebo název vanilla materiálu (ARROW, DIAMOND...)

    public ItemStack parse(String spec, int amount) {
        ItemStack custom = create(spec, amount);
        if (custom != null) {
            return custom;
        }
        Material m = Material.matchMaterial(spec);
        return m == null || !m.isItem() ? null : new ItemStack(m, Math.max(1, amount));
    }

    public Component displayName(String spec) {
        RpgItem def = items.get(spec);
        if (def != null) {
            return Text.plain(def.name, def.rarity.color);
        }
        Material m = Material.matchMaterial(spec);
        return m == null ? Text.plain(spec, NamedTextColor.RED)
                : Component.translatable(m.translationKey(), NamedTextColor.WHITE).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    private boolean matches(ItemStack stack, String spec) {
        if (stack == null || stack.getType().isAir()) {
            return false;
        }
        String id = idOf(stack);
        if (items.containsKey(spec)) {
            return spec.equals(id);
        }
        Material m = Material.matchMaterial(spec);
        return id == null && m != null && stack.getType() == m;
    }

    public int count(Player player, String spec) {
        int n = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (matches(stack, spec)) {
                n += stack.getAmount();
            }
        }
        return n;
    }

    /** Odebere {@code amount} kusů. Volat až po kontrole {@link #count}. */
    public void take(Player player, String spec, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack stack = contents[i];
            if (!matches(stack, spec)) {
                continue;
            }
            int take = Math.min(amount, stack.getAmount());
            amount -= take;
            if (take == stack.getAmount()) {
                contents[i] = null;
            } else {
                stack.setAmount(stack.getAmount() - take);
            }
        }
        player.getInventory().setStorageContents(contents);
    }

    /** Dá předmět hráči; co se nevejde, spadne mu k nohám. */
    public void give(Player player, ItemStack stack) {
        if (stack == null) {
            return;
        }
        for (ItemStack rest : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItem(player.getLocation(), rest);
        }
    }
}
