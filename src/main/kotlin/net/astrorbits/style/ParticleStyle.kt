package net.astrorbits.style

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import net.astrorbits.expr.BoundNumber
import net.astrorbits.expr.CompiledExpr
import net.astrorbits.expr.Exprs
import net.astrorbits.math.Streams
import net.astrorbits.math.HashRandom
import net.astrorbits.model.Appearance
import net.astrorbits.model.Blend
import net.astrorbits.model.EffectDefinition
import net.astrorbits.model.Emission
import net.astrorbits.model.EmissionMode
import net.astrorbits.model.Physics
import net.astrorbits.model.SpriteVariation
import net.astrorbits.model.float
import net.astrorbits.model.getOrNull
import net.astrorbits.model.int
import net.astrorbits.model.objectOrNull
import net.astrorbits.model.string
import net.astrorbits.model.vec3
import net.minecraft.resources.Identifier
import net.minecraft.world.phys.Vec3

/** 样式绑定期产出的参数容器，由各样式自行定义。 */
interface BoundStyle

interface ParticleStyle {
	val id: String

	val schema: StyleSchema

	fun bind(binder: Binder): BoundStyle

	fun sample(effect: BoundEffect, origin: Vec3, seed: Long, age: Float, out: ParticleBuffer)

	/** 该样式在 [age] 时刻的近似速度，供 appearance.stretch 拉长面片；无法确定返回 null。 */
	fun stretchVector(effect: BoundEffect, age: Float): Vec3? = null

	/**
	 * 循环播放时是否按周期重新发射一批粒子。
	 *
	 * 为 `true` 时客户端会把仍可能存活的更早周期依次采样后追加到同一缓冲区，
	 * 传入的 `age` 可超过 `duration`；按周期发射的样式用 [EmissionMath.birth] 与
	 * [EmissionMath.lifetime] 过滤即可。连续运动、不重新生成粒子的样式返回 `false`。
	 */
	val cyclicEmission: Boolean get() = true
}

object ParticleStyles {
	private val byId = LinkedHashMap<String, ParticleStyle>()

	fun register(style: ParticleStyle) {
		byId[style.id] = style
	}

	/** 供公开 API 使用：id 已存在时返回 false，不覆盖。 */
	fun registerIfAbsent(style: ParticleStyle): Boolean {
		if (byId.containsKey(style.id)) return false
		byId[style.id] = style
		return true
	}

	fun get(id: Identifier): ParticleStyle? = byId[id.toString()]

	fun getOrNull(id: String): ParticleStyle? = byId[id]

	fun all(): Collection<ParticleStyle> = byId.values

	fun ids(): Set<String> = byId.keys
}

/**
 * 参数绑定器：把 JSON 参数折成常量或 LUT，并记录编译失败的参数。
 */
