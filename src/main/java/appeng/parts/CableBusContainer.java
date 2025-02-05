/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2015, AlgorithmX2, All rights reserved.
 *
 * Applied Energistics 2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Applied Energistics 2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Applied Energistics 2.  If not, see <http://www.gnu.org/licenses/lgpl>.
 */

package appeng.parts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import appeng.api.config.YesNo;
import appeng.api.exceptions.FailedConnectionException;
import appeng.api.implementations.parts.ICablePart;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.parts.IFacadeContainer;
import appeng.api.parts.IFacadePart;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartItem;
import appeng.api.parts.LayerFlags;
import appeng.api.parts.PartItemStack;
import appeng.api.parts.SelectedPart;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.api.util.AEPartLocation;
import appeng.api.util.DimensionalCoord;
import appeng.client.render.cablebus.CableBusRenderState;
import appeng.client.render.cablebus.CableCoreType;
import appeng.client.render.cablebus.FacadeRenderState;
import appeng.core.AELog;
import appeng.core.Api;
import appeng.facade.FacadeContainer;
import appeng.helpers.AEMultiTile;
import appeng.me.GridConnection;
import appeng.parts.networking.CablePart;
import appeng.util.InteractionUtil;
import appeng.util.Platform;

public class CableBusContainer extends CableBusStorage implements AEMultiTile, ICableBusContainer {

    private static final ThreadLocal<Boolean> IS_LOADING = new ThreadLocal<>();
    private final EnumSet<LayerFlags> myLayerFlags = EnumSet.noneOf(LayerFlags.class);
    private YesNo hasRedstone = YesNo.UNDECIDED;
    private IPartHost tcb;
    private boolean requiresDynamicRender = false;
    private boolean inWorld = false;
    // Cached collision shape for living entities
    private VoxelShape cachedCollisionShapeLiving;
    // Cached collision shape for anything but living entities
    private VoxelShape cachedCollisionShape;
    private VoxelShape cachedShape;

    public CableBusContainer(final IPartHost host) {
        this.tcb = host;
    }

    public static boolean isLoading() {
        final Boolean is = IS_LOADING.get();
        return is != null && is;
    }

    public void setHost(final IPartHost host) {
        this.tcb.clearContainer();
        this.tcb = host;
    }

    public void rotateLeft() {
        final IPart[] newSides = new IPart[6];

        newSides[AEPartLocation.UP.ordinal()] = this.getSide(AEPartLocation.UP);
        newSides[AEPartLocation.DOWN.ordinal()] = this.getSide(AEPartLocation.DOWN);

        newSides[AEPartLocation.EAST.ordinal()] = this.getSide(AEPartLocation.NORTH);
        newSides[AEPartLocation.SOUTH.ordinal()] = this.getSide(AEPartLocation.EAST);
        newSides[AEPartLocation.WEST.ordinal()] = this.getSide(AEPartLocation.SOUTH);
        newSides[AEPartLocation.NORTH.ordinal()] = this.getSide(AEPartLocation.WEST);

        for (final AEPartLocation dir : AEPartLocation.SIDE_LOCATIONS) {
            this.setSide(dir, newSides[dir.ordinal()]);
        }

        this.getFacadeContainer().rotateLeft();
    }

    @Override
    public IFacadeContainer getFacadeContainer() {
        return new FacadeContainer(this, this::invalidateShapes);
    }

    @Override
    public boolean canAddPart(ItemStack is, final AEPartLocation side) {
        if (PartPlacement.isFacade(is, side) != null) {
            return true;
        }

        if (is.getItem() instanceof IPartItem) {
            final IPartItem<?> bi = (IPartItem<?>) is.getItem();

            is = is.copy();
            is.setCount(1);

            final IPart bp = bi.createPart(is);
            if (bp != null) {
                if (bp instanceof ICablePart) {
                    boolean canPlace = true;
                    for (final AEPartLocation d : AEPartLocation.SIDE_LOCATIONS) {
                        if (this.getPart(d) != null
                                && !this.getPart(d).canBePlacedOn(((ICablePart) bp).supportsBuses())) {
                            canPlace = false;
                        }
                    }

                    if (!canPlace) {
                        return false;
                    }

                    return this.getPart(AEPartLocation.INTERNAL) == null;
                } else if (!(bp instanceof ICablePart) && side != AEPartLocation.INTERNAL) {
                    final IPart cable = this.getPart(AEPartLocation.INTERNAL);
                    if (cable != null && !bp.canBePlacedOn(((ICablePart) cable).supportsBuses())) {
                        return false;
                    }

                    return this.getPart(side) == null;
                }
            }
        }
        return false;
    }

