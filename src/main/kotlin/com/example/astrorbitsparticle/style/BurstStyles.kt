package com.example.astrorbitsparticle.style

import com.example.astrorbitsparticle.expr.BoundNumber
import com.example.astrorbitsparticle.math.HashRandom
import com.example.astrorbitsparticle.math.Streams
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

private const val DEG_TO_RAD = (PI / 180.0).toFloat()

object RadialBurst : ParticleStyle {
	override val id = "astrorbitsparticle:radial_burst"

	class Params(
		val speed: BoundNumber,
		val speedJitter: Float,
		val originJitter: Float,
		val axis: Vec3,
		val cone: Float,
		val shellBias: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		speed = binder.num("speed", 6f),
		speedJitter = binder.number("speedJitter", 0.12f),
		originJitter = binder.number("originJitter", 0f),
		axis = binder.vec3("axis", Vec3(0.0, 1.0, 0.0)),
		cone = binder.number("cone", 180f),
		shellBias = binder.number("shellBias", 0f),
	)

	override fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer) {
		val params = effect.styleParams as Params
		val dir = FloatArray(3)
		val pos = FloatArray(3)
		val offset = FloatArray(3)
		val halfAngle = params.cone * DEG_TO_RAD
		val speedBase = params.speed.at(age)

		for (i in 0 until effect.count) {
			val life = EmissionMath.lifetime(effect.emission, seed, i)
			val local = age - EmissionMath.birth(effect.emission, seed, i, effect.count)
			if (local <= 0f || local >= life) continue

			if (params.cone >= 179.9f) StyleMath.sphereInto(dir, seed, i)
			else StyleMath.coneInto(dir, params.axis, halfAngle, seed, i)

			val speed = speedBase *
				(1f + params.speedJitter * HashRandom.signed(seed, Streams.SPEED, i)) *
				(1f + params.shellBias * HashRandom.unit(seed, Streams.SPEED, i, 2))
			StyleMath.posInto(
				pos, origin.x.toFloat(), origin.y.toFloat(), origin.z.toFloat(),
				dir[0] * speed, dir[1] * speed, dir[2] * speed,
				effect.physics.gravity, effect.physics.drag, effect.physics.wind, local,
			)
			StyleMath.jitterInto(offset, params.originJitter, seed, i)
			StyleSupport.emit(
				effect, seed, i, age, local / life,
				pos[0] + offset[0], pos[1] + offset[1], pos[2] + offset[2], out,
			)
		}
	}
}

object SphericalBurst : ParticleStyle {
	override val id = "astrorbitsparticle:spherical_burst"

	class Params(
		val speed: BoundNumber,
		val fill: Float,
		val shellJitter: Float,
		val flatten: Float,
		val originJitter: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		speed = binder.num("speed", 5f),
		fill = binder.number("fill", 0f).coerceIn(0f, 1f),
		shellJitter = binder.number("shellJitter", 0.03f),
		flatten = binder.number("flatten", 1f),
		originJitter = binder.number("originJitter", 0f),
	)

	override fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer) {
		val params = effect.styleParams as Params
		val dir = FloatArray(3)
		val pos = FloatArray(3)
		val offset = FloatArray(3)
		val speedBase = params.speed.at(age)

		for (i in 0 until effect.count) {
			val life = EmissionMath.lifetime(effect.emission, seed, i)
			val local = age - EmissionMath.birth(effect.emission, seed, i, effect.count)
			if (local <= 0f || local >= life) continue

			StyleMath.sphereInto(dir, seed, i)
			val c = HashRandom.unit(seed, Streams.SPEED, i)
			val shell = 1f + params.shellJitter * (2f * c - 1f)
			val filled = Math.cbrt(c.toDouble()).toFloat()
			val speed = speedBase * (shell + (filled - shell) * params.fill)
			StyleMath.posInto(
				pos, origin.x.toFloat(), origin.y.toFloat(), origin.z.toFloat(),
				dir[0] * speed, dir[1] * speed, dir[2] * speed,
				effect.physics.gravity, effect.physics.drag, effect.physics.wind, local,
			)
			StyleMath.jitterInto(offset, params.originJitter, seed, i)
			val y = origin.y.toFloat() + (pos[1] - origin.y.toFloat()) * params.flatten
			StyleSupport.emit(
				effect, seed, i, age, local / life,
				pos[0] + offset[0], y + offset[1], pos[2] + offset[2], out,
			)
		}
	}
}

object RingBurst : ParticleStyle {
	override val id = "astrorbitsparticle:ring_burst"

	class Params(
		val planeNormal: Vec3,
		val speed: BoundNumber,
		val speedJitter: Float,
		val thickness: Float,
		val radius0: Float,
		val even: Boolean,
		val angleJitter: Float,
		val arc: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		planeNormal = binder.vec3("planeNormal", Vec3(0.0, 1.0, 0.0)),
		speed = binder.num("speed", 6f),
		speedJitter = binder.number("speedJitter", 0.08f),
		thickness = binder.number("thickness", 0f),
		radius0 = binder.number("radius0", 0f),
		even = binder.bool("even", true),
		angleJitter = binder.number("angleJitter", 0f),
		arc = binder.number("arc", 360f),
	)

	override fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer) {
		val params = effect.styleParams as Params
		val normal = if (params.planeNormal.lengthSqr() < 1e-9) Vec3(0.0, 1.0, 0.0) else params.planeNormal.normalize()
		val (e1, e2) = StyleMath.orthoBasis(normal)
		val pos = FloatArray(3)
		val arcRad = params.arc * DEG_TO_RAD
		val speedBase = params.speed.at(age)
		val last = (effect.count - 1).coerceAtLeast(1)

		for (i in 0 until effect.count) {
			val life = EmissionMath.lifetime(effect.emission, seed, i)
			val local = age - EmissionMath.birth(effect.emission, seed, i, effect.count)
			if (local <= 0f || local >= life) continue

			val baseAngle = if (params.even) arcRad * i / last else arcRad * HashRandom.unit(seed, Streams.DIR, i)
			val angle = baseAngle + params.angleJitter * DEG_TO_RAD * (HashRandom.unit(seed, Streams.PHASE, i) - 0.5f)
			val dx = (e1.x * cos(angle.toDouble()) + e2.x * sin(angle.toDouble())).toFloat()
			val dy = (e1.y * cos(angle.toDouble()) + e2.y * sin(angle.toDouble())).toFloat()
			val dz = (e1.z * cos(angle.toDouble()) + e2.z * sin(angle.toDouble())).toFloat()
			val speed = speedBase * (1f + params.speedJitter * HashRandom.signed(seed, Streams.SPEED, i))
			val thickness = params.thickness * (HashRandom.unit(seed, Streams.RADIUS, i) - 0.5f)
			val startX = (origin.x + dx * params.radius0 + normal.x * thickness).toFloat()
			val startY = (origin.y + dy * params.radius0 + normal.y * thickness).toFloat()
			val startZ = (origin.z + dz * params.radius0 + normal.z * thickness).toFloat()
			StyleMath.posInto(
				pos, startX, startY, startZ, dx * speed, dy * speed, dz * speed,
				effect.physics.gravity, effect.physics.drag, effect.physics.wind, local,
			)
			StyleSupport.emit(effect, seed, i, age, local / life, pos[0], pos[1], pos[2], out)
		}
	}
}