class Binder(
	val params: JsonObject,
	val duration: Float,
	val count: Int,
	val schema: StyleSchema? = null,
) {
	val problems = ArrayList<String>()

	init {
		val schema = schema
		if (schema != null) {
			for (key in params.keySet()) {
				if (schema.spec(key) == null) problems += "unknown parameter: $key"
			}
		}
	}

	private fun spec(name: String): ParamSpec? = schema?.spec(name)

	private fun defaultNumber(name: String): Float =
		(spec(name)?.default as? ParamDefault.Num)?.value ?: 0f

	private fun clamp(name: String, value: Float): Float {
		val spec = spec(name) ?: return value
		val min = spec.min
		val max = spec.max
		return value.coerceIn(min ?: Float.NEGATIVE_INFINITY, max ?: Float.POSITIVE_INFINITY)
	}

	fun num(name: String): BoundNumber = bindNumber(params.get(name), defaultNumber(name), name)

	fun num(name: String, default: Float): BoundNumber = bindNumber(params.get(name), default, name)

	fun number(name: String): Float = clamp(name, num(name).at(0f))

	fun number(name: String, default: Float): Float = clamp(name, num(name, default).at(0f))

	fun vec3(name: String): Vec3 {
		val default = spec(name)?.default as? ParamDefault.Vec
		val fallback = if (default == null) Vec3.ZERO
		else Vec3(default.x.toDouble(), default.y.toDouble(), default.z.toDouble())
		return params.vec3(name, fallback)
	}

	fun bool(name: String): Boolean {
		val default = (spec(name)?.default as? ParamDefault.Flag)?.value ?: false
		return params.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
			?: default
	}

	fun int(name: String): Int {
		val default = (spec(name)?.default as? ParamDefault.Whole)?.value ?: 0
		val value = params.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
			?: default
		val spec = spec(name) ?: return value
		return value.coerceIn(spec.min?.toInt() ?: Int.MIN_VALUE, spec.max?.toInt() ?: Int.MAX_VALUE)
	}

	fun colorOrNull(name: String): Int? = params.get(name)?.let { parseColor(it, 0) }

	fun strings(name: String): List<String> {
		val array = params.get(name) as? JsonArray
		if (array != null) return array.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
		return (spec(name)?.default as? ParamDefault.Texts)?.values ?: emptyList()
	}

	fun floats(name: String): FloatArray? {
		val array = params.get(name) as? JsonArray
		if (array != null) return FloatArray(array.size()) { array[it].asFloat }
		return (spec(name)?.default as? ParamDefault.Numbers)?.values
	}

	fun integral(name: String): BoundNumber = integral(name, defaultNumber(name))

	fun integral(name: String, default: Float): BoundNumber {
		val element = params.get(name) ?: return BoundNumber.Lut(floatArrayOf(0f, default * duration), duration)
		if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
			return BoundNumber.Lut(floatArrayOf(0f, default * duration), duration)
		}
		val compiled = Exprs.compile(element.asString)
		if (compiled.isFailure) {
			problems += "$name: ${compiled.exceptionOrNull()?.message}"
			return BoundNumber.Lut(floatArrayOf(0f, default * duration), duration)
		}
		return compiled.getOrThrow().bakeIntegral(duration, count)
	}

	fun vec3(name: String, default: Vec3): Vec3 = params.vec3(name, default)

	fun bool(name: String, default: Boolean): Boolean =
		params.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: default

	fun int(name: String, default: Int): Int =
		params.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: default

	fun curve(name: String, default: FloatArray): FloatCurve =
		params.get(name)?.let { parseCurve(it) } ?: FloatCurve(default)

	fun curve(element: JsonElement?, default: FloatArray): FloatCurve =
		element?.let { parseCurve(it) } ?: FloatCurve(default)

	fun gradient(name: String): ColorGradient? = params.get(name)?.let { parseGradient(it) }

	fun gradient(element: JsonElement?): ColorGradient? = element?.let { parseGradient(it) }

	fun bindNumber(element: JsonElement?, default: Float, path: String = ""): BoundNumber {
		if (element == null) return BoundNumber.Const(default)
		if (!element.isJsonPrimitive) {
			problems += "$path: expected number"
			return BoundNumber.Const(default)
		}
		val primitive = element.asJsonPrimitive
		if (primitive.isNumber) return BoundNumber.Const(clamp(path, primitive.asFloat))
		if (!primitive.isString) return BoundNumber.Const(default)
		val compiled = Exprs.compile(primitive.asString)
		if (compiled.isFailure) {
			problems += "$path: ${compiled.exceptionOrNull()?.message}"
			return BoundNumber.Const(default)
		}
		return compiled.getOrThrow().bake(duration, count)
	}

	private fun parseCurve(element: JsonElement): FloatCurve? {
		val array = element as? JsonArray ?: return null
		val points = ArrayList<Float>(array.size() * 2)
		for (point in array) {
			val pair = point as? JsonArray ?: return null
			if (pair.size() < 2) return null
			val u = pair[0].asFloat
			val v = pair[1].asFloat
			points += u
			points += v
		}
		if (points.size < 4) return null
		return FloatCurve(points.toFloatArray())
	}

	private fun parseGradient(element: JsonElement): ColorGradient? {
		val array = element as? JsonArray ?: return null
		val stops = ArrayList<Float>(array.size())
		val colors = ArrayList<Int>(array.size())
		for (stop in array) {
			val pair = stop as? JsonArray ?: return null
			if (pair.size() < 2) return null
			stops += pair[0].asFloat
			colors += parseColor(pair[1], 0xFFFFFFFF.toInt())
		}
		if (stops.size < 2) return null
		return ColorGradient(stops.toFloatArray(), colors.toIntArray())
	}
}

class BoundEffect(
	val definition: EffectDefinition,
	val style: ParticleStyle,
	val count: Int,
	val duration: Float,
	val emission: Emission,
	val appearance: BoundAppearance,
	val physics: Physics,
	val styleParams: BoundStyle,
) {
	/** 采样各年龄的粒子位置，取最大距离外扩 1 格，避免飞得远的样式被提前剔除。 */
	fun withLifetime(lifetime: Float): BoundEffect = BoundEffect(
		definition, style, count, duration, emission.copy(lifetime = lifetime),
		appearance, physics, styleParams,
	)

	val radius: Float = boundsRadius()

	private fun boundsRadius(): Float {
		val buffer = ParticleBuffer()
		var maxSq = 0.0
		val samples = 6
		val stride = (count / 2048).coerceAtLeast(1)
		for (step in 0..samples) {
			val sampleAge = duration * step / samples
			buffer.reset(count)
			style.sample(this, Vec3.ZERO, 0L, sampleAge, buffer)
			var i = 0
			while (i < buffer.count) {
				val dx = buffer.x[i].toDouble()
				val dy = buffer.y[i].toDouble()
				val dz = buffer.z[i].toDouble()
				val distance = dx * dx + dy * dy + dz * dz
				if (distance > maxSq) maxSq = distance
				i += stride
			}
		}
		return (kotlin.math.sqrt(maxSq).toFloat() + 1f).coerceIn(1f, 256f)
	}
}

