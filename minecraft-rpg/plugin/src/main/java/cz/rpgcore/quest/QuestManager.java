package cz.rpgcore.quest;

import cz.rpgcore.RPGCore;
import cz.rpgcore.boss.BossDef;
import cz.rpgcore.item.ItemManager;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.mob.MobDef;
import cz.rpgcore.npc.NpcManager;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Text;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Úkoly: přijetí, sledování postupu, odevzdání a odměny. */
public final class QuestManager {
    public enum ObjectiveType {KILL, COLLECT, TALK, LEVEL}

    public enum State {LOCKED, AVAILABLE, ACTIVE, READY, DONE}

    public record Objective(ObjectiveType type, String target, int amount, String text) {
    }

    public static final class Quest {
        public final String id;
        public String name;
        public String giver;
        public int level = 1;
        public boolean repeatable;
        public final List<String> requires = new ArrayList<>();
        public final List<String> description = new ArrayList<>();
        public final List<Objective> objectives = new ArrayList<>();
        public double xp;
        public long coins;
        public final Map<String, Integer> items = new LinkedHashMap<>();

        Quest(String id) {
            this.id = id;
        }
    }

    private final RPGCore plugin;
    private final Map<String, Quest> quests = new LinkedHashMap<>();

    public QuestManager(RPGCore plugin) {
        this.plugin = plugin;
    }

    public void load() {
        quests.clear();
        YamlConfiguration cfg = plugin.loadYaml("quests.yml");
        for (String id : cfg.getKeys(false)) {
            ConfigurationSection s = cfg.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Quest q = new Quest(id);
            q.name = s.getString("name", id);
            q.giver = s.getString("giver");
            q.level = s.getInt("level", 1);
            q.repeatable = s.getBoolean("repeatable");
            q.requires.addAll(s.getStringList("requires"));
            q.description.addAll(s.getStringList("description"));
            for (Map<?, ?> m : s.getMapList("objectives")) {
                ObjectiveType type = ItemManager.parseEnum(ObjectiveType.class, m.get("type") == null ? null : String.valueOf(m.get("type")), null);
                if (type == null) {
                    plugin.getLogger().warning("quests.yml: úkol '" + id + "' má cíl s neznámým typem.");
                    continue;
                }
                Object target = switch (type) {
                    case KILL -> m.get("target");
                    case COLLECT -> m.get("item");
                    case TALK -> m.get("npc");
                    case LEVEL -> "";
                };
                int amount = type == ObjectiveType.TALK ? 1 : (m.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1);
                q.objectives.add(new Objective(type, target == null ? "" : String.valueOf(target), amount,
                        m.get("text") == null ? null : String.valueOf(m.get("text"))));
            }
            ConfigurationSection r = s.getConfigurationSection("rewards");
            if (r != null) {
                q.xp = r.getDouble("xp");
                q.coins = r.getLong("coins");
                ConfigurationSection items = r.getConfigurationSection("items");
                if (items != null) {
                    for (String item : items.getKeys(false)) {
                        q.items.put(item, items.getInt(item, 1));
                    }
                }
            }
            if (q.objectives.isEmpty()) {
                plugin.getLogger().warning("quests.yml: úkol '" + id + "' nemá žádný cíl, přeskočeno.");
                continue;
            }
            quests.put(id, q);
        }
        plugin.getLogger().info("Načteno " + quests.size() + " úkolů.");
    }

    public Quest get(String id) {
        return quests.get(id);
    }

    public Collection<Quest> all() {
        return quests.values();
    }

    // ------------------------------------------------------------------ stav a postup

    public State state(Player p, PlayerData d, Quest q) {
        if (d.quests.containsKey(q.id)) {
            for (int i = 0; i < q.objectives.size(); i++) {
                if (progress(p, d, q, i) < q.objectives.get(i).amount()) {
                    return State.ACTIVE;
                }
            }
            return State.READY;
        }
        if (d.completed.contains(q.id) && !q.repeatable) {
            return State.DONE;
        }
        if (d.level < q.level || !d.completed.containsAll(q.requires)) {
            return State.LOCKED;
        }
        return State.AVAILABLE;
    }

    public int progress(Player p, PlayerData d, Quest q, int index) {
        Objective o = q.objectives.get(index);
        return switch (o.type()) {
            case COLLECT -> Math.min(o.amount(), plugin.items().count(p, o.target()));
            case LEVEL -> Math.min(o.amount(), d.level);
            default -> {
                int[] stored = stored(d, q);
                yield Math.min(o.amount(), stored[index]);
            }
        };
    }

