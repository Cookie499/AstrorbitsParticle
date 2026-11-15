package net.astrorbits.client

import net.astrorbits.AstrorbitsParticle
import net.astrorbits.math.HashRandom
import net.astrorbits.math.Streams
import net.astrorbits.model.EffectDefinition
import net.astrorbits.model.Orientation
import net.astrorbits.model.SpriteVariation
import net.astrorbits.model.SubEffectTrigger
import net.astrorbits.model.Trail
import net.astrorbits.model.TrailMode
import net.astrorbits.net.EffectClearPayload
import net.astrorbits.net.EffectHandshakePayload
import net.astrorbits.net.EffectImpactPayload
import net.astrorbits.net.EffectStartPayload
import net.astrorbits.net.EffectStopPayload
import net.astrorbits.net.PROTOCOL_VERSION
import net.astrorbits.net.RNG_VERSION
import net.astrorbits.particle.EffectAnchorOptions
import net.astrorbits.style.BoundEffect
import net.astrorbits.style.EffectBinder
import net.astrorbits.style.ParticleBuffer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

private const val IMPACT_CHECKS_PER_TICK = 128
private const val MAX_IMPACT_REPORTS = 8
private const val MAX_LOOP_LOOKBACK = 8

/** 单特效轨迹粒子硬上限；超出时截断较早的段，避免一帧写出几十万颗粒子。 */
private const val MAX_TRAIL_PARTICLES = 16384

/** 轨迹扩散用 JITTER 流的高位 draw，避开样式自己的随机取用。 */
private const val TRAIL_SPREAD_DRAW = 1000

