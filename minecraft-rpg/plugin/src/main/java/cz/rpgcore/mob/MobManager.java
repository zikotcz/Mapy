package cz.rpgcore.mob;

import cz.rpgcore.RPGCore;
import cz.rpgcore.boss.BossDef;
import cz.rpgcore.util.Keys;
import cz.rpgcore.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Vlastní moby: načítání, spawn, přirozené nahrazování a spawnery. */
public final class MobManager implements Listener {
    /** Nad tuhle hodnotu se zdraví počítá "virtuálně" (vanilla strop atributu je 1024). */
    private static final double REAL_HEALTH_CAP = 1000;

    public static final class Spawner {
        public int id;
        public String mob;
        public String world;
        public double x, y, z;
        public double radius;
        public int max;
        public int interval;
        final Set<UUID> alive = new HashSet<>();
        long next;
    }

    private final RPGCore plugin;
    private final Map<String, MobDef> defs = new LinkedHashMap<>();
    private final List<Spawner> spawners = new ArrayList<>();
    private final Set<UUID> aggressive = new HashSet<>();
    private final File spawnerFile;

    public MobManager(RPGCore plugin) {
        this.plugin = plugin;
        this.spawnerFile = new File(plugin.getDataFolder(), "spawners.yml");
    }

    public void load() {
        defs.clear();
        YamlConfiguration cfg = plugin.loadYaml("mobs.yml");
        for (String id : cfg.getKeys(false)) {
            ConfigurationSection s = cfg.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            try {
                defs.put(id, MobDef.parse(id, s));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("mobs.yml: mob '" + id + "' přeskočen - " + ex.getMessage());
            }
        }
        plugin.getLogger().info("Načteno " + defs.size() + " mobů.");
    }

