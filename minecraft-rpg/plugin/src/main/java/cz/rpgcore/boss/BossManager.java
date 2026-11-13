package cz.rpgcore.boss;

import cz.rpgcore.RPGCore;
import cz.rpgcore.combat.Telegraph;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.mob.MobDef;
import cz.rpgcore.mob.MobManager;
import cz.rpgcore.util.Element;
import cz.rpgcore.util.Fx;
import cz.rpgcore.util.Keys;
import cz.rpgcore.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Bossové: spawn, boss bar, fáze, schopnosti, odměny a arény s obnovováním. */
public final class BossManager {
    /** Místo, kde se boss sám obnovuje. */
    public static final class Arena {
        public final String boss;
        public final Location location;
        public final int respawnSeconds;
        long nextSpawn;

        Arena(String boss, Location location, int respawnSeconds) {
            this.boss = boss;
            this.location = location;
            this.respawnSeconds = respawnSeconds;
        }
    }

    private final RPGCore plugin;
    private final Map<String, BossDef> defs = new LinkedHashMap<>();
    private final Map<UUID, ActiveBoss> active = new HashMap<>();
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final Set<String> summoning = new HashSet<>();
    private final File arenaFile;

    public BossManager(RPGCore plugin) {
        this.plugin = plugin;
        this.arenaFile = new File(plugin.getDataFolder(), "arenas.yml");
    }

    public void load() {
        defs.clear();
        YamlConfiguration cfg = plugin.loadYaml("bosses.yml");
        for (String id : cfg.getKeys(false)) {
            ConfigurationSection s = cfg.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            try {
                defs.put(id, BossDef.parse(id, s));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("bosses.yml: boss '" + id + "' přeskočen - " + ex.getMessage());
            }
        }
        plugin.getLogger().info("Načteno " + defs.size() + " bossů.");
    }

