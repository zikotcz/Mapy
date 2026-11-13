package cz.rpgcore;

import cz.rpgcore.boss.BossManager;
import cz.rpgcore.combat.Abilities;
import cz.rpgcore.combat.Combat;
import cz.rpgcore.combat.SkillManager;
import cz.rpgcore.command.RpgCommand;
import cz.rpgcore.crate.CrateManager;
import cz.rpgcore.gui.MenuListener;
import cz.rpgcore.item.ItemManager;
import cz.rpgcore.mob.MobManager;
import cz.rpgcore.npc.NpcManager;
import cz.rpgcore.pack.PackServer;
import cz.rpgcore.player.PlayerManager;
import cz.rpgcore.quest.QuestManager;
import cz.rpgcore.reward.RewardManager;
import cz.rpgcore.util.Keys;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class RPGCore extends JavaPlugin {
    private ItemManager items;
    private PlayerManager players;
    private Combat combat;
    private Abilities abilities;
    private SkillManager skills;
    private MobManager mobs;
    private BossManager bosses;
    private NpcManager npcs;
    private QuestManager quests;
    private Content content;
    private CrateManager crates;
    private RewardManager rewards;
    private PackServer pack;

    @Override
    public void onEnable() {
        Keys.init(this);
        saveDefaultConfig();
        items = new ItemManager(this);
        players = new PlayerManager(this);
        combat = new Combat(this);
        abilities = new Abilities(this);
        skills = new SkillManager(this);
        mobs = new MobManager(this);
        bosses = new BossManager(this);
        npcs = new NpcManager(this);
        quests = new QuestManager(this);
        content = new Content(this);
        crates = new CrateManager(this);
        rewards = new RewardManager(this);
        pack = new PackServer(this);
        loadAll();

        var pm = getServer().getPluginManager();
        pm.registerEvents(players, this);
        pm.registerEvents(combat, this);
        pm.registerEvents(mobs, this);
        pm.registerEvents(npcs, this);
        pm.registerEvents(pack, this);
        pm.registerEvents(rewards, this);
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(new InteractListener(this), this);

        players.start();
        mobs.start();
        bosses.start();
        npcs.start();
        pack.start();

        RpgCommand command = new RpgCommand(this);
        for (String name : new String[]{"rpg", "quests", "class", "stats", "skills", "rewards"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(command);
                cmd.setTabCompleter(command);
            }
        }
    }

    @Override
    public void onDisable() {
        if (players == null) {
            return;
        }
        bosses.shutdown();
        npcs.shutdown();
        pack.stop();
        players.shutdown();
    }

    private void loadAll() {
        items.load();
        mobs.load();
        bosses.load();
        npcs.load();
        quests.load();
        content.load();
        crates.load();
        rewards.load();
    }

    public void reload() {
        reloadConfig();
        loadAll();
        pack.start();
    }

    /** Načte yml z plugins/RPGCore; když tam není, zkopíruje výchozí z jaru. */
    public YamlConfiguration loadYaml(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    public ItemManager items() {
        return items;
    }

    public PlayerManager players() {
        return players;
    }

    public Combat combat() {
        return combat;
    }

    public Abilities abilities() {
        return abilities;
    }

    public SkillManager skills() {
        return skills;
    }

    public MobManager mobs() {
        return mobs;
    }

    public BossManager bosses() {
        return bosses;
    }

    public NpcManager npcs() {
        return npcs;
    }

    public QuestManager quests() {
        return quests;
    }

    public Content content() {
        return content;
    }

    public CrateManager crates() {
        return crates;
    }

    public RewardManager rewards() {
        return rewards;
    }

    public PackServer pack() {
        return pack;
    }
}
