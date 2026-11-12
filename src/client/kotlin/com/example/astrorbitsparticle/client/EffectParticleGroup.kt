package com.example.astrorbitsparticle.client

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

val EFFECT_RENDER_TYPE = ParticleRenderType("astrorbitsparticle:effect", "AOP")

private val PARTICLE_ATLAS = Identifier.withDefaultNamespace("textures/atlas/particles.png")

/**
 * 所有特效共用一个粒子组：组负责推进年龄、做视锥剔除，
 * 并把全部粒子写进同一个 QuadParticleRenderState，逐粒子不创建 Particle 对象。
 */
class EffectParticleGroup(engine: ParticleEngine) : ParticleGroup<Particle>(engine) {
	private val sprites = HashMap<Identifier, SpriteInfo>()
	private var loggedFirstFrame = false

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
		var quads = 0

		for (effect in ClientEffects.all()) {
			if (!frustum.isVisible(effect.cullBox)) continue
			val buffer = effect.sample()
			if (buffer.count == 0) continue
			val sprite = spriteInfo(effect.bound.appearance.sprite)
			val light = lightAt(level, effect)
			val ox = effect.origin.x.toFloat()
			val oy = effect.origin.y.toFloat()
			val oz = effect.origin.z.toFloat()
			val scale = effect.scale

			for (i in 0 until buffer.count) {
				state.add(
					sprite.layer,
					(ox + (buffer.x[i] - ox) * scale - cameraPos.x).toFloat(),
					(oy + (buffer.y[i] - oy) * scale - cameraPos.y).toFloat(),
					(oz + (buffer.z[i] - oz) * scale - cameraPos.z).toFloat(),
					rotation.x, rotation.y, rotation.z, rotation.w,
					buffer.size[i] * scale,
					sprite.u0, sprite.u1, sprite.v0, sprite.v1,
					buffer.argb[i],
					light,
				)
			}
			quads += buffer.count
		}
		if (!loggedFirstFrame && quads > 0) {
			loggedFirstFrame = true
			com.example.astrorbitsparticle.AstrorbitsParticle.logger.info(
				"[effect] first frame: {} quads", quads,
			)
		}
		return state
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
