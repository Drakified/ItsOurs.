package me.drex.itsours.claim;

import me.drex.itsours.ItsOurs;
import me.drex.itsours.claim.permission.*;
import me.drex.itsours.claim.permission.holder.ClaimPermissionHolder;
import me.drex.itsours.claim.permission.context.IgnoreContext;
import me.drex.itsours.claim.permission.context.OwnerContext;
import me.drex.itsours.claim.permission.holder.RestrictionHolder;
import me.drex.itsours.claim.permission.node.Node;
import me.drex.itsours.claim.permission.util.InvalidPermissionException;
import me.drex.itsours.claim.permission.util.Value;
import me.drex.itsours.claim.permission.visitor.PermissionVisitor;
import me.drex.itsours.claim.permission.visitor.PermissionVisitorImpl;
import me.drex.itsours.user.ClaimPlayer;
import me.drex.itsours.user.PlayerList;
import me.drex.itsours.user.Settings;
import me.drex.itsours.util.ClaimBox;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
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
    private static final Block[] SHOW_BLOCKS = {Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.LAPIS_BLOCK, Blocks.NETHERITE_BLOCK};
    public BlockPos min;
    public BlockPos max;
    private ClaimBox box;
    private String name;
    private UUID owner;
    private RegistryKey<World> dimension;
    private final List<Subzone> subzoneList = new ArrayList<>();
    private ClaimPermissionHolder permissionManager;
    private RestrictionHolder restrictionManager;

    public AbstractClaim(String name, UUID owner, BlockPos first, BlockPos second, ServerWorld world) {
        this.name = name;
        this.owner = owner;
        this.box = ClaimBox.create(first, second);
        this.dimension = world.getRegistryKey();
        this.permissionManager = new ClaimPermissionHolder(new NbtCompound());
        this.restrictionManager = new RestrictionHolder(new NbtCompound());
    }

    public AbstractClaim(NbtCompound tag) {
        fromNBT(tag);
    }

    public static boolean isNameInvalid(String name) {
        return !NAME.matcher(name).matches();
    }

    public RestrictionHolder getRestrictionManager() {
        return restrictionManager;
    }

    public void fromNBT(NbtCompound tag) {
        this.name = tag.getString("name");
        this.owner = tag.getUuid("owner");
        NbtCompound position = tag.getCompound("position");
        this.box = ClaimBox.load(position);
        this.dimension = World.CODEC.parse(NbtOps.INSTANCE, position.get("world")).resultOrPartial(LOGGER::error).orElse(World.OVERWORLD);
        NbtList list = (NbtList) tag.get("subzones");
        if (list != null) {
            list.forEach(subzones -> {
                Subzone subzone = new Subzone((NbtCompound) subzones, this);
                subzoneList.add(subzone);
            });
        }
        this.permissionManager = new ClaimPermissionHolder(tag.getCompound("permissions"));
        this.restrictionManager = new RestrictionHolder(tag.getCompound("restrictions"));
    }

    public NbtCompound toNBT() {
        NbtCompound tag = new NbtCompound();
        tag.putString("name", this.name);
        tag.putUuid("owner", this.owner);
        NbtCompound position = new NbtCompound();
        box.save(position);
        Identifier.CODEC.encodeStart(NbtOps.INSTANCE, this.dimension.getValue()).resultOrPartial(LOGGER::error).ifPresent(nbt -> position.put("world", nbt));
        tag.put("position", position);
        if (!subzoneList.isEmpty()) {
            NbtList list = new NbtList();
            subzoneList.forEach(subzone -> list.add(subzone.toNBT()));
            tag.put("subzones", list);
        }
        tag.put("permissions", this.permissionManager.toNBT());
        //tag.put("restrictions", this.restrictionManager.toNBT());
        return tag;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ClaimBox getBox() {
        return box;
    }

    public void setBox(ClaimBox box) {
        ClaimList.INSTANCE.removeClaim(this);
        this.box = box;
        ClaimList.INSTANCE.addClaim(this);
    }

    public abstract String getFullName();

    public abstract Claim getMainClaim();

    public UUID getOwner() {
        return this.owner;
    }

    public void setOwner(UUID owner) {
        ClaimList.INSTANCE.removeClaim(this);
        this.owner = owner;
        ClaimList.INSTANCE.addClaim(this);
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

    public void onEnter(@Nullable AbstractClaim previousClaim, ServerPlayerEntity player) {
        if (previousClaim == null) {
            PlayerList.set(player.getUuid(), Settings.CACHED_FLIGHT, player.getAbilities().allowFlying);
        }
        assert player.getServer() != null;
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

    public void onLeave(@Nullable AbstractClaim nextClaim, ServerPlayerEntity player) {
        if (nextClaim == null) {
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
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(-1, 20, -1));
            player.networkHandler.sendPacket(new OverlayMessageS2CPacket(Text.translatable("text.itsours.claim.leave", this.getFullName())));
        }
    }

    public boolean hasPermission(@Nullable UUID uuid, Permission permission) {
        PermissionVisitorImpl visitor = new PermissionVisitorImpl();
        visit(uuid, permission, visitor);
        return visitor.getResult().value;
    }

    public boolean hasPermission(@Nullable UUID uuid, Node... nodes) {
        return hasPermission(uuid, PermissionImpl.withNodes(nodes));
    }

    public void visit(@Nullable UUID uuid, Permission permission, PermissionVisitor visitor) {
        if (Objects.equals(uuid, owner)) visitor.visit(this, permission, OwnerContext.INSTANCE, Value.ALLOW);
        if (PlayerList.get(uuid, Settings.IGNORE)) visitor.visit(this, permission, IgnoreContext.INSTANCE, Value.ALLOW);
        this.permissionManager.visit(this, uuid, permission, visitor);
    }

    @Deprecated
    public boolean hasPermission(UUID uuid, String permission) {
        try {
            return hasPermission(uuid, PermissionImpl.fromId(permission));
        } catch (InvalidPermissionException e) {
            // TODO:
            LOGGER.warn(e);
            return false;
        }
    }

    public boolean getSetting(String setting) {
        try {
            return hasPermission(null, PermissionImpl.setting(setting));
        } catch (InvalidPermissionException e) {
            // TODO:
            LOGGER.warn(e);
            return false;
        }
    }

    public ClaimPermissionHolder getPermissionManager() {
        return this.permissionManager;
    }

    public abstract int getDepth();

    public int getArea() {
        return box.getBlockCountX() * box.getBlockCountZ();
    }

    public Vec3i getSize() {
        return box.getDimensions().add(1, 1, 1);
    }

    public boolean contains(BlockPos pos) {
        return box.contains(pos);
    }

    public boolean intersects(AbstractClaim claim) {
        if (!claim.dimension.equals(this.dimension)) return false;
        return box.intersects(claim.getBox());
        // TODO: Test new intersects implementation
    }

    public Optional<AbstractClaim> intersects() {
        return ClaimList.INSTANCE.getClaims().stream().filter(claim ->
                claim.dimension.equals(this.dimension) &&
                        claim.getDepth() == this.getDepth() &&
                        !this.equals(claim) &&
                        (this.intersects(claim) || claim.intersects(this))
        ).findFirst();
    }

    public void show(boolean show) {
        for (ServerPlayerEntity player : ItsOurs.INSTANCE.server.getPlayerManager().getPlayerList()) {
            ClaimPlayer claimPlayer = (ClaimPlayer) player;
            if (claimPlayer.getLastShowClaim() == this) {
                this.show(player, show);
            }
        }
    }

    public void show(ServerPlayerEntity player, boolean show) {
        BlockState blockState = show ? SHOW_BLOCKS[Math.min(this.getDepth(), SHOW_BLOCKS.length - 1)].getDefaultState() : null;
        if (show) {
            getBox().drawOutline(player, blockState, Blocks.EMERALD_BLOCK.getDefaultState());
        } else {
            getBox().drawOutline(player, null);
        }
        for (Subzone subzone : this.getSubzones()) {
            subzone.show(player, show);
        }
    }

    public String toString() {
        return String.format("%s[name=%s, full_name=%s, owner=%s, box=%s, world=%s, subzones=%s]", this.getClass().getSimpleName(), this.name, this.getFullName(), this.getOwner(), this.box.toString(), this.dimension.toString(), Arrays.toString(subzoneList.toArray()));
    }

    public String toShortString() {
        return String.format("%s[name=%s, owner=%s]", this.getClass().getSimpleName(), this.name, this.getOwner());
    }

    public static class Util {

        // TODO: remove
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