    @Override
    public AEPartLocation addPart(ItemStack is, final AEPartLocation side, final @Nullable PlayerEntity player,
            final @Nullable Hand hand) {
        if (this.canAddPart(is, side)) {
            if (is.getItem() instanceof IPartItem) {
                final IPartItem<?> bi = (IPartItem<?>) is.getItem();

                is = is.copy();
                is.setCount(1);

                final IPart bp = bi.createPart(is);
                if (bp instanceof ICablePart) {
                    boolean canPlace = true;
                    for (final AEPartLocation d : AEPartLocation.SIDE_LOCATIONS) {
                        if (this.getPart(d) != null
                                && !this.getPart(d).canBePlacedOn(((ICablePart) bp).supportsBuses())) {
                            canPlace = false;
                        }
                    }

                    if (!canPlace) {
                        return null;
                    }

                    if (this.getPart(AEPartLocation.INTERNAL) != null) {
                        return null;
                    }

                    this.setCenter((ICablePart) bp);
                    bp.setPartHostInfo(AEPartLocation.INTERNAL, this, this.tcb.getTile());

                    if (player != null) {
                        bp.onPlacement(player, hand, is, side);
                    }

                    if (this.inWorld) {
                        bp.addToWorld();
                    }

                    final IGridNode cn = this.getCenter().getGridNode();
                    if (cn != null) {
                        for (final AEPartLocation ins : AEPartLocation.SIDE_LOCATIONS) {
                            final IPart sbp = this.getPart(ins);
                            if (sbp != null) {
                                final IGridNode sn = sbp.getGridNode();
                                if (sn != null) {
                                    try {
                                        GridConnection.create(cn, sn, AEPartLocation.INTERNAL);
                                    } catch (final FailedConnectionException e) {
                                        AELog.debug(e);

                                        bp.removeFromWorld();
                                        this.setCenter(null);
                                        return null;
                                    }
                                }
                            }
                        }
                    }

                    this.invalidateShapes();
                    this.updateConnections();
                    this.markForUpdate();
                    this.markForSave();
                    this.partChanged();
                    return AEPartLocation.INTERNAL;
                } else if (bp != null && !(bp instanceof ICablePart) && side != AEPartLocation.INTERNAL) {
                    final IPart cable = this.getPart(AEPartLocation.INTERNAL);
                    if (cable != null && !bp.canBePlacedOn(((ICablePart) cable).supportsBuses())) {
                        return null;
                    }

                    this.setSide(side, bp);
                    bp.setPartHostInfo(side, this, this.getTile());

                    if (player != null) {
                        bp.onPlacement(player, hand, is, side);
                    }

                    if (this.inWorld) {
                        bp.addToWorld();
                    }

                    if (this.getCenter() != null) {
                        final IGridNode cn = this.getCenter().getGridNode();
                        final IGridNode sn = bp.getGridNode();

                        if (cn != null && sn != null) {
                            try {
                                GridConnection.create(cn, sn, AEPartLocation.INTERNAL);
                            } catch (final FailedConnectionException e) {
                                AELog.debug(e);

                                bp.removeFromWorld();
                                this.setSide(side, null);
                                return null;
                            }
                        }
                    }

                    this.invalidateShapes();
                    this.updateDynamicRender();
                    this.updateConnections();
                    this.markForUpdate();
                    this.markForSave();
                    this.partChanged();
                    return side;
                }
            }
        }
        return null;
    }