class BoundAppearance(
	val sprite: Identifier,
	val spriteVariation: SpriteVariation,
	val spriteCycleTicks: Int,
	val size: BoundNumber,
	val sizeJitter: Float,
	val sizeByAge: FloatCurve,
	val color: Int,
	val colorJitter: Float,
	val gradient: ColorGradient?,
	val alphaByAge: FloatCurve,
	val blend: Blend,
	val fullbright: Boolean,
	val stretch: BoundNumber,
	val spin: BoundNumber,
)

object EffectBinder {
	private val DEFAULT_SIZE = floatArrayOf(0f, 1f, 1f, 0f)
	private val DEFAULT_ALPHA = floatArrayOf(0f, 1f, 0.7f, 1f, 1f, 0f)

	fun bind(definition: EffectDefinition): Result<BoundEffect> = runCatching {
		val style = ParticleStyles.get(definition.style)
			?: error("unknown style: ${definition.style}")
		val binder = Binder(definition.params, definition.duration, definition.emission.count, style.schema)
		val styleParams = style.bind(binder)
		val appearance = bindAppearance(definition.appearance, binder)
		if (binder.problems.isNotEmpty()) {
			error("invalid parameters: " + binder.problems.joinToString("; "))
		}
		BoundEffect(
			definition = definition,
			style = style,
			count = definition.emission.count,
			duration = definition.duration,
			emission = definition.emission,
			appearance = appearance,
			physics = definition.physics,
			styleParams = styleParams,
		)
	}

	private fun bindAppearance(appearance: Appearance, binder: Binder): BoundAppearance = BoundAppearance(
		sprite = appearance.sprite,
		spriteVariation = appearance.spriteVariation,
		spriteCycleTicks = appearance.spriteCycleTicks,
		size = binder.bindNumber(appearance.size, 0.2f, "appearance.size"),
		sizeJitter = appearance.sizeJitter,
		sizeByAge = binder.curve(appearance.sizeByAge, DEFAULT_SIZE),
		color = appearance.color?.let { parseColor(it, 0xFFFFFFFF.toInt()) } ?: 0xFFFFFFFF.toInt(),
		colorJitter = appearance.colorJitter,
		gradient = binder.gradient(appearance.colorByAge),
		alphaByAge = binder.curve(appearance.alphaByAge, DEFAULT_ALPHA),
		blend = appearance.blend,
		fullbright = appearance.fullbright,
		stretch = binder.bindNumber(appearance.stretch, 0f, "appearance.stretch"),
		spin = binder.bindNumber(appearance.spin, 0f, "appearance.spin"),
	)
}

class FloatCurve(points: FloatArray) {
	val points: FloatArray = if (points.size >= 4) points else DEFAULT

	fun at(u: Float): Float {
		val x = u.coerceIn(0f, 1f)
		var i = 0
		while (i + 3 < points.size && x > points[i + 2]) i += 2
		val u0 = points[i]
		val v0 = points[i + 1]
		val u1 = points[i + 2]
		val v1 = points[i + 3]
		if (u1 <= u0) return v1
		return v0 + (v1 - v0) * (x - u0) / (u1 - u0)
	}

	private companion object {
		val DEFAULT = floatArrayOf(0f, 1f, 1f, 0f)
	}
}

class ColorGradient(private val stops: FloatArray, private val colors: IntArray) {
	fun at(u: Float): Int {
		val x = u.coerceIn(0f, 1f)
		var i = 0
		while (i + 1 < stops.size - 1 && x > stops[i + 1]) i++
		val u0 = stops[i]
		val u1 = stops[i + 1]
		val t = if (u1 <= u0) 1f else ((x - u0) / (u1 - u0)).coerceIn(0f, 1f)
		return lerpColor(colors[i], colors[i + 1], t)
	}
}

