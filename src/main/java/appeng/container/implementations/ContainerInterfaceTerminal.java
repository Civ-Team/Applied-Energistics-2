/*
 * This file is part of Applied Energistics 2.
 * Copyright (c) 2013 - 2014, AlgorithmX2, All rights reserved.
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

package appeng.container.implementations;


import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import appeng.api.config.Upgrades;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.items.IItemHandler;

import appeng.api.config.Settings;
import appeng.api.config.YesNo;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.container.AEBaseContainer;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketCompressedNBT;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.helpers.InventoryAction;
import appeng.items.misc.ItemEncodedPattern;
import appeng.parts.misc.PartInterface;
import appeng.parts.reporting.PartInterfaceTerminal;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.tile.misc.TileInterface;
import appeng.util.InventoryAdaptor;
import appeng.util.Platform;
import appeng.util.helpers.ItemHandlerUtil;
import appeng.util.inv.AdaptorItemHandler;
import appeng.util.inv.WrapperCursorItemHandler;
import appeng.util.inv.WrapperFilteredItemHandler;
import appeng.util.inv.WrapperRangeItemHandler;
import appeng.util.inv.filter.IAEItemFilter;


public final class ContainerInterfaceTerminal extends AEBaseContainer {

    /**
     * this stuff is all server side..
     */

    private static long autoBase = Long.MIN_VALUE;
    private final Multimap<IInterfaceHost, InvTracker> diList = HashMultimap.create();
    private final Map<Long, InvTracker> byId = new HashMap<>();
    private IGrid grid;
    private NBTTagCompound data = new NBTTagCompound();

    public ContainerInterfaceTerminal(final InventoryPlayer ip, final PartInterfaceTerminal anchor) {
        super(ip, anchor);

        if (Platform.isServer()) {
            this.grid = anchor.getActionableNode().getGrid();
        }

        this.bindPlayerInventory(ip, 0, 222 - /* height of player inventory */82);
    }

    @Override
    public void detectAndSendChanges() {
        if (Platform.isClient()) {
            return;
        }

        super.detectAndSendChanges();

        if (this.grid == null) {
            return;
        }

        int total = 0;
        boolean missing = false;

        final IActionHost host = this.getActionHost();
        if (host != null) {
            final IGridNode agn = host.getActionableNode();
            if (agn != null && agn.isActive()) {
                for (final IGridNode gn : this.grid.getMachines(TileInterface.class)) {
                    InterfaceCheck interfaceCheck = new InterfaceCheck().invoke(gn);
                    total += interfaceCheck.getTotal();
                    missing |= interfaceCheck.isMissing();
                }

                for (final IGridNode gn : this.grid.getMachines(PartInterface.class)) {
                    InterfaceCheck interfaceCheck = new InterfaceCheck().invoke(gn);
                    total += interfaceCheck.getTotal();
                    missing |= interfaceCheck.isMissing();
                }
            }
        }

        if (total != this.diList.size() || missing) {
            this.regenList(this.data);
        } else {
            for (final InvTracker inv : this.diList.values()) {
                for (int x = 0; x < inv.client.getSlots(); x++) {
                    if (this.isDifferent(inv.server.getStackInSlot(inv.offset + x), inv.client.getStackInSlot(x))) {
                        this.addItems(this.data, inv, x, 1);
                    }
                }
            }
        }

        if (!this.data.hasNoTags()) {
            try {
                NetworkHandler.instance().sendTo(new PacketCompressedNBT(this.data), (EntityPlayerMP) this.getPlayerInv().player);
            } catch (final IOException e) {
                // :P
            }

            this.data = new NBTTagCompound();
        }
    }

    @Override
    public void doAction(final EntityPlayerMP player, final InventoryAction action, final int slot, final long id) {
        final InvTracker inv = this.byId.get(id);
        if (inv != null) {
            final ItemStack is = inv.server.getStackInSlot(slot + inv.offset);
            final boolean hasItemInHand = !player.inventory.getItemStack().isEmpty();

            final InventoryAdaptor playerHand = new AdaptorItemHandler(new WrapperCursorItemHandler(player.inventory));

            // final IInventory theSlot = slotInv.getWrapper(slot + inv.offset);
            final IItemHandler theSlot = new WrapperFilteredItemHandler(new WrapperRangeItemHandler(inv.server, slot, slot + 1), new PatternSlotFilter());
            final InventoryAdaptor interfaceSlot = new AdaptorItemHandler(theSlot);

            switch (action) {
                case PICKUP_OR_SET_DOWN:

                    if (hasItemInHand) {
                        ItemStack inSlot = theSlot.getStackInSlot(0);
                        if (inSlot.isEmpty()) {
                            player.inventory.setItemStack(interfaceSlot.addItems(player.inventory.getItemStack()));
                        } else {
                            inSlot = inSlot.copy();
                            final ItemStack inHand = player.inventory.getItemStack().copy();

                            ItemHandlerUtil.setStackInSlot(theSlot, 0, ItemStack.EMPTY);
                            player.inventory.setItemStack(ItemStack.EMPTY);

                            player.inventory.setItemStack(interfaceSlot.addItems(inHand.copy()));

                            if (player.inventory.getItemStack().isEmpty()) {
                                player.inventory.setItemStack(inSlot);
                            } else {
                                player.inventory.setItemStack(inHand);
                                ItemHandlerUtil.setStackInSlot(theSlot, 0, inSlot);
                            }
                        }
                    } else {
//                        final IInventory mySlot = slotInv.getWrapper(slot + inv.offset);
                        ItemHandlerUtil.setStackInSlot(theSlot, 0, playerHand.addItems(theSlot.getStackInSlot(0)));
                    }

                    break;
                case SPLIT_OR_PLACE_SINGLE:

                    if (hasItemInHand) {
                        ItemStack extra = playerHand.removeItems(1, ItemStack.EMPTY, null);
                        if (!extra.isEmpty()) {
                            extra = interfaceSlot.addItems(extra);
                        }
                        if (!extra.isEmpty()) {
                            playerHand.addItems(extra);
                        }
                    } else if (!is.isEmpty()) {
                        ItemStack extra = interfaceSlot.removeItems((is.getCount() + 1) / 2, ItemStack.EMPTY, null);
                        if (!extra.isEmpty()) {
                            extra = playerHand.addItems(extra);
                        }
                        if (!extra.isEmpty()) {
                            interfaceSlot.addItems(extra);
                        }
                    }

                    break;
                case SHIFT_CLICK:
//                    final IInventory mySlot = slotInv.getWrapper(slot + inv.offset);
                    final InventoryAdaptor playerInv = InventoryAdaptor.getAdaptor(player);

                    ItemHandlerUtil.setStackInSlot(theSlot, 0, playerInv.addItems(theSlot.getStackInSlot(0)));

                    break;
                case MOVE_REGION:

                    final InventoryAdaptor playerInvAd = InventoryAdaptor.getAdaptor(player);
                    for (int x = 0; x < inv.server.getSlots(); x++) {
                        ItemHandlerUtil.setStackInSlot(inv.server, x + inv.offset, playerInvAd.addItems(inv.server.getStackInSlot(x + inv.offset)));
                    }

                    break;
                case CREATIVE_DUPLICATE:

                    if (player.capabilities.isCreativeMode && !hasItemInHand) {
                        player.inventory.setItemStack(is.isEmpty() ? ItemStack.EMPTY : is.copy());
                    }

                    break;
                default:
                    return;
            }

            this.updateHeld(player);
        }
    }

    private void regenList(final NBTTagCompound data) {
        this.byId.clear();
        this.diList.clear();

        final IActionHost host = this.getActionHost();
        if (host != null) {
            final IGridNode agn = host.getActionableNode();
            if (agn != null && agn.isActive()) {
                for (final IGridNode gn : this.grid.getMachines(TileInterface.class)) {
                    final IInterfaceHost ih = (IInterfaceHost) gn.getMachine();
                    final DualityInterface dual = ih.getInterfaceDuality();
                    if (gn.isActive() && dual.getConfigManager().getSetting(Settings.INTERFACE_TERMINAL) == YesNo.YES) {
                        for (int i = 0; i <= dual.getInstalledUpgrades(Upgrades.PATTERN_CAPACITY); ++i) {
                            this.diList.put(ih, new InvTracker(dual, dual.getPatterns(), dual.getTermName(), i * 9, 9));
                        }
                    }
                }

                for (final IGridNode gn : this.grid.getMachines(PartInterface.class)) {
                    final IInterfaceHost ih = (IInterfaceHost) gn.getMachine();
                    final DualityInterface dual = ih.getInterfaceDuality();
                    if (gn.isActive() && dual.getConfigManager().getSetting(Settings.INTERFACE_TERMINAL) == YesNo.YES) {
                        for (int i = 0; i <= dual.getInstalledUpgrades(Upgrades.PATTERN_CAPACITY); ++i) {
                            this.diList.put(ih, new InvTracker(dual, dual.getPatterns(), dual.getTermName(), i * 9, 9));
                        }
                    }
                }
            }
        }

        data.setBoolean("clear", true);

        for (final InvTracker inv : this.diList.values()) {
            this.byId.put(inv.which, inv);
            this.addItems(data, inv, 0, inv.server.getSlots());
        }
    }

    private boolean isDifferent(final ItemStack a, final ItemStack b) {
        if (a.isEmpty() && b.isEmpty()) {
            return false;
        }

        if (a.isEmpty() || b.isEmpty()) {
            return true;
        }

        return !ItemStack.areItemStacksEqual(a, b);
    }

    private void addItems(final NBTTagCompound data, final InvTracker inv, final int offset, final int length) {
        final String name = '=' + Long.toString(inv.which, Character.MAX_RADIX);
        final NBTTagCompound tag = data.getCompoundTag(name);

        if (tag.hasNoTags()) {
            tag.setLong("sortBy", inv.sortBy);
            tag.setString("un", inv.unlocalizedName);
        }

        for (int x = 0; x < length; x++) {
            final NBTTagCompound itemNBT = new NBTTagCompound();

            final ItemStack is = inv.server.getStackInSlot(x + offset + inv.offset);

            // "update" client side.
            ItemHandlerUtil.setStackInSlot(inv.client, x + offset, is.isEmpty() ? ItemStack.EMPTY : is.copy());

            if (!is.isEmpty()) {
                is.writeToNBT(itemNBT);
            }

            tag.setTag(Integer.toString(x + offset), itemNBT);
        }

        data.setTag(name, tag);
    }

    private static class InvTracker {

        private final long sortBy;
        private final long which = autoBase++;
        private final String unlocalizedName;
        private final IItemHandler client;
        private final IItemHandler server;
        private final int offset;

        public InvTracker(final DualityInterface dual, final IItemHandler patterns, final String unlocalizedName, int offset, int size) {
            this.server = patterns;
            this.client = new AppEngInternalInventory(null, size);
            this.unlocalizedName = unlocalizedName;
            this.sortBy = dual.getSortValue() + offset << 16;
            this.offset = offset;
        }
    }

    private static class PatternSlotFilter implements IAEItemFilter {
        @Override
        public boolean allowExtract(IItemHandler inv, int slot, int amount) {
            return true;
        }

        @Override
        public boolean allowInsert(IItemHandler inv, int slot, ItemStack stack) {
            return !stack.isEmpty() && stack.getItem() instanceof ItemEncodedPattern;
        }
    }

    private class InterfaceCheck {

        int total = 0;
        boolean missing = false;

        public InterfaceCheck() {
        }

        public int getTotal() {
            return total;
        }

        public boolean isMissing() {
            return missing;
        }

        public InterfaceCheck invoke(IGridNode gn) {
            if (gn.isActive()) {
                final IInterfaceHost ih = (IInterfaceHost) gn.getMachine();
                if (ih.getInterfaceDuality().getConfigManager().getSetting(Settings.INTERFACE_TERMINAL) == YesNo.NO) {
                    return this;
                }

                final Collection<InvTracker> t = ContainerInterfaceTerminal.this.diList.get(ih);

                if (t.isEmpty()) {
                    missing = true;
                } else {
                    final DualityInterface dual = ih.getInterfaceDuality();
                    for (InvTracker it : t) {
                        if (!it.unlocalizedName.equals(dual.getTermName())) {
                            missing = true;
                        }
                    }
                }

                total += (ih.getInterfaceDuality().getInstalledUpgrades(Upgrades.PATTERN_CAPACITY) + 1);
            }
            return this;
        }
    }
}
