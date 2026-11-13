package cz.rpgcore.item;

import net.kyori.adventure.text.format.TextColor;

public enum Rarity {
    COMMON("Běžný", 0xFFFFFF),
    UNCOMMON("Neobvyklý", 0x55FF55),
    RARE("Vzácný", 0x5599FF),
    EPIC("Epický", 0xC060FF),
    LEGENDARY("Legendární", 0xFFAA00),
    MYTHIC("Mýtický", 0xFF4060);

    public final String label;
    public final TextColor color;

    Rarity(String label, int rgb) {
        this.label = label;
        this.color = TextColor.color(rgb);
    }
}
