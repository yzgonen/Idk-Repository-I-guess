package dev.wtuxy.auction;

import org.bukkit.ChatColor;
import org.bukkit.Material;

public enum TeamColor {
    PURPLE(ChatColor.DARK_PURPLE, Material.PURPLE_DYE),
    BLUE(ChatColor.BLUE, Material.BLUE_DYE),
    WHITE(ChatColor.WHITE, Material.WHITE_DYE),
    YELLOW(ChatColor.YELLOW, Material.YELLOW_DYE),
    ORANGE(ChatColor.GOLD, Material.ORANGE_DYE),
    RED(ChatColor.RED, Material.RED_DYE),
    GREEN(ChatColor.GREEN, Material.GREEN_DYE),
    BLACK(ChatColor.BLACK, Material.BLACK_DYE);

    private final ChatColor chatColor;
    private final Material dye;

    TeamColor(ChatColor chatColor, Material dye) {
        this.chatColor = chatColor;
        this.dye = dye;
    }

    public ChatColor chatColor() { return chatColor; }
    public Material dye() { return dye; }

    public static TeamColor parse(String value) {
        return TeamColor.valueOf(value.toUpperCase());
    }
}