    @Override
    public IPart getPart(final AEPartLocation partLocation) {
        if (partLocation == AEPartLocation.INTERNAL) {
            return this.getCenter();
        }
        return this.getSide(partLocation);
    }

    @Override
    public IPart getPart(final Direction side) {
        return this.getSide(AEPartLocation.fromFacing(side));
    }

    @Override
    public void removePart(final AEPartLocation side, final boolean suppressUpdate) {
        if (side == AEPartLocation.INTERNAL) {
            if (this.getCenter() != null) {
                this.getCenter().removeFromWorld();
            }
            this.setCenter(null);
        } else {
            if (this.getSide(side) != null) {
                this.getSide(side).removeFromWorld();
            }
            this.setSide(side, null);
        }

        if (!suppressUpdate) {
            this.invalidateShapes();
            this.updateDynamicRender();
            this.updateConnections();
            this.markForUpdate();
            this.markForSave();
            this.partChanged();

            // Cleanup the cable bus once it is no longer containing any parts.
            // Also only when the cable bus actually exists, otherwise it might perform a cleanup during initialization.
            if (this.isInWorld() && this.isEmpty()) {
                this.cleanup();
            }
        }
    }

    @Override
    public void markForUpdate() {
        this.tcb.markForUpdate();
    }

    @Override
    public DimensionalCoord getLocation() {
        return this.tcb.getLocation();
    }

    @Override
    public TileEntity getTile() {
        return this.tcb.getTile();
    }

    @Override
    public AEColor getColor() {
        if (this.getCenter() != null) {
            final ICablePart c = this.getCenter();
            return c.getCableColor();
        }
        return AEColor.TRANSPARENT;
    }

    @Override
    public void clearContainer() {
        throw new UnsupportedOperationException("Now that is silly!");
    }

    @Override
    public boolean isBlocked(final Direction side) {
        return this.tcb.isBlocked(side);
    }

    @Override
    public SelectedPart selectPart(final Vector3d pos) {
        for (final AEPartLocation side : AEPartLocation.values()) {
            final IPart p = this.getPart(side);
            if (p != null) {
                final List<AxisAlignedBB> boxes = new ArrayList<>();

                final IPartCollisionHelper bch = new BusCollisionHelper(boxes, side, true);
                p.getBoxes(bch);
                for (AxisAlignedBB bb : boxes) {
                    bb = bb.grow(0.002, 0.002, 0.002);
                    if (bb.contains(pos)) {
                        return new SelectedPart(p, side);
                    }
                }
            }
        }

        if (Api.instance().partHelper().getCableRenderMode().opaqueFacades) {
            final IFacadeContainer fc = this.getFacadeContainer();
            for (final AEPartLocation side : AEPartLocation.SIDE_LOCATIONS) {
                final IFacadePart p = fc.getFacade(side);
                if (p != null) {
                    final List<AxisAlignedBB> boxes = new ArrayList<>();

                    final IPartCollisionHelper bch = new BusCollisionHelper(boxes, side, true);
                    p.getBoxes(bch, true);
                    for (AxisAlignedBB bb : boxes) {
                        bb = bb.grow(0.01, 0.01, 0.01);
                        if (bb.contains(pos)) {
                            return new SelectedPart(p, side);
                        }
                    }
                }
            }
        }

        return new SelectedPart();
    }

    @Override
    public void markForSave() {
        this.tcb.markForSave();
    }

    @Override
    public void partChanged() {
        if (this.getCenter() == null) {
            final List<ItemStack> facades = new ArrayList<>();

            final IFacadeContainer fc = this.getFacadeContainer();
            for (final AEPartLocation d : AEPartLocation.SIDE_LOCATIONS) {
                final IFacadePart fp = fc.getFacade(d);
                if (fp != null) {
                    facades.add(fp.getItemStack());
                    fc.removeFacade(this.tcb, d);
                }
            }

            if (!facades.isEmpty()) {
                final TileEntity te = this.tcb.getTile();
                Platform.spawnDrops(te.getWorld(), te.getPos(), facades);
            }
        }

        this.tcb.partChanged();
    }