class ClientEffect(
	val id: Long,
	val bound: BoundEffect,
	val seed: Long,
	val origin: Vec3,
	val scale: Float,
	private val baseAge: Float,
	private val startGameTime: Long,
	private val viewYaw: Float,
	private val viewPitch: Float,
) {

	val buffer = ParticleBuffer()
	private val impactBuffer = ParticleBuffer()

	/** 周期采样暂存：渲染与客户端 tick 各一份，避免两个线程写同一组数组。 */
	private val renderCycle = ParticleBuffer()
	private val impactCycle = ParticleBuffer()

	/** 轨迹采样暂存：历史年龄的样式输出先写这里，再按段追加到 [buffer]。 */
	private val trailScratch = ParticleBuffer()
	private val trailCycle = ParticleBuffer()

	private val impactedBlocks = HashSet<Long>()
	private var impactReports: Int = 0
	val cullBox: AABB = AABB(origin, origin).inflate(
		(bound.radius * scale + bound.definition.visibility.boundsPadding).toDouble(),
	)

	private val playScale: Float =
		bound.definition.timeScale * if (bound.definition.reverse) -1f else 1f

	/** 循环时回溯的周期数：寿命上界覆盖的周期数，至少 1，上限 [MAX_LOOP_LOOKBACK]。 */
	private val loopLookback: Int = run {
		val emission = bound.emission
		val lifespan = emission.lifetime * (1f + abs(emission.lifetimeJitter))
		ceil(lifespan.coerceAtLeast(0f) / bound.duration).toInt().coerceIn(1, MAX_LOOP_LOOKBACK)
	}

	private val cyclesForward: Boolean =
		bound.definition.loop && bound.style.cyclicEmission && playScale >= 0f

	/** 单周期最多产出的源粒子数；一个发射序号拆成多颗粒子的样式由样式自己给出。 */
	private val sourcePerCycle: Int = bound.style.particleCapacity(bound)

	/** 单帧源粒子数上界，含正向循环回溯的周期。 */
	private val sourceMaxParticles: Int =
		(sourcePerCycle.toLong() * (if (cyclesForward) loopLookback.toLong() + 1L else 1L))
			.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

	private val trail: Trail? = bound.trail

	/** 轨迹 RGB；0 表示沿用历史采样里的颜色。 */
	private val trailColor: Int = bound.trailColor

	/** 请求的轨迹段数；单特效轨迹总量超过 [MAX_TRAIL_PARTICLES] 时从较早的段截断。 */
	private val trailCap: Int = trail?.cap ?: 0

	private val trailMaxParticles: Int =
		minOf(sourceMaxParticles.toLong() * trailCap, MAX_TRAIL_PARTICLES.toLong()).toInt()

	private val trailSizeMul = FloatArray(trailCap + 1)
	private val trailAlphaMul = FloatArray(trailCap + 1)

	/** 源粒子 id 到当前缓冲槽位；重复 id 取最后一次出现。 */
	private val trailSourceIndex = Int2IntOpenHashMap().apply { defaultReturnValue(-1) }
	private var trailPrevX = FloatArray(0)
	private var trailPrevY = FloatArray(0)
	private var trailPrevZ = FloatArray(0)
	private var trailAccum = FloatArray(0)
	private var trailEmitted = IntArray(0)
	private var trailActive = BooleanArray(0)
	private var trailSeen = IntArray(0)
	private val trailOffset = FloatArray(3)
	private var trailReserved = false

	private var stopped = false
	private var stopAge = 0f
	private var stopScaled = 0f
	private var stopCycle = 0L
	private var stopPhase = 0f
	private var stopContinuation = 0f
	private var birthKeep = BooleanArray(0)

	/** 停止发射后最多还要等多久：取粒子寿命上界加轨迹段的时间跨度。 */
	private val stopTail: Float = run {
		val trailSpan = if (trail != null && trail.mode == TrailMode.TIME) {
			trailCap * trail.interval
		} else {
			0f
		}
		(bound.emission.lifetime * (1f + abs(bound.emission.lifetimeJitter)))
			.coerceAtLeast(0.05f)
			.plus(trailSpan)
			.div(abs(playScale).coerceAtLeast(0.01f))
	}

	init {
		var size = 1f
		var alpha = 1f
		val trail = this.trail
		for (segment in 1..trailCap) {
			size *= trail?.sizeScale ?: 1f
			alpha *= trail?.alphaScale ?: 1f
			trailSizeMul[segment] = size
			trailAlphaMul[segment] = alpha
		}
	}

	/** 单帧最多产出的粒子数（含轨迹），缓冲区容量与客户端渲染预算都按它估算。 */
	val maxParticles: Int = sourceMaxParticles + trailMaxParticles

	/** `orientation=view` 且触发时带方向时才旋转，避免世界朝向的特效多算一遍。 */
	private val rotates: Boolean = bound.definition.orientation == Orientation.VIEW &&
		(viewYaw != 0f || viewPitch != 0f)

	private val cosYaw: Float = cos(Math.toRadians(viewYaw.toDouble())).toFloat()
	private val sinYaw: Float = sin(Math.toRadians(viewYaw.toDouble())).toFloat()
	private val cosPitch: Float = cos(Math.toRadians(viewPitch.toDouble())).toFloat()
	private val sinPitch: Float = sin(Math.toRadians(viewPitch.toDouble())).toFloat()

	/**
	 * 客户端 tick 用独立缓冲采样并检测固体方块碰撞，返回新碰撞点（每 3 个 float 一组）。
	 * 与渲染线程共用 [buffer] 会并发写同一组数组，因此单独分配 [impactBuffer]。
	 */
	fun collectImpacts(level: ClientLevel): FloatArray {
		if (impactReports >= MAX_IMPACT_REPORTS) return EMPTY_POINTS
		sampleInto(impactBuffer, impactCycle, ageAt(level.gameTime, 0f), false)
		if (impactBuffer.count == 0) return EMPTY_POINTS
		val points = ArrayList<Float>(MAX_IMPACT_REPORTS * 3)
		val step = (impactBuffer.count / IMPACT_CHECKS_PER_TICK).coerceAtLeast(1)
		var i = 0
		while (i < impactBuffer.count && impactReports < MAX_IMPACT_REPORTS) {
			val pos = BlockPos.containing(
				impactBuffer.x[i].toDouble(), impactBuffer.y[i].toDouble(), impactBuffer.z[i].toDouble(),
			)
			if (level.isLoaded(pos) && level.getBlockState(pos).isSolid) {
				if (impactedBlocks.add(pos.asLong())) {
					points += impactBuffer.x[i]
					points += impactBuffer.y[i]
					points += impactBuffer.z[i]
					impactReports++
				}
			}
			i += step
		}
		return points.toFloatArray()
	}

	fun ageAt(gameTime: Long, partialTick: Float): Float =
		baseAge + ((gameTime - startGameTime).coerceAtLeast(0L) + partialTick) / 20f

	/**
	 * 停止发射：当前年龄之后的出生被过滤，已有粒子按各自寿命继续运行。
	 * 循环特效固定在停止时的周期，不再进入新周期。
	 */
	fun stop(gameTime: Long) {
		if (stopped) return
		stopped = true
		stopAge = ageAt(gameTime, 0f)
		stopScaled = stopAge * playScale
		val duration = bound.duration
		if (bound.definition.loop && cyclesForward && playScale > 0f) {
			stopCycle = floor(stopScaled / duration).toLong()
			stopPhase = stopScaled - stopCycle * duration
		} else if (bound.definition.loop) {
			val wrapped = stopScaled % duration
			stopPhase = if (wrapped < 0f) wrapped + duration else wrapped
			stopContinuation = stopPhase
		} else {
			stopPhase = if (playScale < 0f) {
				(duration + stopScaled).coerceIn(0f, duration)
			} else {
				stopScaled
			}
			stopContinuation = stopPhase
		}
	}

	fun expired(gameTime: Long): Boolean {
		val age = ageAt(gameTime, 0f)
		if (stopped) return age >= stopAge + stopTail
		if (!bound.definition.loop && age >= bound.duration) {
			// 服务端结束包可能晚一帧到达；本地先进入停止状态，避免整批粒子在 duration 处被切掉。
			stop(gameTime)
			return false
		}
		return false
	}

	fun sample(gameTime: Long, partialTick: Float): ParticleBuffer {
		reserveTrailBuffers()
		sampleInto(buffer, renderCycle, ageAt(gameTime, partialTick), true)
		return buffer
	}

	private fun sampleInto(out: ParticleBuffer, cycle: ParticleBuffer, age: Float, withTrail: Boolean) {
		if (stopped) {
			sampleStoppedInto(out, cycle, age)
		} else {
			sampleRawInto(out, cycle, age)
		}
		if (withTrail) appendTrails(out, age)
		if (rotates) rotate(out)
	}

	private fun sampleRawInto(out: ParticleBuffer, cycle: ParticleBuffer, age: Float) {
		val scaled = age * playScale
		if (!bound.definition.loop) {
			val sampleAge = if (playScale < 0f) {
				(bound.duration + scaled).coerceIn(0f, bound.duration)
			} else {
				scaled
			}
			out.reset(sourceMaxParticles)
			bound.style.sample(bound, origin, seed, sampleAge, out)
			return
		}
		if (!cyclesForward) {
			val wrapped = scaled % bound.duration
			out.reset(sourceMaxParticles)
			bound.style.sample(
				bound, origin, seed,
				if (wrapped < 0f) wrapped + bound.duration else wrapped,
				out,
			)
			return
		}
		val cycleIndex = floor(scaled / bound.duration).toLong()
		val phase = scaled - cycleIndex * bound.duration
		// 从最早可能存活的周期采到当前周期；样式按 local/life 过滤，缓冲区只留下还活着的粒子。
		out.reset(sourceMaxParticles)
		for (back in minOf(cycleIndex, loopLookback.toLong()).toInt() downTo 0) {
			cycle.reset(sourcePerCycle)
			bound.style.sample(bound, origin, seed, phase + back * bound.duration, cycle)
			out.append(cycle)
		}
	}

	/**
	 * 停止后的采样：循环特效只保留停止时周期及更早的存活粒子，不再进入新周期；
	 * 非循环特效按停止时刻的采样年龄继续推进，并过滤停止后才出生的粒子。
	 */
	private fun sampleStoppedInto(out: ParticleBuffer, cycle: ParticleBuffer, age: Float) {
		val scaled = age * playScale
		out.reset(sourceMaxParticles)
		if (bound.definition.loop && cyclesForward && playScale > 0f) {
			var cycleIndex = stopCycle
			val oldest = (stopCycle - loopLookback.toLong()).coerceAtLeast(0L)
			while (cycleIndex >= oldest) {
				val sampleAge = scaled - cycleIndex * bound.duration
				cycle.reset(sourcePerCycle)
				bound.style.sample(bound, origin, seed, sampleAge, cycle)
				retainBirths(cycle, if (cycleIndex == stopCycle) stopPhase else Float.POSITIVE_INFINITY)
				out.append(cycle)
				cycleIndex--
			}
			return
		}
		val sampleAge = stopContinuation + (age - stopAge) * playScale
		cycle.reset(sourcePerCycle)
		bound.style.sample(bound, origin, seed, sampleAge, cycle)
		val cutoff = when {
			bound.definition.loop -> stopPhase
			playScale >= 0f -> stopPhase
			else -> Float.POSITIVE_INFINITY
		}
		retainBirths(cycle, cutoff)
		out.append(cycle)
	}

	/** 丢弃出生时间晚于 [cutoff] 的粒子；id 超出 count 时按发射序号 0 处理。 */
	private fun retainBirths(hist: ParticleBuffer, cutoff: Float) {
		if (cutoff == Float.POSITIVE_INFINITY) return
		if (birthKeep.size < hist.count) {
			birthKeep = BooleanArray(maxOf(hist.count, birthKeep.size * 2))
		}
		var i = 0
		while (i < hist.count) {
			val id = hist.ids[i]
			val index = if (id in 0 until bound.count) id else 0
			val birth = bound.style.birthAge(bound, seed, index)
			birthKeep[i] = birth <= cutoff + 1e-4f
			i++
		}
		hist.retain(birthKeep)
	}

	/** 预分配轨迹缓冲，避免逐帧扩容；关闭轨迹时不占额外内存。 */
	private fun reserveTrailBuffers() {
		if (trailReserved || trailCap == 0) return
		buffer.reserve(maxParticles)
		renderCycle.reserve(sourceMaxParticles)
		trailScratch.reserve(sourceMaxParticles)
		trailCycle.reserve(sourcePerCycle)
		trailReserved = true
	}

	private fun appendTrails(out: ParticleBuffer, age: Float) {
		val trail = trail ?: return
		if (trailCap == 0) return
		// 停止后 distance 模式无法从历史事件恢复，直接不再追加；time 模式保留已有事件。
		if (stopped && trail.mode != TrailMode.TIME) return
		val sourceCount = out.count
		if (sourceCount == 0 && !stopped) return
		// 停止后源粒子可能已经全部消失，用单帧源粒子上界估算仍需保留的轨迹数量。
		val baseCount = if (sourceCount > 0) sourceCount else sourceMaxParticles
		// 源粒子 + 每颗最多 trailCap 段；单特效轨迹总量另有硬上限。
		val limit = baseCount + minOf(baseCount * trailCap, MAX_TRAIL_PARTICLES)
		if (trail.mode == TrailMode.DISTANCE) {
			appendDistanceTrails(out, age, sourceCount, limit)
		} else {
			appendTimeTrails(out, age, sourceCount, baseCount, limit)
		}
	}

	/** 时间模式：按 age - k*interval 逐段采样，位置取自历史时刻。 */
	private fun appendTimeTrails(
		out: ParticleBuffer,
		age: Float,
		sourceCount: Int,
		strideBase: Int,
		limit: Int,
	) {
		val trail = trail ?: return
		if (trail.drift > 0f) {
			appendDriftingTrails(out, age, strideBase, limit)
			return
		}
		if (trail.spread > 0f) indexSource(out, sourceCount)
		var segment = 1
		while (segment <= trailCap) {
			val pastAge = age - segment * trail.interval
			// 非循环特效没有负年龄的历史；循环特效用周期回溯，允许 pastAge < 0。
			if (!bound.definition.loop && pastAge <= 0f) break
			// 停止后只保留停止时刻之前已经发射的轨迹，不再生成新的事件。
			if (stopped && pastAge > stopAge) {
				segment++
				continue
			}
			sampleRawInto(trailScratch, trailCycle, pastAge)
			if (trailScratch.count > 0) {
				val stride = ((trailScratch.count + strideBase - 1) / strideBase).coerceAtLeast(1)
				appendSegment(
					out, trailScratch, segment, stride,
					trail.spread * segment / trailCap, limit,
				)
				if (out.count >= limit) break
			}
			segment++
		}
	}

	/**
	 * 有 `drift` 时按固定发射时刻取历史位置：轨迹粒子出生后不再跟随源粒子速度，
	 * 而是沿各自的随机方向按 `drift` 飘散。事件按 `interval` 对齐，旧事件逐渐长大并消失。
	 */
	private fun appendDriftingTrails(out: ParticleBuffer, age: Float, sourceCount: Int, limit: Int) {
		val trail = trail ?: return
		val step = trail.interval
		if (step <= 0f) return
		val rawLatest = floor((age / step + 1e-4f).toDouble()).toLong()
		// 停止后不产生新事件，最多保留停止时刻及之前的发射事件。
		val latest = if (stopped) {
			minOf(rawLatest, floor((stopAge / step + 1e-4f).toDouble()).toLong())
		} else {
			rawLatest
		}
		val oldest = (rawLatest - trailCap + 1L).coerceAtLeast(0L)
		var eventId = latest
		while (eventId >= oldest && out.count < limit) {
			val eventAge = age - eventId * step
			val segment = ((eventAge / step).toInt() + 1).coerceIn(1, trailCap)
			sampleRawInto(trailScratch, trailCycle, eventId * step)
			if (trailScratch.count > 0) {
				val stride = ((trailScratch.count + sourceCount - 1) / sourceCount).coerceAtLeast(1)
				appendDriftSegment(
					out, trailScratch, eventId, eventAge, segment, stride, limit,
				)
				if (out.count >= limit) break
			}
			eventId--
		}
	}

	private fun appendDriftSegment(
		out: ParticleBuffer,
		hist: ParticleBuffer,
		eventId: Long,
		eventAge: Float,
		segment: Int,
		stride: Int,
		limit: Int,
	) {
		val trail = trail ?: return
		val sizeScale = trailSizeMul[segment]
		val alphaScale = trailAlphaMul[segment]
		val spreadRadius = trail.spread * (eventAge / (trailCap * trail.interval)).coerceIn(0f, 1f)
		val driftRadius = trail.drift * eventAge
		val eventKey = (eventId xor (eventId ushr 32)).toInt()
		var i = 0
		while (i < hist.count && out.count < limit) {
			val alpha = hist.alpha[i] * alphaScale
			if (alpha > 0.004f) {
				val id = hist.ids[i]
				val key = id xor eventKey
				driftDirection(key)
				val radius = spreadRadius + driftRadius
				out.push(
					hist.x[i] + trailOffset[0] * radius,
					hist.y[i] + trailOffset[1] * radius,
					hist.z[i] + trailOffset[2] * radius,
					hist.size[i] * sizeScale,
					alpha,
					colorWithAlpha(trailColorOr(hist.argb[i]), alpha),
					id,
				)
			}
			i += stride
		}
	}

	/** 按 (粒子 id, 发射时刻) 生成稳定的随机飘散方向，写入 [trailOffset]。 */
	private fun driftDirection(key: Int) {
		var dx = HashRandom.signed(seed, Streams.JITTER, key, 3000)
		var dy = HashRandom.signed(seed, Streams.JITTER, key, 3001)
		var dz = HashRandom.signed(seed, Streams.JITTER, key, 3002)
		val length = sqrt(dx * dx + dy * dy + dz * dz)
		if (length < 1e-4f) {
			dx = 0f
			dy = 1f
			dz = 0f
		} else {
			dx /= length
			dy /= length
			dz /= length
		}
		trailOffset[0] = dx
		trailOffset[1] = dy
		trailOffset[2] = dz
	}

	/**
	 * 距离模式：从当前源粒子向过去逐步推进，沿每颗粒子自己的路径按 [Trail.distance] 取点。
	 * 采样次数固定为 `2 * cap`，回溯窗口由 [Trail.lag] 限制。
	 */
	private fun appendDistanceTrails(out: ParticleBuffer, age: Float, sourceCount: Int, limit: Int) {
		val trail = trail ?: return
		val probes = trailCap * 2
		val probe = trail.lag / probes
		if (probe <= 0f) return
		ensureTrailState(sourceMaxParticles)
		indexSource(out, sourceCount)
		var slot = 0
		while (slot < sourceCount) {
			trailPrevX[slot] = out.x[slot]
			trailPrevY[slot] = out.y[slot]
			trailPrevZ[slot] = out.z[slot]
			trailAccum[slot] = 0f
			trailEmitted[slot] = 0
			trailActive[slot] = true
			trailSeen[slot] = 0
			slot++
		}

		var stamp = 0
		var previousCycle = cycleIndex(age)
		var j = 1
		scan@ while (j <= probes && out.count < limit) {
			val pastAge = age - j * probe
			if (!bound.definition.loop && pastAge <= 0f) break
			val cycle = cycleIndex(pastAge)
			// 周期边界处同 id 的粒子属于另一个周期，链无法连续，直接停止本帧的跟踪。
			if (bound.definition.loop && cycle != previousCycle) {
				previousCycle = cycle
				var s = 0
				while (s < sourceCount) {
					trailActive[s] = false
					s++
				}
				j++
				continue@scan
			}
			previousCycle = cycle
			sampleRawInto(trailScratch, trailCycle, pastAge)
			stamp++

			var i = trailScratch.count - 1
			while (i >= 0) {
				val id = trailScratch.ids[i]
				val s = if (id >= 0) trailSourceIndex.get(id) else -1
				if (s >= 0 && s < sourceCount && trailActive[s] && trailSeen[s] != stamp) {
					trailSeen[s] = stamp
					val cx = trailScratch.x[i]
					val cy = trailScratch.y[i]
					val cz = trailScratch.z[i]
					val dx = cx - trailPrevX[s]
					val dy = cy - trailPrevY[s]
					val dz = cz - trailPrevZ[s]
					val distance = sqrt(dx * dx + dy * dy + dz * dz)
					if (distance > 1e-6f) {
						val start = trailAccum[s]
						val end = start + distance
						var segment = trailEmitted[s]
						while (segment < trailCap) {
							val target = trail.distance * (segment + 1)
							if (target > end) break
							val u = ((target - start) / distance).coerceIn(0f, 1f)
							val px = trailPrevX[s] + dx * u
							val py = trailPrevY[s] + dy * u
							val pz = trailPrevZ[s] + dz * u
							val alpha = trailScratch.alpha[i] * trailAlphaMul[segment + 1]
							if (alpha > 0.004f) {
								var ox = 0f
								var oy = 0f
								var oz = 0f
								if (trail.spread > 0f) {
									lateralOffset(
										out, px, py, pz, id, segment + 1,
										trail.spread * (segment + 1) / trailCap,
									)
									ox = trailOffset[0]
									oy = trailOffset[1]
									oz = trailOffset[2]
								}
								out.push(
									px + ox, py + oy, pz + oz,
									trailScratch.size[i] * trailSizeMul[segment + 1],
									alpha,
									colorWithAlpha(trailColorOr(trailScratch.argb[i]), alpha),
									id,
								)
							}
							segment++
							if (out.count >= limit) {
								trailEmitted[s] = segment
								break@scan
							}
						}
						trailEmitted[s] = segment
						trailAccum[s] = end
					}
					trailPrevX[s] = cx
					trailPrevY[s] = cy
					trailPrevZ[s] = cz
				}
				i--
			}

			var s = 0
			while (s < sourceCount) {
				if (trailActive[s] && trailSeen[s] != stamp) trailActive[s] = false
				s++
			}
			j++
		}
	}

	private fun appendSegment(
		out: ParticleBuffer,
		hist: ParticleBuffer,
		segment: Int,
		stride: Int,
		spreadRadius: Float,
		limit: Int,
	) {
		val sizeScale = trailSizeMul[segment]
		val alphaScale = trailAlphaMul[segment]
		var i = 0
		while (i < hist.count && out.count < limit) {
			val alpha = hist.alpha[i] * alphaScale
			if (alpha > 0.004f) {
				var px = hist.x[i]
				var py = hist.y[i]
				var pz = hist.z[i]
				if (spreadRadius > 0f) {
					lateralOffset(out, px, py, pz, hist.ids[i], segment, spreadRadius)
					px += trailOffset[0]
					py += trailOffset[1]
					pz += trailOffset[2]
				}
				out.push(
					px, py, pz, hist.size[i] * sizeScale, alpha,
					colorWithAlpha(trailColorOr(hist.argb[i]), alpha), hist.ids[i],
				)
			}
			i += stride
		}
	}

	private fun indexSource(out: ParticleBuffer, sourceCount: Int) {
		trailSourceIndex.clear()
		var i = 0
		while (i < sourceCount) {
			val id = out.ids[i]
			if (id >= 0) trailSourceIndex.put(id, i)
			i++
		}
	}

	/** 横向偏移：垂直于该源粒子当前的运动方向；查不到源粒子时退回水平面。 */
	private fun lateralOffset(
		out: ParticleBuffer,
		px: Float,
		py: Float,
		pz: Float,
		id: Int,
		segment: Int,
		radius: Float,
	) {
		val slot = if (id >= 0) trailSourceIndex.get(id) else -1
		var tx = 0f
		var ty = 0f
		var tz = 0f
		if (slot >= 0) {
			tx = out.x[slot] - px
			ty = out.y[slot] - py
			tz = out.z[slot] - pz
		}
		var e1x = 1f
		var e1y = 0f
		var e1z = 0f
		var e2x = 0f
		var e2y = 0f
		var e2z = 1f
		val length = sqrt(tx * tx + ty * ty + tz * tz)
		if (length > 1e-4f) {
			tx /= length
			ty /= length
			tz /= length
			val rx = if (abs(ty) < 0.9f) 0f else 1f
			val ry = if (abs(ty) < 0.9f) 1f else 0f
			var nx = ty * 0f - tz * ry
			var ny = tz * rx - tx * 0f
			var nz = tx * ry - ty * rx
			val nl = sqrt(nx * nx + ny * ny + nz * nz)
			if (nl > 1e-4f) {
				nx /= nl
				ny /= nl
				nz /= nl
				e1x = nx
				e1y = ny
				e1z = nz
				e2x = ty * nz - tz * ny
				e2y = tz * nx - tx * nz
				e2z = tx * ny - ty * nx
			}
		}
		val a = HashRandom.signed(seed, Streams.JITTER, id, TRAIL_SPREAD_DRAW + segment * 2)
		val b = HashRandom.signed(seed, Streams.JITTER, id, TRAIL_SPREAD_DRAW + segment * 2 + 1)
		val unit = sqrt(a * a + b * b)
		val scale = if (unit > 1e-4f) radius / unit else 0f
		trailOffset[0] = (e1x * a + e2x * b) * scale
		trailOffset[1] = (e1y * a + e2y * b) * scale
		trailOffset[2] = (e1z * a + e2z * b) * scale
	}

	private fun ensureTrailState(size: Int) {
		if (trailPrevX.size >= size) return
		val capacity = maxOf(size, 64)
		trailPrevX = FloatArray(capacity)
		trailPrevY = FloatArray(capacity)
		trailPrevZ = FloatArray(capacity)
		trailAccum = FloatArray(capacity)
		trailEmitted = IntArray(capacity)
		trailActive = BooleanArray(capacity)
		trailSeen = IntArray(capacity)
	}

	private fun cycleIndex(age: Float): Long =
		if (!bound.definition.loop) 0L else floor(age * playScale / bound.duration.toDouble()).toLong()

	/** 轨迹指定了独立的 RGB 时覆盖历史采样颜色；alpha 仍由段衰减决定。 */
	private fun trailColorOr(source: Int): Int =
		if (trailColor == 0) source else (trailColor and 0x00FFFFFF) or (0xFF shl 24)

	private fun colorWithAlpha(argb: Int, alpha: Float): Int {
		val byte = (alpha.coerceIn(0f, 1f) * 255f).toInt()
		return (argb and 0x00FFFFFF) or (byte shl 24)
	}

	/** 把局部坐标绕原点转到触发时的视线方向：先按 pitch 绕 X 轴，再按 yaw 绕 Y 轴。 */
	private fun rotate(out: ParticleBuffer) {
		val ox = origin.x.toFloat()
		val oy = origin.y.toFloat()
		val oz = origin.z.toFloat()
		var i = 0
		while (i < out.count) {
			val dx = out.x[i] - ox
			val dy = out.y[i] - oy
			val dz = out.z[i] - oz
			val y = dy * cosPitch - dz * sinPitch
			val z = dy * sinPitch + dz * cosPitch
			out.x[i] = ox + dx * cosYaw - z * sinYaw
			out.y[i] = oy + y
			out.z[i] = oz + dx * sinYaw + z * cosYaw
			i++
		}
	}

	/** 渲染用：`orientation=view` 时把样式给出的速度向量转到实际朝向。 */
	fun stretchVector(age: Float): Vec3? {
		val vector = bound.style.stretchVector(bound, age) ?: return null
		if (!rotates) return vector
		val y = vector.y * cosPitch - vector.z * sinPitch
		val z = vector.y * sinPitch + vector.z * cosPitch
		return Vec3(
			vector.x * cosYaw - z * sinYaw,
			y,
			vector.x * sinYaw + z * cosYaw,
		)
	}

	private companion object {
		val EMPTY_POINTS = FloatArray(0)
	}
}

