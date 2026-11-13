package cz.rpgcore.item;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

public enum ItemType {
    // Všechny zbraně nablízko stojí na železném meči - nemá žádné vedlejší chování
    // (sekera by loupala kůru, motyka orala, trojzubec by se házel).
    SWORD("Meč", Material.IRON_SWORD, Kind.MELEE, -2.4, 1),
    GREATSWORD("Obouruční meč", Material.IRON_SWORD, Kind.MELEE, -2.9, 1),
    AXE("Sekera", Material.IRON_SWORD, Kind.MELEE, -3.0, 1),
    HAMMER("Kladivo", Material.IRON_SWORD, Kind.MELEE, -3.2, 1),
    SCYTHE("Kosa", Material.IRON_SWORD, Kind.MELEE, -2.8, 1),
    SPEAR("Kopí", Material.IRON_SWORD, Kind.MELEE, -2.6, 1),
    DAGGER("Dýka", Material.IRON_SWORD, Kind.MELEE, -1.6, 1),
    STAFF("Hůl", Material.BREEZE_ROD, Kind.MAGIC, -2.4, 1),
    WAND("Hůlka", Material.BREEZE_ROD, Kind.MAGIC, -2.4, 1),
    BOW("Luk", Material.BOW, Kind.RANGED, 0, 1),
    HELMET("Helma", Material.IRON_HELMET, Kind.ARMOR, 0, 1),
    CHESTPLATE("Zbroj", Material.IRON_CHESTPLATE, Kind.ARMOR, 0, 1),
    LEGGINGS("Kalhoty", Material.IRON_LEGGINGS, Kind.ARMOR, 0, 1),
    BOOTS("Boty", Material.IRON_BOOTS, Kind.ARMOR, 0, 1),
    // Ozvěnový střep nemá ve vanille žádné použití kromě craftingu (ten blokujeme).
    ACCESSORY("Doplněk", Material.ECHO_SHARD, Kind.OTHER, 0, 1),
    CONSUMABLE("Spotřební předmět", Material.ECHO_SHARD, Kind.OTHER, 0, 16),
    MATERIAL("Materiál", Material.ECHO_SHARD, Kind.OTHER, 0, 64),
    SUMMON("Vyvolávací předmět", Material.ECHO_SHARD, Kind.OTHER, 0, 16),
    CRATE("Bedna", Material.ECHO_SHARD, Kind.OTHER, 0, 16);

    public enum Kind {MELEE, MAGIC, RANGED, ARMOR, OTHER}

    public final String label;
    public final Material material;
    public final Kind kind;
    public final double attackSpeed;
    public final int maxStack;

    ItemType(String label, Material material, Kind kind, double attackSpeed, int maxStack) {
        this.label = label;
        this.material = material;
        this.kind = kind;
        this.attackSpeed = attackSpeed;
        this.maxStack = maxStack;
    }

    public boolean isWeapon() {
        return kind == Kind.MELEE || kind == Kind.MAGIC || kind == Kind.RANGED;
    }

    public boolean isArmor() {
        return kind == Kind.ARMOR;
    }

    public EquipmentSlot slot() {
        return switch (this) {
            case HELMET -> EquipmentSlot.HEAD;
            case CHESTPLATE -> EquipmentSlot.CHEST;
            case LEGGINGS -> EquipmentSlot.LEGS;
            case BOOTS -> EquipmentSlot.FEET;
            default -> EquipmentSlot.HAND;
        };
    }
}
