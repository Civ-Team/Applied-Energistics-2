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

package appeng.container.slot;

import javax.annotation.Nonnull;

import appeng.client.Point;
import net.minecraft.client.renderer.Rectangle2d;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

public class OptionalFakeSlot extends FakeSlot implements IOptionalSlot {

    private final int groupNum;
    private final IOptionalSlotHost host;
    private final Rectangle2d backgroundSrcRect;
    private boolean renderDisabled = true;

    public OptionalFakeSlot(final IItemHandler inv, final IOptionalSlotHost containerBus, int invSlot, int groupNum) {
        super(inv, invSlot);
        this.groupNum = groupNum;
        this.host = containerBus;
        this.backgroundSrcRect = new Rectangle2d(0, 0, 18, 18);
    }

    @Override
    @Nonnull
    public ItemStack getStack() {
        if (!this.isSlotEnabled()) {
            if (!this.getDisplayStack().isEmpty()) {
                this.clearStack();
            }
        }

        return super.getStack();
    }

    @Override
    public boolean isSlotEnabled() {
        if (this.host == null) {
            return false;
        }

        return this.host.isSlotEnabled(this.groupNum);
    }

    @Override
    public boolean isRenderDisabled() {
        return this.renderDisabled;
    }

    public void setRenderDisabled(final boolean renderDisabled) {
        this.renderDisabled = renderDisabled;
    }

    @Override
    public Rectangle2d getBackgroundSrcRect() {
        return backgroundSrcRect;
    }

    @Override
    public Point getBackgroundPos() {
        return new Point(xPos - 1, yPos - 1);
    }
}
