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

package appeng.client.gui.implementations;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.text.ITextComponent;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.widgets.CommonButtons;
import appeng.container.implementations.WirelessContainer;
import appeng.core.localization.GuiText;
import appeng.util.Platform;

public class WirelessScreen extends AEBaseScreen<WirelessContainer> {

    public WirelessScreen(WirelessContainer container, PlayerInventory playerInventory, ITextComponent title) {
        super(container, playerInventory, title);
        this.ySize = 166;
    }

    @Override
    public void init() {
        super.init();

        this.addButton(CommonButtons.togglePowerUnit(this.guiLeft - 18, this.guiTop + 8));
    }

    @Override
    public void drawFG(MatrixStack matrixStack, final int offsetX, final int offsetY, final int mouseX,
            final int mouseY) {
        this.font.drawString(matrixStack, this.getGuiDisplayName(GuiText.Wireless.text()).getString(), 8, 6, 4210752);
        this.font.drawString(matrixStack, GuiText.inventory.getLocal(), 8, this.ySize - 96 + 3, 4210752);

        if (container.getRange() > 0) {
            final String firstMessage = GuiText.Range.getLocal() + ": " + (container.getRange() / 10.0) + " m";
            final String secondMessage = GuiText.PowerUsageRate.getLocal() + ": "
                    + Platform.formatPowerLong(container.getDrain(), true);

            final int strWidth = Math.max(this.font.getStringWidth(firstMessage),
                    this.font.getStringWidth(secondMessage));
            final int cOffset = (this.xSize / 2) - (strWidth / 2);
            this.font.drawString(matrixStack, firstMessage, cOffset, 20, 4210752);
            this.font.drawString(matrixStack, secondMessage, cOffset, 20 + 12, 4210752);
        }
    }

    @Override
    public void drawBG(MatrixStack matrices, final int offsetX, final int offsetY, final int mouseX, final int mouseY,
            float partialTicks) {
        this.bindTexture("guis/wireless.png");
        blit(matrices, offsetX, offsetY, 0, 0, this.xSize, this.ySize);
    }
}
