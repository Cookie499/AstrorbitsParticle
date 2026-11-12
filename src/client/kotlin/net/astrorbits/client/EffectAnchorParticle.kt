package net.astrorbits.client

import net.astrorbits.particle.EffectAnchorOptions
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleRenderType

/** 触发粒子组创建并携带特效 id，组接管后立即丢弃。 */
class EffectAnchorParticle(
	level: ClientLevel,
	x: Double,
	y: Double,
	z: Double,
	val effectId: Long,
) : Particle(level, x, y, z) {
	override fun getGroup(): ParticleRenderType = EFFECT_RENDER_TYPE

	override fun tick() {
		remove()
	}

	init {
		setLifetime(1)
	}

	companion object {
		fun create(options: EffectAnchorOptions, level: ClientLevel, x: Double, y: Double, z: Double): Particle =
			EffectAnchorParticle(level, x, y, z, options.effectId)
	}
}