object ClientEffects {
	private const val DEFINITION_CACHE_LIMIT = 64

	private val definitions = LinkedHashMap<Int, EffectDefinition>()
	private val boundCache = HashMap<Int, BoundEffect>()
	private val active = LinkedHashMap<Long, ClientEffect>()
	private var versionMatches: Boolean? = null

	fun handshake(payload: EffectHandshakePayload) {
		val protocolMatches = payload.protocolVersion == PROTOCOL_VERSION
		val rngMatches = payload.rngVersion == RNG_VERSION
		versionMatches = protocolMatches && rngMatches
		if (versionMatches == true) {
			AstrorbitsParticle.logger.info(
				"[net] handshake ok: protocol={}, rng={}, client protocol={}, rng={}",
				payload.protocolVersion, payload.rngVersion, PROTOCOL_VERSION, RNG_VERSION,
			)
			return
		}
		AstrorbitsParticle.logger.warn(
			"[net] handshake mismatch: server protocol={} rng={}, client protocol={} rng={}; dropping effects",
			payload.protocolVersion, payload.rngVersion, PROTOCOL_VERSION, RNG_VERSION,
		)
		active.clear()
		boundCache.clear()
		definitions.clear()
		Minecraft.getInstance().player?.sendSystemMessage(
			Component.literal(
				"AstrorbitsParticle 版本不一致：服务端协议 ${payload.protocolVersion}、随机 ${payload.rngVersion}，" +
					"客户端协议 ${PROTOCOL_VERSION}、随机 ${RNG_VERSION}。特效已停用。",
			),
		)
	}

