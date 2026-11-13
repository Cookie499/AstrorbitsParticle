package net.astrorbits.client

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.astrorbits.AstrorbitsParticle
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * additive 混合使用独立管线。Iris 只认识自己映射过的管线，
 * 未登记的管线在光影下不绘制，所以启动时通过反射调用 Iris API 登记。
 */
object AdditiveParticles {
	private val particleAtlas = Identifier.withDefaultNamespace("textures/atlas/particles.png")

	private val pipeline: RenderPipeline = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("astrorbitsparticle", "pipeline/additive_particle"))
		.withColorTargetState(ColorTargetState(BlendFunction.ADDITIVE))
		.build()

	val layer: SingleQuadParticle.Layer = SingleQuadParticle.Layer(true, particleAtlas, pipeline)

	private var assigned = false
	private var shaderPackActive = false

	/** 光影启用时 Iris 仍会丢失我们的纹理绑定，退回原版半透明层保证贴图正确。 */
	fun layerFor(fallback: SingleQuadParticle.Layer): SingleQuadParticle.Layer =
		if (shaderPackActive) fallback else layer

	/**
	 * 客户端初始化时 Iris 可能还没建立程序表，过早登记会被丢弃。
	 * 每次提取时检查一次，等光影真正启用后再登记。
	 */
	fun registerIris() {
		if (assigned || !FabricLoader.getInstance().isModLoaded("iris")) return
		runCatching {
			val apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
			val programClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram")
			val api = apiClass.getMethod("getInstance").invoke(null)
			val inUse = apiClass.getMethod("isShaderPackInUse").invoke(api) as Boolean
			shaderPackActive = inUse
			if (!inUse) return
			val program = programClass.getField("PARTICLES_TRANSLUCENT").get(null)
			apiClass.getMethod("assignPipeline", RenderPipeline::class.java, programClass)
				.invoke(api, pipeline, program)
			assigned = true
			AstrorbitsParticle.logger.info("[effect] assigned additive pipeline to Iris")
		}.onFailure {
			AstrorbitsParticle.logger.warn("[effect] failed to assign additive pipeline to Iris: {}", it.message)
		}
	}
}