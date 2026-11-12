package com.example.astrorbitsparticle.style

import com.example.astrorbitsparticle.expr.BoundNumber
import com.example.astrorbitsparticle.math.HashRandom
import com.example.astrorbitsparticle.math.Streams
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

object Ambience : ParticleStyle {
	override val id = "astrorbitsparticle:ambience"

	class Params(
		val box: Vec3,
		val drift: Boolean,
		val bob: Boolean,
		val flicker: Boolean,
		val driftSpeed: Float,
		val driftAmplitude: Float,
		val bobAmplitude: Float,
		val bobPeriod: Float,
		val flickerRate: Float,
		val visibleFraction: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle {
		val modes = binder.strings("mode").map { it.lowercase() }.toSet()
		val fallback = modes.isEmpty()
		return Params(
			box = binder.vec3("box", Vec3(18.0, 8.0, 18.0)),
			drift = fallback || "drift" in modes,
			bob = fallback || "bob" in modes,
			flicker = fallback || "flicker" in modes,
			driftSpeed = binder.number("driftSpeed", 0.15f),
			driftAmplitude = binder.number("driftAmplitude", 1.2f),
			bobAmplitude = binder.number("bobAmplitude", 0.5f),
			bobPeriod = binder.number("bobPeriod", 7f).coerceAtLeast(0.1f),
			flickerRate = binder.number("flickerRate", 0.08f),
			visibleFraction = binder.number("visibleFraction", 0.65f).coerceIn(0f, 1f),
		)
	}

	override fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer) {
		val params = effect.styleParams as Params
		val life = effect.emission.lifetime
		val u = (age / life).coerceIn(0f, 1f)

		for (i in 0 until effect.count) {
			val baseX = (HashRandom.unit(seed, Streams.JITTER, i, 0) - 0.5f) * params.box.x.toFloat()
			val baseY = (HashRandom.unit(seed, Streams.JITTER, i, 1) - 0.5f) * params.box.y.toFloat()
			val baseZ = (HashRandom.unit(seed, Streams.JITTER, i, 2) - 0.5f) * params.box.z.toFloat()

			var alphaScale = 1f
			if (params.flicker) {
				val gate = StyleMath.fract(HashRandom.unit(seed, Streams.TIME, i) + age * params.flickerRate)
				if (gate >= params.visibleFraction) continue
				alphaScale = StyleMath.smoothstep(0f, 0.08f, gate) *
					StyleMath.smoothstep(0f, 0.08f, params.visibleFraction - gate)
			}

			var driftX = 0f
			var driftY = 0f
			var driftZ = 0f
			if (params.drift && params.driftAmplitude != 0f) {
				val f1 = params.driftSpeed * (0.6f + 0.8f * HashRandom.unit(seed, Streams.PHASE, i, 0))
				val f2 = params.driftSpeed * (0.6f + 0.8f * HashRandom.unit(seed, Streams.PHASE, i, 1))
				val f3 = params.driftSpeed * (0.6f + 0.8f * HashRandom.unit(seed, Streams.PHASE, i, 2))
				val p1 = HashRandom.unit(seed, Streams.PHASE, i, 3) * 2.0 * PI
				val p2 = HashRandom.unit(seed, Streams.PHASE, i, 4) * 2.0 * PI
				val p3 = HashRandom.unit(seed, Streams.PHASE, i, 5) * 2.0 * PI
				val amp = params.driftAmplitude
				driftX = amp * sin(2.0 * PI * f1 * age + p1).toFloat()
				driftY = amp * sin(2.0 * PI * f2 * age + p2).toFloat()
				driftZ = amp * sin(2.0 * PI * f3 * age + p3).toFloat()
			}

			var bob = 0f
			if (params.bob && params.bobAmplitude != 0f) {
				val phase = HashRandom.unit(seed, Streams.PHASE, i, 6) * 2.0 * PI
				bob = params.bobAmplitude * sin(2.0 * PI * age / params.bobPeriod + phase).toFloat()
			}

			StyleSupport.emit(
				effect, seed, i, age, u,
				(origin.x + baseX + driftX).toFloat(),
				(origin.y + baseY + driftY + bob).toFloat(),
				(origin.z + baseZ + driftZ).toFloat(),
				out, 1f, alphaScale,
			)
		}
	}
}

object Rain : ParticleStyle {
	override val id = "astrorbitsparticle:rain"

	class Params(
		val direction: Vec3,
		val speedMin: Float,
		val speedMax: Float,
		val area: Vec3,
		val streakLength: Float,
		val wrap: Boolean,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle {
		val range = binder.floats("speedRange") ?: floatArrayOf(14f, 22f)
		val min = range.getOrElse(0) { 14f }
		val max = range.getOrElse(1) { 22f }
		return Params(
			direction = binder.vec3("direction", Vec3(0.12, -1.0, 0.0)),
			speedMin = minOf(min, max),
			speedMax = maxOf(min, max),
			area = binder.vec3("area", Vec3(26.0, 18.0, 26.0)),
			streakLength = binder.number("streakLength", 0.8f),
			wrap = binder.bool("wrap", true),
		)
	}

	override fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer) {
		val params = effect.styleParams as Params
		val dir = if (params.direction.lengthSqr() < 1e-9) Vec3(0.0, -1.0, 0.0) else params.direction.normalize()
		val extent = (abs(dir.x) * params.area.x + abs(dir.y) * params.area.y + abs(dir.z) * params.area.z).toFloat()
		val halfX = (params.area.x * 0.5).toFloat()
		val halfY = (params.area.y * 0.5).toFloat()
		val halfZ = (params.area.z * 0.5).toFloat()

		for (i in 0 until effect.count) {
			val life = EmissionMath.lifetime(effect.emission, seed, i)
			val local = age - EmissionMath.birth(effect.emission, seed, i, effect.count)
			if (local <= 0f || local >= life) continue

			val speed = params.speedMin + (params.speedMax - params.speedMin) * HashRandom.unit(seed, Streams.SPEED, i)
			val along = speed * age + HashRandom.unit(seed, Streams.TIME, i) * extent
			var x = (origin.x + dir.x * along).toFloat()
			var y = (origin.y + dir.y * along).toFloat()
			var z = (origin.z + dir.z * along).toFloat()
			if (params.wrap) {
				x = wrap(x, origin.x.toFloat(), params.area.x.toFloat())
				y = wrap(y, origin.y.toFloat(), params.area.y.toFloat())
				z = wrap(z, origin.z.toFloat(), params.area.z.toFloat())
			}
			StyleSupport.emit(effect, seed, i, age, local / life, x, y, z, out)
		}
	}

	private fun wrap(value: Float, center: Float, size: Float): Float {
		if (size <= 1e-4f) return value
		val offset = (value - center + size * 0.5f).mod(size)
		return center + offset - size * 0.5f
	}
}

object BuiltinStyles {
	fun registerAll() {
		ParticleStyles.register(RadialBurst)
		ParticleStyles.register(SphericalBurst)
		ParticleStyles.register(RingBurst)
		ParticleStyles.register(Fountain)
		ParticleStyles.register(Spiral)
		ParticleStyles.register(Wave)
		ParticleStyles.register(Comet)
		ParticleStyles.register(Ambience)
		ParticleStyles.register(Rain)
	}
}
