package net.astrorbits.client

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import net.astrorbits.AstrorbitsParticle
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * 粒子的两条自建管线。原版半透明粒子管线写深度，重叠的半透明四边形会互相遮挡，
 * 在密集特效上表现为深度闪烁；这里复制一份只把深度写入关掉，仍做深度测试。
 * Iris 只认识自己映射过的管线，未登记的管线在光影下不绘制，所以启用光影时通过反射登记。
 */
object ParticlePipelines {
	private val particleAtlas = Identifier.withDefaultNamespace("textures/atlas/particles.png")

	private val pipeline: RenderPipeline = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("astrorbitsparticle", "pipeline/additive_particle"))
		.withColorTargetState(ColorTargetState(BlendFunction.ADDITIVE))
		.build()

	private val translucentPipeline: RenderPipeline = RenderPipeline.builder(RenderPipelines.PARTICLE_SNIPPET)
		.withLocation(Identifier.fromNamespaceAndPath("astrorbitsparticle", "pipeline/translucent_particle"))
		.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
		.withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
		.build()

	val layer: SingleQuadParticle.Layer = SingleQuadParticle.Layer(true, particleAtlas, pipeline)

	/** alpha 混合使用自建半透明层：关闭深度写入，避免重叠四边形按深度互相裁掉。 */
	private val customTranslucentLayer: SingleQuadParticle.Layer =
		SingleQuadParticle.Layer(true, particleAtlas, translucentPipeline)

	/** alpha 混合的粒子层；光影启用时退回原版层，保证 Iris 能绑定贴图。 */
	fun translucentLayer(): SingleQuadParticle.Layer =
		if (shaderPackActive) SingleQuadParticle.Layer.TRANSLUCENT else customTranslucentLayer

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
			val assign = apiClass.getMethod("assignPipeline", RenderPipeline::class.java, programClass)
			assign.invoke(api, pipeline, program)
			assign.invoke(api, translucentPipeline, program)
			assigned = true
			AstrorbitsParticle.logger.info("[effect] assigned particle pipelines to Iris")
		}.onFailure {
			AstrorbitsParticle.logger.warn("[effect] failed to assign particle pipelines to Iris: {}", it.message)
		}
	}
}