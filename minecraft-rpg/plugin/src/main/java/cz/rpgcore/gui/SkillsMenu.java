package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.combat.SkillManager;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Přehled dovedností třídy. */
public final class SkillsMenu extends Menu {
    public SkillsMenu(RPGCore plugin, Player player) {
        super(plugin, player, 3, "<dark_gray>Dovednosti");
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        if (d.rpgClass == null) {
            set(13, icon(Material.BARRIER, "<red>Nemáš třídu", "<gray>Klikni a vyber si ji."), e -> new ClassMenu(plugin, player).open());
        } else {
            int[] slots = {10, 12, 14, 16};
            for (SkillManager.Skill s : plugin.skills().of(d.rpgClass)) {
                boolean unlocked = d.level >= s.level();
                set(slots[s.slot() - 1], icon(unlocked ? s.icon() : Material.GRAY_DYE,
                        (unlocked ? "<gold>" : "<gray>") + s.name(),
                        "<gray>" + s.description(),
                        "",
                        "<gray>Mana: <aqua>" + Text.num(s.mana()) + "   <gray>Čekání: <white>" + Text.num(s.cooldown()) + " s",
                        "<gray>Ovládání: <yellow>" + SkillManager.KEYS[s.slot() - 1],
                        unlocked ? "<green>Odemčeno" : "<red>Odemkne se na úrovni " + s.level()));
            }
            set(22, icon(Material.PAPER, "<yellow>Jak na to",
                    "<gray>Drž v ruce RPG zbraň a použij:",
                    "<yellow>Shift + pravý klik <gray>- 1. dovednost",
                    "<yellow>Shift + levý klik <gray>- 2. dovednost",
                    "<yellow>F <gray>- 3. dovednost",
                    "<yellow>Shift + F <gray>- 4. dovednost",
                    "",
                    "<gray>Pravý klik bez Shiftu = schopnost zbraně."));
        }
        set(18, icon(Material.ARROW, "<gray>Zpět"), e -> new MainMenu(plugin, player).open());
        fill();
    }
}
