package me.drex.itsours.gui.screen;

import me.drex.itsours.claim.permission.Permission;
import me.drex.itsours.claim.permission.util.node.util.GroupNode;
import me.drex.itsours.claim.permission.util.node.util.Node;
import me.drex.itsours.gui.util.ScreenHelper;
import me.drex.itsours.gui.util.SlotEntry;
import me.drex.itsours.gui.util.context.ClaimContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractMapScreen<K extends ClaimContext> extends PagedScreen<K> {

    protected final Node node;
    protected List<Node> nodes;
    private int cachedSize = 0;
    private Node.CompareMode compareMode;
    private FilterMode filterMode;

    public AbstractMapScreen(ServerPlayerEntity player, int rows, K context, SimpleScreen<?> previous, Node node, Node.CompareMode compareMode, FilterMode filterMode) {
        super(player, rows, context, previous);
        this.node = node;
        this.compareMode = compareMode;
        this.filterMode = node instanceof GroupNode ? FilterMode.ALL : filterMode;

        filter();
        sort();
    }

    public void filter() {
        if (nodes != null) cachedSize = nodes.size();
        if (node instanceof GroupNode) {
            nodes = new ArrayList<>(node.getContained());
        } else {
            nodes = new ArrayList<>(node.getNodes());
        }
        if (filterMode == FilterMode.CHANGED) {
            nodes.removeIf(next -> getValue(getPermission(next)).equals(Permission.Value.UNSET));
        } else if (filterMode == FilterMode.CHANGED_SUBNODES) {
            nodes.removeIf(next -> getChanged(getPermission(), next) < 2);
        }
    }

    private int getChanged(String perm, Node node) {
        int result = 0;
        String s = perm.equals("") ? node.getId() : perm + "." + node.getId();
        if (!getValue(s).equals(Permission.Value.UNSET)) {
            result++;
        }
        for (Node child : node.getNodes()) {
            int changed = getChanged(s, child);
            result += changed;
        }
        return result;
    }

    public void sort() {
        nodes.sort((o1, o2) -> o1.compareTo(o2, compareMode));
    }

    public String getPermission() {
        if (previous instanceof AbstractMapScreen permissionScreen) {
            return permissionScreen.getPermission() + node.getId();
        } else {
            return node.getId();
        }
    }

    public String getPermission(Node node) {
        String s = getPermission();
        return s.equals("") ? node.getId() : s + "." + node.getId();
    }

    public ItemStack buildItem(Node node) {
        ItemStack item = new ItemStack(node.getItem());
        if (item.isEmpty()) item = new ItemStack(Items.BARRIER);
        if (!(this.node instanceof GroupNode)) {
            String perm = getPermission(node);
            Permission.Value value = getValue(perm);
            MutableText text = Text.translatable("text.itsours.gui.map.item.hover.value", value.format());
            ScreenHelper.addLore(item, text);
            if (value == Permission.Value.TRUE) ScreenHelper.addGlint(item);
            ScreenHelper.setCustomName(item, perm);
            ScreenHelper.addLore(item, Text.translatable("text.itsours.gui.map.item.hover.left_click"));
            if (!node.getNodes().isEmpty() || node instanceof GroupNode) {
                ScreenHelper.addLore(item, Text.translatable("text.itsours.gui.map.item.hover.right_click"));
            }
            if (filterMode == FilterMode.CHANGED_SUBNODES) {
                int changed = getChanged(getPermission(), node);
                if (changed > 0) {
                    ScreenHelper.addLore(item, Text.translatable("text.itsours.gui.map.item.hover.changed_subnode", changed).formatted(Formatting.GOLD));
                }
            }
        } else {
            ScreenHelper.setCustomName(item, node.getId());
        }
        if (!node.getInformation().equals("-"))
            ScreenHelper.addLore(item, Text.literal(node.getInformation()).formatted(Formatting.AQUA));
        return item;
    }

    public abstract void executeSet(String permission, Permission.Value value);

    public abstract Permission.Value getValue(String perm);

    public abstract AbstractMapScreen<K> buildScreen(ServerPlayerEntity player, int rows, K context, SimpleScreen<?> previous, Node node, Node.CompareMode compareMode, FilterMode filterMode);

    @Override
    public void draw() {
        filter();
        sort();
        ItemStack orderItem = new ItemStack(Items.COMPASS);
        ScreenHelper.setCustomName(orderItem, Text.translatable("text.itsours.gui.map.sort"));
        for (Node.CompareMode value : Node.CompareMode.values()) {
            ScreenHelper.addLore(orderItem, Text.literal(value.getName()).formatted(value == compareMode ? Formatting.AQUA : Formatting.GRAY));
        }
        SlotEntry<K> order = new SlotEntry<>(orderItem, (permissionContext, leftClick, shiftClick) -> {
            int length = Node.CompareMode.values().length;
            compareMode = Node.CompareMode.values()[(compareMode.ordinal() + (leftClick ? 1 : length - 1)) % length];
            draw();
        });
        addSlot(order, 8);

        ItemStack filterItem = new ItemStack(Items.HOPPER);
        ScreenHelper.setCustomName(filterItem, Text.translatable("text.itsours.gui.map.filter"));
        for (FilterMode value : FilterMode.values()) {
            ScreenHelper.addLore(filterItem, Text.literal(value.getName()).formatted(value == filterMode ? Formatting.AQUA : Formatting.GRAY));
        }
        SlotEntry<K> filter = new SlotEntry<>(filterItem, (permissionContext, leftClick, shiftClick) -> {
            int length = FilterMode.values().length;
            filterMode = FilterMode.values()[(filterMode.ordinal() + (leftClick ? 1 : length - 1)) % length];
            draw();
        });
        if (!(this.node instanceof GroupNode)) addSlot(filter, 7);

        for (Node n : nodes) {
            SlotEntry<K> slotEntry = new SlotEntry<>(buildItem(n), (claimContext, leftClick, shiftClick) -> {
                if (leftClick && !(this.node instanceof GroupNode)) {
                    //Switch state
                    String s = getPermission();
                    String perm = s.equals("") ? n.getId() : s + "." + n.getId();
                    Permission.Value value = getValue(perm);
                    int ordinal = value.ordinal() + 1;
                    Permission.Value next = Permission.Value.values()[ordinal % 3];
                    executeSet(perm, next);
                    draw();
                } else if (!n.getNodes().isEmpty() || n instanceof GroupNode) {
                    //Open subnodes
                    close();
                    AbstractMapScreen<K> screen = buildScreen(player, rows, context, this, n, compareMode, filterMode == FilterMode.CHANGED_SUBNODES ? FilterMode.CHANGED : filterMode);
                    screen.render();
                }
            });
            addPageEntry(slotEntry);
        }

        for (int i = nodes.size(); i < cachedSize; i++) {
            addPageEntry(fill);
        }

        super.draw();
    }

    protected enum FilterMode {
        ALL("All"), CHANGED("Changed only"), CHANGED_SUBNODES("Changed subnodes");
        private final String name;

        FilterMode(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

}
