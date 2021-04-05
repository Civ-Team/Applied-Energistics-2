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

package appeng.client.gui.me;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.text.ITextComponent;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Blitter;
import appeng.client.gui.widgets.CommonButtons;
import appeng.client.gui.widgets.Scrollbar;
import appeng.container.me.MachineGroup;
import appeng.container.me.NetworkStatus;
import appeng.container.me.NetworkStatusContainer;
import appeng.core.localization.GuiText;
import appeng.util.Platform;

public class NetworkStatusScreen extends AEBaseScreen<NetworkStatusContainer> {

    private static final int ROWS = 4;
    private static final int COLUMNS = 5;

    // Origin of the machine table
    private static final int TABLE_X = 14;
    private static final int TABLE_Y = 41;

    // Dimensions of each table cell
    private static final int CELL_WIDTH = 30;
    private static final int CELL_HEIGHT = 18;

    private NetworkStatus status = new NetworkStatus();

    private static final Blitter BACKGROUND = Blitter.texture("guis/networkstatus.png")
            .src(0, 0, 195, 153);

    public NetworkStatusScreen(NetworkStatusContainer container, PlayerInventory playerInventory,
            ITextComponent title) {
        super(container, playerInventory, title);
        final Scrollbar scrollbar = new Scrollbar();

        this.setScrollBar(scrollbar);
        this.xSize = BACKGROUND.getSrcWidth();
        this.ySize = BACKGROUND.getSrcHeight();
    }

    @Override
    public void init() {
        super.init();

        this.addButton(CommonButtons.togglePowerUnit(this.guiLeft - 18, this.guiTop + 8));
    }

    @Override
    public void drawBG(MatrixStack matrices, final int offsetX, final int offsetY, final int mouseX, final int mouseY,
            float partialTicks) {
        BACKGROUND.dest(offsetX, offsetY).blit(matrices, getBlitOffset());
    }

    @Override
    public void drawFG(MatrixStack matrixStack, final int offsetX, final int offsetY, final int mouseX,
            final int mouseY) {
        this.font.drawString(matrixStack, GuiText.NetworkDetails.getLocal(), 8, 6, COLOR_DARK_GRAY);

        this.font.drawString(matrixStack,
                GuiText.StoredPower.getLocal() + ": " + Platform.formatPower(status.getStoredPower(), false),
                13, 16, COLOR_DARK_GRAY);
        this.font.drawString(matrixStack,
                GuiText.MaxPower.getLocal() + ": " + Platform.formatPower(status.getMaxStoredPower(), false), 13, 26,
                COLOR_DARK_GRAY);

        this.font.drawString(matrixStack, GuiText.PowerInputRate.getLocal() + ": "
                + Platform.formatPower(status.getAveragePowerInjection(), true), 13, 143 - 10, COLOR_DARK_GRAY);
        this.font.drawString(matrixStack,
                GuiText.PowerUsageRate.getLocal() + ": " + Platform.formatPower(status.getAveragePowerUsage(), true),
                13, 143 - 20, COLOR_DARK_GRAY);

        int x = 0;
        int y = 0;
        final int viewStart = getScrollBar().getCurrentScroll() * COLUMNS;
        final int viewEnd = viewStart + COLUMNS * ROWS;

        List<ITextComponent> tooltip = null;
        int toolPosX = 0;
        int toolPosY = 0;

        List<MachineGroup> machines = status.getGroupedMachines();
        for (int i = viewStart; i < Math.min(viewEnd, machines.size()); i++) {
            MachineGroup entry = machines.get(i);

            int cellX = TABLE_X + x * CELL_WIDTH;
            int cellY = TABLE_Y + y * CELL_HEIGHT;

            // Position the item with a margin of 1px to the right of the cell
            int itemX = cellX + CELL_WIDTH - 17;
            int itemY = cellY + 1;

            drawMachineCount(matrixStack, itemX, cellY, entry.getCount());

            this.drawItem(itemX, itemY, entry.getDisplay());

            // Update the tooltip based on the calculated cell rectangle and mouse position
            if (isPointInRegion(cellX, cellY, CELL_WIDTH, CELL_HEIGHT, mouseX, mouseY)) {
                tooltip = new ArrayList<>();
                tooltip.add(entry.getDisplay().getDisplayName());

                tooltip.add(GuiText.Installed.withSuffix(": " + entry.getCount()));
                if (entry.getIdlePowerUsage() > 0) {
                    tooltip.add(GuiText.EnergyDrain
                            .withSuffix(": " + Platform.formatPower(entry.getIdlePowerUsage(), true)));
                }

                // Hard to know why drawTooltip uses these offsets, it seems to be the center of the item stack (16x16)
                // which leads to the tooltip being perfectly to the right of it.
                toolPosX = itemX + 8;
                toolPosY = itemY + 8;
            }

            if (++x >= COLUMNS) {
                y++;
                x = 0;
            }
        }

        if (tooltip != null) {
            this.drawTooltip(matrixStack, toolPosX, toolPosY, tooltip);
        }
    }

    // x,y is upper left corner of related machine icon (which is 16x16)
    private void drawMachineCount(MatrixStack matrixStack, int x, int y, long count) {
        String str;
        if (count >= 10000) {
            str = Long.toString(count / 1000) + 'k';
        } else {
            str = Long.toString(count);
        }

        // Keep in mind the text will be scaled down 50%
        float textWidth = this.font.getStringWidth(str) / 2.0f;
        float textHeight = this.font.FONT_HEIGHT / 2.0f;

        // Draw the count at half-size
        matrixStack.push();
        matrixStack.translate(
                x - 1 - textWidth,
                y + (CELL_HEIGHT - textHeight) / 2.0f,
                0);
        matrixStack.scale(0.5f, 0.5f, 0.5f);
        this.font.drawString(matrixStack, str, 0, 0, COLOR_DARK_GRAY);
        matrixStack.pop();
    }

    public void processServerUpdate(NetworkStatus status) {
        this.status = status;
        this.setScrollBar();
    }

    private void setScrollBar() {
        final int size = this.status.getGroupedMachines().size();
        this.getScrollBar().setTop(39).setLeft(175).setHeight(78);
        int overflowRows = (size + (COLUMNS - 1)) / COLUMNS - ROWS;
        this.getScrollBar().setRange(0, overflowRows, 1);
    }

}
