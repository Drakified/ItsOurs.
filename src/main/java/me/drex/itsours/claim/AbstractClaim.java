package me.drex.itsours.claim;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import me.drex.itsours.ItsOurs;
import me.drex.itsours.claim.permission.Permission;
import me.drex.itsours.claim.permission.PermissionManager;
import me.drex.itsours.claim.permission.RestrictionManager;
import me.drex.itsours.claim.permission.roles.Role;
import me.drex.itsours.claim.permission.util.context.ContextEntry;
import me.drex.itsours.claim.permission.util.context.PermissionContext;
import me.drex.itsours.claim.permission.util.context.Priority;
import me.drex.itsours.user.ClaimPlayer;
import me.drex.itsours.user.PlayerList;
import me.drex.itsours.user.Settings;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Pattern;

import static me.drex.itsours.claim.AbstractClaim.Util.getPosOnGround;

public abstract class AbstractClaim {

    public static final Pattern NAME = Pattern.compile("\\w{3,16}");
    private static final Logger LOGGER = ItsOurs.LOGGER;
    private static final Block[] SHOW_BLOCKS = {Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.LAPIS_BLOCK};
    public BlockPos min, max, tp;
    private String name;
    private UUID owner;
    private RegistryKey<World> dimension;
    private final List<Subzone> subzoneList = new ArrayList<>();
    private PermissionManager permissionManager;
    private RestrictionManager restrictionManager;

    public AbstractClaim(String name, UUID owner, BlockPos first, BlockPos second, ServerWorld world, BlockPos tp) {
        this.name = name;
        this.owner = owner;
        int minX, minY, minZ, maxX, maxY, maxZ;
        minX = Math.min(first.getX(), second.getX());
        maxX = Math.max(first.getX(), second.getX());
        minY = Math.min(first.getY(), second.getY());
        maxY = Math.max(first.getY(), second.getY());
        minZ = Math.min(first.getZ(), second.getZ());
        maxZ = Math.max(first.getZ(), second.getZ());
        this.min = new BlockPos(minX, minY, minZ);
        this.max = new BlockPos(maxX, maxY, maxZ);
        this.dimension = world.getRegistryKey();
        this.tp = tp;
        this.permissionManager = new PermissionManager(new NbtCompound());
        this.restrictionManager = new RestrictionManager(new NbtCompound());
    }

    public AbstractClaim(NbtCompound tag) {
        fromNBT(tag);
    }

    public static boolean isNameInvalid(String name) {
        return !NAME.matcher(name).matches();
    }

    public RestrictionManager getRestrictionManager() {
        return restrictionManager;
    }

    public void fromNBT(NbtCompound tag) {
        this.name = tag.getString("name");
        this.owner = tag.getUuid("owner");
        NbtCompound position = tag.getCompound("position");
        this.min = Util.blockPosFromNBT(position.getCompound("min"));
        this.max = Util.blockPosFromNBT(position.getCompound("max"));
        this.tp = Util.blockPosFromNBT(position.getCompound("tp"));
        // TODO: Add option to ignore claims which are located in unknown worlds
        this.dimension = World.CODEC.parse(NbtOps.INSTANCE, position.get("world")).resultOrPartial(LOGGER::error).orElse(World.OVERWORLD);
        NbtList list = (NbtList) tag.get("subzones");
        if (list != null) {
            list.forEach(subzones -> {
                Subzone subzone = new Subzone((NbtCompound) subzones, this);
                subzoneList.add(subzone);
            });
        }
        this.permissionManager = new PermissionManager(tag.getCompound("permissions"));
        this.restrictionManager = new RestrictionManager(tag.getCompound("restrictions"));
    }

