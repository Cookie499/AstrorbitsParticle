package com.example.astrorbitsparticle.math

/**
 * 确定性随机：服务端只下发 seed，两端用同一套哈希现算每个粒子的随机量。
 *
 * 改动 [mix]、流编号或取用顺序都会改变回放结果，必须同步提升协议里的 rngVersion。
 */
object HashRandom {
	private val K1 = 0x9E3779B97F4A7C15uL.toLong()
	private val K2 = 0xC2B2AE3D27D4EB4FuL.toLong()
	private val M1 = 0xBF58476D1CE4E5B9uL.toLong()
	private val M2 = 0x94D049BB133111EBuL.toLong()

	fun mix(z0: Long): Long {
		var z = z0
		z = (z xor (z ushr 30)) * M1
		z = (z xor (z ushr 27)) * M2
		return z xor (z ushr 31)
	}

	fun hash(seed: Long, stream: Int, index: Int, draw: Int = 0): Long =
		mix(seed xor (stream.toLong() * K1) xor (index.toLong() * K2) xor (draw.toLong() shl 32))

	/** [0,1)。 */
	fun unit(seed: Long, stream: Int, index: Int, draw: Int = 0): Float =
		(hash(seed, stream, index, draw) ushr 40).toFloat() * (1f / (1 shl 24))

	/** [-1,1)。 */
	fun signed(seed: Long, stream: Int, index: Int, draw: Int = 0): Float =
		unit(seed, stream, index, draw) * 2f - 1f
}

/** 随机流编号。 */
object Streams {
	const val DIR = 0
	const val SPEED = 1
	const val RADIUS = 2
	const val PHASE = 3
	const val LIFE = 4
	const val SIZE = 5
	const val COLOR = 6
	const val TIME = 7
	const val JITTER = 8
	const val SPRITE = 9
}
