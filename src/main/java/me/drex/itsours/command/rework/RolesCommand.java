package me.drex.itsours.command.rework;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import me.drex.itsours.claim.permission.rework.PermissionStorage;
import me.drex.itsours.claim.permission.rework.roles.RoleManagerRework;
import me.drex.itsours.claim.permission.rework.roles.RoleRework;
import me.drex.itsours.command.rework.argument.RoleArgument;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class RolesCommand extends AbstractCommand {

    public static final RolesCommand INSTANCE = new RolesCommand();

    public static final CommandSyntaxException ALREADY_EXISTS = new SimpleCommandExceptionType(Text.translatable("text.itsours.argument.role.already_exists")).create();
    private static final String LITERAL = "roles";
    private static final String LITERAL_UPDATE_ORDER = "updateOrder";

    private RolesCommand() {
        super(LITERAL);
    }

    @Override
    protected void register(LiteralArgumentBuilder<ServerCommandSource> literal) {
        // TODO: check permissions
        literal
                .then(
                        literal(LITERAL_UPDATE_ORDER)
                                .then(
                                    RoleArgument.roles()
                                            .then(
                                                    argument("offset", IntegerArgumentType.integer())
                                                            .executes(ctx -> executeUpdateOffset(ctx.getSource(), RoleArgument.getRole(ctx), IntegerArgumentType.getInteger(ctx, "offset")))
                                            )
                                )
                )
                .then(
                        literal("add")
                                .then(
                                        argument("role", StringArgumentType.string())
                                                .executes(ctx -> executeAddRole(ctx.getSource(), StringArgumentType.getString(ctx, "role")))
                                )
                )
                .then(
                        literal("remove")
                                .then(
                                        RoleArgument.roles()
                                                .executes(ctx -> executeRemoveRole(ctx.getSource(), RoleArgument.getRole(ctx)))
                                )
                )
                /*.then(
                        // TODO:
                        argument("role", StringArgumentType.string())
                                .executes(ctx -> executeOpenGui(ctx.getSource(), StringArgumentType.getString(ctx, "role")))
                )*/
                .executes(ctx -> executeListRoles(ctx.getSource()));
    }

    private int executeListRoles(ServerCommandSource src) {
        List<RoleRework> orderedRoles = RoleManagerRework.INSTANCE.getOrderedRoles();
        src.sendFeedback(Text.translatable("text.itsours.commands.roles"), false);
        for (int i = 0; i < orderedRoles.size(); i++) {
            boolean first = i == 0;
            boolean last = i == orderedRoles.size() - 1;
            RoleRework role = orderedRoles.get(i);
            src.sendFeedback(Text.translatable("text.itsours.commands.roles.entry",
                    RoleManagerRework.INSTANCE.getName(role)
                            .styled(style -> style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, role.permissions().toText()))),
                    Text.translatable("text.itsours.commands.roles.entry.down")
                            .styled(
                                    style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, String.format("/%s %s %s %s %d", CommandManager.LITERAL, LITERAL, LITERAL_UPDATE_ORDER, role.getId(), 1)))
                            ).formatted(last ? Formatting.WHITE : Formatting.AQUA),
                    Text.translatable("text.itsours.commands.roles.entry.up")
                            .styled(
                                    style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, String.format("/%s %s %s %s %d", CommandManager.LITERAL, LITERAL, LITERAL_UPDATE_ORDER, role.getId(), -1)))
                            ).formatted(first ? Formatting.WHITE : Formatting.AQUA)
            ), false);
        }
        return orderedRoles.size();
    }

    private int executeAddRole(ServerCommandSource src, String roleId) throws CommandSyntaxException {
        RoleRework role = RoleManagerRework.INSTANCE.getRole(roleId);
        if (role != null) throw ALREADY_EXISTS;
        role = new RoleRework(roleId, PermissionStorage.storage());
        RoleManagerRework.INSTANCE.addRole(role);
        src.sendFeedback(Text.translatable("text.itsours.commands.roles.add", roleId), false);
        return 1;
    }

    private int executeRemoveRole(ServerCommandSource src, RoleRework role) {
        RoleManagerRework.INSTANCE.removeRole(role);
        src.sendFeedback(Text.translatable("text.itsours.commands.roles.remove", role.getId()), false);
        return 1;
    }

    private int executeUpdateOffset(ServerCommandSource src, RoleRework role, int offset) {
        RoleManagerRework.INSTANCE.updateRoleOrder(role, offset);
        return executeListRoles(src);
    }

}
