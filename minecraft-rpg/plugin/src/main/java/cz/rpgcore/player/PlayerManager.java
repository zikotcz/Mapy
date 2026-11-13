package cz.rpgcore.player;

import cz.rpgcore.RPGCore;
import cz.rpgcore.item.ItemManager;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.item.Stat;
import cz.rpgcore.util.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Data hráčů: načítání, ukládání, úrovně, přepočet statů a HUD. */
public final class PlayerManager implements Listener {
    /** Vanilla strop atributu max_health. */
    private static final double HEALTH_CAP = 1024;
    private static final Key ICON_FONT = Key.key("rpg", "icons");

    private final RPGCore plugin;
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private final File dir;
    private int tick;

    public PlayerManager(RPGCore plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "players");
    }

    public void start() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            join(p);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveAll, 6000L, 6000L);
    }

    public void shutdown() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            quit(p);
        }
        data.clear();
    }

    public PlayerData get(Player player) {
        return data.get(player.getUniqueId());
    }

    // ------------------------------------------------------------------ připojení / odpojení

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        join(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        quit(e.getPlayer());
        data.remove(e.getPlayer().getUniqueId());
    }

    private void join(Player p) {
        PlayerData d = load(p.getUniqueId());
        data.put(p.getUniqueId(), d);
        recalc(p);
        if (!p.isDead()) {
            p.setHealth(clampHealth(p, d.healthFraction * d.maxHealth));
        }
    }

    private void quit(Player p) {
        PlayerData d = data.get(p.getUniqueId());
        if (d == null) {
            return;
        }
        d.healthFraction = p.isDead() || p.getHealth() <= 0 ? 1.0 : Math.min(1.0, p.getHealth() / d.maxHealth);
        save(d);
        // Vrátíme vanilla hodnoty, aby po odebrání pluginu nezůstali hráči s upraveným zdravím.
        setBase(p, Attribute.MAX_HEALTH, 20);
        setBase(p, Attribute.MOVEMENT_SPEED, 0.1);
        p.setHealthScaled(false);
    }

    // ------------------------------------------------------------------ ukládání

    private File file(UUID uuid) {
        return new File(dir, uuid + ".yml");
    }

    private PlayerData load(UUID uuid) {
        PlayerData d = new PlayerData(uuid);
        File f = file(uuid);
        if (!f.exists()) {
            return d;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        d.level = Math.max(1, y.getInt("level", 1));
        d.xp = y.getDouble("xp");
        d.rpgClass = ItemManager.parseEnum(RpgClass.class, y.getString("class"), null);
        d.points = y.getInt("points");
        d.mana = y.getDouble("mana", 50);
        d.coins = y.getLong("coins");
        d.healthFraction = y.getDouble("health-fraction", 1.0);
        ConfigurationSection attrs = y.getConfigurationSection("attrs");
        if (attrs != null) {
            for (String key : attrs.getKeys(false)) {
                Stat stat = Stat.byKey(key);
                if (stat != null) {
                    d.attrs.put(stat, attrs.getInt(key));
                }
            }
        }
        ConfigurationSection quests = y.getConfigurationSection("quests");
        if (quests != null) {
            for (String id : quests.getKeys(false)) {
                List<Integer> list = quests.getIntegerList(id);
                d.quests.put(id, list.stream().mapToInt(Integer::intValue).toArray());
            }
        }
        d.completed.addAll(y.getStringList("completed"));
        List<String> acc = y.getStringList("accessories");
        for (int i = 0; i < Math.min(acc.size(), d.accessories.length); i++) {
            if (!acc.get(i).isEmpty()) {
                try {
                    d.accessories[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(acc.get(i)));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Nelze načíst doplněk hráče " + uuid + ": " + ex.getMessage());
                }
            }
        }
        return d;
    }

    public void save(PlayerData d) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("level", d.level);
        y.set("xp", d.xp);
        y.set("class", d.rpgClass == null ? null : d.rpgClass.name());
        y.set("points", d.points);
        y.set("mana", d.mana);
        y.set("coins", d.coins);
        y.set("health-fraction", d.healthFraction);
        d.attrs.forEach((stat, v) -> y.set("attrs." + stat.key(), v));
        d.quests.forEach((id, progress) -> {
            List<Integer> list = new ArrayList<>();
            for (int v : progress) {
                list.add(v);
            }
            y.set("quests." + id, list);
        });
        y.set("completed", new ArrayList<>(d.completed));
        List<String> acc = new ArrayList<>();
        for (ItemStack stack : d.accessories) {
            acc.add(stack == null || stack.getType().isAir() ? "" : Base64.getEncoder().encodeToString(stack.serializeAsBytes()));
        }
        y.set("accessories", acc);
        try {
            y.save(file(d.uuid));
        } catch (IOException ex) {
            plugin.getLogger().severe("Nelze uložit data hráče " + d.uuid + ": " + ex.getMessage());
        }
    }

    public void saveAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = get(p);
            if (d != null) {
                if (!p.isDead() && p.getHealth() > 0) {
                    d.healthFraction = Math.min(1.0, p.getHealth() / d.maxHealth);
                }
                save(d);
            }
        }
    }

    // ------------------------------------------------------------------ úrovně a mince

    public int maxLevel() {
        return plugin.getConfig().getInt("leveling.max-level", 100);
    }

    public double xpNeeded(int level) {
        double base = plugin.getConfig().getDouble("leveling.xp-base", 60);
        double factor = plugin.getConfig().getDouble("leveling.xp-factor", 28);
        double exp = plugin.getConfig().getDouble("leveling.xp-exponent", 1.6);
        return Math.round(base + factor * Math.pow(level, exp));
    }

    public void addXp(Player p, double amount) {
        PlayerData d = get(p);
        if (d == null || amount <= 0 || d.level >= maxLevel()) {
            return;
        }
        d.xp += amount;
        int gained = 0;
        while (d.level < maxLevel() && d.xp >= xpNeeded(d.level)) {
            d.xp -= xpNeeded(d.level);
            d.level++;
            gained++;
        }
        if (d.level >= maxLevel()) {
            d.xp = 0;
        }
        if (gained > 0) {
            levelUp(p, d, gained);
        }
    }

    public void setLevel(Player p, int level) {
        PlayerData d = get(p);
        if (d == null) {
            return;
        }
        int target = Math.max(1, Math.min(maxLevel(), level));
        int diff = target - d.level;
        d.level = target;
        d.xp = 0;
        if (diff > 0) {
            levelUp(p, d, diff);
        } else {
            recalc(p);
        }
    }

    private void levelUp(Player p, PlayerData d, int levels) {
        int points = levels * plugin.getConfig().getInt("leveling.points-per-level", 3);
        d.points += points;
        recalc(p);
        if (!p.isDead()) {
            p.setHealth(clampHealth(p, d.maxHealth));
        }
        d.mana = d.maxMana;
        p.showTitle(Title.title(Text.mm("<gold><bold>ÚROVEŇ " + d.level), Text.mm("<gray>+" + points + " bodů atributů <dark_gray>(/rpg)")));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        Text.send(p, "<gold>Postoupil jsi na úroveň <yellow>" + d.level + "<gold>! <gray>Máš <green>" + d.points + "<gray> volných bodů.");
    }

    public void addCoins(Player p, long amount) {
        PlayerData d = get(p);
        if (d != null) {
            d.coins = Math.max(0, d.coins + amount);
        }
    }

    // ------------------------------------------------------------------ přepočet statů

    public void recalc(Player p) {
        PlayerData d = get(p);
        if (d == null) {
            return;
        }
        ItemManager items = plugin.items();
        Map<Stat, Double> t = d.totals;
        t.clear();
        if (d.rpgClass != null) {
            d.rpgClass.addBonuses(t, d.level);
        }
        d.attrs.forEach((stat, v) -> t.merge(stat, (double) v, Double::sum));

        ItemStack hand = p.getInventory().getItemInMainHand();
        RpgItem weapon = items.get(hand);
        if (weapon != null && weapon.type.isWeapon() && d.meets(weapon)) {
            items.addStats(t, hand, weapon);
        }
        Map<String, Integer> setPieces = new HashMap<>();
        for (ItemStack piece : p.getInventory().getArmorContents()) {
            RpgItem armor = items.get(piece);
            if (armor != null && armor.type.isArmor() && d.meets(armor)) {
                items.addStats(t, piece, armor);
                if (armor.setId != null) {
                    setPieces.merge(armor.setId, 1, Integer::sum);
                }
            }
        }
        setPieces.forEach((setId, count) -> {
            ItemManager.ArmorSet set = items.set(setId);
            if (count >= 4 && set != null) {
                set.fullSet().forEach((stat, v) -> t.merge(stat, v, Double::sum));
            }
        });
        for (ItemStack acc : d.accessories) {
            RpgItem def = items.get(acc);
            if (def != null && d.meets(def)) {
                items.addStats(t, acc, def);
            }
        }
        long now = System.currentTimeMillis();
        d.buffs.forEach((stat, buff) -> {
            if (buff[1] > now) {
                t.merge(stat, buff[0], Double::sum);
            }
        });

        d.maxHealth = Math.min(HEALTH_CAP, 20 + (d.level - 1) * 2.0 + d.stat(Stat.HEALTH) + d.stat(Stat.VITALITY) * 3);
        d.maxMana = 50 + (d.level - 1) * 3.0 + d.stat(Stat.MANA) + d.stat(Stat.INTELLIGENCE) * 4;
        d.mana = Math.min(d.mana, d.maxMana);

        setBase(p, Attribute.MAX_HEALTH, d.maxHealth);
        double speed = Math.min(60, d.stat(Stat.SPEED) + d.stat(Stat.AGILITY) * 0.1);
        setBase(p, Attribute.MOVEMENT_SPEED, 0.1 * (1 + speed / 100.0));
        p.setHealthScale(plugin.getConfig().getDouble("hud.health-scale", 20));
        p.setHealthScaled(true);
    }

    private static void setBase(Player p, Attribute attribute, double value) {
        AttributeInstance inst = p.getAttribute(attribute);
        if (inst != null && Math.abs(inst.getBaseValue() - value) > 1e-6) {
            inst.setBaseValue(value);
        }
    }

    /** setHealth vyhodí výjimku, když hodnota přesáhne maximum - proto vždy přes tohle. */
    public static double clampHealth(Player p, double value) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        double max = inst == null ? 20 : inst.getValue();
        return Math.max(1, Math.min(max, value));
    }

    public void heal(Player p, double amount) {
        if (!p.isDead() && p.getHealth() > 0 && amount > 0) {
            p.setHealth(clampHealth(p, p.getHealth() + amount));
        }
    }

    public boolean spendMana(Player p, PlayerData d, double amount) {
        if (d.mana < amount) {
            notify(p, "<red>Nedostatek many <gray>(" + Text.num(d.mana) + "/" + Text.num(amount) + ")");
            return false;
        }
        d.mana -= amount;
        return true;
    }

    // ------------------------------------------------------------------ HUD

    private void tick() {
        tick++;
        long now = System.currentTimeMillis();
        boolean hud = plugin.getConfig().getBoolean("hud.enabled", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = get(p);
            if (d == null) {
                continue;
            }
            boolean expired = d.buffs.values().removeIf(b -> b[1] <= now);
            if (expired || tick % 2 == 0) {
                recalc(p);
            }
            // mimo boj se zdraví pomalu doplňuje (vanilla regenerace je na velké životy moc slabá)
            if (tick % 2 == 0 && now - d.lastCombat > 6000 && p.getFoodLevel() > 6) {
                heal(p, d.maxHealth * 0.02 + d.stat(Stat.VITALITY) * 0.1);
            }
            double regen = 1 + d.maxMana * 0.01 + d.stat(Stat.MANA_REGEN) + d.stat(Stat.INTELLIGENCE) * 0.05;
            d.mana = Math.min(d.maxMana, d.mana + regen / 2);
            if (hud && now >= d.hudHoldUntil) {
                p.sendActionBar(hud(p, d));
            }
        }
    }

    private Component hud(Player p, PlayerData d) {
        boolean icons = plugin.getConfig().getBoolean("hud.custom-icons", true);
        double xpPct = d.level >= maxLevel() ? 100 : d.xp / xpNeeded(d.level) * 100;
        return Component.empty()
                .append(icon(icons, "", "❤", 0xFF5566))
                .append(Component.text(" " + Text.num(Math.ceil(p.getHealth())) + "/" + Text.num(d.maxHealth), TextColor.color(0xFF5566)))
                .append(Component.text("   "))
                .append(icon(icons, "", "✦", 0x55AAFF))
                .append(Component.text(" " + Text.num(Math.floor(d.mana)) + "/" + Text.num(d.maxMana), TextColor.color(0x55AAFF)))
                .append(Component.text("   "))
                .append(icon(icons, "", "⛨", 0xAAB4C8))
                .append(Component.text(" " + Text.num(d.stat(Stat.DEFENSE)), TextColor.color(0xAAB4C8)))
                .append(Component.text("   "))
                .append(icon(icons, "", "✪", 0x88E070))
                .append(Component.text(" Lv." + d.level + " ", TextColor.color(0x88E070)))
                .append(Component.text(Math.round(xpPct) + "%", NamedTextColor.GRAY));
    }

    private static Component icon(boolean custom, String glyph, String fallback, int rgb) {
        return custom
                ? Component.text(glyph, NamedTextColor.WHITE).font(ICON_FONT)
                : Component.text(fallback, TextColor.color(rgb));
    }

    /** Krátká zpráva nad hotbarem (na chvíli přebije HUD). */
    public void notify(Player p, String miniMessage) {
        PlayerData d = get(p);
        if (d != null) {
            d.hudHoldUntil = System.currentTimeMillis() + 1800;
        }
        p.sendActionBar(Text.mm(miniMessage));
    }
}
