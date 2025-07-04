/*
 * Copyright (c) 2019-2025 Team Galacticraft
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.galacticraft.mod.client.gui.screen.ingame;

import com.google.common.base.Supplier;
import com.google.common.collect.Lists;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.galacticraft.api.client.accessor.ClientSatelliteAccessor;
import dev.galacticraft.api.registry.AddonRegistries;
import dev.galacticraft.api.universe.celestialbody.CelestialBody;
import dev.galacticraft.api.universe.celestialbody.satellite.Orbitable;
import dev.galacticraft.api.universe.celestialbody.star.Star;
import dev.galacticraft.api.universe.galaxy.Galaxy;
import dev.galacticraft.impl.universe.celestialbody.config.DecorativePlanetConfig;
import dev.galacticraft.impl.universe.celestialbody.config.PlanetConfig;
import dev.galacticraft.impl.universe.celestialbody.type.SatelliteType;
import dev.galacticraft.impl.universe.display.config.IconCelestialDisplayConfig;
import dev.galacticraft.impl.universe.position.config.SatelliteConfig;
import dev.galacticraft.mod.content.GCCelestialBodies;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.List;

import static dev.galacticraft.mod.Constant.CelestialScreen.*;
import static dev.galacticraft.mod.Constant.REENTRY_HEIGHT;

@SuppressWarnings({"DataFlowIssue"})
@Environment(EnvType.CLIENT)
public class CelestialScreen extends Screen implements ClientSatelliteAccessor.SatelliteListener {
    protected int borderSize = 0;
    protected int borderEdgeSize = 0;

    protected final RegistryAccess manager = Minecraft.getInstance().level.registryAccess();
    protected final Registry<CelestialBody<?, ?>> celestialBodies = this.manager.registryOrThrow(AddonRegistries.CELESTIAL_BODY);
    protected final Registry<Galaxy> galaxies = this.manager.registryOrThrow(AddonRegistries.GALAXY);
    protected final List<CelestialBody<?, ?>> bodiesToRender = new ArrayList<>();

    protected float preSelectZoom = 0.0F;
    protected Vec2 preSelectPosition = Vec2.ZERO;

    protected float ticksSinceSelectionF = 0;
    protected float ticksSinceUnselectionF = -1;
    protected float ticksSinceMenuOpenF = 0;
    protected float ticksTotalF = 0;

    protected final Map<CelestialBody<?, ?>, Vec3> planetPositions = new IdentityHashMap<>();

    protected @Nullable CelestialBody<?, ?> selectedBody;
    protected @Nullable CelestialBody<?, ?> selectedParent = celestialBodies.get(SOL);
    protected @Nullable CelestialBody<?, ?> lastSelectedBody;

    protected float translationX = 0.0f;
    protected float translationY = 0.0f;
    protected boolean mouseDragging = false;

    public CelestialScreen(Component title) {
        super(title);

        this.bodiesToRender.addAll(this.celestialBodies.stream().toList());
        this.bodiesToRender.sort((o1, o2) -> Float.compare(o1.position().lineScale(), o2.position().lineScale()));

        ClientSatelliteAccessor accessor = (ClientSatelliteAccessor) Objects.requireNonNull(Minecraft.getInstance().getConnection());
        accessor.addListener(this);
    }

    @Override
    public void init() {
        assert this.minecraft != null;

        this.borderSize = this.width / 65;
        this.borderEdgeSize = this.borderSize / 4;
    }

    @Override
    public void onClose() {
        super.onClose();
        assert this.minecraft != null;
        ((ClientSatelliteAccessor) Objects.requireNonNull(this.minecraft.getConnection())).removeListener(this);
    }

    protected boolean isGrandchildBody(@Nullable CelestialBody<?, ?> type) {
        return type != null && (type.parent().isPresent() && type.parentValue(celestialBodies).parent().isPresent());
    }

    protected boolean isPlanet(@Nullable CelestialBody<?, ?> type) {
        return type != null && type.parent().isPresent() && type.parentValue(celestialBodies).type() instanceof Star;
    }

    protected boolean isStar(CelestialBody<?, ?> type) {
        return type != null && type.type() instanceof Star;
    }

    protected float lineScale(CelestialBody<?, ?> celestialBody) {
        if (Float.isNaN(celestialBody.position().lineScale())) return Float.NaN;
        return 3.0F * celestialBody.position().lineScale() * (isPlanet(celestialBody) ? 25.0F : 1.0F / 5.0F);
    }

    protected List<CelestialBody<?, ?>> getSiblings(CelestialBody<?, ?> celestialBody) {
        if (celestialBody == null) return Collections.emptyList();
        List<CelestialBody<?, ?>> bodyList = Lists.newArrayList();

        Optional<ResourceKey<CelestialBody<?, ?>>> parent = celestialBody.parent();
        if (parent.isEmpty()) return Collections.emptyList();

        for (CelestialBody<?, ?> planet : celestialBodies) {
            if (planet.parent().isPresent() && planet.parent().equals(parent)) {
                bodyList.add(planet);
            }
        }

        bodyList.sort((o1, o2) -> Float.compare(o1.position().lineScale(), o2.position().lineScale()));
        return bodyList;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.selectedBody != null) {
                this.unselectCelestialBody();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    protected void unselectCelestialBody() {
        //this.selectionState = EnumSelection.UNSELECTED;
        this.ticksSinceUnselectionF = 0;
        this.lastSelectedBody = this.selectedBody;
        this.selectedBody = null;
    }

    @Override
    public void tick() {
        this.translationX = 0.0F;
        this.translationY = 0.0F;
        if (this.minecraft.player != null && this.minecraft.player.getY() >= REENTRY_HEIGHT) {
            this.minecraft.player.setDeltaMovement(new Vec3(0.0D, 0.0D, 0.0D));
        }
       // this.keyboardTranslation();
    }



    @Override
    public boolean mouseDragged(double x, double y, int activeButton, double dragX, double dragY) {
        if (activeButton == GLFW.GLFW_MOUSE_BUTTON_RIGHT || (this.mouseDragging && activeButton == GLFW.GLFW_MOUSE_BUTTON_LEFT)) {
            this.translationX += (float) ((dragX) * 1);
            this.translationY += (float) ((dragY) * 1);
        }

        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        super.mouseReleased(x, y, button);

        this.mouseDragging = false;

        this.translationX = 0;
        this.translationY = 0;
        return true;
    }


    @Override
    public boolean isPauseScreen() {
        return false;
    }


    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.ticksSinceMenuOpenF += delta;
        this.ticksTotalF += delta;

        if (this.selectedBody != null) {
            this.ticksSinceSelectionF += delta;
        }

        if (this.selectedBody == null && this.ticksSinceUnselectionF >= 0) {
            this.ticksSinceUnselectionF += delta;
        };

        this.setBlackBackground(graphics);

        RenderAllBodies();

        this.drawBorder(graphics);
        RenderSystem.enableCull();
        RenderSystem.disableDepthTest();
        graphics.drawString(this.font, Float.toString(translationX) + " " + Float.toString(translationY), mouseX,mouseY, 0x404040);
        RenderSystem.enableDepthTest();
    }

    float yaw = 45f;
    float pitch = 45f;

    public void RenderAllBodies()
    {
        for(int i = 0; i < bodiesToRender.size(); i++)
        {
           // if(isGrandchildBody(bodiesToRender.get(i)))
           // {
            //    continue;
            //}

            double px = 0.0f;
            double py = 0.0f;
            var par = bodiesToRender.get(i).config();
            if(par instanceof PlanetConfig pcfg)
            {
                var parbody = celestialBodies.get(pcfg.parent().get());
                px += parbody.position().x((long)ticksSinceMenuOpenF,0);
                py += parbody.position().y((long)ticksSinceMenuOpenF,0);
            }

            double x = bodiesToRender.get(i).position().x((long) ticksSinceMenuOpenF,0) / 10;
            double y = bodiesToRender.get(i).position().y((long) ticksSinceMenuOpenF,0) / 10;
            float iconSize = 0;
            if( bodiesToRender.get(i).type() instanceof Star)
            {
                iconSize = 1.4f;
            }
            else
            {
                Optional<ResourceKey<CelestialBody<?, ?>>> parentKey = bodiesToRender.get(i).parent();
                iconSize = celestialBodies.get(parentKey.get()).type() instanceof Star ? 0.5f : 0.2f;

            }
            IconCelestialDisplayConfig d = (IconCelestialDisplayConfig) bodiesToRender.get(i).display().config();



            drawCelestialBody(new Vec3(x+px/10,0,y+py/10),iconSize,  d.texture());
        }
    }

    public Matrix4f getOrbitViewMatrix(float deltaYaw, float deltaPitch, float distance, Vec3 target) {
        yaw += deltaYaw;
        pitch += deltaPitch;
        pitch = Math.max(-89f, Math.min(89f, pitch));
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        double x = target.x + distance * (float)(Math.cos(pitchRad) * Math.sin(yawRad));
        double y = target.y + distance * (float)(Math.sin(pitchRad));
        double z = target.z + distance * (float)(Math.cos(pitchRad) * Math.cos(yawRad));
        Vec3 cameraPos = new Vec3(x, y, z);
        Matrix4f a = new Matrix4f().identity();
        a.lookAt(cameraPos.toVector3f(), target.toVector3f(), new Vector3f(0, 1, 0));
        return a;
    }

    public void drawCelestialBody(Vec3 Position,float size, ResourceLocation bodyTexture)
    {
        RenderSystem.disableBlend();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        Camera maincam = Minecraft.getInstance().gameRenderer.getMainCamera();

        Matrix4f model = new Matrix4f().identity().translate(Position.toVector3f());

        Matrix4f view = getOrbitViewMatrix(-translationX*0.01f, translationY*0.01f, 20.0f, new Vec3(0,0,0));
        Matrix4f viewmodel = view.mul(model);
        Matrix4f getProjection = Minecraft.getInstance().gameRenderer.getProjectionMatrix(70);

        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        Supplier<ShaderInstance> si = GameRenderer::getPositionTexColorShader;

        si.get().setDefaultUniforms(VertexFormat.Mode.TRIANGLES,viewmodel,getProjection,Minecraft.getInstance().getWindow());
        si.get().apply();


        Minecraft.getInstance().getTextureManager().bindForSetup(bodyTexture);

        drawCube(buffer,0,0,0, size);

        BufferUploader.draw( buffer.build());



        RenderSystem.enableCull();
    }

    public static void drawCube(BufferBuilder buffer, float centerX, float centerY, float centerZ, float size) {
        float hs = size / 2f;

        float x0 = centerX - hs;
        float x1 = centerX + hs;
        float y0 = centerY - hs;
        float y1 = centerY + hs;
        float z0 = centerZ - hs;
        float z1 = centerZ + hs;

        buffer.addVertex(x0, y0, z1).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
        buffer.addVertex(x1, y0, z1).setColor(255, 255, 255, 255).setUv(1, 0).setUv2(0, 0).setNormal(0, 0, 1);
        buffer.addVertex(x1, y1, z1).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(0, 0, 1);

        buffer.addVertex(x0, y0, z1).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(0, 0, 1);
        buffer.addVertex(x1, y1, z1).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(0, 0, 1);
        buffer.addVertex(x0, y1, z1).setColor(255, 255, 255, 255).setUv(0, 1).setUv2(0, 0).setNormal(0, 0, 1);

        buffer.addVertex(x1, y0, z0).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(0, 0, -1);
        buffer.addVertex(x0, y0, z0).setColor(255, 255, 255, 255).setUv(1, 0).setUv2(0, 0).setNormal(0, 0, -1);
        buffer.addVertex(x0, y1, z0).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(0, 0, -1);

        buffer.addVertex(x1, y0, z0).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(0, 0, -1);
        buffer.addVertex(x0, y1, z0).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(0, 0, -1);
        buffer.addVertex(x1, y1, z0).setColor(255, 255, 255, 255).setUv(0, 1).setUv2(0, 0).setNormal(0, 0, -1);

        buffer.addVertex(x0, y0, z0).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(-1, 0, 0);
        buffer.addVertex(x0, y0, z1).setColor(255, 255, 255, 255).setUv(1, 0).setUv2(0, 0).setNormal(-1, 0, 0);
        buffer.addVertex(x0, y1, z1).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(-1, 0, 0);

        buffer.addVertex(x0, y0, z0).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(-1, 0, 0);
        buffer.addVertex(x0, y1, z1).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(-1, 0, 0);
        buffer.addVertex(x0, y1, z0).setColor(255, 255, 255, 255).setUv(0, 1).setUv2(0, 0).setNormal(-1, 0, 0);

        buffer.addVertex(x1, y0, z1).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(1, 0, 0);
        buffer.addVertex(x1, y0, z0).setColor(255, 255, 255, 255).setUv(1, 0).setUv2(0, 0).setNormal(1, 0, 0);
        buffer.addVertex(x1, y1, z0).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(1, 0, 0);

        buffer.addVertex(x1, y0, z1).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(1, 0, 0);
        buffer.addVertex(x1, y1, z0).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(1, 0, 0);
        buffer.addVertex(x1, y1, z1).setColor(255, 255, 255, 255).setUv(0, 1).setUv2(0, 0).setNormal(1, 0, 0);

        buffer.addVertex(x0, y1, z1).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(0, 1, 0);
        buffer.addVertex(x1, y1, z1).setColor(255, 255, 255, 255).setUv(1, 0).setUv2(0, 0).setNormal(0, 1, 0);
        buffer.addVertex(x1, y1, z0).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(0, 1, 0);

        buffer.addVertex(x0, y1, z1).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(0, 1, 0);
        buffer.addVertex(x1, y1, z0).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(0, 1, 0);
        buffer.addVertex(x0, y1, z0).setColor(255, 255, 255, 255).setUv(0, 1).setUv2(0, 0).setNormal(0, 1, 0);

        buffer.addVertex(x0, y0, z0).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(0, -1, 0);
        buffer.addVertex(x1, y0, z0).setColor(255, 255, 255, 255).setUv(1, 0).setUv2(0, 0).setNormal(0, -1, 0);
        buffer.addVertex(x1, y0, z1).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(0, -1, 0);

        buffer.addVertex(x0, y0, z0).setColor(255, 255, 255, 255).setUv(0, 0).setUv2(0, 0).setNormal(0, -1, 0);
        buffer.addVertex(x1, y0, z1).setColor(255, 255, 255, 255).setUv(1, 1).setUv2(0, 0).setNormal(0, -1, 0);
        buffer.addVertex(x0, y0, z1).setColor(255, 255, 255, 255).setUv(0, 1).setUv2(0, 0).setNormal(0, -1, 0);
    }

    /**
     * Draws border around gui
     */
    public void drawBorder(GuiGraphics graphics) {
        graphics.fill(0, 0, this.borderSize, this.height, BORDER_Z, BORDER_GREY);
        graphics.fill(this.width - this.borderSize, 0, this.width, this.height, BORDER_Z, BORDER_GREY);
        graphics.fill(0, 0, this.width, this.borderSize, BORDER_Z, BORDER_GREY);
        graphics.fill(0, this.height - this.borderSize, this.width, this.height, BORDER_Z, BORDER_GREY);
        graphics.fill(this.borderSize, this.borderSize, this.borderSize + this.borderEdgeSize, this.height - this.borderSize, BORDER_Z, BORDER_EDGE_TOP_LEFT);
        graphics.fill(this.borderSize, this.borderSize, this.width - this.borderSize, this.borderSize + this.borderEdgeSize, BORDER_Z, BORDER_EDGE_TOP_LEFT);
        graphics.fill(this.width - this.borderSize - this.borderEdgeSize, this.borderSize, this.width - this.borderSize, this.height - this.borderSize, BORDER_Z, BORDER_EDGE_BOTTOM_RIGHT);
        graphics.fill(this.borderSize + this.borderEdgeSize, this.height - this.borderSize - this.borderEdgeSize, this.width - this.borderSize, this.height - this.borderSize, BORDER_Z, BORDER_EDGE_BOTTOM_RIGHT);
    }

    protected boolean isSatellite(CelestialBody<?, ?> selectedBody) {
        return selectedBody != null && selectedBody.isSatellite();
    }

    public void setBlackBackground(GuiGraphics graphics) {
        RenderSystem.depthMask(false);
        graphics.fill(0, 0, this.width, this.height, 0, BLACK);
        RenderSystem.depthMask(true);
    }





    @Contract("_, _, _ -> new")
    protected static @NotNull Vec2 lerpVec2(float delta, @NotNull Vec2 start, @NotNull Vec2 end) {
        return new Vec2(Mth.lerp(delta, start.x, end.x), Mth.lerp(delta, start.y, end.y));
    }

    @Override
    public void onSatelliteUpdated(CelestialBody<SatelliteConfig, SatelliteType> satellite, boolean added) {
        if (!added) {
            this.bodiesToRender.remove(satellite);
        } else {
            this.bodiesToRender.add(satellite);
        }

        this.bodiesToRender.sort((o1, o2) -> Float.compare(o1.position().lineScale(), o2.position().lineScale()));
    }

}