    public void start() {
        loadArenas();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public void shutdown() {
        for (ActiveBoss boss : new ArrayList<>(active.values())) {
            cleanup(boss);
            boss.entity.remove();
        }
        active.clear();
    }

    public BossDef def(String id) {
        return defs.get(id);
    }

    public Collection<String> ids() {
        return defs.keySet();
    }

    public Collection<ActiveBoss> active() {
        return active.values();
    }

    public ActiveBoss byEntity(Entity e) {
        return active.get(e.getUniqueId());
    }

    public boolean isActive(String bossId) {
        return active.values().stream().anyMatch(b -> b.def.id.equals(bossId) && b.alive());
    }

    // ------------------------------------------------------------------ spawn

    public ActiveBoss spawn(String id, Location at, String arena) {
        BossDef def = defs.get(id);
        if (def == null || at.getWorld() == null) {
            return null;
        }
        LivingEntity e = plugin.mobs().spawn(def.mob, at, true);
        e.getPersistentDataContainer().set(Keys.BOSS, PersistentDataType.STRING, def.id);
        e.setPersistent(false);              // po restartu serveru nezůstane "osiřelý" boss bez boss baru
        e.setRemoveWhenFarAway(false);
        e.setGlowing(plugin.getConfig().getBoolean("bosses.glowing", true));
        setAttr(e, Attribute.KNOCKBACK_RESISTANCE, 0.85);
        setAttr(e, Attribute.FOLLOW_RANGE, 48);
        AttributeInstance speed = e.getAttribute(Attribute.MOVEMENT_SPEED);

        BossBar bar = BossBar.bossBar(
                Component.text(def.mob.name, NamedTextColor.WHITE).append(Component.text("  Lv." + def.mob.level, NamedTextColor.GRAY)),
                1f, def.barColor, BossBar.Overlay.NOTCHED_10);
        ActiveBoss boss = new ActiveBoss(def, e, bar, at.clone(), arena, speed == null ? 0.25 : speed.getBaseValue());
        boss.nextCast = System.currentTimeMillis() + 3000;
        active.put(e.getUniqueId(), boss);

        Fx.sound(at, Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.8f);
        Element.SHADOW.burst(at.clone().add(0, 1, 0), 60, 0.8);
        if (plugin.getConfig().getBoolean("bosses.broadcast", true)) {
            Bukkit.broadcast(Text.mm("<dark_red>☠ <red>Boss <gold>" + Text.esc(def.mob.name) + " <red>se probudil! <gray>("
                    + at.getWorld().getName() + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ")"));
        }
        return boss;
    }

    /** Vyvolání bosse předmětem - po krátkém varování se objeví na daném místě. */
    public void summon(Player p, RpgItem item, ItemStack stack, Location at) {
        BossDef def = defs.get(item.boss);
        if (def == null) {
            Text.send(p, "<red>Tento předmět nemá přiřazeného bosse.");
            return;
        }
        if (isActive(def.id) || summoning.contains(def.id)) {
            plugin.players().notify(p, "<red>" + Text.esc(def.mob.name) + " už je vyvolaný.");
            return;
        }
        stack.setAmount(stack.getAmount() - 1);
        summoning.add(def.id);
        Location spot = MobManager.ground(at);
        Fx.sound(spot, Sound.BLOCK_PORTAL_TRIGGER, 1f, 0.6f);
        for (Player near : BossAbilities.playersNear(spot, 40)) {
            Text.send(near, "<dark_purple>Země se chvěje... <gray>něco se blíží.");
        }
        Telegraph.run(plugin, spot, 4, 60, Element.VOID, () -> {
            summoning.remove(def.id);
            spot.getWorld().strikeLightningEffect(spot);
            spawn(def.id, spot, null);
        });
    }

    private static void setAttr(LivingEntity e, Attribute attribute, double value) {
        AttributeInstance inst = e.getAttribute(attribute);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    // ------------------------------------------------------------------ průběh boje

    private void tick() {
        long now = System.currentTimeMillis();
        double leash = plugin.getConfig().getDouble("bosses.leash-range", 40);
        long despawn = plugin.getConfig().getLong("bosses.despawn-seconds", 90) * 1000;

        for (ActiveBoss boss : new ArrayList<>(active.values())) {
            LivingEntity e = boss.entity;
            if (!boss.alive()) {
                if (!e.isDead()) {
                    // zmizel bez zabití (odnačtený chunk apod.)
                    active.remove(e.getUniqueId());
                    cleanup(boss);
                    scheduleArena(boss, 15);
                }
                continue;
            }
            AttributeInstance maxAttr = e.getAttribute(Attribute.MAX_HEALTH);
            double max = maxAttr == null ? e.getHealth() : maxAttr.getValue();
            float progress = (float) Math.max(0, Math.min(1, e.getHealth() / max));
            boss.bar.progress(progress);

            // boss bar vidí hráči do 48 bloků
            Set<UUID> near = new HashSet<>();
            for (Player p : e.getWorld().getPlayers()) {
                if (p.getGameMode() != GameMode.SPECTATOR && p.getLocation().distanceSquared(e.getLocation()) <= 48 * 48) {
                    near.add(p.getUniqueId());
                    if (boss.viewers.add(p.getUniqueId())) {
                        p.showBossBar(boss.bar);
                    }
                }
            }
            boss.viewers.removeIf(uuid -> {
                if (near.contains(uuid)) {
                    return false;
                }
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.hideBossBar(boss.bar);
                }
                return true;
            });

            List<Player> targets = BossAbilities.playersNear(e.getLocation(), 36);
            if (near.isEmpty()) {
                if (now - boss.lastPlayerSeen > despawn) {
                    active.remove(e.getUniqueId());
                    cleanup(boss);
                    e.remove();
                    scheduleArena(boss, 5);
                }
                continue;
            }
            boss.lastPlayerSeen = now;

            if (e.getWorld() != boss.home.getWorld() || e.getLocation().distanceSquared(boss.home) > leash * leash) {
                e.teleport(boss.home);
                Element.VOID.burst(boss.home.clone().add(0, 1, 0), 30, 0.6);
            }

            int phase = phaseFor(boss.def, progress * 100);
            if (phase > boss.phase) {
                enterPhase(boss, phase, targets);
            }
            if (targets.isEmpty()) {
                continue;
            }
            if (e instanceof Mob mob) {
                LivingEntity current = mob.getTarget();
                if (!(current instanceof Player cp) || !BossAbilities.validTarget(cp)) {
                    mob.setTarget(MobManager.nearestPlayer(e.getLocation(), 36));
                }
            }
            if (now >= boss.nextCast) {
                castRandom(boss, targets, now);
            }
        }
        tickArenas(now);
    }

    private static int phaseFor(BossDef def, double healthPercent) {
        int phase = 0;
        for (int i = 0; i < def.phases.size(); i++) {
            if (healthPercent <= def.phases.get(i).below()) {
                phase = i;
            }
        }
        return phase;
    }

    private void enterPhase(ActiveBoss boss, int index, List<Player> targets) {
        boolean first = boss.phase < 0;
        boss.phase = index;
        BossDef.Phase phase = boss.def.phases.get(index);
        boss.damageMult = phase.damage();
        plugin.mobs().setDamage(boss.entity, boss.def.mob.damage * phase.damage());
        setAttr(boss.entity, Attribute.MOVEMENT_SPEED, boss.baseSpeed * phase.speed());
        if (first) {
            return;
        }
        Location at = boss.entity.getLocation();
        Fx.sound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 0.8f);
        Element.FIRE.burst(at.clone().add(0, 1.5, 0), 50, 0.9);
        if (phase.message() != null) {
            Component msg = Text.mm(phase.message());
            for (Player p : targets) {
                p.sendMessage(msg);
            }
        }
        boss.nextCast = System.currentTimeMillis() + 1500;
    }

