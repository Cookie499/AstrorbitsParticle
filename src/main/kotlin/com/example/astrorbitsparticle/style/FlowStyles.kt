package com.example.astrorbitsparticle.style

import com.example.astrorbitsparticle.expr.BoundNumber
import com.example.astrorbitsparticle.math.HashRandom
import com.example.astrorbitsparticle.math.Streams
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

private const val DEG = (PI / 180.0).toFloat()

object Fountain : ParticleStyle {
	override val id = "astrorbitsparticle:fountain"

	class Params(
		val axis: Vec3,
		val spread: Float,
		val speed: BoundNumber,
		val speedJitter: Float,
		val nozzleRadius: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		axis = binder.vec3("axis", Vec3(0.0, 1.0, 0.0)),
		spread = binder.number("spread", 18f),
		speed = binder.num("speed", 8f),
		speedJitter = binder.number("speedJitter", 0.12f),
		nozzleRadius = binder.number("nozzleRadius", 0.1f),
	)

	override fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer) {
		val params = effect.styleParams as Params
		val axis = if (params.axis.lengthSqr() < 1e-9) Vec3(0.0, 1.0, 0.0) else params.axis.normalize()
		val dir = FloatArray(3)
		val pos = FloatArray(3)
		val offset = FloatArray(3)
		val halfAngle = params.spread * DEG

		for (i in 0 until effect.count) {
			val birth = EmissionMath.birth(effect.emission, seed, i, effect.count)
			val local = age - birth
			if (local <= 0f) continue
			val life = EmissionMath.lifetime(effect.emission, seed, i)
			if (local >= life) continue

			StyleMath.coneInto(dir, axis, halfAngle, seed, i)
			val speed = params.speed.at(birth) * (1f + params.speedJitter * HashRandom.signed(seed, Streams.SPEED, i))
			StyleMath.jitterInto(offset, params.nozzleRadius, seed, i)
			StyleMath.posInto(
				pos,
				(origin.x + offset[0]).toFloat(), (origin.y + offset[1]).toFloat(), (origin.z + offset[2]).toFloat(),
				dir[0] * speed, dir[1] * speed, dir[2] * speed,
				effect.physics.gravity, effect.physics.drag, effect.physics.wind, local,
			)
			StyleSupport.emit(effect, seed, i, age, local / life, pos[0], pos[1], pos[2], out)
		}
	}
}

object Spiral : ParticleStyle {
	override val id = "astrorbitsparticle:spiral"

	class Params(
		val axis: Vec3,
		val radius: BoundNumber,
		val radiusJitter: Float,
		val angular: BoundNumber,
		val axial: BoundNumber,
		val phase: Float,
		val phaseJitter: Float,
		val strands: Int,
		val scatter: Float,
		val scatterGrowth: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		axis = binder.vec3("axis", Vec3(0.0, 1.0, 0.0)),
		radius = binder.num("radius", 1.6f),
		radiusJitter = binder.number("radiusJitter", 0.04f),
		angular = binder.integral("angularSpeed", 180f),
		axial = binder.integral("axialSpeed", 0.9f),
		phase = binder.number("phase", 0f),
		phaseJitter = binder.number("phaseJitter", 6f),
		strands = binder.int("strands", 1).coerceIn(1, 16),
		scatter = binder.number("scatter", 0f),
		scatterGrowth = binder.number("scatterGrowth", 1f),
	)

