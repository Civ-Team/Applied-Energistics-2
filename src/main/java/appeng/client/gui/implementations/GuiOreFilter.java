package appeng.client.gui.implementations;

import appeng.client.gui.AEBaseGui;
import appeng.container.AEBaseContainer;
import appeng.container.implementations.ContainerOreFilter;
import appeng.core.AELog;
import appeng.core.localization.GuiText;
import appeng.core.sync.GuiBridge;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketSwitchGuis;
import appeng.core.sync.packets.PacketValueConfig;
import appeng.helpers.IOreFilterable;
import appeng.parts.automation.PartSharedItemBus;
import appeng.parts.misc.PartStorageBus;
import appeng.tile.misc.TileCellWorkbench;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.entity.player.InventoryPlayer;

import java.io.IOException;

public class GuiOreFilter extends AEBaseGui {
    private GuiTextField filter;
    public GuiOreFilter(InventoryPlayer ip, IOreFilterable obj) {
        super(new ContainerOreFilter(ip, obj));
    }

    @Override
    public void initGui() {
        super.initGui();
        this.filter = new GuiTextField(this.fontRendererObj, this.guiLeft + 13, this.guiTop + 36, 150, this.fontRendererObj.FONT_HEIGHT);
        this.filter.setEnableBackgroundDrawing(false);
        this.filter.setMaxStringLength(32);
        this.filter.setTextColor(0xFFFFFF);
        this.filter.setVisible(true);
        this.filter.setFocused(true);
        ((ContainerOreFilter) this.inventorySlots).setTextField(this.filter);
    }

    @Override
    public void drawFG(int offsetX, int offsetY, int mouseX, int mouseY) {
        this.fontRendererObj.drawString( GuiText.OreFilterLabel.getLocal(), 12, 8, 4210752 );
    }

    @Override
    public void drawBG(int offsetX, int offsetY, int mouseX, int mouseY) {
        this.bindTexture( "guis/renamer.png" );
        this.drawTexturedModalRect( offsetX, offsetY, 0, 0, this.xSize, this.ySize );
        this.filter.drawTextBox();
    }

    @Override
    protected void keyTyped(final char character, final int key) {
        if (key == 28) // Enter
        {
            try
            {
                NetworkHandler.instance.sendToServer(new PacketValueConfig("OreFilter", this.filter.getText()));
            }
            catch (IOException e)
            {
                AELog.debug(e);
            }
            final Object target = ( (AEBaseContainer) this.inventorySlots ).getTarget();
            GuiBridge OriginalGui = null;
            if (target instanceof PartStorageBus)
                OriginalGui = GuiBridge.GUI_STORAGEBUS;
            else if (target instanceof PartSharedItemBus)
                OriginalGui = GuiBridge.GUI_BUS;
            else if (target instanceof TileCellWorkbench)
                OriginalGui = GuiBridge.GUI_CELL_WORKBENCH;

            if (OriginalGui != null)
                NetworkHandler.instance.sendToServer( new PacketSwitchGuis( OriginalGui ) );
            else
                this.mc.thePlayer.closeScreen();
        }
        else if (this.filter.textboxKeyTyped(character, key))
        {
            ((ContainerOreFilter) this.inventorySlots).setFilter(filter.getText());
        }
        else
            super.keyTyped(character, key);
    }
}
