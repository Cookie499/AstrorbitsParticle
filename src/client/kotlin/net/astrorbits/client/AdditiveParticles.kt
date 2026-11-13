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

	fun registerIris() {
		if (assigned || !FabricLoader.getInstance().isModLoaded("iris")) return
		runCatching {
			val apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
			val programClass = Class.forName("net.irisshaders.iris.api.v0.IrisProgram")
			val api = apiClass.getMethod("getInstance").invoke(null)
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