package cz.rpgcore.gui;

import cz.rpgcore.RPGCore;
import cz.rpgcore.combat.SkillManager;
import cz.rpgcore.player.PlayerData;
import cz.rpgcore.player.RpgClass;
import cz.rpgcore.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Výběr třídy s animací: třídy se postupně "vyloupnou", okraj menu bliká
 * a po výběru se menu zaplní barvou třídy a kolem hráče vyletí spirála.
 * První výběr je zdarma a hráč dostane startovní zbraň.
 */
public final class ClassMenu extends Menu {
    private static final int[] SLOTS = {10, 12, 14, 16};
    private static final Material[] PANES = {Material.RED_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE,
            Material.LIME_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE};
    private static final float[] NOTES = {0.8f, 1.0f, 1.2f, 1.6f};

    private int frame;
    private int revealed;
    private RpgClass chosen;
    private int fillStep;

    public ClassMenu(RPGCore plugin, Player player) {
        super(plugin, player, 3, "<dark_gray>Výběr třídy");
    }

    @Override
    protected void draw() {
        PlayerData d = plugin.players().get(player);
        if (d == null) {
            return;
        }
        long cost = plugin.getConfig().getLong("classes.change-cost", 500);
        RpgClass[] classes = RpgClass.values();
        for (int i = 0; i < classes.length; i++) {
            RpgClass c = classes[i];
            if (i >= revealed) {
                set(SLOTS[i], icon(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>???"));
                continue;
            }
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + c.description);
            lore.add("");
            lore.add("<gold>Dovednosti:");
            for (SkillManager.Skill s : plugin.skills().of(c)) {
                lore.add("<dark_gray> • <white>" + s.name() + " <dark_gray>(úroveň " + s.level() + ")");
            }
            lore.add("");
            if (d.rpgClass == c) {
                lore.add("<green>✔ Tvoje třída");
            } else if (d.rpgClass == null) {
                lore.add("<green>Klikni pro výběr <gray>(zdarma)");
            } else {
                lore.add("<yellow>Shift + klik: <gray>změnit za <gold>" + cost + " mincí");
            }
            ItemStack icon = icon(c.icon, c.label, lore.toArray(String[]::new));
            icon.editMeta(meta -> {
                meta.displayName(Text.plain(c.label, c.color));
                // vybraná třída svítí, ostatní pomalu "dýchají"
                meta.setEnchantmentGlintOverride(d.rpgClass == c || (frame / 4) % 4 == c.ordinal());
            });
            set(SLOTS[i], icon, e -> choose(d, c, cost, e.isShiftClick()));
        }
        set(22, icon(Material.ENCHANTED_BOOK, "<yellow>Moje dovednosti", "<gray>Přehled a ovládání."), e -> new SkillsMenu(plugin, player).open());
        border();
        if (frame == 0) {
            animate(3L, this::step);
        }
    }

    /** Běžící světla po okraji menu. */
    private void border() {
        for (int i = 0; i < 27; i++) {
            boolean edge = i < 9 || i >= 18 || i % 9 == 0 || i % 9 == 8;
            if (edge && i != 22) {
                int pos = i < 9 ? i : i >= 18 ? 26 - i + 9 : (i % 9 == 8 ? 9 : 19);
                setRaw(i, pane((pos + frame) % 5 == 0 ? Material.YELLOW_STAINED_GLASS_PANE
                        : (pos + frame) % 5 == 1 ? Material.ORANGE_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE));
            }
        }
    }

    private void step() {
        frame++;
        if (chosen != null) {
            celebrateFrame();
            return;
        }
        if (revealed < 4 && frame % 3 == 0) {
            revealed++;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, NOTES[revealed - 1]);
            redraw();
        } else if (frame % 4 == 0 && revealed >= 4) {
            redraw();
        } else {
            border();
        }
    }

    private void choose(PlayerData d, RpgClass c, long cost, boolean shift) {
        if (d.rpgClass == c || chosen != null) {
            return;
        }
        if (d.rpgClass == null) {
            d.rpgClass = c;
            String starter = switch (c) {
                case WARRIOR -> "training_sword";
                case MAGE -> "apprentice_wand";
                case ARCHER -> "hunter_bow";
                case ROGUE -> "rusty_dagger";
            };
            plugin.items().give(player, plugin.items().create(starter, 1));
            if (c == RpgClass.ARCHER) {
                plugin.items().give(player, new ItemStack(Material.ARROW, 32));
            }
        } else {
            if (!shift) {
                plugin.players().notify(player, "<gray>Pro změnu třídy drž Shift.");
                return;
            }
            if (d.coins < cost) {
                plugin.players().notify(player, "<red>Změna třídy stojí " + cost + " mincí.");
                return;
            }
            d.coins -= cost;
            d.rpgClass = c;
        }
        plugin.players().recalc(player);
        chosen = c;
        fillStep = 0;
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.9f, 1.2f);
    }

    /** Menu se od vybrané třídy do stran zaplní její barvou, pak se zavře. */
    private void celebrateFrame() {
        int center = SLOTS[chosen.ordinal()] % 9;
        Material pane = PANES[chosen.ordinal()];
        for (int i = 0; i < 27; i++) {
            if (i != SLOTS[chosen.ordinal()] && Math.abs(i % 9 - center) == fillStep) {
                setRaw(i, pane(pane));
            }
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 0.7f + fillStep * 0.12f);
        if (++fillStep > 9) {
            RpgClass c = chosen;
            stopAnimation();
            player.closeInventory();
            celebrate(plugin, player, c);
        }
    }

    public static void celebrate(RPGCore plugin, Player player, RpgClass c) {
        player.showTitle(Title.title(Text.plain(c.label, c.color), Text.mm("<gray>" + c.description)));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        Text.send(player, "<green>Tvá třída je teď <white>" + c.label + "<green>. <gray>Ovládání dovedností najdeš v <yellow>/skills<gray>.");
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(c.color.value()), 1.4f);
        new BukkitRunnable() {
            int t;

            @Override
            public void run() {
                if (t++ > 40 || !player.isOnline()) {
                    cancel();
                    return;
                }
                Location base = player.getLocation();
                for (int arm = 0; arm < 2; arm++) {
                    double a = t * 0.45 + arm * Math.PI;
                    Location at = base.clone().add(Math.cos(a) * 1.1, t * 0.06, Math.sin(a) * 1.1);
                    player.getWorld().spawnParticle(Particle.DUST, at, 3, 0.03, 0.03, 0.03, 0, dust);
                }
                if (t == 40) {
                    player.getWorld().spawnParticle(Particle.FIREWORK, base.add(0, 2.4, 0), 40, 0.2, 0.2, 0.2, 0.12);
                    player.playSound(base, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1.2f);
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> plugin.players().recalc(player));
    }
}