    public NbtCompound toNBT() {
        NbtCompound tag = new NbtCompound();
        tag.putString("name", this.name);
        tag.putUuid("owner", this.owner);
        NbtCompound position = new NbtCompound();
        position.put("min", Util.blockPosToNBT(this.min));
        position.put("max", Util.blockPosToNBT(this.max));
        if (tp != null) {
            position.put("tp", Util.blockPosToNBT(this.tp));
        }
        Identifier.CODEC.encodeStart(NbtOps.INSTANCE, this.dimension.getValue()).resultOrPartial(LOGGER::error).ifPresent(nbt -> position.put("world", nbt));

        tag.put("position", position);
        if (!subzoneList.isEmpty()) {
            NbtList list = new NbtList();
            subzoneList.forEach(subzone -> list.add(subzone.toNBT()));
            tag.put("subzones", list);
        }
        tag.put("permissions", this.permissionManager.toNBT());
        tag.put("restrictions", this.restrictionManager.toNBT());
        return tag;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public abstract String getFullName();

    public UUID getOwner() {
        return this.owner;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    @Nullable
    public ServerWorld getWorld() {
        return ItsOurs.INSTANCE.server.getWorld(this.dimension);
    }

    public RegistryKey<World> getDimension() {
        return this.dimension;
    }

    public List<Subzone> getSubzones() {
        return this.subzoneList;
    }

    public void addSubzone(Subzone subzone) {
        this.subzoneList.add(subzone);
    }

    public void removeSubzone(Subzone subzone) {
        this.subzoneList.remove(subzone);
    }

    public void onEnter(Optional<AbstractClaim> previousClaim, ServerPlayerEntity player) {
        if (previousClaim.isEmpty()) {
            PlayerList.set(player.getUuid(), Settings.CACHED_FLIGHT, player.getAbilities().allowFlying);
        }
        boolean hasPermission = ItsOurs.hasPermission(player.getCommandSource(), "itsours.fly") && player.getWorld().equals(player.getServer().getOverworld());
        boolean cachedFlying = hasPermission && player.getAbilities().flying;
        // Update abilities for respective gamemode
        player.interactionManager.getGameMode().setAbilities(player.getAbilities());
        // Enable flying if player enabled it
        if (!player.getAbilities().allowFlying) {
            player.getAbilities().allowFlying = PlayerList.get(player.getUuid(), Settings.FLIGHT) && hasPermission;
        }
        // Set the flight state to what it was before entering
        if (player.getAbilities().allowFlying) {
            player.getAbilities().flying = cachedFlying;
        }
        player.sendAbilitiesUpdate();
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(-1, 20, -1));
        player.networkHandler.sendPacket(new OverlayMessageS2CPacket(Text.translatable("text.itsours.claim.enter", this.getFullName())));
    }

    public void onLeave(Optional<AbstractClaim> claim, ServerPlayerEntity player) {
        if (claim.isEmpty()) {
            boolean cachedFlying = player.getAbilities().flying;
            // Update abilities for respective gamemode
            player.interactionManager.getGameMode().setAbilities(player.getAbilities());
            if (cachedFlying && !player.getAbilities().flying) {
                BlockPos pos = getPosOnGround(player.getBlockPos(), player.getWorld());
                if (pos.getY() + 3 < player.getY()) {
                    player.teleport((ServerWorld) player.world, player.getX(), pos.getY(), player.getZ(), player.getYaw(), player.getPitch());
                }
            }
            player.sendAbilitiesUpdate();
        }
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(-1, 20, -1));
        player.networkHandler.sendPacket(new OverlayMessageS2CPacket(Text.translatable("text.itsours.claim.leave", this.getFullName())));
    }

    protected PermissionContext getPermissionContext(UUID uuid, Permission permission) {
        PermissionContext context = new PermissionContext(permission);
        context.combine(this.permissionManager.getPermissionContext(this, uuid, permission));
        if (uuid.equals(owner))
            context.addEntry(ContextEntry.of(this, Priority.OWNER, permission, Permission.Value.TRUE));
        if (PlayerList.get(uuid, Settings.IGNORE))
            context.addEntry(ContextEntry.of(this, Priority.IGNORE, permission, Permission.Value.TRUE));
        context.combine(getRolePermissionContext(uuid, permission));
        return context;
    }

    public PermissionContext getContext(UUID uuid, Permission permission) {
        PermissionContext context = getPermissionContext(uuid, permission);
        return context;
    }

    public boolean hasPermission(UUID uuid, String permission) {
        Optional<Permission> optional = Permission.permission(permission);
        return optional.filter(value -> getContext(uuid, value).getValue().value).isPresent();
    }

