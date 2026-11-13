package cz.rpgcore.command;

import cz.rpgcore.RPGCore;
import cz.rpgcore.boss.ActiveBoss;
import cz.rpgcore.boss.BossManager;
import cz.rpgcore.gui.AccessoryMenu;
import cz.rpgcore.gui.ClassMenu;
import cz.rpgcore.gui.ItemBrowserMenu;
import cz.rpgcore.gui.MainMenu;
import cz.rpgcore.gui.QuestMenu;
import cz.rpgcore.gui.RewardsMenu;
import cz.rpgcore.gui.SkillsMenu;
import cz.rpgcore.gui.StatsMenu;
import cz.rpgcore.item.RpgItem;
import cz.rpgcore.mob.MobManager;
import cz.rpgcore.npc.NpcManager;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class RpgCommand implements TabExecutor {
    private static final List<String> PLAYER_SUBS = List.of("help", "class", "stats", "skills", "quests", "acc", "rewards", "coins");
    private static final List<String> ADMIN_SUBS = List.of("give", "items", "mob", "boss", "npc", "spawner", "xp", "level", "setcoins", "pack", "reload");

    private final RPGCore plugin;

    public RpgCommand(RPGCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("rpg")) {
            // /quests, /class, /stats, /skills, /rewards jsou zkratky
            return run(sender, new String[]{name});
        }
        return run(sender, args);
    }

    private boolean run(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (ADMIN_SUBS.contains(sub)) {
            if (!sender.hasPermission("rpgcore.admin")) {
                Text.send(sender, "<red>Na tohle nemáš oprávnění.");
                return true;
            }
            try {
                admin(sender, sub, args);
            } catch (NumberFormatException ex) {
                Text.send(sender, "<red>Neplatné číslo.");
            }
            return true;
        }
        if (!(sender instanceof Player p)) {
            Text.send(sender, "<gray>Z konzole: /rpg give|mob|boss|xp|level|setcoins|reload");
            return true;
        }
        PlayerData d = plugin.players().get(p);
        switch (sub) {
            case "" -> new MainMenu(plugin, p).open();
            case "class" -> new ClassMenu(plugin, p).open();
            case "stats" -> new StatsMenu(plugin, p).open();
            case "skills" -> new SkillsMenu(plugin, p).open();
            case "quests" -> new QuestMenu(plugin, p, null).open();
            case "acc" -> new AccessoryMenu(plugin, p).open();
            case "rewards" -> new RewardsMenu(plugin, p).open();
            case "coins" -> Text.send(p, "<gray>Máš <gold>" + (d == null ? 0 : d.coins) + " mincí<gray>.");
            default -> help(p);
        }
        return true;
    }

    private void help(CommandSender s) {
        Text.send(s, "<gold>RPG příkazy");
        s.sendMessage(Text.mm("<yellow>/rpg <gray>- menu postavy   <yellow>/class <gray>- třída   <yellow>/skills <gray>- dovednosti"));
        s.sendMessage(Text.mm("<yellow>/stats <gray>- atributy   <yellow>/quests <gray>- úkoly   <yellow>/rewards <gray>- odměny   <yellow>/rpg acc <gray>- doplňky"));
        if (s.hasPermission("rpgcore.admin")) {
            s.sendMessage(Text.mm("<red>Admin: <gray>/rpg give <hráč> <předmět> [počet] • /rpg items • /rpg mob <id>"));
            s.sendMessage(Text.mm("<gray>/rpg boss spawn|arena|delarena|list|kill • /rpg npc spawn|remove|list"));
            s.sendMessage(Text.mm("<gray>/rpg spawner add <mob> [okruh] [max] [sekundy] | remove <id> | list"));
            s.sendMessage(Text.mm("<gray>/rpg xp|level|setcoins <hráč> <číslo> • /rpg pack • /rpg reload"));
        }
    }

    private void admin(CommandSender s, String sub, String[] a) {
        Player self = s instanceof Player p ? p : null;
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                Text.send(s, "<green>Nastavení znovu načteno.");
            }
            case "pack" -> {
                if (self != null) {
                    Text.send(s, plugin.pack().send(self) ? "<green>Resource pack odeslán." : "<red>Resource pack je vypnutý nebo se nenačetl.");
                }
            }
            case "items" -> {
                if (self != null) {
                    new ItemBrowserMenu(plugin, self, 0).open();
                }
            }
            case "give" -> {
                if (a.length < 3) {
                    Text.send(s, "<gray>/rpg give <hráč> <předmět> [počet]");
                    return;
                }
                Player target = Bukkit.getPlayerExact(a[1]);
                ItemStack stack = plugin.items().create(a[2], a.length > 3 ? Integer.parseInt(a[3]) : 1);
                if (target == null || stack == null) {
                    Text.send(s, "<red>Hráč není online nebo předmět neexistuje.");
                    return;
                }
                plugin.items().give(target, stack);
                Text.send(s, "<green>Předáno: " + stack.getAmount() + "× " + a[2] + " → " + target.getName());
            }
            case "xp", "level", "setcoins" -> {
                if (a.length < 3) {
                    Text.send(s, "<gray>/rpg " + sub + " <hráč> <číslo>");
                    return;
                }
                Player target = Bukkit.getPlayerExact(a[1]);
                PlayerData d = target == null ? null : plugin.players().get(target);
                if (d == null) {
                    Text.send(s, "<red>Hráč není online.");
                    return;
                }
                long v = Long.parseLong(a[2]);
                switch (sub) {
                    case "xp" -> plugin.players().addXp(target, v);
                    case "level" -> plugin.players().setLevel(target, (int) v);
                    default -> d.coins = Math.max(0, v);
                }
                Text.send(s, "<green>Hotovo. <gray>" + target.getName() + ": úroveň " + d.level + ", " + d.coins + " mincí");
            }
            case "mob" -> {
                Location at = location(s, a, 2);
                if (a.length < 2 || at == null) {
                    Text.send(s, "<gray>/rpg mob <id> [svět x y z]");
                    return;
                }
                LivingEntity e = plugin.mobs().spawn(a[1], at);
                Text.send(s, e == null ? "<red>Neznámý mob." : "<green>Mob " + a[1] + " vytvořen.");
            }
            case "boss" -> boss(s, self, a);
            case "npc" -> npc(s, self, a);
            case "spawner" -> spawner(s, self, a);
            default -> help(s);
        }
    }

    /** Pozice hráče, nebo "svět x y z" z argumentů (pro konzoli). */
    private Location location(CommandSender s, String[] a, int from) {
        if (a.length >= from + 4) {
            World w = Bukkit.getWorld(a[from]);
            return w == null ? null : new Location(w, Double.parseDouble(a[from + 1]), Double.parseDouble(a[from + 2]), Double.parseDouble(a[from + 3]));
        }
        return s instanceof Player p ? p.getLocation() : null;
    }

    private void boss(CommandSender s, Player self, String[] a) {
        String action = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        BossManager bm = plugin.bosses();
        switch (action) {
            case "spawn" -> {
                Location at = location(s, a, 3);
                if (a.length < 3 || at == null || bm.def(a[2]) == null) {
                    Text.send(s, "<gray>/rpg boss spawn <id> [svět x y z]  <dark_gray>" + String.join(", ", bm.ids()));
                    return;
                }
                bm.spawn(a[2], at, null);
                Text.send(s, "<green>Boss vytvořen.");
            }
            case "arena" -> {
                if (self == null || a.length < 3 || bm.def(a[2]) == null) {
                    Text.send(s, "<gray>/rpg boss arena <id> [minuty do obnovení]");
                    return;
                }
                int minutes = a.length > 3 ? Integer.parseInt(a[3]) : 30;
                bm.setArena(a[2], self.getLocation(), minutes * 60);
                Text.send(s, "<green>Aréna nastavena. <gray>Boss se tu objeví, když někdo přijde, a obnoví se za " + minutes + " min po porážce.");
            }
            case "delarena" -> Text.send(s, a.length > 2 && bm.removeArena(a[2]) ? "<green>Aréna smazána." : "<red>Taková aréna není.");
            case "kill" -> Text.send(s, "<green>Odstraněno bossů: " + bm.killAll());
            case "list" -> {
                Text.send(s, "<gold>Bossové: <gray>" + String.join(", ", bm.ids()));
                for (ActiveBoss b : bm.active()) {
                    Location l = b.entity.getLocation();
                    s.sendMessage(Text.mm("<red> • živý: <white>" + b.def.id + " <gray>" + l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                            + " <dark_gray>(" + Text.num(plugin.mobs().health(b.entity)) + " HP)"));
                }
                bm.arenas().forEach((id, arena) -> s.sendMessage(Text.mm("<aqua> • aréna: <white>" + id + " <gray>"
                        + arena.location.getBlockX() + " " + arena.location.getBlockY() + " " + arena.location.getBlockZ())));
            }
            default -> Text.send(s, "<gray>/rpg boss spawn|arena|delarena|list|kill");
        }
    }

    private void npc(CommandSender s, Player self, String[] a) {
        String action = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        NpcManager nm = plugin.npcs();
        switch (action) {
            case "spawn" -> {
                if (self == null || a.length < 3 || nm.template(a[2]) == null) {
                    Text.send(s, "<gray>/rpg npc spawn <id>  <dark_gray>" + String.join(", ", nm.templateIds()));
                    return;
                }
                NpcManager.Placed placed = nm.place(a[2], self.getLocation());
                Text.send(s, "<green>NPC postaveno <gray>(č. " + placed.id + ").");
            }
            case "remove" -> {
                NpcManager.Placed target = a.length > 2 ? null : (self == null ? null : nm.nearest(self.getLocation(), 6));
                int id = a.length > 2 ? Integer.parseInt(a[2]) : (target == null ? -1 : target.id);
                Text.send(s, nm.remove(id) ? "<green>NPC odstraněno." : "<red>Žádné NPC poblíž (nebo špatné číslo).");
            }
            case "list" -> {
                Text.send(s, "<gold>Šablony: <gray>" + String.join(", ", nm.templateIds()));
                for (NpcManager.Placed p : nm.placed()) {
                    s.sendMessage(Text.mm("<gray> č. " + p.id + " <white>" + p.template + " <gray>" + p.world + " " + (int) p.x + " " + (int) p.y + " " + (int) p.z));
                }
            }
            default -> Text.send(s, "<gray>/rpg npc spawn <id> | remove [číslo] | list");
        }
    }

    private void spawner(CommandSender s, Player self, String[] a) {
        String action = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        MobManager mm = plugin.mobs();
        switch (action) {
            case "add" -> {
                if (self == null || a.length < 3 || mm.def(a[2]) == null) {
                    Text.send(s, "<gray>/rpg spawner add <mob> [okruh=6] [max=4] [sekundy=15]");
                    return;
                }
                MobManager.Spawner sp = mm.addSpawner(a[2], self.getLocation(),
                        a.length > 3 ? Double.parseDouble(a[3]) : 6,
                        a.length > 4 ? Integer.parseInt(a[4]) : 4,
                        a.length > 5 ? Integer.parseInt(a[5]) : 15);
                Text.send(s, "<green>Spawner č. " + sp.id + " vytvořen <gray>(" + sp.mob + ", max " + sp.max + ").");
            }
            case "remove" -> Text.send(s, a.length > 2 && mm.removeSpawner(Integer.parseInt(a[2])) ? "<green>Spawner smazán." : "<red>Takový spawner není.");
            case "list" -> {
                Text.send(s, "<gold>Spawnery:");
                for (MobManager.Spawner sp : mm.spawners()) {
                    s.sendMessage(Text.mm("<gray> č. " + sp.id + " <white>" + sp.mob + " <gray>" + sp.world + " " + (int) sp.x + " " + (int) sp.y + " " + (int) sp.z
                            + " <dark_gray>(okruh " + Text.num(sp.radius) + ", max " + sp.max + ", " + sp.interval + " s)"));
                }
            }
            default -> Text.send(s, "<gray>/rpg spawner add|remove|list");
        }
    }

    // ------------------------------------------------------------------ doplňování

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] a) {
        if (!command.getName().equalsIgnoreCase("rpg")) {
            return List.of();
        }
        boolean admin = sender.hasPermission("rpgcore.admin");
        List<String> options = new ArrayList<>();
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        if (a.length == 1) {
            options.addAll(PLAYER_SUBS);
            if (admin) {
                options.addAll(ADMIN_SUBS);
            }
        } else if (admin && a.length == 2) {
            switch (sub) {
                case "give", "xp", "level", "setcoins" -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                case "mob" -> options.addAll(plugin.mobs().ids());
                case "boss" -> options.addAll(List.of("spawn", "arena", "delarena", "list", "kill"));
                case "npc" -> options.addAll(List.of("spawn", "remove", "list"));
                case "spawner" -> options.addAll(List.of("add", "remove", "list"));
                default -> {
                }
            }
        } else if (admin && a.length == 3) {
            switch (sub) {
                case "give" -> plugin.items().all().forEach(i -> options.add(i.id));
                case "boss" -> options.addAll(plugin.bosses().ids());
                case "npc" -> options.addAll(plugin.npcs().templateIds());
                case "spawner" -> options.addAll(plugin.mobs().ids());
                default -> {
                }
            }
        }
        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).limit(60).toList();
    }
}
