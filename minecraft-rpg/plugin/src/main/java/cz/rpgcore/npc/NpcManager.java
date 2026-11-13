package cz.rpgcore.npc;

import cz.rpgcore.RPGCore;
import cz.rpgcore.gui.BlacksmithMenu;
import cz.rpgcore.gui.ClassMenu;
import cz.rpgcore.gui.QuestMenu;
import cz.rpgcore.gui.ShopMenu;
import cz.rpgcore.item.ItemManager;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.player.PlayerManager;
import cz.rpgcore.util.Element;
import cz.rpgcore.util.Keys;
import cz.rpgcore.util.Text;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NPC postavy. Jsou to entity Mannequin (vypadají jako hráč) se skinem z resource packu.
 * Entity se neukládají do světa - plugin je vytváří sám podle npcs-placed.yml,
 * takže po pádu serveru nevzniknou duplikáty.
 */
public final class NpcManager implements Listener {
    public enum Role {QUEST, SHOP, BLACKSMITH, TRAINER, HEALER}

    public record Template(String id, String name, String title, Role role, String skin, String shop, List<String> lines) {
    }

    public static final class Placed {
        public int id;
        public String template;
        public String world;
        public double x, y, z;
        public float yaw;
        UUID entity;
    }

    private final RPGCore plugin;
    private final Map<String, Template> templates = new LinkedHashMap<>();
    private final List<Placed> placed = new ArrayList<>();
    private final File file;
    private int tick;

    public NpcManager(RPGCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs-placed.yml");
    }

    public void load() {
        templates.clear();
        YamlConfiguration cfg = plugin.loadYaml("npcs.yml");
        for (String id : cfg.getKeys(false)) {
            ConfigurationSection s = cfg.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Role role = ItemManager.parseEnum(Role.class, s.getString("role"), null);
            if (role == null) {
                plugin.getLogger().warning("npcs.yml: NPC '" + id + "' má neznámou roli, přeskočeno.");
                continue;
            }
            templates.put(id, new Template(id, s.getString("name", id), s.getString("title", ""), role,
                    s.getString("skin", "elder"), s.getString("shop"), s.getStringList("lines")));
        }
        // po reloadu se NPC vytvoří znovu s novým jménem/skinem
        removeEntities();
    }

    public void start() {
        loadPlaced();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    public void shutdown() {
        removeEntities();
    }

    private void removeEntities() {
        for (Placed p : placed) {
            Entity e = p.entity == null ? null : Bukkit.getEntity(p.entity);
            if (e != null) {
                e.remove();
            }
            p.entity = null;
        }
    }

    public Collection<String> templateIds() {
        return templates.keySet();
    }

    public Template template(String id) {
        return templates.get(id);
    }

    public List<Placed> placed() {
        return placed;
    }

    public boolean isNpc(Entity e) {
        return e != null && e.getPersistentDataContainer().has(Keys.NPC, PersistentDataType.INTEGER);
    }

    // ------------------------------------------------------------------ umístěná NPC

    private void loadPlaced() {
        placed.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : y.getMapList("npcs")) {
            try {
                Placed p = new Placed();
                p.id = ((Number) m.get("id")).intValue();
                p.template = String.valueOf(m.get("template"));
                p.world = String.valueOf(m.get("world"));
                p.x = ((Number) m.get("x")).doubleValue();
                p.y = ((Number) m.get("y")).doubleValue();
                p.z = ((Number) m.get("z")).doubleValue();
                p.yaw = ((Number) m.get("yaw")).floatValue();
                placed.add(p);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("npcs-placed.yml: neplatný záznam přeskočen.");
            }
        }
    }