    private void castRandom(ActiveBoss boss, List<Player> targets, long now) {
        BossDef.Phase phase = boss.def.phases.get(Math.max(0, boss.phase));
        List<BossDef.Ability> ready = new ArrayList<>();
        for (String key : phase.abilities()) {
            Long until = boss.cooldowns.get(key);
            if (until == null || until <= now) {
                ready.add(boss.def.abilities.get(key));
            }
        }
        if (ready.isEmpty()) {
            return;
        }
        BossDef.Ability a = ready.get(ThreadLocalRandom.current().nextInt(ready.size()));
        boss.cooldowns.put(a.key(), now + (long) (a.cooldown() * 1000));
        boss.nextCast = now + (long) (boss.def.abilityInterval * 1000);
        if (a.message() != null) {
            Component msg = Text.mm(a.message());
            for (Player p : targets) {
                p.sendMessage(msg);
            }
        }
        BossAbilities.cast(plugin, boss, a, targets);
    }

    public void recordDamage(LivingEntity target, Player attacker, double amount) {
        ActiveBoss boss = active.get(target.getUniqueId());
        if (boss != null) {
            boss.damage.merge(attacker.getUniqueId(), amount, Double::sum);
        }
    }

    // ------------------------------------------------------------------ smrt a odměny

    public void onDeath(ActiveBoss boss) {
        active.remove(boss.entity.getUniqueId());
        cleanup(boss);
        BossDef def = boss.def;
        Location at = boss.entity.getLocation();
        at.getWorld().strikeLightningEffect(at);
        Element.HOLY.burst(at.clone().add(0, 1, 0), 80, 1.0);

        double total = boss.damage.values().stream().mapToDouble(Double::doubleValue).sum();
        List<Map.Entry<UUID, Double>> ranking = new ArrayList<>(boss.damage.entrySet());
        ranking.sort(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()));

