package Reika.DragonAPI.Extras;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import Reika.DragonAPI.DragonAPICore;
import Reika.DragonAPI.DragonAPIInit;
import Reika.DragonAPI.Auxiliary.Trackers.TickRegistry;
import Reika.DragonAPI.Auxiliary.Trackers.TickRegistry.TickHandler;
import Reika.DragonAPI.Auxiliary.Trackers.TickRegistry.TickType;
import Reika.DragonAPI.IO.Shaders.ShaderHook;
import Reika.DragonAPI.IO.Shaders.ShaderProgram;
import Reika.DragonAPI.IO.Shaders.ShaderRegistry;
import Reika.DragonAPI.IO.Shaders.ShaderRegistry.ShaderDomain;
import Reika.DragonAPI.Instantiable.Data.Immutable.DecimalPosition;
import Reika.DragonAPI.Libraries.ReikaPlayerAPI;
import Reika.DragonAPI.Libraries.IO.ReikaColorAPI;
import Reika.DragonAPI.Libraries.IO.ReikaRenderHelper;
import Reika.DragonAPI.Libraries.IO.ReikaRenderHelper.ScratchFramebuffer;
import Reika.DragonAPI.Libraries.IO.ReikaTextureHelper;

import cpw.mods.fml.common.gameevent.TickEvent.Phase;

public class ReikaShader implements ShaderHook, TickHandler {

	public static final ReikaShader instance = new ReikaShader();

	private ShaderProgram stencilShader;
	private ShaderProgram effectShader;
	private ScratchFramebuffer stencil;

	private final ArrayList<ShaderPoint> points = new ArrayList();
	private boolean rendering;

	private BufferedImage tempImage;
	private int tempTex;

	private ReikaShader() {

	}

	public void register() {
		stencilShader = ShaderRegistry.createShader(DragonAPIInit.instance, "reika_stencil", DragonAPICore.class, "Resources/", ShaderDomain.ENTITY).setEnabled(false);
		effectShader = ShaderRegistry.createShader(DragonAPIInit.instance, "reika_effect", DragonAPICore.class, "Resources/", ShaderDomain.GLOBALNOGUI).setEnabled(false);
		TickRegistry.instance.registerTickHandler(this);
		stencil = new ScratchFramebuffer(Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight, true);
		stencil.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
		effectShader.setHook(this);
		stencilShader.setHook(this);

		tempImage = new BufferedImage(Minecraft.getMinecraft().displayWidth, Minecraft.getMinecraft().displayHeight, BufferedImage.TYPE_INT_ARGB);
		Random rand = new Random();
		rand.nextBoolean();
		for (int i = 0; i < 1000; i++) {
			int x = rand.nextInt(tempImage.getWidth());
			int y = rand.nextInt(tempImage.getHeight());
			tempImage.setRGB(x, y, ReikaColorAPI.RGBtoHex(rand.nextInt(255), rand.nextInt(255), rand.nextInt(255), 255));
		}
		tempTex = ReikaTextureHelper.binder.allocateAndSetupTexture(tempImage);
	}

	public void updatePosition(Entity ep) {
		if (rendering)
			return;
		if (ep == Minecraft.getMinecraft().thePlayer)
			return;
		boolean flag = true;
		long time = ep.worldObj.getTotalWorldTime();
		Iterator<ShaderPoint> it = points.iterator();
		while (it.hasNext()) {
			ShaderPoint p = it.next();
			if (p.position.equals(ep.posX, ep.posY, ep.posZ)) {
				p.refresh(time);
				flag = false;
			}
			if (p.tick(time)) {
				it.remove();
			}
		}
		if (flag) {
			ShaderPoint p = new ShaderPoint(ep);
			points.add(0, p);
		}
	}