	fun start(payload: EffectStartPayload) {
		if (versionMatches == false) {
			AstrorbitsParticle.logger.debug(
				"[effect] dropped id={}: version mismatch", payload.effectId,
			)
			return
		}
		val definition = resolveDefinition(payload) ?: return
		val bound = boundCache.getOrPut(payload.definitionHash) {
			val raw = EffectBinder.bind(definition).getOrElse {
				AstrorbitsParticle.logger.warn(
					"[effect] client rejected definition {}: {}", definition.style, it.message,
				)
				return
			}
			adjustLifetime(raw)
		}
		val baseAge = payload.elapsedTicks / 20f
		val effect = ClientEffect(
			id = payload.effectId,
			bound = bound,
			seed = payload.seed,
			origin = Vec3(payload.x.toDouble(), payload.y.toDouble(), payload.z.toDouble()),
			scale = payload.scale,
			baseAge = baseAge,
			startGameTime = Minecraft.getInstance().level?.gameTime ?: 0L,
			viewYaw = payload.viewYaw,
			viewPitch = payload.viewPitch,
		)
		active[payload.effectId] = effect
		AstrorbitsParticle.logger.info(
			"[effect] client start id={} style={} particles={} budget={} trail={}",
			payload.effectId, definition.style, bound.count, effect.maxParticles,
			bound.trail?.let { "${it.mode.name.lowercase()}:${it.cap}" } ?: "-",
		)
	}

