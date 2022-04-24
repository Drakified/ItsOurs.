package me.drex.itsours.claim.permission.util;

import me.drex.itsours.claim.AbstractClaim;
import me.drex.itsours.claim.permission.Permission;
import me.drex.itsours.claim.permission.util.context.ContextEntry;
import me.drex.itsours.claim.permission.util.context.PermissionContext;
import me.drex.itsours.claim.permission.util.context.Priority;
import me.drex.itsours.claim.permission.util.node.util.GroupNode;
import me.drex.itsours.claim.permission.util.node.util.Node;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Optional;

public class PermissionMap extends HashMap<String, Boolean> {

    public PermissionMap(NbtCompound tag) {
        fromNBT(tag);
    }

    public void fromNBT(NbtCompound tag) {
        tag.getKeys().forEach(permission -> this.put(permission, tag.getBoolean(permission)));
    }

    public NbtCompound toNBT() {
        NbtCompound tag = new NbtCompound();
        this.forEach(tag::putBoolean);
        return tag;
    }

    public PermissionContext getPermission(AbstractClaim claim, Permission permission, Priority priority) {
        PermissionContext context = new PermissionContext(permission);
        if (this.containsKey(permission.asString()))
            context.addEntry(ContextEntry.of(claim, priority, permission, getValue(permission)));
        for (int i = permission.getNodes().size() - 1; i > 0; i--) {
            Node node = permission.getNodes().get(i);
            Node child = permission.getNodes().get(i - 1);
            for (Node childNode : node.getNodes()) {
                if (childNode instanceof GroupNode groupNode) {
                    if (groupNode.contains(child.getId())) {
                        String s = permission.asString(groupNode.getId(), i - 1);
                        if (this.containsKey(s)) {
                            Optional<Permission> optional = Permission.permission(s);
                            optional.ifPresent(perm -> context.addEntry(ContextEntry.of(claim, priority, perm, getValue(s))));
                        }
                    }
                }
            }
        }
        return context;
    }

    public void setPermission(String permission, Permission.Value value) {
        if (value == Permission.Value.UNSET) this.remove(permission);
        else this.put(permission, value.value);
    }

    public Permission.Value getValue(Permission permission) {
        return getValue(permission.asString());
    }

    public Permission.Value getValue(String permission) {
        Boolean bool = this.get(permission);
        if (bool != null) {
            return Permission.Value.of(bool);
        } else {
            return Permission.Value.UNSET;
        }
    }

    public Text toText() {
        MutableText text = Text.empty();
        for (Entry<String, Boolean> entry : this.entrySet()) {
            text.append(
                    Text.literal(entry.getKey()).formatted(entry.getValue() ? Formatting.GREEN : Formatting.RED)
            ).append(" ");
        }
        return text;
    }

}
