package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.npc.NpcManager;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.quest.QuestManager;
import cz.rpgcore.quest.QuestManager.Quest;
import cz.rpgcore.quest.QuestManager.State;
import cz.rpgcore.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Úkoly - buď u zadavatele (giver != null), nebo deník hráče. */
public final class QuestMenu extends Menu {
    private final NpcManager.Template giver;

    public QuestMenu(RPGCore plugin, Player player, NpcManager.Template giver) {
        super(plugin, player, 4, giver == null ? "<dark_gray>Deník úkolů" : "<dark_gray>" + Text.esc(giver.name()));
        this.giver = giver;
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        QuestManager qm = plugin.quests();
        int slot = 0;
        for (Quest q : qm.all()) {
            State state = qm.state(player, d, q);
            boolean show = giver == null
                    ? state == State.ACTIVE || state == State.READY
                    : giver.id().equals(q.giver) && state != State.DONE;
            if (!show || slot >= 27) {
                continue;
            }
            List<String> lore = new ArrayList<>();
            for (String line : q.description) {
                lore.add("<gray>" + Text.esc(line));
            }
            lore.add("");
            lore.add("<gold>Cíle:");
            for (int i = 0; i < q.objectives.size(); i++) {
                int need = q.objectives.get(i).amount();
                int have = state == State.ACTIVE || state == State.READY ? qm.progress(player, d, q, i) : 0;
                lore.add((have >= need ? "<green> ✔ " : "<gray> • ") + Text.esc(qm.objectiveText(q, i))
                        + (need > 1 ? " <white>" + have + "/" + need : ""));
            }
            lore.add("");
            lore.add("<gold>Odměna: <green>" + Text.num(q.xp) + " XP<gray>, <gold>" + q.coins + " mincí");
            q.items.forEach((item, amount) -> lore.add("<dark_gray> + <white>" + amount + "× <gray>" + itemName(item)));
            lore.add("");
            Material material;
            switch (state) {
                case LOCKED -> {
                    material = Material.GRAY_DYE;
                    lore.add("<red>Zamčeno <gray>- potřebuješ úroveň " + q.level
                            + (q.requires.isEmpty() ? "" : " a splnit předchozí úkoly"));
                }
                case AVAILABLE -> {
                    material = Material.BOOK;
                    lore.add("<green>Klikni pro přijetí");
                }
                case READY -> {
                    material = Material.ENCHANTED_BOOK;
                    lore.add(giver != null ? "<green>Klikni pro odevzdání" : "<green>Hotovo! <gray>Vrať se za: " + Text.esc(qm.giverName(q)));
                }
                default -> {
                    material = Material.WRITABLE_BOOK;
                    lore.add("<yellow>Probíhá" + (giver == null ? " <dark_gray>(Shift + pravý klik = zrušit)" : ""));
                }
            }
            String color = switch (state) {
                case LOCKED -> "<gray>";
                case READY -> "<green>";
                default -> "<yellow>";
            };
            set(slot++, icon(material, color + Text.esc(q.name) + (q.repeatable ? " <dark_gray>(opakovatelný)" : ""), lore.toArray(String[]::new)), e -> {
                State now = qm.state(player, d, q);
                if (giver != null && now == State.AVAILABLE) {
                    qm.accept(player, q);
                } else if (giver != null && now == State.READY) {
                    qm.turnIn(player, q);
                } else if (giver == null && now == State.ACTIVE && e.isShiftClick() && e.isRightClick()) {
                    qm.abandon(player, q);
                }
                redraw();
            });
        }
        if (slot == 0) {
            set(13, icon(Material.PAPER, giver == null ? "<gray>Nemáš žádné aktivní úkoly" : "<gray>Teď pro tebe nic nemám",
                    giver == null ? "<gray>Promluv si s lidmi ve vesnici." : "<gray>Vrať se, až budeš silnější."));
        }
        if (giver == null) {
            set(27, icon(Material.ARROW, "<gray>Zpět"), e -> new MainMenu(plugin, player).open());
        }
        fill();
    }

    private String itemName(String spec) {
        ItemStack stack = plugin.items().parse(spec, 1);
        if (stack == null) {
            return spec;
        }
        Component name = plugin.items().displayName(spec);
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(name);
    }
}