fun lerpColor(from: Int, to: Int, t: Float): Int {
	val a = ((from ushr 24 and 0xFF) + ((to ushr 24 and 0xFF) - (from ushr 24 and 0xFF)) * t).toInt()
	val r = ((from ushr 16 and 0xFF) + ((to ushr 16 and 0xFF) - (from ushr 16 and 0xFF)) * t).toInt()
	val g = ((from ushr 8 and 0xFF) + ((to ushr 8 and 0xFF) - (from ushr 8 and 0xFF)) * t).toInt()
	val b = ((from and 0xFF) + ((to and 0xFF) - (from and 0xFF)) * t).toInt()
	return (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
		(g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}

fun parseColor(element: JsonElement, default: Int): Int {
	if (element.isJsonPrimitive) {
		val primitive = element.asJsonPrimitive
		if (primitive.isNumber) return primitive.asInt
		if (!primitive.isString) return default
		val raw = primitive.asString.removePrefix("#")
		val value = raw.toLongOrNull(16) ?: return default
		return when (raw.length) {
			6 -> (0xFF shl 24) or value.toInt()
			8 -> value.toInt()
			else -> default
		}
	}
	val array = element as? JsonArray ?: return default
	if (array.size() < 3) return default
	val r = (array[0].asFloat * 255f).toInt().coerceIn(0, 255)
	val g = (array[1].asFloat * 255f).toInt().coerceIn(0, 255)
	val b = (array[2].asFloat * 255f).toInt().coerceIn(0, 255)
	val a = if (array.size() > 3) (array[3].asFloat * 255f).toInt().coerceIn(0, 255) else 255
	return (a shl 24) or (r shl 16) or (g shl 8) or b
}

class ParticleBuffer {
	var count = 0
		private set
	var x = FloatArray(0)
		private set
	var y = FloatArray(0)
		private set
	var z = FloatArray(0)
		private set
	var size = FloatArray(0)
		private set
	var alpha = FloatArray(0)
		private set
	var argb = IntArray(0)
		private set

	/** 每颗粒子对应的发射序号；直接用 [push] 写入时为 -1。 */
	var ids = IntArray(0)
		private set

	/** 保证容量至少为 [n]，不改变已有粒子。 */
	fun reserve(n: Int) {
		if (x.size >= n) return
		x = x.copyOf(n)
		y = y.copyOf(n)
		z = z.copyOf(n)
		size = size.copyOf(n)
		alpha = alpha.copyOf(n)
		argb = argb.copyOf(n)
		ids = ids.copyOf(n)
	}

	fun reset(n: Int) {
		reserve(n)
		count = 0
	}

	/** 把 [other] 的粒子追加到末尾；数组复用，只在容量不足时扩容。 */
	fun append(other: ParticleBuffer) {
		if (other.count == 0) return
		val start = count
		val total = start + other.count
		reserve(total)
		System.arraycopy(other.x, 0, x, start, other.count)
		System.arraycopy(other.y, 0, y, start, other.count)
		System.arraycopy(other.z, 0, z, start, other.count)
		System.arraycopy(other.size, 0, size, start, other.count)
		System.arraycopy(other.alpha, 0, alpha, start, other.count)
		System.arraycopy(other.argb, 0, argb, start, other.count)
		System.arraycopy(other.ids, 0, ids, start, other.count)
		count = total
	}

	fun push(px: Float, py: Float, pz: Float, pSize: Float, pAlpha: Float, color: Int) {
		push(px, py, pz, pSize, pAlpha, color, -1)
	}

	/** [id] 是发射序号，供按粒子定位的样式或子特效使用；未知时传 -1。 */
	fun push(px: Float, py: Float, pz: Float, pSize: Float, pAlpha: Float, color: Int, id: Int) {
		val i = count
		if (i >= x.size) reserve(maxOf(16, x.size * 2))
		x[i] = px
		y[i] = py
		z[i] = pz
		size[i] = pSize
		alpha[i] = pAlpha
		argb[i] = color
		ids[i] = id
		count = i + 1
	}
}

object EmissionMath {
	fun birth(emission: Emission, seed: Long, index: Int, count: Int): Float {
		val base = when (emission.mode) {
			EmissionMode.BURST -> 0f
			EmissionMode.STREAM ->
				if (count <= 1) emission.windowStart
				else emission.windowStart + (emission.windowEnd - emission.windowStart) * index / (count - 1)
			EmissionMode.RANDOM -> {
				val span = emission.windowEnd - emission.windowStart
				emission.windowStart + span * HashRandom.unit(seed, Streams.TIME, index)
			}
		}
		return base + emission.jitter * HashRandom.signed(seed, Streams.TIME, index, 1)
	}

	fun lifetime(emission: Emission, seed: Long, index: Int): Float =
		(emission.lifetime * (1f + emission.lifetimeJitter * HashRandom.signed(seed, Streams.LIFE, index)))
			.coerceAtLeast(0.05f)

	/** 粒子在 [age] 时刻的局部年龄；未出生返回负数。 */
	fun localAge(emission: Emission, seed: Long, index: Int, count: Int, age: Float): Float =
		age - birth(emission, seed, index, count)
}