    @Override
    public boolean hasRedstone(final AEPartLocation side) {
        if (this.hasRedstone == YesNo.UNDECIDED) {
            this.updateRedstone();
        }

        return this.hasRedstone == YesNo.YES;
    }

    @Override
    public boolean isEmpty() {
        final IFacadeContainer fc = this.getFacadeContainer();
        for (final AEPartLocation s : AEPartLocation.values()) {
            final IPart part = this.getPart(s);
            if (part != null) {
                return false;
            }

            if (s != AEPartLocation.INTERNAL) {
                final IFacadePart fp = fc.getFacade(s);
                if (fp != null) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public Set<LayerFlags> getLayerFlags() {
        return this.myLayerFlags;
    }

    @Override
    public void cleanup() {
        this.tcb.cleanup();
    }

    @Override
    public void notifyNeighbors() {
        this.tcb.notifyNeighbors();
    }

    @Override
    public boolean isInWorld() {
        return this.inWorld;
    }

    private void updateRedstone() {
        final TileEntity te = this.getTile();
        this.hasRedstone = te.getWorld().getRedstonePowerFromNeighbors(te.getPos()) != 0 ? YesNo.YES : YesNo.NO;
    }

    private void updateDynamicRender() {
        this.requiresDynamicRender = false;
        for (final AEPartLocation s : AEPartLocation.SIDE_LOCATIONS) {
            final IPart p = this.getPart(s);
            if (p != null) {
                this.setRequiresDynamicRender(this.isRequiresDynamicRender() || p.requireDynamicRender());
            }
        }
    }

    /**
     * use for FMP
     */
    public void updateConnections() {
        if (this.getCenter() != null) {
            final EnumSet<Direction> sides = EnumSet.allOf(Direction.class);

            for (final Direction s : Direction.values()) {
                if (this.getPart(s) != null || this.isBlocked(s)) {
                    sides.remove(s);
                }
            }

            this.getCenter().setValidSides(sides);
            final IGridNode n = this.getCenter().getGridNode();
            if (n != null) {
                n.updateState();
            }
        }
    }

    public void addToWorld() {
        if (this.inWorld) {
            return;
        }

        this.inWorld = true;
        IS_LOADING.set(true);

        final TileEntity te = this.getTile();

        // start with the center, then install the side parts into the grid.
        for (int x = 6; x >= 0; x--) {
            final AEPartLocation s = AEPartLocation.fromOrdinal(x);
            final IPart part = this.getPart(s);

            if (part != null) {
                part.setPartHostInfo(s, this, te);
                part.addToWorld();

                if (s != AEPartLocation.INTERNAL) {
                    final IGridNode sn = part.getGridNode();
                    if (sn != null) {
                        // this is a really stupid if statement, why was this
                        // here?
                        // if ( !sn.getConnections().iterator().hasNext() )

                        final IPart center = this.getPart(AEPartLocation.INTERNAL);
                        if (center != null) {
                            final IGridNode cn = center.getGridNode();
                            if (cn != null) {
                                try {
                                    Api.instance().grid().createGridConnection(cn, sn);
                                } catch (final FailedConnectionException e) {
                                    // ekk
                                    AELog.debug(e);
                                }
                            }
                        }
                    }
                }
            }
        }

        this.partChanged();

        IS_LOADING.set(false);
    }

    public void removeFromWorld() {
        if (!this.inWorld) {
            return;
        }

        this.inWorld = false;

        for (final AEPartLocation s : AEPartLocation.values()) {
            final IPart part = this.getPart(s);
            if (part != null) {
                part.removeFromWorld();
            }
        }

        this.invalidateShapes();
        this.partChanged();
    }

    @Override
    public IGridNode getGridNode(final AEPartLocation side) {
        final IPart part = this.getPart(side);
        if (part != null) {
            final IGridNode n = part.getExternalFacingNode();
            if (n != null) {
                return n;
            }
        }

        if (this.getCenter() != null) {
            return this.getCenter().getGridNode();
        }

        return null;
    }

    @Override
    public AECableType getCableConnectionType(final AEPartLocation dir) {
        final IPart part = this.getPart(dir);
        if (part instanceof IGridHost) {
            final AECableType t = ((IGridHost) part).getCableConnectionType(dir);
            if (t != null && t != AECableType.NONE) {
                return t;
            }
        }

        if (this.getCenter() != null) {
            final ICablePart c = this.getCenter();
            return c.getCableConnectionType();
        }
        return AECableType.NONE;
    }

    @Override
    public float getCableConnectionLength(AECableType cable) {
        return this.getPart(AEPartLocation.INTERNAL) instanceof ICablePart
                ? this.getPart(AEPartLocation.INTERNAL).getCableConnectionLength(cable)
                : -1;
    }

    @Override
    public void securityBreak() {
        for (final AEPartLocation d : AEPartLocation.values()) {
            final IPart p = this.getPart(d);
            if (p instanceof IGridHost) {
                ((IGridHost) p).securityBreak();
            }
        }
    }

    @Override
    public int isProvidingStrongPower(final Direction side) {
        final IPart part = this.getPart(side);
        return part != null ? part.isProvidingStrongPower() : 0;
    }

    @Override
    public int isProvidingWeakPower(final Direction side) {
        final IPart part = this.getPart(side);
        return part != null ? part.isProvidingWeakPower() : 0;
    }

    @Override
    public boolean canConnectRedstone(final Direction opposite) {
        final IPart part = this.getPart(opposite);
        return part != null && part.canConnectRedstone();
    }

    @Override
    public void onEntityCollision(final Entity entity) {
        for (final AEPartLocation s : AEPartLocation.values()) {
            final IPart part = this.getPart(s);
            if (part != null) {
                part.onEntityCollision(entity);
            }
        }
    }

    @Override
    public boolean activate(final PlayerEntity player, final Hand hand, final Vector3d pos) {
        final SelectedPart p = this.selectPart(pos);
        if (p != null && p.part != null) {
            // forge sends activate even when sneaking in some cases (eg emtpy hand)
            // if sneaking try shift activate first.
            if (InteractionUtil.isInAlternateUseMode(player) && p.part.onShiftActivate(player, hand, pos)) {
                return true;
            }
            return p.part.onActivate(player, hand, pos);
        }
        return false;
    }

    @Override
    public boolean clicked(PlayerEntity player, Hand hand, Vector3d hitVec) {
        final SelectedPart p = this.selectPart(hitVec);
        if (p != null && p.part != null) {
            if (InteractionUtil.isInAlternateUseMode(player)) {
                return p.part.onShiftClicked(player, hand, hitVec);
            } else {
                return p.part.onClicked(player, hand, hitVec);
            }
        }
        return false;
    }

    @Override
    public void onNeighborChanged(IBlockReader w, BlockPos pos, BlockPos neighbor) {
        this.hasRedstone = YesNo.UNDECIDED;

        for (final AEPartLocation s : AEPartLocation.values()) {
            final IPart part = this.getPart(s);
            if (part != null) {
                part.onNeighborChanged(w, pos, neighbor);
            }
        }

        // Some parts will change their shape (connected texture style)
        invalidateShapes();
    }

    @Override
    public boolean isLadder(final LivingEntity entity) {
        for (final AEPartLocation side : AEPartLocation.values()) {
            final IPart p = this.getPart(side);
            if (p != null) {
                if (p.isLadder(entity)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void animateTick(final World world, final BlockPos pos, final Random r) {
        for (final AEPartLocation side : AEPartLocation.values()) {
            final IPart p = this.getPart(side);
            if (p != null) {
                p.animateTick(world, pos, r);
            }
        }
    }

    @Override
    public int getLightValue() {
        int light = 0;

        for (final AEPartLocation d : AEPartLocation.values()) {
            final IPart p = this.getPart(d);
            if (p != null) {
                light = Math.max(p.getLightLevel(), light);
            }
        }

        return light;
    }

    public void writeToStream(final PacketBuffer data) throws IOException {
        int sides = 0;
        for (int x = 0; x < 7; x++) {
            final IPart p = this.getPart(AEPartLocation.fromOrdinal(x));
            if (p != null) {
                sides |= (1 << x);
            }
        }

        data.writeByte((byte) sides);

        for (int x = 0; x < 7; x++) {
            final IPart p = this.getPart(AEPartLocation.fromOrdinal(x));
            if (p != null) {
                final ItemStack is = p.getItemStack(PartItemStack.NETWORK);

                data.writeVarInt(Item.getIdFromItem(is.getItem()));

                p.writeToStream(data);
            }
        }

        this.getFacadeContainer().writeToStream(data);
    }

    public boolean readFromStream(final PacketBuffer data) throws IOException {
        final byte sides = data.readByte();

        boolean updateBlock = false;

        for (int x = 0; x < 7; x++) {
            AEPartLocation side = AEPartLocation.fromOrdinal(x);
            if (((sides & (1 << x)) == (1 << x))) {
                IPart p = this.getPart(side);

                final int itemID = data.readVarInt();

                final Item myItem = Item.getItemById(itemID);

                final ItemStack current = p != null ? p.getItemStack(PartItemStack.NETWORK) : null;
                if (current != null && current.getItem() == myItem) {
                    if (p.readFromStream(data)) {
                        updateBlock = true;
                    }
                } else {
                    this.removePart(side, false);
                    side = this.addPart(new ItemStack(myItem, 1), side, null, null);
                    if (side != null) {
                        p = this.getPart(side);
                        p.readFromStream(data);
                    } else {
                        throw new IllegalStateException("Invalid Stream For CableBus Container.");
                    }
                }
            } else if (this.getPart(side) != null) {
                this.removePart(side, false);
            }
        }

        updateBlock |= this.getFacadeContainer().readFromStream(data);

        // Updating tiles may change the collision shape
        this.invalidateShapes();

        return updateBlock;
    }

    public void writeToNBT(final CompoundNBT data) {
        data.putInt("hasRedstone", this.hasRedstone.ordinal());

        final IFacadeContainer fc = this.getFacadeContainer();
        for (final AEPartLocation s : AEPartLocation.values()) {
            fc.writeToNBT(data);

            final IPart part = this.getPart(s);
            if (part != null) {
                final CompoundNBT def = new CompoundNBT();
                part.getItemStack(PartItemStack.WORLD).write(def);

                final CompoundNBT extra = new CompoundNBT();
                part.writeToNBT(extra);

                data.put("def:" + this.getSide(part).ordinal(), def);
                data.put("extra:" + this.getSide(part).ordinal(), extra);
            }
        }
    }

    private AEPartLocation getSide(final IPart part) {
        if (this.getCenter() == part) {
            return AEPartLocation.INTERNAL;
        } else {
            for (final AEPartLocation side : AEPartLocation.SIDE_LOCATIONS) {
                if (this.getSide(side) == part) {
                    return side;
                }
            }
        }

        throw new IllegalStateException("Uhh Bad Part (" + part + ") on Side.");
    }

    public void readFromNBT(final CompoundNBT data) {
        invalidateShapes();

        if (data.contains("hasRedstone")) {
            this.hasRedstone = YesNo.values()[data.getInt("hasRedstone")];
        }

        for (int x = 0; x < 7; x++) {
            AEPartLocation side = AEPartLocation.fromOrdinal(x);

            String defKey = "def:" + side.ordinal();
            String extraKey = "extra:" + side.ordinal();
            if (data.contains(defKey, Constants.NBT.TAG_COMPOUND)
                    && data.contains(extraKey, Constants.NBT.TAG_COMPOUND)) {
                final CompoundNBT def = data.getCompound(defKey);
                final CompoundNBT extra = data.getCompound(extraKey);
                IPart p = this.getPart(side);
                final ItemStack iss = ItemStack.read(def);
                if (iss.isEmpty()) {
                    continue;
                }

                final ItemStack current = p == null ? ItemStack.EMPTY : p.getItemStack(PartItemStack.WORLD);

                if (Platform.itemComparisons().isEqualItemType(iss, current)) {
                    p.readFromNBT(extra);
                } else {
                    this.removePart(side, true);
                    side = this.addPart(iss, side, null, null);
                    if (side != null) {
                        p = this.getPart(side);
                        p.readFromNBT(extra);
                    } else {
                        AELog.warn("Invalid NBT For CableBus Container: " + iss.getItem().getClass().getName()
                                + " is not a valid part; it was ignored.");
                    }
                }
            } else {
                this.removePart(side, false);
            }
        }

        this.getFacadeContainer().readFromNBT(data);
    }

    public List<ItemStack> getDrops(final List<ItemStack> drops) {
        for (final AEPartLocation s : AEPartLocation.values()) {
            final IPart part = this.getPart(s);
            if (part != null) {
                drops.add(part.getItemStack(PartItemStack.BREAK));
                part.getDrops(drops, false);
            }

            if (s != AEPartLocation.INTERNAL) {
                final IFacadePart fp = this.getFacadeContainer().getFacade(s);
                if (fp != null) {
                    drops.add(fp.getItemStack());
                }
            }
        }

        return drops;
    }

    public List<ItemStack> getNoDrops(final List<ItemStack> drops) {
        for (final AEPartLocation s : AEPartLocation.values()) {
            final IPart part = this.getPart(s);
            if (part != null) {
                part.getDrops(drops, false);
            }
        }

        return drops;
    }

    @Override
    public boolean recolourBlock(final Direction side, final AEColor colour, final PlayerEntity who) {
        final IPart cable = this.getPart(AEPartLocation.INTERNAL);
        if (cable != null) {
            final ICablePart pc = (ICablePart) cable;
            return pc.changeColor(colour, who);
        }
        return false;
    }

    public boolean isRequiresDynamicRender() {
        return this.requiresDynamicRender;
    }

    private void setRequiresDynamicRender(final boolean requiresDynamicRender) {
        this.requiresDynamicRender = requiresDynamicRender;
    }

    @Override
    public CableBusRenderState getRenderState() {
        final CablePart cable = (CablePart) this.getCenter();

        final CableBusRenderState renderState = new CableBusRenderState();

        if (cable != null) {
            renderState.setCableColor(cable.getCableColor());
            renderState.setCableType(cable.getCableConnectionType());
            renderState.setCoreType(CableCoreType.fromCableType(cable.getCableConnectionType()));

            // Check each outgoing connection for the desired characteristics
            for (Direction facing : Direction.values()) {
                // Is there a connection?
                if (!cable.isConnected(facing)) {
                    continue;
                }

                // If there is one, check out which type it has, but default to this cable's
                // type
                AECableType connectionType = cable.getCableConnectionType();

                // Only use the incoming cable-type of the adjacent block, if it's not a cable
                // bus itself
                // Dense cables however also respect the adjacent cable-type since their
                // outgoing connection
                // point would look too big for other cable types
                final BlockPos adjacentPos = this.getTile().getPos().offset(facing);
                final TileEntity adjacentTe = this.getTile().getWorld().getTileEntity(adjacentPos);

                if (adjacentTe instanceof IGridHost) {
                    final IGridHost gridHost = (IGridHost) adjacentTe;
                    final AECableType adjacentType = gridHost
                            .getCableConnectionType(AEPartLocation.fromFacing(facing.getOpposite()));

                    connectionType = AECableType.min(connectionType, adjacentType);
                }

                // Check if the adjacent TE is a cable bus or not
                if (adjacentTe instanceof IPartHost) {
                    renderState.getCableBusAdjacent().add(facing);
                }

                renderState.getConnectionTypes().put(facing, connectionType);
            }

            // Collect the number of channels used per side
            // We have to do this even for non-smart cables since a glass cable can display
            // a connection as smart if the
            // adjacent tile requires it
            for (Direction facing : Direction.values()) {
                int channels = cable.getCableConnectionType().isSmart() ? cable.getChannelsOnSide(facing) : 0;
                renderState.getChannelsOnSide().put(facing, channels);
            }
        }

        // Determine attachments and facades
        for (Direction facing : Direction.values()) {
            final FacadeRenderState facadeState = this.getFacadeRenderState(facing);

            if (facadeState != null) {
                renderState.getFacades().put(facing, facadeState);
            }

            final IPart part = this.getPart(facing);

            if (part == null) {
                continue;
            }

            renderState.getPartModelData().put(facing, part.getModelData());

            // This will add the part's bounding boxes to the render state, which is
            // required for facades
            final AEPartLocation loc = AEPartLocation.fromFacing(facing);
            final IPartCollisionHelper bch = new BusCollisionHelper(renderState.getBoundingBoxes(), loc, true);
            part.getBoxes(bch);

            if (part instanceof IGridHost) {
                // Some attachments want a thicker cable than glass, account for that
                final IGridHost gridHost = (IGridHost) part;
                final AECableType desiredType = gridHost.getCableConnectionType(AEPartLocation.INTERNAL);

                if (renderState.getCoreType() == CableCoreType.GLASS
                        && (desiredType == AECableType.SMART || desiredType == AECableType.COVERED)) {
                    renderState.setCoreType(CableCoreType.COVERED);
                }

                int length = (int) part.getCableConnectionLength(null);
                if (length > 0 && length <= 8) {
                    renderState.getAttachmentConnections().put(facing, length);
                }
            }

            renderState.getAttachments().put(facing, part.getStaticModels());
        }

        return renderState;
    }

    private FacadeRenderState getFacadeRenderState(Direction side) {
        // Store the "masqueraded" itemstack for the given side, if there is a facade
        final IFacadePart facade = this.getFacade(side.ordinal());

        if (facade != null) {
            final ItemStack textureItem = facade.getTextureItem();
            final BlockState blockState = facade.getBlockState();

            World world = getTile().getWorld();
            if (blockState != null && textureItem != null && world != null) {
                return new FacadeRenderState(blockState,
                        !facade.getBlockState().isOpaqueCube(world, getTile().getPos()));
            }
        }

        return null;
    }

    /**
     * See {@link net.minecraft.block.Block#getShape}
     */
    public VoxelShape getShape() {
        if (cachedShape == null) {
            cachedShape = createShape(false, false);
        }

        return cachedShape;
    }

    /**
     * See {@link net.minecraft.block.Block#getCollisionShape}
     */
    public VoxelShape getCollisionShape(Entity entity) {
        // This is a hack for facades
        boolean itemEntity = entity instanceof ItemEntity;

        if (itemEntity) {
            if (cachedCollisionShapeLiving == null) {
                cachedCollisionShapeLiving = createShape(true, true);
            }
            return cachedCollisionShapeLiving;
        } else {
            if (cachedCollisionShape == null) {
                cachedCollisionShape = createShape(true, false);
            }
            return cachedCollisionShape;
        }
    }

    private VoxelShape createShape(boolean forCollision, boolean forItemEntity) {
        final List<AxisAlignedBB> boxes = new ArrayList<>();

        final IFacadeContainer fc = this.getFacadeContainer();
        for (final AEPartLocation s : AEPartLocation.values()) {
            final IPartCollisionHelper bch = new BusCollisionHelper(boxes, s, !forCollision);

            final IPart part = this.getPart(s);
            if (part != null) {
                part.getBoxes(bch);
            }

            if (Api.instance().partHelper().getCableRenderMode().opaqueFacades || forCollision) {
                if (s != AEPartLocation.INTERNAL) {
                    final IFacadePart fp = fc.getFacade(s);
                    if (fp != null) {
                        fp.getBoxes(bch, forItemEntity);
                    }
                }
            }
        }

        return VoxelShapeCache.get(boxes);
    }

    private void invalidateShapes() {
        cachedShape = null;
        cachedCollisionShape = null;
        cachedCollisionShapeLiving = null;
    }

}