        List<String> top = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : ranking) {
            Player p = Bukkit.getPlayer(entry.getKey());
            double share = total <= 0 ? 0 : entry.getValue() / total;
            if (top.size() < 3 && p != null) {
                top.add("<yellow>" + p.getName() + " <gray>(" + Math.round(share * 100) + " %)");
            }
            // odměnu dostane každý, kdo udělal aspoň 1 % poškození - každý má vlastní loot
            if (p == null || !p.isOnline() || share < 0.01) {
                continue;
            }
            plugin.combat().reward(p, def.mob.xp, def.coins);
            List<ItemStack> loot = MobDef.roll(plugin, def.loot);
            Text.send(p, "<gold>Porazil jsi bosse <yellow>" + Text.esc(def.mob.name) + "<gold>! Kořist:");
            for (ItemStack stack : loot) {
                Component name = plugin.items().displayName(plugin.items().idOf(stack) != null ? plugin.items().idOf(stack) : stack.getType().name());
                p.sendMessage(Component.text("  + " + stack.getAmount() + "× ", NamedTextColor.GRAY).append(name));
                plugin.items().give(p, stack);
            }
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            plugin.quests().onKill(p, def.id);
        }
        if (plugin.getConfig().getBoolean("bosses.broadcast", true)) {
            Bukkit.broadcast(Text.mm("<gold>⚔ Boss <yellow>" + Text.esc(def.mob.name) + " <gold>byl poražen!"
                    + (top.isEmpty() ? "" : " <gray>Nejvíc poškození: " + String.join("<gray>, ", top))));
        }
        scheduleArena(boss, -1);
    }

    private void cleanup(ActiveBoss boss) {
        for (UUID uuid : boss.viewers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                p.hideBossBar(boss.bar);
            }
        }
        boss.viewers.clear();
        for (UUID uuid : boss.minions) {
            Entity minion = Bukkit.getEntity(uuid);
            if (minion != null) {
                minion.remove();
            }
        }
        boss.minions.clear();
    }

    public int killAll() {
        int n = 0;
        for (ActiveBoss boss : new ArrayList<>(active.values())) {
            active.remove(boss.entity.getUniqueId());
            cleanup(boss);
            boss.entity.remove();
            scheduleArena(boss, -1);
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ arény

    private void loadArenas() {
        arenas.clear();
        if (!arenaFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(arenaFile);
        for (String id : y.getKeys(false)) {
            World world = Bukkit.getWorld(y.getString(id + ".world", ""));
            if (world == null || !defs.containsKey(id)) {
                continue;
            }
            Location at = new Location(world, y.getDouble(id + ".x"), y.getDouble(id + ".y"), y.getDouble(id + ".z"));
            arenas.put(id, new Arena(id, at, y.getInt(id + ".respawn-seconds", 1800)));
        }
    }

    private void saveArenas() {
        YamlConfiguration y = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            y.set(a.boss + ".world", a.location.getWorld().getName());
            y.set(a.boss + ".x", a.location.getX());
            y.set(a.boss + ".y", a.location.getY());
            y.set(a.boss + ".z", a.location.getZ());
            y.set(a.boss + ".respawn-seconds", a.respawnSeconds);
        }
        try {
            y.save(arenaFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Nelze uložit arenas.yml: " + ex.getMessage());
        }
    }

    public Map<String, Arena> arenas() {
        return arenas;
    }

    public void setArena(String bossId, Location at, int respawnSeconds) {
        arenas.put(bossId, new Arena(bossId, at.clone(), respawnSeconds));
        saveArenas();
    }

    public boolean removeArena(String bossId) {
        boolean removed = arenas.remove(bossId) != null;
        if (removed) {
            saveArenas();
        }
        return removed;
    }

    /** @param seconds za jak dlouho se má boss v aréně obnovit; -1 = podle nastavení arény */
    private void scheduleArena(ActiveBoss boss, int seconds) {
        Arena arena = boss.arena == null ? null : arenas.get(boss.arena);
        if (arena != null) {
            arena.nextSpawn = System.currentTimeMillis() + (seconds < 0 ? arena.respawnSeconds : seconds) * 1000L;
        }
    }

    /** Boss v aréně se objeví, až když je čas a někdo je poblíž (nenačítáme kvůli němu chunky). */
    private void tickArenas(long now) {
        for (Arena arena : arenas.values()) {
            if (now < arena.nextSpawn || isActive(arena.boss) || summoning.contains(arena.boss)) {
                continue;
            }
            World world = arena.location.getWorld();
            if (world == null || !world.isChunkLoaded(arena.location.getBlockX() >> 4, arena.location.getBlockZ() >> 4)) {
                continue;
            }
            if (BossAbilities.playersNear(arena.location, 40).isEmpty()) {
                continue;
            }
            arena.nextSpawn = now + 10_000;
            spawn(arena.boss, arena.location, arena.boss);
        }
    }
}