    public void start() {
        loadSpawners();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    public MobDef def(String id) {
        return defs.get(id);
    }

    public Collection<String> ids() {
        return defs.keySet();
    }

    // ------------------------------------------------------------------ dotazy na entity

    public boolean isCustom(Entity e) {
        return e != null && e.getPersistentDataContainer().has(Keys.HP_SCALE, PersistentDataType.DOUBLE);
    }

    public boolean isMinion(Entity e) {
        return e.getPersistentDataContainer().has(Keys.MINION, PersistentDataType.BYTE);
    }

    /** Definice obyčejného vlastního moba (pro bosse vrací null). */
    public MobDef of(Entity e) {
        if (e == null) {
            return null;
        }
        String id = e.getPersistentDataContainer().get(Keys.MOB, PersistentDataType.STRING);
        return id == null ? null : defs.get(id);
    }

    public double hpScale(Entity e) {
        Double v = e.getPersistentDataContainer().get(Keys.HP_SCALE, PersistentDataType.DOUBLE);
        return v == null ? 1.0 : v;
    }

    public double damageOf(Entity e) {
        Double v = e.getPersistentDataContainer().get(Keys.MOB_DAMAGE, PersistentDataType.DOUBLE);
        return v == null ? 1.0 : v;
    }

    public void setDamage(LivingEntity e, double damage) {
        e.getPersistentDataContainer().set(Keys.MOB_DAMAGE, PersistentDataType.DOUBLE, damage);
        setAttr(e, Attribute.ATTACK_DAMAGE, damage);
    }

    /** Skutečné ("virtuální") zdraví moba. */
    public double health(LivingEntity e) {
        return e.getHealth() * hpScale(e);
    }

    // ------------------------------------------------------------------ spawn

    public LivingEntity spawn(String id, Location at) {
        MobDef def = defs.get(id);
        if (def == null || at.getWorld() == null) {
            return null;
        }
        return spawn(def, at, false);
    }

    @SuppressWarnings("unchecked")
    public LivingEntity spawn(MobDef def, Location at, boolean boss) {
        Class<? extends LivingEntity> type = (Class<? extends LivingEntity>) def.type.getEntityClass();
        return at.getWorld().spawn(at, type, e -> apply(e, def, boss));
    }

    /** Udělá z entity vlastního moba: zdraví, poškození, kostým, jmenovka. */
    public void apply(LivingEntity e, MobDef def, boolean boss) {
        PersistentDataContainer pdc = e.getPersistentDataContainer();
        if (!boss) {
            pdc.set(Keys.MOB, PersistentDataType.STRING, def.id);
        }
        double real = Math.min(def.health, REAL_HEALTH_CAP);
        pdc.set(Keys.HP_SCALE, PersistentDataType.DOUBLE, def.health / real);
        setAttr(e, Attribute.MAX_HEALTH, real);
        AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
        e.setHealth(max == null ? real : Math.min(real, max.getValue()));
        setDamage(e, def.damage);

        AttributeInstance speed = e.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null && def.speed != 1) {
            speed.setBaseValue(speed.getBaseValue() * def.speed);
        }
        if (def.scale != 1) {
            setAttr(e, Attribute.SCALE, def.scale);
        }
        if (e instanceof Ageable ageable) {
            ageable.setAdult();
        }
        if (e instanceof Wolf wolf && def.aggressive) {
            wolf.setAngry(true);
        }
        if (def.aggressive) {
            aggressive.add(e.getUniqueId());
        }
        if (def.fireproof) {
            e.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        }
        e.setCanPickupItems(false);

        EntityEquipment eq = e.getEquipment();
        if (eq != null) {
            if (def.costume != null) {
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    eq.setItem(slot, plugin.items().costume(def.costume, slot));
                    eq.setDropChance(slot, 0f);
                }
            }
            if (def.weapon != null) {
                ItemStack weapon = plugin.items().parse(def.weapon, 1);
                if (weapon != null) {
                    eq.setItemInMainHand(weapon);
                    eq.setItemInMainHandDropChance(0f);
                }
            } else if (def.costume != null && eq.getItemInMainHand().getType() != Material.BOW) {
                eq.setItemInMainHand(null);
            }
        }
        e.setCustomNameVisible(true);
        e.customName(nameTag(def, def.health, boss));
    }

    private static void setAttr(LivingEntity e, Attribute attribute, double value) {
        AttributeInstance inst = e.getAttribute(attribute);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    private static Component nameTag(MobDef def, double health, boolean boss) {
        Component name = boss
                ? Component.text(def.name, TextColor.color(0xFF5555), TextDecoration.BOLD)
                : Component.text(def.name, NamedTextColor.WHITE);
        return Component.text("[Lv." + def.level + "] ", NamedTextColor.GRAY)
                .append(name)
                .append(Component.text(" " + Text.num(Math.ceil(health)) + "❤", TextColor.color(0xFF5566)));
    }

    public void updateName(LivingEntity e) {
        if (!e.isValid() || e.isDead()) {
            return;
        }
        MobDef def = of(e);
        boolean boss = false;
        if (def == null) {
            String bossId = e.getPersistentDataContainer().get(Keys.BOSS, PersistentDataType.STRING);
            BossDef bd = bossId == null ? null : plugin.bosses().def(bossId);
            if (bd == null) {
                return;
            }
            def = bd.mob;
            boss = true;
        }
        e.customName(nameTag(def, health(e), boss));
    }

    // ------------------------------------------------------------------ přirozený spawn

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                || !plugin.getConfig().getBoolean("mobs.natural-spawn", true)) {
            return;
        }
        World world = e.getLocation().getWorld();
        List<String> worlds = plugin.getConfig().getStringList("mobs.worlds");
        if (world == null || (!worlds.isEmpty() && !worlds.contains(world.getName()))) {
            return;
        }
        Location spawn = world.getSpawnLocation();
        double dx = e.getLocation().getX() - spawn.getX();
        double dz = e.getLocation().getZ() - spawn.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        List<MobDef> candidates = new ArrayList<>();
        int total = 0;
        for (MobDef def : defs.values()) {
            if (def.natural && def.type == e.getEntityType() && distance >= def.minDistance && distance <= def.maxDistance) {
                candidates.add(def);
                total += def.weight;
            }
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (candidates.isEmpty() || r.nextDouble(100) >= plugin.getConfig().getDouble("mobs.natural-chance", 70)) {
            return;
        }
        int pick = r.nextInt(total);
        for (MobDef def : candidates) {
            pick -= def.weight;
            if (pick < 0) {
                apply(e.getEntity(), def, false);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ spawnery

    private void loadSpawners() {
        spawners.clear();
        if (!spawnerFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(spawnerFile);
        for (Map<?, ?> m : y.getMapList("spawners")) {
            try {
                Spawner s = new Spawner();
                s.id = ((Number) m.get("id")).intValue();
                s.mob = String.valueOf(m.get("mob"));
                s.world = String.valueOf(m.get("world"));
                s.x = ((Number) m.get("x")).doubleValue();
                s.y = ((Number) m.get("y")).doubleValue();
                s.z = ((Number) m.get("z")).doubleValue();
                s.radius = ((Number) m.get("radius")).doubleValue();
                s.max = ((Number) m.get("max")).intValue();
                s.interval = ((Number) m.get("interval")).intValue();
                spawners.add(s);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("spawners.yml: neplatný záznam přeskočen.");
            }
        }
    }

    private void saveSpawners() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Spawner s : spawners) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id);
            m.put("mob", s.mob);
            m.put("world", s.world);
            m.put("x", s.x);
            m.put("y", s.y);
            m.put("z", s.z);
            m.put("radius", s.radius);
            m.put("max", s.max);
            m.put("interval", s.interval);
            list.add(m);
        }
        y.set("spawners", list);
        try {
            y.save(spawnerFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nelze uložit spawners.yml: " + ex.getMessage());
        }
    }

    public List<Spawner> spawners() {
        return spawners;
    }

    public Spawner addSpawner(String mob, Location at, double radius, int max, int interval) {
        Spawner s = new Spawner();
        s.id = spawners.stream().mapToInt(x -> x.id).max().orElse(0) + 1;
        s.mob = mob;
        s.world = at.getWorld().getName();
        s.x = at.getX();
        s.y = at.getY();
        s.z = at.getZ();
        s.radius = radius;
        s.max = max;
        s.interval = interval;
        spawners.add(s);
        saveSpawners();
        return s;
    }

    public boolean removeSpawner(int id) {
        Spawner found = spawners.stream().filter(s -> s.id == id).findFirst().orElse(null);
        if (found == null) {
            return false;
        }
        for (UUID uuid : found.alive) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null) {
                e.remove();
            }
        }
        spawners.remove(found);
        saveSpawners();
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (Spawner s : spawners) {
            World world = Bukkit.getWorld(s.world);
            if (world == null || !world.isChunkLoaded((int) Math.floor(s.x) >> 4, (int) Math.floor(s.z) >> 4)) {
                continue;
            }
            Location center = new Location(world, s.x, s.y, s.z);
            boolean playerNear = false;
            for (Player p : world.getPlayers()) {
                if (p.getGameMode() != GameMode.SPECTATOR && p.getLocation().distanceSquared(center) <= 48 * 48) {
                    playerNear = true;
                    break;
                }
            }
            if (!playerNear) {
                continue;
            }
            s.alive.removeIf(uuid -> {
                Entity e = Bukkit.getEntity(uuid);
                return e == null || e.isDead() || !e.isValid();
            });
            if (s.alive.size() >= s.max || now < s.next) {
                continue;
            }
            s.next = now + s.interval * 1000L;
            Location at = ground(center.clone().add(r.nextDouble(-s.radius, s.radius + 0.01), 0, r.nextDouble(-s.radius, s.radius + 0.01)));
            LivingEntity e = spawn(s.mob, at);
            if (e != null) {
                e.setPersistent(false);     // po odchodu hráčů zmizí, spawner je vytvoří znovu
                s.alive.add(e.getUniqueId());
            }
        }
        // moby označené aggressive (např. vlci) si samy hledají cíl
        aggressive.removeIf(uuid -> {
            Entity e = Bukkit.getEntity(uuid);
            if (e == null || e.isDead() || !e.isValid()) {
                return true;
            }
            if (e instanceof Mob mob && mob.getTarget() == null) {
                Player target = nearestPlayer(e.getLocation(), 14);
                if (target != null) {
                    if (mob instanceof Wolf wolf) {
                        wolf.setAngry(true);
                    }
                    mob.setTarget(target);
                }
            }
            return false;
        });
    }

    /** Najde zem pod (nebo nad) daným místem, aby se mob nespawnul ve vzduchu ani v bloku. */
    public static Location ground(Location at) {
        Location l = at.clone().add(0, 3, 0);
        for (int i = 0; i < 10; i++) {
            if (l.getBlock().isPassable() && !l.clone().subtract(0, 1, 0).getBlock().isPassable()) {
                return l;
            }
            l.subtract(0, 1, 0);
        }
        return at;
    }

    public static Player nearestPlayer(Location at, double range) {
        Player best = null;
        double bestDist = range * range;
        if (at.getWorld() == null) {
            return null;
        }
        for (Player p : at.getWorld().getPlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) {
                continue;
            }
            double d = p.getLocation().distanceSquared(at);
            if (d <= bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }
}
