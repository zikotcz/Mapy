package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.reward.RewardManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** Denní série odměn a přehled odměn za úrovně. */
public final class RewardsMenu extends Menu {
    public RewardsMenu(RPGCore plugin, Player player) {
        super(plugin, player, 4, "<dark_gray>Odměny");
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        RewardManager rm = plugin.rewards();
        List<RewardManager.Reward> daily = rm.daily();
        boolean available = rm.dailyAvailable(d);
        // pozice v týdenním cyklu: které dny už jsou za hráčem
        int streak = available ? rm.nextStreak(d) : d.dailyStreak;
        int today = daily.isEmpty() ? 0 : (streak - 1) % daily.size();
        for (int i = 0; i < Math.min(9, daily.size()); i++) {
            RewardManager.Reward r = daily.get(i);
            String what = rm.describe(r);
            if (i < today || (i == today && !available)) {
                set(i, icon(Material.LIME_DYE, "<green>Den " + (i + 1) + " ✔", "<gray>" + what));
            } else if (i == today) {
                set(i, icon(Material.CHEST, "<yellow>Den " + (i + 1) + " - vyzvedni!", "<gray>" + what, "", "<green>Klikni pro vyzvednutí"), e -> {
                    rm.claimDaily(player, d);
                    redraw();
                });
            } else {
                set(i, icon(Material.GRAY_DYE, "<gray>Den " + (i + 1), "<gray>" + what, "", "<dark_gray>Přijď každý den, ať sérii nepřerušíš."));
            }
        }
        int slot = 18;
        for (Map.Entry<Integer, RewardManager.Reward> e : rm.levels().entrySet()) {
            if (slot >= 36) {
                break;
            }
            boolean got = d.levelRewards.contains(e.getKey());
            set(slot++, icon(got ? Material.LIME_DYE : Material.EXPERIENCE_BOTTLE,
                    (got ? "<green>" : "<yellow>") + "Úroveň " + e.getKey() + (got ? " ✔" : ""),
                    "<gray>" + rm.describe(e.getValue()),
                    got ? "<dark_gray>Vyzvednuto" : "<dark_gray>Dostaneš automaticky po dosažení úrovně."));
        }
        fill();
    }
}