    /** Uložený postup; když se mezitím změnil počet cílů v quests.yml, pole se přizpůsobí. */
    private static int[] stored(PlayerData d, Quest q) {
        int[] arr = d.quests.get(q.id);
        if (arr == null || arr.length != q.objectives.size()) {
            arr = arr == null ? new int[q.objectives.size()] : Arrays.copyOf(arr, q.objectives.size());
            d.quests.put(q.id, arr);
        }
        return arr;
    }

    public String objectiveText(Quest q, int index) {
        Objective o = q.objectives.get(index);
        if (o.text() != null) {
            return o.text();
        }
        return switch (o.type()) {
            case KILL -> "Zabij: " + mobName(o.target());
            case COLLECT -> {
                RpgItem item = plugin.items().byId(o.target());
                yield "Přines: " + (item == null ? o.target() : item.name);
            }
            case TALK -> {
                NpcManager.Template t = plugin.npcs().template(o.target());
                yield "Promluv si: " + (t == null ? o.target() : t.name());
            }
            case LEVEL -> "Dosáhni úrovně " + o.amount();
        };
    }

    private String mobName(String id) {
        MobDef mob = plugin.mobs().def(id);
        if (mob != null) {
            return mob.name;
        }
        BossDef boss = plugin.bosses().def(id);
        return boss == null ? id : boss.mob.name;
    }

    public String giverName(Quest q) {
        NpcManager.Template t = plugin.npcs().template(q.giver);
        return t == null ? "zadavatel" : t.name();
    }

    // ------------------------------------------------------------------ akce

    public void accept(Player p, Quest q) {
        PlayerData d = plugin.players().get(p);
        if (d == null || state(p, d, q) != State.AVAILABLE) {
            return;
        }
        d.quests.put(q.id, new int[q.objectives.size()]);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        Text.send(p, "<green>Nový úkol: <yellow>" + Text.esc(q.name));
        for (int i = 0; i < q.objectives.size(); i++) {
            p.sendMessage(Text.mm("<dark_gray>  • <gray>" + Text.esc(objectiveText(q, i))
                    + (q.objectives.get(i).amount() > 1 ? " <dark_gray>(" + q.objectives.get(i).amount() + ")" : "")));
        }
    }

    public void abandon(Player p, Quest q) {
        PlayerData d = plugin.players().get(p);
        if (d != null && d.quests.remove(q.id) != null) {
            Text.send(p, "<gray>Úkol <yellow>" + Text.esc(q.name) + " <gray>byl zrušen.");
        }
    }

    public void turnIn(Player p, Quest q) {
        PlayerData d = plugin.players().get(p);
        if (d == null || state(p, d, q) != State.READY) {
            return;
        }
        for (Objective o : q.objectives) {
            if (o.type() == ObjectiveType.COLLECT) {
                plugin.items().take(p, o.target(), o.amount());
            }
        }
        d.quests.remove(q.id);
        d.completed.add(q.id);
        Text.send(p, "<gold>✔ Úkol splněn: <yellow>" + Text.esc(q.name));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        q.items.forEach((item, amount) -> {
            ItemStack stack = plugin.items().parse(item, amount);
            if (stack != null) {
                plugin.items().give(p, stack);
            }
        });
        plugin.players().addCoins(p, q.coins);
        plugin.players().addXp(p, q.xp);
        Text.send(p, "<gray>Odměna: <green>" + Text.num(q.xp) + " XP<gray>, <gold>" + q.coins + " mincí"
                + (q.items.isEmpty() ? "" : "<gray> a předměty"));
    }

    public void onKill(Player p, String mobId) {
        advance(p, ObjectiveType.KILL, mobId);
    }

    public void onTalk(Player p, String npcId) {
        advance(p, ObjectiveType.TALK, npcId);
    }

    private void advance(Player p, ObjectiveType type, String target) {
        PlayerData d = plugin.players().get(p);
        if (d == null || d.quests.isEmpty()) {
            return;
        }
        for (String id : new ArrayList<>(d.quests.keySet())) {
            Quest q = quests.get(id);
            if (q == null) {
                continue;
            }
            int[] stored = stored(d, q);
            for (int i = 0; i < q.objectives.size(); i++) {
                Objective o = q.objectives.get(i);
                if (o.type() != type || !o.target().equals(target) || stored[i] >= o.amount()) {
                    continue;
                }
                stored[i]++;
                if (state(p, d, q) == State.READY) {
                    Text.send(p, "<gold>Úkol <yellow>" + Text.esc(q.name) + " <gold>je hotový! <gray>Vrať se za: " + Text.esc(giverName(q)));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                } else if (type == ObjectiveType.KILL) {
                    plugin.players().notify(p, "<yellow>" + Text.esc(q.name) + "<gray>: " + Text.esc(objectiveText(q, i))
                            + " <white>" + stored[i] + "/" + o.amount());
                } else {
                    Text.send(p, "<green>✔ <gray>" + Text.esc(objectiveText(q, i)));
                }
            }
        }
    }
}
