package net.astrorbits.style

import net.astrorbits.math.HashRandom
import net.astrorbits.math.Streams

object StyleSupport {
	/**
	 * 统一的外观求值：尺寸抖动、按年龄的尺寸/透明度、颜色抖动或渐变。
	 * `colorOverride` 为 0 时使用外观自身的颜色。
	 * [emissionIndex] 写进粒子的 id，供按粒子定位的子特效使用；同一发射序号拆成多段时传序号本身。
	 */
	fun emit(
		effect: BoundEffect,
		seed: Long,
		index: Int,
		age: Float,
		u: Float,
		px: Float,
		py: Float,
		pz: Float,
		out: ParticleBuffer,
		sizeScale: Float = 1f,
		alphaScale: Float = 1f,
		colorOverride: Int = 0,
		emissionIndex: Int = index,
	) {
		val appearance = effect.appearance
		val size = appearance.size.at(age) *
			(1f + appearance.sizeJitter * HashRandom.signed(seed, Streams.SIZE, index)) *
			appearance.sizeByAge.at(u) * sizeScale
		if (size <= 0f) return
		val alpha = appearance.alphaByAge.at(u) * alphaScale
		if (alpha <= 0.004f) return

		var color = when {
			colorOverride != 0 -> colorOverride
			appearance.gradient != null -> appearance.gradient.at(u)
			else -> appearance.color
		}
		if (appearance.colorJitter > 0f) {
			color = scaleRgb(color, 1f + appearance.colorJitter * HashRandom.signed(seed, Streams.COLOR, index))
		}
		val alphaByte = (alpha.coerceIn(0f, 1f) * 255f).toInt()
		color = (color and 0x00FFFFFF) or (alphaByte shl 24)
		out.push(px, py, pz, size, alpha, color, emissionIndex)
	}
}

fun scaleRgb(color: Int, factor: Float): Int {
	val r = (((color ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
	val g = (((color ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
	val b = ((color and 0xFF) * factor).toInt().coerceIn(0, 255)
	return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
}
