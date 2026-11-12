package net.astrorbits.particle

import net.astrorbits.AstrorbitsParticle
import com.mojang.serialization.Codec
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes
import net.minecraft.core.Registry
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.codec.ByteBufCodecs

/**
 * 锚点粒子只用来触发客户端的粒子组并把特效 id 带进渲染管线，本身不渲染。
 */
class EffectAnchorOptions(val effectId: Long) : ParticleOptions {
	override fun getType(): ParticleType<*> = TYPE

	companion object {
		val CODEC: Codec<EffectAnchorOptions> =
			Codec.LONG.xmap(::EffectAnchorOptions, EffectAnchorOptions::effectId)

		val TYPE: ParticleType<EffectAnchorOptions> = Registry.register(
			BuiltInRegistries.PARTICLE_TYPE,
			AstrorbitsParticle.id("effect_anchor"),
			FabricParticleTypes.complex(
				Codec.LONG.fieldOf("effect").xmap(::EffectAnchorOptions, EffectAnchorOptions::effectId),
				ByteBufCodecs.VAR_LONG.map(::EffectAnchorOptions, EffectAnchorOptions::effectId),
			),
		)
	}
}
