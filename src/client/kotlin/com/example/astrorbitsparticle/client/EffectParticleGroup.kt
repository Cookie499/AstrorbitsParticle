package com.example.astrorbitsparticle.client

import com.example.astrorbitsparticle.model.Blend
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.client.particle.ParticleGroup
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import net.minecraft.client.resources.model.sprite.SpriteId
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import org.joml.Quaternionf
import kotlin.math.roundToInt

val EFFECT_RENDER_TYPE = ParticleRenderType("astrorbitsparticle:effect", "AOP")

private val PARTICLE_ATLAS = Identifier.withDefaultNamespace("textures/atlas/particles.png")

private const val MAX_VISIBLE_PARTICLES = 20000
private const val MAX_VISIBLE_EFFECTS = 64
private const val FRAME_BUDGET_MS = 4f
private const val MAX_STRIDE = 4
private const val LOD_NEAR = 24f
private const val LOD_FAR = 96f
private const val STRETCH_SEGMENTS = 3

/**
 * 所有特效共用一个粒子组：组负责推进年龄、做视锥剔除与 LOD，
 * 并把全部粒子写进同一个 QuadParticleRenderState，逐粒子不创建 Particle 对象。
 */
class EffectParticleGroup(engine: ParticleEngine) : ParticleGroup<Particle>(engine) {
	private val sprites = HashMap<Identifier, SpriteInfo>()
	private val shadersActive = net.fabricmc.loader.api.FabricLoader.getInstance()
		.isModLoaded("iris") || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("oculus")
	private val additiveLayer: SingleQuadParticle.Layer? by lazy {
		// 自定义管线在 Iris/Oculus 重载光影时可能失效，光影下退回普通半透明混合
		if (shadersActive) {
			com.example.astrorbitsparticle.AstrorbitsParticle.logger.info(
				"[effect] shader mod detected, additive blend falls back to translucent",
			)
			return@lazy null
		}
		val pipeline = RenderPipeline.builder(net.minecraft.client.renderer.RenderPipelines.PARTICLE_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("astrorbitsparticle", "pipeline/additive_particle"))
			.withColorTargetState(ColorTargetState(BlendFunction.ADDITIVE))
			.build()
		SingleQuadParticle.Layer(true, PARTICLE_ATLAS, pipeline)
	}
	private var loggedFirstFrame = false
	private var adaptiveStride = 1
	private var slowFrames = 0
	private var fastFrames = 0

	override fun add(particle: Particle): Boolean {
		if (particle is EffectAnchorParticle) {
			particle.remove()
			return false
		}
		return super.add(particle)
	}

	override fun tickParticles() {
		ClientEffects.tick()
	}

	override fun isEmpty(): Boolean = ClientEffects.isEmpty()

	override fun size(): Int = ClientEffects.count()

	override fun extractRenderState(frustum: Frustum, camera: Camera, partialTick: Float): ParticleGroupRenderState {
		val state = QuadParticleRenderState()
		val level = Minecraft.getInstance().level ?: return state
		val cameraPos = camera.position()
		val rotation = camera.rotation()
		val started = System.nanoTime()

		val visible = ClientEffects.all()
			.filter { frustum.isVisible(it.cullBox) }
			.sortedBy { it.origin.distanceToSqr(cameraPos) }
			.take(MAX_VISIBLE_EFFECTS)
		val planned = visible.sumOf { it.bound.count }
		val budgetStride = if (planned > MAX_VISIBLE_PARTICLES) {
			kotlin.math.ceil(planned.toFloat() / MAX_VISIBLE_PARTICLES).toInt().coerceIn(1, MAX_STRIDE)
		} else {
			1
		}
		val globalStride = maxOf(adaptiveStride, budgetStride)

		var quads = 0
		for (effect in visible) {
			val buffer = effect.sample()
			if (buffer.count == 0) continue
			val sprite = spriteInfo(effect.bound.appearance.sprite)
			val layer = if (effect.bound.appearance.blend == Blend.ADDITIVE) {
				additiveLayer ?: sprite.layer
			} else {
				sprite.layer
			}
			val light = lightAt(level, effect)
			val ox = effect.origin.x.toFloat()
			val oy = effect.origin.y.toFloat()
			val oz = effect.origin.z.toFloat()
			val scale = effect.scale
			val age = effect.age
			val step = maxOf(globalStride, lodStride(effect.origin.distanceToSqr(cameraPos)))
			val spin = effect.bound.appearance.spin.at(age)
			val quadRotation = if (spin != 0f) {
				Quaternionf(rotation).rotateZ(Math.toRadians((spin * age).toDouble()).toFloat())
			} else {
				rotation
			}
			val stretch = effect.bound.appearance.stretch.at(age)
			val stretchVec = if (stretch > 0f) effect.bound.style.stretchVector(effect.bound, age) else null
			val svx = stretchVec?.x?.toFloat() ?: 0f
			val svy = stretchVec?.y?.toFloat() ?: 0f
			val svz = stretchVec?.z?.toFloat() ?: 0f
			val stretched = stretchVec != null && svx * svx + svy * svy + svz * svz > 1e-6f

			var i = 0
			while (i < buffer.count) {
				val px = (ox + (buffer.x[i] - ox) * scale - cameraPos.x).toFloat()
				val py = (oy + (buffer.y[i] - oy) * scale - cameraPos.y).toFloat()
				val pz = (oz + (buffer.z[i] - oz) * scale - cameraPos.z).toFloat()
				val size = buffer.size[i] * scale
				val color = buffer.argb[i]
				state.add(
					layer, px, py, pz,
					quadRotation.x, quadRotation.y, quadRotation.z, quadRotation.w,
					size, sprite.u0, sprite.u1, sprite.v0, sprite.v1, color, light,
				)
				if (stretched) {
					for (segment in 1 until STRETCH_SEGMENTS) {
						val f = segment.toFloat() / STRETCH_SEGMENTS
						state.add(
							layer,
							px - svx * stretch * f,
							py - svy * stretch * f,
							pz - svz * stretch * f,
							quadRotation.x, quadRotation.y, quadRotation.z, quadRotation.w,
							size * (1f - f * 0.4f),
							sprite.u0, sprite.u1, sprite.v0, sprite.v1,
							fadeAlpha(color, 1f - f * 0.6f),
							light,
						)
					}
				}
				i += step
			}
			quads += (buffer.count + step - 1) / step
		}

		adapt((System.nanoTime() - started) / 1_000_000f)

		if (!loggedFirstFrame && quads > 0) {
			loggedFirstFrame = true
			com.example.astrorbitsparticle.AstrorbitsParticle.logger.info(
				"[effect] first frame: {} quads (stride {})", quads, globalStride,
			)
		}
		return state
	}