	override fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer) {
		val params = effect.styleParams as Params
		val axis = if (params.axis.lengthSqr() < 1e-9) Vec3(0.0, 1.0, 0.0) else params.axis.normalize()
		val (e1, e2) = StyleMath.orthoBasis(axis)
		val scatterDir = FloatArray(3)
		val radiusBase = params.radius.at(age)
		val axial = params.axial.at(age)
		val angular = params.angular.at(age) * DEG

		for (i in 0 until effect.count) {
			val life = EmissionMath.lifetime(effect.emission, seed, i)
			val local = age - EmissionMath.birth(effect.emission, seed, i, effect.count)
			if (local <= 0f || local >= life) continue

			val strand = i % params.strands
			val baseAngle = (params.phase + 360f * strand / params.strands +
				params.phaseJitter * (HashRandom.unit(seed, Streams.PHASE, i) - 0.5f)) * DEG
			val angle = baseAngle + params.angular.at(local) * DEG
			val radius = radiusBase * (1f + params.radiusJitter * HashRandom.signed(seed, Streams.RADIUS, i))
			val x = e1.x * cos(angle.toDouble()) * radius + e2.x * sin(angle.toDouble()) * radius
			val y = e1.y * cos(angle.toDouble()) * radius + e2.y * sin(angle.toDouble()) * radius
			val z = e1.z * cos(angle.toDouble()) * radius + e2.z * sin(angle.toDouble()) * radius

			var scatterX = 0f
			var scatterY = 0f
			var scatterZ = 0f
			if (params.scatter > 0f) {
				StyleMath.sphereInto(scatterDir, seed, i)
				val distance = params.scatter * Math.pow((local / effect.duration).toDouble(), params.scatterGrowth.toDouble()).toFloat()
				scatterX = scatterDir[0] * distance
				scatterY = scatterDir[1] * distance
				scatterZ = scatterDir[2] * distance
			}

			StyleSupport.emit(
				effect, seed, i, age, local / life,
				(origin.x + x + axis.x * axial + scatterX).toFloat(),
				(origin.y + y + axis.y * axial + scatterY).toFloat(),
				(origin.z + z + axis.z * axial + scatterZ).toFloat(),
				out,
			)
		}
	}
}

object Wave : ParticleStyle {
	override val id = "astrorbitsparticle:wave"

	class Params(
		val travelAxis: Vec3,
		val amplitude: BoundNumber,
		val wavelength: Float,
		val flowSpeed: Float,
		val driftSpeed: Float,
		val phase: Float,
		val width: Float,
		val depth: Float,
		val rows: Int,
		val jitter: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		travelAxis = binder.vec3("travelAxis", Vec3(1.0, 0.0, 0.0)),
		amplitude = binder.num("amplitude", 0.9f),
		wavelength = binder.number("wavelength", 5f).coerceAtLeast(0.1f),
		flowSpeed = binder.number("flowSpeed", 3f),
		driftSpeed = binder.number("driftSpeed", 0f),
		phase = binder.number("phase", 0f),
		width = binder.number("width", 16f).coerceAtLeast(0.1f),
		depth = binder.number("depth", 1f),
		rows = binder.int("rows", 1).coerceIn(1, 64),
		jitter = binder.number("jitter", 0.05f),
	)

	override fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer) {
		val params = effect.styleParams as Params
		val travel = if (params.travelAxis.lengthSqr() < 1e-9) Vec3(1.0, 0.0, 0.0) else params.travelAxis.normalize()
		val side = travel.cross(Vec3(0.0, 1.0, 0.0)).let {
			if (it.lengthSqr() < 1e-9) Vec3(0.0, 0.0, 1.0) else it.normalize()
		}
		val rows = params.rows
		val cols = ceil(effect.count.toDouble() / rows).toInt().coerceAtLeast(1)
		val amplitude = params.amplitude.at(age)
		val k = 2.0 * PI / params.wavelength.toDouble()

		for (i in 0 until effect.count) {
			val life = EmissionMath.lifetime(effect.emission, seed, i)
			val local = age - EmissionMath.birth(effect.emission, seed, i, effect.count)
			if (local <= 0f || local >= life) continue

			val row = i % rows
			val col = i / rows
			val s = col.toFloat() / (cols - 1).coerceAtLeast(1)
			val along = params.width * StyleMath.fract(s + params.flowSpeed * age / params.width)
			val rowPhase = if (rows > 1) PI * row / (rows - 1) else 0.0
			val y = amplitude * sin(k * (along - params.driftSpeed * age) + params.phase * DEG + rowPhase).toFloat()
			val rowDepth = if (rows > 1) params.depth * (row.toFloat() / (rows - 1) - 0.5f) else 0f
			val jitter = params.jitter * HashRandom.signed(seed, Streams.JITTER, i)

			StyleSupport.emit(
				effect, seed, i, age, local / life,
				(origin.x + travel.x * along + side.x * (rowDepth + jitter)).toFloat(),
				(origin.y + y).toFloat(),
				(origin.z + travel.z * along + side.z * (rowDepth + jitter)).toFloat(),
				out,
			)
		}
	}
}

