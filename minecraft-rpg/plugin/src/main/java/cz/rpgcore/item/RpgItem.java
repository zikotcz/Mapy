package cz.rpgcore.item;

import cz.rpgcore.player.RpgClass;
import cz.rpgcore.util.Element;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Definice jednoho předmětu načtená z items.yml. */
public final class RpgItem {
    public enum AbilityType {BOLT, CONE, NOVA, STRIKE, DASH, HEAL}

    public enum UseType {HEAL, MANA, BUFF, FOOD, XP, TELEPORT, RESET_STATS}

    /** Schopnost na pravý klik. */
    public record Ability(AbilityType type, Element element, String name, double power, double range,
                          double radius, double mana, double cooldown, boolean pull, boolean pierce, double drain) {
    }

    /** Efekt při zásahu (šance v %). */
    public record OnHit(Element element, double chance) {
    }

    /** Co udělá spotřební předmět. */
    public record Use(UseType type, Stat stat, double amount, int duration) {
    }

    public final String id;
    public String name;
    public ItemType type;
    public Rarity rarity = Rarity.COMMON;
    public int level = 1;
    public final Set<RpgClass> classes = EnumSet.noneOf(RpgClass.class);
    public final Map<Stat, Double> stats = new EnumMap<>(Stat.class);
    public Ability ability;
    public OnHit onHit;
    public Use use;
    public final List<String> lore = new ArrayList<>();
    public boolean glint;
    public long price;
    public long sell;
    public String setId;     // set brnění, ke kterému kus patří
    public String boss;      // boss, kterého vyvolávací předmět přivolá

    public RpgItem(String id) {
        this.id = id;
    }
}
