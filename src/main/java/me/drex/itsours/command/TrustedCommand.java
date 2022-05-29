package me.drex.itsours.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import me.drex.itsours.claim.AbstractClaim;
import me.drex.itsours.claim.permission.holder.PlayerRoleHolder;
import me.drex.itsours.claim.permission.roles.RoleManager;
import me.drex.itsours.claim.permission.roles.Role;
import me.drex.itsours.command.argument.ClaimArgument;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.text.Texts;

import java.util.*;

public class TrustedCommand extends AbstractCommand {

    public static final TrustedCommand INSTANCE = new TrustedCommand();

    public TrustedCommand() {
        super("trusted");
    }

    @Override
    protected void register(LiteralArgumentBuilder<ServerCommandSource> literal) {
        literal.then(
                        ClaimArgument.ownClaims()
                                .executes(ctx -> executeTrusted(ctx.getSource(), ClaimArgument.getClaim(ctx)))
                )
                .executes(ctx -> executeTrusted(ctx.getSource(), getClaim(ctx.getSource().getPlayer())));
    }

    private int executeTrusted(ServerCommandSource src, AbstractClaim claim) {
        Map<UUID, PlayerRoleHolder> roles = claim.getPermissionManager().getRoles();
        List<GameProfile> profiles = new LinkedList<>();
        for (Map.Entry<UUID, PlayerRoleHolder> entry : roles.entrySet()) {
            Role trusted = RoleManager.INSTANCE.getRole(RoleManager.TRUSTED_ID);
            if (entry.getValue().getRoles().contains(trusted)) {
                Optional<GameProfile> optional = src.getServer().getUserCache().getByUuid(entry.getKey());
                profiles.add(optional.orElse(new GameProfile(entry.getKey(), null)));
            }
        }

        if (profiles.isEmpty()) src.sendError(Text.translatable("text.itsours.commands.trusted.nobody_trusted"));
        else src.sendFeedback(Text.translatable("text.itsours.commands.trusted",
                Texts.join(profiles, Texts::toText)
        ), false);
        return 1;
    }

}