	private fun lodStride(distanceSqr: Double): Int {
		val distance = kotlin.math.sqrt(distanceSqr).toFloat()
		val t = ((distance - LOD_NEAR) / (LOD_FAR - LOD_NEAR)).coerceIn(0f, 1f)
		val lod = (1f - t * 0.6f).coerceIn(0.35f, 1f)
		return (1f / lod).roundToInt().coerceAtLeast(1)
	}

	/** 单帧超过预算就加步长，连续轻载再逐步降回来。 */
	private fun adapt(elapsedMs: Float) {
		if (elapsedMs > FRAME_BUDGET_MS) {
			slowFrames++
			fastFrames = 0
		} else if (elapsedMs < FRAME_BUDGET_MS * 0.5f) {
			fastFrames++
			slowFrames = 0
		} else {
			slowFrames = 0
			fastFrames = 0
		}
		if (slowFrames >= 30 && adaptiveStride < MAX_STRIDE) {
			adaptiveStride++
			slowFrames = 0
		}
		if (fastFrames >= 120 && adaptiveStride > 1) {
			adaptiveStride--
			fastFrames = 0
		}
	}

	private fun fadeAlpha(color: Int, factor: Float): Int {
		val alpha = (((color ushr 24) and 0xFF) * factor).toInt().coerceIn(0, 255)
		return (color and 0x00FFFFFF) or (alpha shl 24)
	}

	private fun lightAt(level: ClientLevel, effect: ClientEffect): Int {
		if (effect.bound.appearance.fullbright) return 0xF000F0
		val pos = BlockPos.containing(effect.origin)
		val raw = level.lightEngine.getRawBrightness(pos, 0)
		return (raw shl 4) or (raw shl 20)
	}

	private fun spriteInfo(id: Identifier): SpriteInfo = sprites.getOrPut(id) {
		runCatching {
			val sprite = Minecraft.getInstance().atlasManager.get(SpriteId(PARTICLE_ATLAS, id))
			com.example.astrorbitsparticle.AstrorbitsParticle.logger.debug(
				"[effect] sprite {} uv=({}, {})-({}, {})", id, sprite.u0, sprite.v0, sprite.u1, sprite.v1,
			)
			SpriteInfo(
				layer = SingleQuadParticle.Layer.bySprite(sprite),
				u0 = sprite.u0,
				u1 = sprite.u1,
				v0 = sprite.v0,
				v1 = sprite.v1,
			)
		}.getOrElse {
			com.example.astrorbitsparticle.AstrorbitsParticle.logger.warn(
				"[effect] sprite lookup failed for {}: {}", id, it.message,
			)
			SpriteInfo(SingleQuadParticle.Layer.TRANSLUCENT, 0f, 0f, 0f, 0f)
		}
	}

	private data class SpriteInfo(
		val layer: SingleQuadParticle.Layer,
		val u0: Float,
		val u1: Float,
		val v0: Float,
		val v1: Float,
	)
}