    public boolean getSetting(String setting) {
        Optional<Permission> optional = Permission.setting(setting);
        if (optional.isPresent()) {
            PermissionContext context = this.permissionManager.settings.getPermission(this, optional.get(), Priority.SETTING);
            return context.getValue().value;
        } else {
            return false;
        }
    }

    private PermissionContext getRolePermissionContext(UUID uuid, Permission permission) {
        PermissionContext context = new PermissionContext(permission);
        for (Object2IntMap.Entry<Role> roleEntry : getRoles(uuid).object2IntEntrySet()) {
            context.combine(roleEntry.getKey().permissions().getPermission(this, permission, Priority.of(ItsOurs.INSTANCE.getRoleManager().getRoleID(roleEntry.getKey()), 3, roleEntry.getIntValue())));
        }
        if (permission.nodes() > 1) {
            Permission perm = permission.up();
            context.combine(getRolePermissionContext(uuid, perm));
        }
        return context;
    }

    public abstract Object2IntMap<Role> getRoles(UUID uuid);

    public PermissionManager getPermissionManager() {
        return this.permissionManager;
    }

    public abstract int getDepth();

    public int getArea() {
        return getSize().getX() * getSize().getZ();
    }

    public BlockPos getSize() {
        return max.subtract(min).add(1, 1, 1);
    }

    public boolean contains(BlockPos pos) {
        return (min.getX() <= pos.getX() && max.getX() >= pos.getX()) && (min.getY() <= pos.getY() && max.getY() >= pos.getY()) && (min.getZ() <= pos.getZ() && max.getZ() >= pos.getZ());
    }

    public boolean intersects(AbstractClaim claim) {
        if (!claim.dimension.equals(this.dimension)) return false;
        BlockPos a = min, b = max, c = new BlockPos(max.getX(), min.getY(), min.getZ()), d = new BlockPos(min.getX(), max.getY(), min.getZ()), e = new BlockPos(min.getX(), min.getY(), max.getZ()), f = new BlockPos(max.getX(), max.getY(), min.getZ()), g = new BlockPos(max.getX(), min.getY(), max.getZ()), h = new BlockPos(min.getX(), max.getY(), max.getZ());
        return claim.contains(a) || claim.contains(b) || claim.contains(c) || claim.contains(d) || claim.contains(e) || claim.contains(f) || claim.contains(g) || claim.contains(h);
    }

    /**
     * @param uuid      uuid of the player who issued the expansion (this is used to check for claim blocks)
     * @param direction the direction in which a claim should get expanded
     * @param amount    the amount of blocks the claim should get expanded
     * @return amount of claim blocks used
     * @throws CommandSyntaxException if the claim couldn't get expanded
     */
    public abstract int expand(UUID uuid, Direction direction, int amount) throws CommandSyntaxException;

    void undoExpand(Direction direction, int amount) {
        this.expand(direction, -amount);
        for (ServerPlayerEntity player : ItsOurs.INSTANCE.server.getPlayerManager().getPlayerList()) {
            ClaimPlayer claimPlayer = (ClaimPlayer) player;
            if (claimPlayer.getLastShowClaim() == this) {
                this.show(player, true);
            }
        }
    }

    public Optional<AbstractClaim> intersects() {
        return ClaimList.INSTANCE.getClaims().stream().filter(claim ->
                claim.dimension.equals(this.dimension) &&
                        claim.getDepth() == this.getDepth() &&
                        !this.equals(claim) &&
                        (this.intersects(claim) || claim.intersects(this))
        ).findFirst();
    }

    void expand(Direction direction, int amount) {
        if (amount < 0) {
            shrink(direction, -amount);
            return;
        }
        BlockPos modifier = new BlockPos(direction.getOffsetX() * amount, direction.getOffsetY() * amount, direction.getOffsetZ() * amount);
        if (modifier.getX() > 0) {
            max = max.add(modifier.getX(), 0, 0);
        } else {
            min = min.add(modifier.getX(), 0, 0);
        }
        if (modifier.getY() > 0) {
            max = max.add(0, modifier.getY(), 0);
        } else {
            min = min.add(0, modifier.getY(), 0);
        }
        if (modifier.getZ() > 0) {
            max = max.add(0, 0, modifier.getZ());
        } else {
            min = min.add(0, 0, modifier.getZ());
        }
    }