object Comet : ParticleStyle {
	override val id = "astrorbitsparticle:comet"

	class Params(
		val velocity: Vec3,
		val headScale: Float,
		val headColor: Int,
		val tailCount: Int,
		val tailLag: Float,
		val tailSpread: Float,
		val tailSpreadGrowth: Float,
		val tailWobble: Float,
		val tailFade: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		velocity = binder.vec3("velocity", Vec3(0.0, 5.0, 0.0)),
		headScale = binder.number("headScale", 3f),
		headColor = binder.params.get("headColor")?.let { parseColor(it, 0) } ?: 0,
		tailCount = binder.int("tailCount", 90).coerceIn(0, 1024),
		tailLag = binder.number("tailLag", 0.9f).coerceAtLeast(0.01f),
		tailSpread = binder.number("tailSpread", 0.4f),
		tailSpreadGrowth = binder.number("tailSpreadGrowth", 1.6f),
		tailWobble = binder.number("tailWobble", 0.06f),
		tailFade = binder.number("tailFade", 1.4f),
	)

	override fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer) {
		val params = effect.styleParams as Params
		val pos = FloatArray(3)
		val dir = FloatArray(3)
		val u = (age / effect.duration).coerceIn(0f, 1f)

		StyleMath.posInto(
			pos, origin.x.toFloat(), origin.y.toFloat(), origin.z.toFloat(),
			params.velocity.x.toFloat(), params.velocity.y.toFloat(), params.velocity.z.toFloat(),
			effect.physics.gravity, effect.physics.drag, effect.physics.wind, age,
		)
		StyleSupport.emit(effect, seed, 0, age, u, pos[0], pos[1], pos[2], out, params.headScale, 1f, params.headColor)

		for (j in 1..params.tailCount) {
			val tau = params.tailLag * j / params.tailCount
			val past = age - tau
			if (past <= 0f) continue
			StyleMath.posInto(
				pos, origin.x.toFloat(), origin.y.toFloat(), origin.z.toFloat(),
				params.velocity.x.toFloat(), params.velocity.y.toFloat(), params.velocity.z.toFloat(),
				effect.physics.gravity, effect.physics.drag, effect.physics.wind, past,
			)
			val ratio = tau / params.tailLag
			val spread = params.tailSpread * Math.pow(ratio.toDouble(), params.tailSpreadGrowth.toDouble()).toFloat()
			StyleMath.sphereInto(dir, seed, j)
			val phase0 = HashRandom.unit(seed, Streams.PHASE, j, 0) * 2.0 * PI
			val phase1 = HashRandom.unit(seed, Streams.PHASE, j, 1) * 2.0 * PI
			val phase2 = HashRandom.unit(seed, Streams.PHASE, j, 2) * 2.0 * PI
			val wobble = params.tailWobble
			val alpha = Math.pow((1.0 - ratio).coerceAtLeast(0.0), params.tailFade.toDouble()).toFloat()
			StyleSupport.emit(
				effect, seed, j, age, 0.35f,
				pos[0] + dir[0] * spread + (wobble * sin(3.1 * age + phase0)).toFloat(),
				pos[1] + dir[1] * spread + (wobble * sin(2.7 * age + phase1)).toFloat(),
				pos[2] + dir[2] * spread + (wobble * sin(3.7 * age + phase2)).toFloat(),
				out, 1f, alpha,
			)
		}
	}
}
