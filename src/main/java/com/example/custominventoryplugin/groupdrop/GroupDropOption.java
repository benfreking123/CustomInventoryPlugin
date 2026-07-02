package com.example.custominventoryplugin.groupdrop;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One selectable entry in a {@link GroupDrop}. The {@code slot} is both the
 * editor placement slot AND the chooser display slot (WYSIWYG fidelity).
 *
 * The placed {@code icon} is the display item and, if no explicit bundle is
 * configured, also the single granted item. A bundle ({@code grants}) lets one
 * option hand out several items; {@code commands} run arbitrary console commands
 * (itemgen / perms / money / etc.) with {@code %player%} substituted.
 */
public class GroupDropOption {

    private int slot;
    private ItemStack icon;
    private String label;
    private final List<ItemStack> grants = new ArrayList<>();
    private final List<String> commands = new ArrayList<>();

    public GroupDropOption(int slot, ItemStack icon) {
        this.slot = slot;
        this.icon = icon;
    }

    public int getSlot()                 { return slot; }
    public void setSlot(int slot)        { this.slot = slot; }
    public ItemStack getIcon()           { return icon; }
    public void setIcon(ItemStack icon)  { this.icon = icon; }
    public String getLabel()             { return label; }
    public void setLabel(String label)   { this.label = label; }
    public List<ItemStack> getGrants()   { return grants; }
    public List<String> getCommands()    { return commands; }

    /** Items actually granted: the explicit bundle, or the icon if none set. */
    public List<ItemStack> effectiveGrants() {
        if (!grants.isEmpty()) return grants;
        List<ItemStack> single = new ArrayList<>();
        if (icon != null && !icon.getType().isAir()) single.add(icon.clone());
        return single;
    }

    /** True if there is something worth previewing (a real bundle or commands). */
    public boolean hasPreview() {
        return effectiveGrants().size() > 1 || !commands.isEmpty();
    }
}
