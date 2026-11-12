package net.astrorbits.style

import net.astrorbits.expr.BoundNumber
import net.astrorbits.math.HashRandom
import net.astrorbits.math.Streams
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

private const val DEG_TO_RAD = (PI / 180.0).toFloat()

object RadialBurst : ParticleStyle {
	override val id = "astrorbitsparticle:radial_burst"

	override val schema = StyleSchema(
		id, "放射状爆发",
		params = listOf(
			numSpec("speed", 6f, "基准速度（块/秒），可写含 t 的表达式", "0..64", 0f, 64f),
			numSpec("speedJitter", 0.12f, "粒子之间的速度差异比例", "0..1", 0f, 1f),
			numSpec("originJitter", 0f, "原点抖动半径（块）", "0..8", 0f, 8f),
			vecSpec("axis", 0f, 1f, 0f, "锥形喷射主方向，只在 cone 小于 180 时生效"),
			numSpec("cone", 180f, "喷射半角（度），180 为全向", "0..180", 0f, 180f),
			numSpec("shellBias", 0f, "速度分布向快端偏移的比例", "0..1", 0f, 1f),
		),
	)

	class Params(
		val speed: BoundNumber,
		val speedJitter: Float,
		val originJitter: Float,
		val axis: Vec3,
		val cone: Float,
		val shellBias: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		speed = binder.num("speed"),
		speedJitter = binder.number("speedJitter"),
		originJitter = binder.number("originJitter"),
		axis = binder.vec3("axis"),
		cone = binder.number("cone"),
		shellBias = binder.number("shellBias"),
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

	override val schema = StyleSchema(
		id, "球状爆发",
		params = listOf(
			numSpec("speed", 5f, "球面最大速度（块/秒）", "0..64", 0f, 64f),
			numSpec("fill", 0f, "0 空心壳，1 实心球，中间值介于两者", "0..1", 0f, 1f),
			numSpec("shellJitter", 0.03f, "壳层厚度抖动比例，fill 为 0 时生效", "0..1", 0f, 1f),
			numSpec("flatten", 1f, "Y 轴压扁系数，小于 1 得到扁球", "0.1..4", 0.1f, 4f),
			numSpec("originJitter", 0f, "原点抖动半径（块）", "0..8", 0f, 8f),
		),
	)

	class Params(
		val speed: BoundNumber,
		val fill: Float,
		val shellJitter: Float,
		val flatten: Float,
		val originJitter: Float,
	) : BoundStyle

	override fun bind(binder: Binder): BoundStyle = Params(
		speed = binder.num("speed"),
		fill = binder.number("fill"),
		shellJitter = binder.number("shellJitter"),
		flatten = binder.number("flatten"),
		originJitter = binder.number("originJitter"),
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

	override val schema = StyleSchema(
		id, "环形爆发",
		params = listOf(
			vecSpec("planeNormal", 0f, 1f, 0f, "环所在平面的法线"),
			numSpec("speed", 6f, "径向速度（块/秒）", "0..64", 0f, 64f),
			numSpec("speedJitter", 0.08f, "粒子之间的速度差异比例", "0..1", 0f, 1f),
			numSpec("thickness", 0f, "沿法线的环厚度（块）", "0..8", 0f, 8f),
			numSpec("radius0", 0f, "初始半径，大于 0 时从既有圆环向外扩张", "0..64", 0f, 64f),
			boolSpec("even", true, "是否等角度分布；false 为随机角度"),
			numSpec("angleJitter", 0f, "等角度分布上叠加的角度抖动（度）", "0..90", 0f, 90f),
			numSpec("arc", 360f, "圆弧张角（度），小于 360 得到扇环", "1..360", 1f, 360f),
		),
	)

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
		planeNormal = binder.vec3("planeNormal"),
		speed = binder.num("speed"),
		speedJitter = binder.number("speedJitter"),
		thickness = binder.number("thickness"),
		radius0 = binder.number("radius0"),
		even = binder.bool("even"),
		angleJitter = binder.number("angleJitter"),
		arc = binder.number("arc"),
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