    void shrink(Direction direction, int amount) {
        BlockPos modifier = new BlockPos(direction.getOffsetX() * amount, direction.getOffsetY() * amount, direction.getOffsetZ() * amount);
        if (modifier.getX() < 0) {
            min = min.add(-modifier.getX(), 0, 0);
        } else {
            max = max.add(-modifier.getX(), 0, 0);
        }
        if (modifier.getY() < 0) {
            min = min.add(0, -modifier.getY(), 0);
        } else {
            max = max.add(0, -modifier.getY(), 0);
        }
        if (modifier.getZ() < 0) {
            min = min.add(0, 0, -modifier.getZ());
        } else {
            max = max.add(0, 0, -modifier.getZ());
        }
    }

    public void show(boolean show) {
        for (ServerPlayerEntity player : ItsOurs.INSTANCE.server.getPlayerManager().getPlayerList()) {
            ClaimPlayer claimPlayer = (ClaimPlayer) player;
            if (claimPlayer.getLastShowClaim() == this) {
                this.show(player, show);
            }
        }
    }

    // TODO: Only send packet if chunk is loaded
    public void show(ServerPlayerEntity player, boolean show) {
        BlockState blockState = show ? SHOW_BLOCKS[Math.min(this.getDepth(), SHOW_BLOCKS.length - 1)].getDefaultState() : null;
        int y = ((ClaimPlayer) player).getLastShowClaim() != null ? ((ClaimPlayer) player).getLastShowPos().getY() : player.getBlockPos().getY();
        for (int i = min.getX(); i < max.getX(); i++) {
            sendBlockPacket(player, new BlockPos(getPosOnGround(new BlockPos(i, y, min.getZ()), player.getEntityWorld())).down(), blockState);
        }
        for (int i = min.getZ(); i < max.getZ(); i++) {
            sendBlockPacket(player, new BlockPos(getPosOnGround(new BlockPos(max.getX(), y, i), player.getEntityWorld())).down(), blockState);
        }
        for (int i = max.getX(); i > min.getX(); i--) {
            sendBlockPacket(player, new BlockPos(getPosOnGround(new BlockPos(i, y, max.getZ()), player.getEntityWorld())).down(), blockState);
        }
        for (int i = max.getZ(); i > min.getZ(); i--) {
            sendBlockPacket(player, new BlockPos(getPosOnGround(new BlockPos(min.getX(), y, i), player.getEntityWorld())).down(), blockState);
        }
        for (Subzone subzone : this.getSubzones()) {
            subzone.show(player, show);
        }
    }

    private void sendBlockPacket(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        BlockUpdateS2CPacket packet;
        packet = state == null ? new BlockUpdateS2CPacket(player.getEntityWorld(), pos) : new BlockUpdateS2CPacket(pos, state);
        player.networkHandler.sendPacket(packet);
    }

    public String toString() {
        return String.format("%s[name=%s, full_name=%s, owner=%s, min=%s, max=%s, world=%s, subzones=%s]", this.getClass().getSimpleName(), this.name, this.getFullName(), this.getOwner(), this.min.toString(), this.max.toString(), this.getWorld().toString(), Arrays.toString(subzoneList.toArray()));
    }

    public String toShortString() {
        return String.format("%s[name=%s, owner=%s]", this.getClass().getSimpleName(), this.name, this.getOwner());
    }

    public static class Util {
        public static NbtCompound blockPosToNBT(BlockPos pos) {
            NbtCompound tag = new NbtCompound();
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            return tag;
        }

        public static BlockPos blockPosFromNBT(NbtCompound tag) {
            return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        }

        public static BlockPos getPosOnGround(BlockPos pos, World world) {
            BlockPos blockPos = new BlockPos(pos.getX(), pos.getY() + 10, pos.getZ());

            do {
                blockPos = blockPos.down();
                if (blockPos.getY() < 1) {
                    return pos;
                }
            } while (!world.getBlockState(blockPos).isFullCube(world, pos));

            return blockPos.up();
        }
    }

}