	public void prepareRender(Entity ep) {
		Minecraft mc = Minecraft.getMinecraft();
		if (ep == mc.thePlayer)
			return;
		if (points.isEmpty())
			return;

		GL11.glPushMatrix();
		double dist = ep.getDistanceSqToEntity(mc.thePlayer);
		if (mc.gameSettings.thirdPersonView > 0) {
			dist += ReikaRenderHelper.thirdPersonDistance;
		}
		float ptick = ReikaRenderHelper.getPartialTickTime();
		double px = ep.lastTickPosX+(ep.posX-ep.lastTickPosX)*ptick;
		double py = ep.lastTickPosY+(ep.posY-ep.lastTickPosY)*ptick;
		double pz = ep.lastTickPosZ+(ep.posZ-ep.lastTickPosZ)*ptick;

		GL11.glTranslated(RenderManager.renderPosX-ep.posX, RenderManager.renderPosY-ep.posY, RenderManager.renderPosZ-ep.posZ);
		GL11.glTranslated(0, -0.8, 0);
		GL11.glRotated(180, 0, 1, 0);
		stencilShader.setEnabled(true);
		effectShader.setEnabled(true);
		HashMap<String, Object> map = new HashMap();
		map.put("distance", dist);
		rendering = true;
		for (ShaderPoint pt : points) {
			float f = pt.getIntensity();
			double f2 = 1;
			double d2 = pt.position.getDistanceTo(ep.posX, ep.posY, ep.posZ);
			if (d2 <= 2) {
				f2 = d2/2D;
			}
			f *= f2;
			if (f > 0) {
				map.put("age", pt.age/(float)pt.LIFESPAN);
				GL11.glPushMatrix();
				DecimalPosition p = pt.position;
				GL11.glTranslated(p.xCoord-px, p.yCoord-py, p.zCoord-pz);
				stencilShader.addFocus(p.xCoord, p.yCoord, p.zCoord);
				//stencilShader.addFocus(ep);
				stencilShader.modifyLastCompoundFocus(f, map);
				GL11.glPopMatrix();
			}
		}
		rendering = false;
		GL11.glPopMatrix();
	}

	public void render(Minecraft mc) {
		stencil.clear();
		stencil.setFramebufferColor(0.0F, 0.0F, 0.0F, 0.0F);
		stencil.createBindFramebuffer(mc.displayWidth, mc.displayHeight);
		ReikaRenderHelper.renderFrameBufferToItself(stencil, mc.displayWidth, mc.displayHeight, stencilShader);
		ReikaRenderHelper.setRenderTarget(mc.getFramebuffer());
		//stencil.framebufferRender(mc.displayWidth, mc.displayHeight);

		stencilShader.clearFoci();
		stencilShader.setEnabled(false);
	}

	public Framebuffer getStencil() {
		return stencil;
	}

	@Override
	public void onPreRender(ShaderProgram s) {
		if (s == effectShader) {
			int base = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
			GL13.glActiveTexture(base + 1); // Texture unit 1
			s.setField("stencilTex", base+1);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, stencil.framebufferTexture);
			GL13.glActiveTexture(base); // Texture unit 0
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getMinecraft().getFramebuffer().framebufferTexture);
		}
	}

	@Override
	public void onPostRender(ShaderProgram s) {
		//if (!effectShader.hasOngoingFoci()) {
		if (s == effectShader)
			s.setEnabled(false);
		//	effectShader.clearFoci();
		//}
	}

	@Override
	public void updateEnabled(ShaderProgram s) {

	}

	private static class ShaderPoint {

		private static final int LIFESPAN = 30;

		private final DecimalPosition position;

		private long creation;
		private int age;

		private ShaderPoint(Entity ep) {
			position = new DecimalPosition(ep);
			creation = ep.worldObj.getTotalWorldTime();
		}

		public void refresh(long world) {
			age = 0;
			creation = world;
		}

		public boolean tick(long world) {
			age++;
			long val = Math.max(age, world-creation);
			return val >= LIFESPAN;
		}

		public float getIntensity() {
			if (age < 5) {
				return 0;
			}
			else if (age < 10) {
				return (age-5)/5F;
			}
			return 1F-(age-10)/(float)(LIFESPAN-10);
		}

	}

	@Override
	public void tick(TickType type, Object... tickData) {
		EntityPlayer ep = (EntityPlayer)tickData[0];
		if (ep.worldObj.isRemote && ReikaPlayerAPI.isReika(ep)) {
			this.updatePosition(ep);
		}
	}

	@Override
	public EnumSet<TickType> getType() {
		return EnumSet.of(TickType.PLAYER);
	}

	@Override
	public boolean canFire(Phase p) {
		return p == Phase.END;
	}

	@Override
	public String getLabel() {
		return "reikashader";
	}

}