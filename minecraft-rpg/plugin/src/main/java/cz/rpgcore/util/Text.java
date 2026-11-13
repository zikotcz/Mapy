package cz.rpgcore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/** Pomocné funkce pro text (MiniMessage, formátování čísel). */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Component PREFIX = mm("<dark_gray>[<gold>RPG<dark_gray>] <gray>");

    private Text() {
    }

    /** MiniMessage -> Component bez kurzívy (lore a názvy jsou jinak kurzívou). */
    public static Component mm(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static Component plain(String s, TextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    public static void send(CommandSender to, String miniMessage) {
        to.sendMessage(PREFIX.append(mm(miniMessage)));
    }

    /** Zabrání tomu, aby se text od hráče/z configu bral jako formátovací tagy. */
    public static String esc(String s) {
        return MM.escapeTags(s);
    }

    public static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.05) {
            return String.valueOf(Math.round(v));
        }
        return String.format(Locale.ROOT, "%.1f", v);
    }

    public static TextColor hex(String hex) {
        TextColor c = TextColor.fromHexString(hex);
        return c == null ? TextColor.color(0xFFFFFF) : c;
    }
}
