package com.example.astrorbitsparticle.expr

import net.minecraft.util.Mth
import net.objecthunter.exp4j.Expression
import net.objecthunter.exp4j.ExpressionBuilder
import net.objecthunter.exp4j.function.Function as ExpFunction

/** 编译后的表达式。只允许在绑定期求值，`t` 为特效年龄（秒）。 */
class CompiledExpr(val source: String, private val expression: Expression) {
	val timeDependent: Boolean = TIME_VARS.containsMatchIn(source)

	fun eval(t: Float, duration: Float, count: Int): Float {
		expression.setVariable("t", t.toDouble())
		expression.setVariable("d", duration.toDouble())
		expression.setVariable("u", (if (duration > 0f) t / duration else 0f).toDouble())
		expression.setVariable("n", count.toDouble())
		return expression.evaluate().toFloat()
	}

	fun bake(duration: Float, count: Int): BoundNumber {
		if (!timeDependent) {
			return BoundNumber.Const(eval(0f, duration, count))
		}
		val samples = LUT_SAMPLES(duration)
		val values = FloatArray(samples)
		for (i in 0 until samples) {
			values[i] = eval(duration * i / (samples - 1), duration, count)
		}
		return BoundNumber.Lut(values, duration)
	}

	/** 把表达式预积分成累积表，供角位移、轴向位移这类量使用。 */
	fun bakeIntegral(duration: Float, count: Int): BoundNumber {
		if (!timeDependent) {
			val v = eval(0f, duration, count)
			return BoundNumber.Lut(floatArrayOf(0f, v * duration), duration)
		}
		val samples = LUT_SAMPLES(duration)
		val step = duration / (samples - 1)
		val values = FloatArray(samples)
		var acc = 0.0
		var prev = eval(0f, duration, count).toDouble()
		for (i in 0 until samples) {
			val t = duration * i / (samples - 1)
			val v = eval(t, duration, count).toDouble()
			if (i > 0) acc += (prev + v) * 0.5 * step
			values[i] = acc.toFloat()
			prev = v
		}
		return BoundNumber.Lut(values, duration)
	}

	private companion object {
		val TIME_VARS = Regex("(?<![A-Za-z0-9_])[tu](?![A-Za-z0-9_])")
		fun LUT_SAMPLES(duration: Float): Int = Mth.clamp((duration * 5f).toInt(), 16, 256)
	}
}

/** 绑定期把表达式折成常量或查找表，运行期只做插值。 */
sealed interface BoundNumber {
	fun at(t: Float): Float

	@JvmInline
	value class Const(val value: Float) : BoundNumber {
		override fun at(t: Float): Float = value
	}

	class Lut(private val values: FloatArray, private val duration: Float) : BoundNumber {
		override fun at(t: Float): Float {
			if (values.size == 1) return values[0]
			val x = (if (duration > 0f) t / duration else 0f).coerceIn(0f, 1f) * (values.size - 1)
			val i = x.toInt().coerceAtMost(values.size - 2)
			val f = x - i
			return values[i] + (values[i + 1] - values[i]) * f
		}
	}

	companion object {
		fun of(value: Float): BoundNumber = Const(value)
	}
}

object Exprs {
	const val MAX_LENGTH = 256

	private val FUNCTIONS: List<ExpFunction> = listOf(
		object : ExpFunction("clamp", 3) {
			override fun apply(vararg args: Double): Double = args[0].coerceIn(args[1], args[2])
		},
		object : ExpFunction("mix", 3) {
			override fun apply(vararg args: Double): Double = args[0] + (args[1] - args[0]) * args[2]
		},
		object : ExpFunction("smoothstep", 3) {
			override fun apply(vararg args: Double): Double {
				val x = ((args[2] - args[0]) / (args[1] - args[0])).coerceIn(0.0, 1.0)
				return x * x * (3 - 2 * x)
			}
		},
		object : ExpFunction("fract", 1) {
			override fun apply(vararg args: Double): Double = args[0] - kotlin.math.floor(args[0])
		},
		object : ExpFunction("sign", 1) {
			override fun apply(vararg args: Double): Double = kotlin.math.sign(args[0])
		},
		object : ExpFunction("step", 2) {
			override fun apply(vararg args: Double): Double = if (args[1] >= args[0]) 1.0 else 0.0
		},
	)

	private val CACHE = object : LinkedHashMap<String, Result<CompiledExpr>>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result<CompiledExpr>>): Boolean =
			size > 256
	}

	fun compile(source: String): Result<CompiledExpr> {
		CACHE[source]?.let { return it }
		val result = runCatching {
			require(source.length <= MAX_LENGTH) { "expression too long: ${source.length}" }
			val builder = ExpressionBuilder(source).variables("t", "d", "u", "n")
			FUNCTIONS.forEach(builder::function)
			val expression = builder.build()
			val validation = expression.validate(false)
			require(validation.isValid) { validation.errors.joinToString("; ") }
			CompiledExpr(source, expression)
		}
		CACHE[source] = result
		return result
	}
}
