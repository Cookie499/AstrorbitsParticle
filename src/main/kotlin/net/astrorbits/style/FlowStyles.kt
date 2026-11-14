package net.astrorbits.style

import net.astrorbits.expr.BoundNumber
import net.astrorbits.math.HashRandom
import net.astrorbits.math.Streams
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

private const val DEG = (PI / 180.0).toFloat()

object Fountain : ParticleStyle {
	override val id = "astrorbitsparticle:fountain"

	override val schema = StyleSchema(
		id, "喷泉",
		params = listOf(
			vecSpec("axis", 0f, 1f, 0f, "喷射主方向"),
			numSpec("spread", 18f, "圆锥半角（度）", "0..90", 0f, 90f),
			numSpec("speed", 8f, "初速度（块/秒），可写含 t 的表达式，按出生时刻求值", "0..64", 0f, 64f),
			numSpec("speedJitter", 0.12f, "粒子之间的速度差异比例", "0..1", 0f, 1f),
			numSpec("nozzleRadius", 0.1f, "喷口半径（块），粒子在喷口球内取起点", "0..4", 0f, 4f),
		),
	)

	class Params(
		val axis: Vec3,
		val spread: Float,
		val speed: BoundNumber,
		val speedJitter: Float,
		val nozzleRadius: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		axis = binder.vec3("axis"),
		spread = binder.number("spread"),
		speed = binder.num("speed"),
		speedJitter = binder.number("speedJitter"),
		nozzleRadius = binder.number("nozzleRadius"),
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

	override val schema = StyleSchema(
		id, "螺旋",
		params = listOf(
			vecSpec("axis", 0f, 1f, 0f, "螺旋轴"),
			numSpec("radius", 1.6f, "旋转半径（块），可写含 t 的表达式", "0..32", 0f, 32f),
			numSpec("radiusJitter", 0.04f, "半径随机抖动比例", "0..1", 0f, 1f),
			numSpec("angularSpeed", 180f, "角速度（度/秒），可写表达式，负值反向旋转"),
			numSpec("axialSpeed", 0.9f, "轴向速度（块/秒），可写表达式"),
			numSpec("phase", 0f, "全局初相位（度）"),
			numSpec("phaseJitter", 6f, "每粒子相位抖动（度）", "0..90", 0f, 90f),
			intSpec("strands", 1, "螺旋股数，2 即双螺旋", "1..16", 1f, 16f),
			numSpec("scatter", 0f, "径向随机扩散速度（块/秒）", "0..16", 0f, 16f),
			numSpec("scatterGrowth", 1f, "扩散随年龄的指数", "0..4", 0f, 4f),
		),
	)

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
		axis = binder.vec3("axis"),
		radius = binder.num("radius"),
		radiusJitter = binder.number("radiusJitter"),
		angular = binder.integral("angularSpeed"),
		axial = binder.integral("axialSpeed"),
		phase = binder.number("phase"),
		phaseJitter = binder.number("phaseJitter"),
		strands = binder.int("strands"),
		scatter = binder.number("scatter"),
		scatterGrowth = binder.number("scatterGrowth"),
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

	override val schema = StyleSchema(
		id, "波浪",
		params = listOf(
			vecSpec("travelAxis", 1f, 0f, 0f, "波的传播方向"),
			numSpec("amplitude", 0.9f, "振幅（块），可写含 t 的表达式", "0..32", 0f, 32f),
			numSpec("wavelength", 5f, "波长（块）", "0.1..64", 0.1f, 64f),
			numSpec("flowSpeed", 3f, "粒子沿曲线流动的速度（块/秒）"),
			numSpec("driftSpeed", 0f, "整条曲线沿传播方向的平移速度（块/秒）"),
			numSpec("phase", 0f, "初相位（度）"),
			numSpec("width", 16f, "波带长度（块）", "0.1..256", 0.1f, 256f),
			numSpec("depth", 1f, "波带宽度（块），rows 大于 1 时生效", "0..64", 0f, 64f),
			intSpec("rows", 1, "横向排数，大于 1 得到波面", "1..64", 1f, 64f),
			numSpec("jitter", 0.05f, "位置随机抖动（块）", "0..4", 0f, 4f),
		),
	)

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
		travelAxis = binder.vec3("travelAxis"),
		amplitude = binder.num("amplitude"),
		wavelength = binder.number("wavelength"),
		flowSpeed = binder.number("flowSpeed"),
		driftSpeed = binder.number("driftSpeed"),
		phase = binder.number("phase"),
		width = binder.number("width"),
		depth = binder.number("depth"),
		rows = binder.int("rows"),
		jitter = binder.number("jitter"),
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

	/** 彗星整条轨迹由当前年龄连续求出，不按周期重新发射。 */
	override val cyclicEmission = false

	override val schema = StyleSchema(
		id, "彗星",
		params = listOf(
			vecSpec("velocity", 0f, 5f, 0f, "主体初速度（块/秒）"),
			numSpec("headScale", 3f, "主体尺寸相对 appearance.size 的倍数", "0.1..16", 0.1f, 16f),
			colorSpec("headColor", "主体颜色，缺省用 appearance.color"),
			intSpec("tailCount", 90, "尾迹粒子数，总粒子数为 1 加该值", "0..1024", 0f, 1024f),
			numSpec("tailLag", 0.9f, "尾迹最长滞后时间（秒）", "0.01..16", 0.01f, 16f),
			numSpec("tailSpread", 0.4f, "最大扩散半径（块）", "0..16", 0f, 16f),
			numSpec("tailSpreadGrowth", 1.6f, "扩散随滞后的指数", "0..4", 0f, 4f),
			numSpec("tailWobble", 0.06f, "尾迹低频抖动幅度（块）", "0..4", 0f, 4f),
			numSpec("tailFade", 1.4f, "尾部亮度衰减指数", "0..8", 0f, 8f),
		),
	)

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
		velocity = binder.vec3("velocity"),
		headScale = binder.number("headScale"),
		headColor = binder.colorOrNull("headColor") ?: 0,
		tailCount = binder.int("tailCount"),
		tailLag = binder.number("tailLag"),
		tailSpread = binder.number("tailSpread"),
		tailSpreadGrowth = binder.number("tailSpreadGrowth"),
		tailWobble = binder.number("tailWobble"),
		tailFade = binder.number("tailFade"),
	)

	override fun stretchVector(effect: BoundEffect, age: Float): Vec3 {
		val params = effect.styleParams as Params
		return params.velocity.add(effect.physics.gravity.scale(age.toDouble()))
	}

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
