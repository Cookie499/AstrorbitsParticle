package com.example.astrorbitsparticle.style

import com.example.astrorbitsparticle.math.HashRandom
import com.example.astrorbitsparticle.math.Streams
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 逐粒子路径只做 float 运算并写入调用方提供的数组，避免每帧为每个粒子分配 Vec3。
 */
object StyleMath {
	private const val TAU = (PI * 2.0).toFloat()

	fun sphereInto(out: FloatArray, seed: Long, index: Int) {
		val z = HashRandom.signed(seed, Streams.DIR, index, 0)
		val phi = (HashRandom.unit(seed, Streams.DIR, index, 1) * TAU).toDouble()
		val r = sqrt((1.0 - z * z.toDouble()).coerceAtLeast(0.0))
		out[0] = (r * cos(phi)).toFloat()
		out[1] = (r * sin(phi)).toFloat()
		out[2] = z
	}

	fun coneInto(out: FloatArray, axis: Vec3, halfAngleRad: Float, seed: Long, index: Int) {
		if (halfAngleRad >= PI.toFloat() - 0.001f) {
			sphereInto(out, seed, index)
			return
		}
		val cosLimit = cos(halfAngleRad.toDouble())
		val cosTheta = 1.0 + HashRandom.unit(seed, Streams.DIR, index, 0) * (cosLimit - 1.0)
		val theta = acos(cosTheta.coerceIn(-1.0, 1.0))
		val phi = HashRandom.unit(seed, Streams.DIR, index, 1) * 2.0 * PI
		val (e1, e2) = orthoBasis(axis)
		val sinTheta = sin(theta)
		val cx = cos(phi) * sinTheta
		val sx = sin(phi) * sinTheta
		out[0] = (axis.x * cosTheta + e1.x * cx + e2.x * sx).toFloat()
		out[1] = (axis.y * cosTheta + e1.y * cx + e2.y * sx).toFloat()
		out[2] = (axis.z * cosTheta + e1.z * cx + e2.z * sx).toFloat()
	}

	fun orthoBasis(axis: Vec3): Pair<Vec3, Vec3> {
		val normalized = if (axis.lengthSqr() < 1e-9) Vec3(0.0, 1.0, 0.0) else axis.normalize()
		val helper = if (abs(normalized.y) < 0.9) Vec3(0.0, 1.0, 0.0) else Vec3(1.0, 0.0, 0.0)
		val e1 = normalized.cross(helper).normalize()
		val e2 = normalized.cross(e1).normalize()
		return e1 to e2
	}

	/** 解析积分 dv/dt = g - k*(v - w)，结果写入 out[0..2]。 */
	fun posInto(
		out: FloatArray,
		ox: Float, oy: Float, oz: Float,
		vx: Float, vy: Float, vz: Float,
		gravity: Vec3, drag: Float, wind: Vec3, t: Float,
	) {
		if (t <= 0f) {
			out[0] = ox
			out[1] = oy
			out[2] = oz
			return
		}
		if (drag <= 1e-5f) {
			val half = 0.5f * t * t
			out[0] = ox + vx * t + gravity.x.toFloat() * half
			out[1] = oy + vy * t + gravity.y.toFloat() * half
			out[2] = oz + vz * t + gravity.z.toFloat() * half
			return
		}
		val k = drag.toDouble()
		val decay = exp(-k * t)
		val tx = gravity.x / k + wind.x
		val ty = gravity.y / k + wind.y
		val tz = gravity.z / k + wind.z
		val scale = ((1.0 - decay) / k).toFloat()
		out[0] = (ox + tx * t + (vx - tx) * scale).toFloat()
		out[1] = (oy + ty * t + (vy - ty) * scale).toFloat()
		out[2] = (oz + tz * t + (vz - tz) * scale).toFloat()
	}

	fun jitterInto(out: FloatArray, radius: Float, seed: Long, index: Int) {
		if (radius <= 0f) {
			out[0] = 0f
			out[1] = 0f
			out[2] = 0f
			return
		}
		sphereInto(out, seed, index)
		val r = radius * HashRandom.unit(seed, Streams.JITTER, index, 2)
		out[0] *= r
		out[1] *= r
		out[2] *= r
	}

	fun fract(x: Float): Float = x - kotlin.math.floor(x)

	fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
		val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
		return t * t * (3f - 2f * t)
	}
}
