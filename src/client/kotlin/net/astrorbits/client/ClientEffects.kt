package net.astrorbits.client

import net.astrorbits.AstrorbitsParticle
import net.astrorbits.model.EffectDefinition
import net.astrorbits.model.Orientation
import net.astrorbits.model.SpriteVariation
import net.astrorbits.model.SubEffectTrigger
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

private const val IMPACT_CHECKS_PER_TICK = 128
private const val MAX_IMPACT_REPORTS = 8
private const val MAX_LOOP_LOOKBACK = 8

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

	/** 单帧最多产出的粒子数，缓冲区容量与客户端渲染预算都按它估算。 */
	val maxParticles: Int = if (cyclesForward) bound.count * (loopLookback + 1) else bound.count

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
		sampleInto(impactBuffer, impactCycle, ageAt(level.gameTime, 0f))
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

	fun expired(gameTime: Long): Boolean = !bound.definition.loop && ageAt(gameTime, 0f) >= bound.duration

	fun sample(gameTime: Long, partialTick: Float): ParticleBuffer {
		sampleInto(buffer, renderCycle, ageAt(gameTime, partialTick))
		return buffer
	}

	private fun sampleInto(out: ParticleBuffer, cycle: ParticleBuffer, age: Float) {
		sampleRawInto(out, cycle, age)
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
			out.reset(bound.count)
			bound.style.sample(bound, origin, seed, sampleAge, out)
			return
		}
		if (!cyclesForward) {
			val wrapped = scaled % bound.duration
			out.reset(bound.count)
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
		out.reset(maxParticles)
		for (back in minOf(cycleIndex, loopLookback.toLong()).toInt() downTo 0) {
			cycle.reset(bound.count)
			bound.style.sample(bound, origin, seed, phase + back * bound.duration, cycle)
			out.append(cycle)
		}
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
		active[payload.effectId] = ClientEffect(
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
		AstrorbitsParticle.logger.info(
			"[effect] client start id={} style={} particles={}",
			payload.effectId, definition.style, bound.count,
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
		payload.effectIds.forEach(active::remove)
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
