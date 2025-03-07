package me.drex.itsours.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.drex.itsours.claim.AbstractClaim;
import me.drex.itsours.claim.permission.Permission;
import me.drex.itsours.claim.permission.PermissionManager;
import me.drex.itsours.claim.permission.context.GlobalContext;
import me.drex.itsours.claim.permission.node.Node;
import me.drex.itsours.claim.permission.util.Modify;
import me.drex.itsours.claim.permission.util.Value;
import me.drex.itsours.command.argument.ClaimArgument;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static me.drex.itsours.command.argument.PermissionArgument.*;
import static net.minecraft.server.command.CommandManager.literal;

public class SettingCommand extends AbstractCommand {

    public static final SettingCommand INSTANCE = new SettingCommand();

    public SettingCommand() {
        super("settings");
    }

    @Override
    protected void register(LiteralArgumentBuilder<ServerCommandSource> literal) {
        literal.then(
                ClaimArgument.ownClaims()
                        .then(
                                literal("check").then(
                                        permission()
                                                .executes(ctx -> executeCheck(ctx.getSource(), ClaimArgument.getClaim(ctx), getPermission(ctx)))
                                )
                        ).then(
                                literal("set").then(
                                        permission().then(
                                                value()
                                                        .executes(ctx -> executeSet(ctx.getSource(), ClaimArgument.getClaim(ctx), getPermission(ctx), getValue(ctx)))
                                        )
                                )
                        ).then(
                                literal("unset").then(
                                        permission()
                                                .executes(ctx -> executeSet(ctx.getSource(), ClaimArgument.getClaim(ctx), getPermission(ctx), Value.UNSET))
                                )
                        )
        );
    }

    public int executeSet(ServerCommandSource src, AbstractClaim claim, Permission permission, Value value) throws CommandSyntaxException {
        validatePermission(src, claim, PermissionManager.MODIFY, Modify.SETTING.node());
        permission.validateContext(new Node.ChangeContext(claim, GlobalContext.INSTANCE, value, src));
        claim.getPermissionHolder().getSettings().set(permission, value);
        src.sendFeedback(() -> Text.translatable("text.itsours.commands.globalSetting.set",
                permission.asString(), claim.getFullName(), value.format()
        ), false);
        return 1;
    }

    public int executeCheck(ServerCommandSource src, AbstractClaim claim, Permission permission) throws CommandSyntaxException {
        validatePermission(src, claim, PermissionManager.MODIFY, Modify.SETTING.node());
        Value value = claim.getPermissionHolder().getSettings().get(permission);
        src.sendFeedback(() -> Text.translatable("text.itsours.commands.globalSetting.check", permission.asString(), claim.getFullName(), value.format()), false);
        return 1;
    }

}