    private void savePlaced() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Placed p : placed) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id);
            m.put("template", p.template);
            m.put("world", p.world);
            m.put("x", p.x);
            m.put("y", p.y);
            m.put("z", p.z);
            m.put("yaw", p.yaw);
            list.add(m);
        }
        y.set("npcs", list);
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nelze uložit npcs-placed.yml: " + ex.getMessage());
        }
    }

    public Placed place(String templateId, Location at) {
        Placed p = new Placed();
        p.id = placed.stream().mapToInt(x -> x.id).max().orElse(0) + 1;
        p.template = templateId;
        p.world = at.getWorld().getName();
        p.x = at.getX();
        p.y = at.getY();
        p.z = at.getZ();
        p.yaw = at.getYaw();
        placed.add(p);
        savePlaced();
        ensure(p);
        return p;
    }

    public boolean remove(int id) {
        Placed found = placed.stream().filter(p -> p.id == id).findFirst().orElse(null);
        if (found == null) {
            return false;
        }
        Entity e = found.entity == null ? null : Bukkit.getEntity(found.entity);
        if (e != null) {
            e.remove();
        }
        placed.remove(found);
        savePlaced();
        return true;
    }

    /** Nejbližší umístěné NPC do dané vzdálenosti. */
    public Placed nearest(Location at, double range) {
        Placed best = null;
        double bestDist = range * range;
        for (Placed p : placed) {
            if (!p.world.equals(at.getWorld().getName())) {
                continue;
            }
            double d = at.distanceSquared(new Location(at.getWorld(), p.x, p.y, p.z));
            if (d <= bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** Vytvoří entitu NPC, pokud ještě neexistuje a její chunk je načtený. */
    private void ensure(Placed p) {
        Entity existing = p.entity == null ? null : Bukkit.getEntity(p.entity);
        if (existing != null && existing.isValid()) {
            return;
        }
        Template t = templates.get(p.template);
        World world = Bukkit.getWorld(p.world);
        if (t == null || world == null || !world.isChunkLoaded((int) Math.floor(p.x) >> 4, (int) Math.floor(p.z) >> 4)) {
            return;
        }
        Location at = new Location(world, p.x, p.y, p.z, p.yaw, 0);
        Mannequin npc = world.spawn(at, Mannequin.class, m -> {
            m.setProfile(ResolvableProfile.resolvableProfile()
                    .skinPatch(skin -> skin.body(Key.key(Keys.PACK, "entity/npc/" + t.skin())))
                    .build());
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setPersistent(false);
            m.setSilent(true);
            m.customName(Text.plain(t.name(), TextColor.color(0xFFD25A)));
            m.setCustomNameVisible(true);
            m.setDescription(t.title().isEmpty() ? null : Text.plain(t.title(), NamedTextColor.GRAY));
            m.getPersistentDataContainer().set(Keys.NPC, PersistentDataType.INTEGER, p.id);
        });
        p.entity = npc.getUniqueId();
    }

    private void tick() {
        boolean check = ++tick % 4 == 0;
        for (Placed p : placed) {
            if (check) {
                ensure(p);
            }
            Entity e = p.entity == null ? null : Bukkit.getEntity(p.entity);
            if (e == null || !e.isValid()) {
                continue;
            }
            // NPC se otáčí za nejbližším hráčem
            Player look = null;
            double best = 7 * 7;
            for (Player pl : e.getWorld().getPlayers()) {
                double d = pl.getLocation().distanceSquared(e.getLocation());
                if (d < best && pl.getGameMode() != GameMode.SPECTATOR) {
                    best = d;
                    look = pl;
                }
            }
            if (look != null) {
                Location dir = e.getLocation().setDirection(look.getEyeLocation().toVector()
                        .subtract(e.getLocation().add(0, 1.6, 0).toVector()));
                e.setRotation(dir.getYaw(), Math.max(-40, Math.min(40, dir.getPitch())));
            } else {
                e.setRotation(p.yaw, 0);
            }
        }
    }

    // ------------------------------------------------------------------ interakce

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        Entity clicked = e.getRightClicked();
        if (!isNpc(clicked)) {
            return;
        }
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Integer id = clicked.getPersistentDataContainer().get(Keys.NPC, PersistentDataType.INTEGER);
        Placed p = placed.stream().filter(x -> id != null && x.id == id).findFirst().orElse(null);
        Template t = p == null ? null : templates.get(p.template);
        Player player = e.getPlayer();
        PlayerData d = plugin.players().get(player);
        if (t == null || d == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - d.lastClick < 300) {
            return;
        }
        d.lastClick = now;

        plugin.quests().onTalk(player, t.id());
        if (!t.lines().isEmpty()) {
            String line = t.lines().get(ThreadLocalRandom.current().nextInt(t.lines().size()));
            player.sendMessage(Text.mm("<gold>" + Text.esc(t.name()) + "<dark_gray>: <white>" + Text.esc(line)));
        }
        player.playSound(clicked.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.7f, 1f);
        switch (t.role()) {
            case QUEST -> new QuestMenu(plugin, player, t).open();
            case SHOP -> {
                if (plugin.content().shop(t.shop()) == null) {
                    Text.send(player, "<red>Tento obchodník nemá nastavený obchod.");
                } else {
                    new ShopMenu(plugin, player, plugin.content().shop(t.shop())).open();
                }
            }
            case BLACKSMITH -> new BlacksmithMenu(plugin, player).open();
            case TRAINER -> new ClassMenu(plugin, player).open();
            case HEALER -> heal(player, d);
        }
    }

    private void heal(Player player, PlayerData d) {
        if (player.getHealth() >= d.maxHealth - 0.5 && d.mana >= d.maxMana - 0.5) {
            Text.send(player, "<gray>Jsi úplně v pořádku.");
            return;
        }
        long cost = (long) d.level * plugin.getConfig().getLong("npcs.heal-cost-per-level", 2);
        if (d.coins < cost) {
            Text.send(player, "<red>Léčení stojí <gold>" + cost + " mincí<red>, tolik nemáš.");
            return;
        }
        d.coins -= cost;
        player.setHealth(PlayerManager.clampHealth(player, d.maxHealth));
        d.mana = d.maxMana;
        player.setFireTicks(0);
        Element.HOLY.burst(player.getLocation().add(0, 1, 0), 30, 0.5);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f);
        Text.send(player, "<green>Jsi vyléčen." + (cost > 0 ? " <gray>(-" + cost + " mincí)" : ""));
    }
}