	/** cycle 变体按贴图数量收紧寿命，让一轮换图结束即消失，颜色渐变也随之对齐。 */
	private fun adjustLifetime(bound: BoundEffect): BoundEffect {
		if (bound.appearance.spriteVariation != SpriteVariation.CYCLE) return bound
		val frames = SpriteVariants.count(bound.appearance.sprite)
		if (frames <= 1) return bound
		val lifetime = (frames * bound.appearance.spriteCycleTicks / 20f)
			.coerceAtMost(bound.duration)
		return if (lifetime < bound.emission.lifetime) bound.withLifetime(lifetime) else bound
	}

	fun stop(payload: EffectStopPayload) {
		if (!payload.fade) {
			payload.effectIds.forEach(active::remove)
			return
		}
		val gameTime = Minecraft.getInstance().level?.gameTime
		if (gameTime == null) {
			payload.effectIds.forEach(active::remove)
			return
		}
		payload.effectIds.forEach { active[it]?.stop(gameTime) }
	}

	fun clear(payload: EffectClearPayload) {
		active.clear()
		boundCache.clear()
		definitions.clear()
	}

	fun attach(effectId: Long) {
		val effect = active[effectId] ?: return
		val level = Minecraft.getInstance().level ?: return
		level.addParticle(
			EffectAnchorOptions(effectId),
			effect.origin.x, effect.origin.y, effect.origin.z,
			0.0, 0.0, 0.0,
		)
	}

	fun tick() {
		val level = Minecraft.getInstance().level ?: return
		active.values.removeIf { it.expired(level.gameTime) }
		for (effect in active.values) {
			val definition = effect.bound.definition
			if (definition.subEffect == null || definition.subEffectTrigger != SubEffectTrigger.IMPACT) continue
			if (!ClientPlayNetworking.canSend(EffectImpactPayload.TYPE)) continue
			val points = effect.collectImpacts(level)
			if (points.isNotEmpty()) {
				ClientPlayNetworking.send(EffectImpactPayload(effect.id, points))
			}
		}
	}

	fun all(): Collection<ClientEffect> = active.values

	fun isEmpty(): Boolean = active.isEmpty()

	fun count(): Int = active.size

	private fun resolveDefinition(payload: EffectStartPayload): EffectDefinition? {
		payload.definition?.let { raw ->
			val parsed = EffectDefinition.parse(raw).getOrElse {
				AstrorbitsParticle.logger.warn("[effect] client parse failed: {}", it.message)
				return null
			}
			if (definitions.size >= DEFINITION_CACHE_LIMIT) definitions.clear()
			definitions[payload.definitionHash] = parsed
			return parsed
		}
		return definitions[payload.definitionHash]
	}
}
