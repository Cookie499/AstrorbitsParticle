package net.astrorbits.client

import net.astrorbits.math.HashRandom
import net.astrorbits.math.Streams
import net.astrorbits.model.Blend
import net.astrorbits.model.SpriteVariation
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.particle.Particle
import net.minecraft.client.particle.ParticleEngine
import net.minecraft.client.particle.ParticleGroup
import net.minecraft.client.particle.ParticleRenderType
import net.minecraft.client.particle.SingleQuadParticle
import net.minecraft.client.renderer.culling.Frustum
import net.minecraft.client.renderer.state.level.ParticleGroupRenderState
import net.minecraft.client.renderer.state.level.QuadParticleRenderState
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.client.resources.model.sprite.SpriteId
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import org.joml.Quaternionf
import kotlin.math.roundToInt

val EFFECT_RENDER_TYPE = ParticleRenderType("astrorbitsparticle:effect", "AOP")

private val PARTICLE_ATLAS = Identifier.withDefaultNamespace("textures/atlas/particles.png")

private const val MAX_STRIDE = 4
private const val LOD_NEAR = 24f
private const val LOD_FAR = 96f
private const val STRETCH_SEGMENTS = 3

/**
 * 所有特效共用一个粒子组：组负责推进年龄、做视锥剔除与 LOD，
 * 并把全部粒子写进同一个 QuadParticleRenderState，逐粒子不创建 Particle 对象。
 *
 * 动画精灵不做逐帧 UV 偏移：图集自己播动画。TextureAtlas 每 tick 调用
 * SpriteContents.AnimationState，把当前帧重绘进该 sprite 在贴图集里的那块区域，
 * 所以四边形只要采样整块 sprite，看到的帧跟着图集走，原版粒子也是这样。
 * 由此同一张贴图的所有粒子共享一帧，逐粒子起始帧无法用图集实现。
 */
class EffectParticleGroup(engine: ParticleEngine) : ParticleGroup<Particle>(engine) {
	private val variants = HashMap<Identifier, Array<SpriteInfo>>()
	private var loggedFirstFrame = false
	private var adaptiveStride = 1
	private var slowFrames = 0
	private var fastFrames = 0

	override fun add(particle: Particle): Boolean {
		if (particle is EffectAnchorParticle) {
			particle.remove()
			return false
		}
		return super.add(particle)
	}

	override fun tickParticles() {
		ClientEffects.tick()
	}

	override fun isEmpty(): Boolean = ClientEffects.isEmpty()

	override fun size(): Int = ClientEffects.count()

	override fun extractRenderState(frustum: Frustum, camera: Camera, partialTick: Float): ParticleGroupRenderState {
		AdditiveParticles.registerIris()
		val state = QuadParticleRenderState()
		val level = Minecraft.getInstance().level ?: return state
		val cameraPos = camera.position()
		val rotation = camera.rotation()
		val started = System.nanoTime()

		val visible = ClientEffects.all()
			.filter { frustum.isVisible(it.cullBox) }
			.sortedBy { it.origin.distanceToSqr(cameraPos) }
			.take(net.astrorbits.config.ModConfig.maxVisibleEffects)
		val planned = visible.sumOf { it.bound.count }
		val budgetStride = if (planned > net.astrorbits.config.ModConfig.maxVisibleParticles) {
			kotlin.math.ceil(planned.toFloat() / net.astrorbits.config.ModConfig.maxVisibleParticles).toInt().coerceIn(1, MAX_STRIDE)
		} else {
			1
		}
		val globalStride = maxOf(adaptiveStride, budgetStride)

		var quads = 0
		for (effect in visible) {
			val buffer = effect.sample(level.gameTime, partialTick)
			if (buffer.count == 0) continue
			val appearance = effect.bound.appearance
			val sprites = spritesFor(appearance.sprite)
			// 整个特效可见就标记它的全部变体，避免逐粒子去重；Sodium 只要求本帧有引用。
			for (info in sprites) {
				val atlasSprite = info.atlasSprite ?: continue
				SodiumSpriteActivity.mark(atlasSprite)
			}
			val additive = appearance.blend == Blend.ADDITIVE
			// effect 模式整个特效共用一张，逐粒子模式按 SPRITE 流逐颗取。
			val sharedSprite = if (sprites.size == 1) {
				sprites[0]
			} else if (appearance.spriteVariation == SpriteVariation.EFFECT) {
				sprites[effectVariant(effect.seed, sprites.size)]
			} else if (appearance.spriteVariation == SpriteVariation.CYCLE) {
				val frame = ((effect.ageAt(level.gameTime, partialTick) * 20f).toInt() / appearance.spriteCycleTicks.coerceAtLeast(1))
					.mod(sprites.size)
				sprites[frame]
			} else {
				null
			}
			val light = lightAt(level, effect)
			val ox = effect.origin.x.toFloat()
			val oy = effect.origin.y.toFloat()
			val oz = effect.origin.z.toFloat()
			val scale = effect.scale
			val age = effect.ageAt(level.gameTime, partialTick)
			val step = maxOf(globalStride, lodStride(effect.origin.distanceToSqr(cameraPos)))
			val spin = appearance.spin.at(age)
			val quadRotation = if (spin != 0f) {
				Quaternionf(rotation).rotateZ(Math.toRadians((spin * age).toDouble()).toFloat())
			} else {
				rotation
			}
			val stretch = appearance.stretch.at(age)
			val stretchVec = if (stretch > 0f) effect.bound.style.stretchVector(effect.bound, age) else null
			val svx = stretchVec?.x?.toFloat() ?: 0f
			val svy = stretchVec?.y?.toFloat() ?: 0f
			val svz = stretchVec?.z?.toFloat() ?: 0f
			val stretched = stretchVec != null && svx * svx + svy * svy + svz * svz > 1e-6f

			var i = 0
			while (i < buffer.count) {
				val sprite = sharedSprite ?: sprites[particleVariant(effect.seed, i, sprites.size)]
				val layer = if (additive) {
					AdditiveParticles.layerFor(sprite.layer)
				} else if (sprite.layer.translucent) {
					sprite.layer
				} else {
					AdditiveParticles.translucentLayer
				}
				val px = (ox + (buffer.x[i] - ox) * scale - cameraPos.x).toFloat()
				val py = (oy + (buffer.y[i] - oy) * scale - cameraPos.y).toFloat()
				val pz = (oz + (buffer.z[i] - oz) * scale - cameraPos.z).toFloat()
				val size = buffer.size[i] * scale
				val color = buffer.argb[i]
				state.add(
					layer, px, py, pz,
					quadRotation.x, quadRotation.y, quadRotation.z, quadRotation.w,
					size, sprite.u0, sprite.u1, sprite.v0, sprite.v1, color, light,
				)
				if (stretched) {
					for (segment in 1 until STRETCH_SEGMENTS) {
						val f = segment.toFloat() / STRETCH_SEGMENTS
						state.add(
							layer,
							px - svx * stretch * f,
							py - svy * stretch * f,
							pz - svz * stretch * f,
							quadRotation.x, quadRotation.y, quadRotation.z, quadRotation.w,
							size * (1f - f * 0.4f),
							sprite.u0, sprite.u1, sprite.v0, sprite.v1,
							fadeAlpha(color, 1f - f * 0.6f),
							light,
						)
					}
				}
				i += step
			}
			quads += (buffer.count + step - 1) / step
		}

		adapt((System.nanoTime() - started) / 1_000_000f)

		if (!loggedFirstFrame && quads > 0) {
			loggedFirstFrame = true
			net.astrorbits.AstrorbitsParticle.logger.info(
				"[effect] first frame: {} quads (stride {})", quads, globalStride,
			)
		}
		return state
	}

	private fun lodStride(distanceSqr: Double): Int {
		val distance = kotlin.math.sqrt(distanceSqr).toFloat()
		val t = ((distance - LOD_NEAR) / (LOD_FAR - LOD_NEAR)).coerceIn(0f, 1f)
		val lod = (1f - t * 0.6f).coerceIn(0.35f, 1f)
		return (1f / lod).roundToInt().coerceAtLeast(1)
	}

	/** 单帧超过预算就加步长，连续轻载再逐步降回来。 */
	private fun adapt(elapsedMs: Float) {
		if (elapsedMs > net.astrorbits.config.ModConfig.frameBudgetMs) {
			slowFrames++
			fastFrames = 0
		} else if (elapsedMs < net.astrorbits.config.ModConfig.frameBudgetMs * 0.5f) {
			fastFrames++
			slowFrames = 0
		} else {
			slowFrames = 0
			fastFrames = 0
		}
		if (slowFrames >= 30 && adaptiveStride < MAX_STRIDE) {
			adaptiveStride++
			slowFrames = 0
		}
		if (fastFrames >= 120 && adaptiveStride > 1) {
			adaptiveStride--
			fastFrames = 0
		}
	}

	private fun fadeAlpha(color: Int, factor: Float): Int {
		val alpha = (((color ushr 24) and 0xFF) * factor).toInt().coerceIn(0, 255)
		return (color and 0x00FFFFFF) or (alpha shl 24)
	}

	private fun lightAt(level: ClientLevel, effect: ClientEffect): Int {
		if (effect.bound.appearance.fullbright) return 0xF000F0
		val pos = BlockPos.containing(effect.origin)
		val raw = level.lightEngine.getRawBrightness(pos, 0)
		return (raw shl 4) or (raw shl 20)
	}

	/** 整个特效共用一张时按 seed 取，与应用顺序无关。 */
	private fun effectVariant(seed: Long, count: Int): Int = ((seed ushr 1) % count).toInt()

	/** 逐粒子取变体，用 SPRITE 流，保证同一 (seed, index) 每次得到同一张。 */
	private fun particleVariant(seed: Long, index: Int, count: Int): Int =
		((HashRandom.hash(seed, Streams.SPRITE, index) ushr 1) % count).toInt()

	/** 允许直接写原版粒子定义 id（如 end_rod），展开它 textures 数组里的全部贴图。 */
	private fun resolveTextures(id: Identifier): List<Identifier> {
		val jsonId = Identifier.fromNamespaceAndPath(id.namespace, "particles/${id.path}.json")
		val resource = Minecraft.getInstance().resourceManager.getResource(jsonId).orElse(null) ?: return listOf(id)
		return runCatching {
			resource.open().use { stream ->
				val textures = com.google.gson.JsonParser.parseReader(stream.bufferedReader())
					.asJsonObject.getAsJsonArray("textures") ?: return@use listOf(id)
				textures.mapNotNull { it.asString?.let(Identifier::tryParse) }.ifEmpty { listOf(id) }
			}
		}.getOrDefault(listOf(id))
	}

	private fun spritesFor(id: Identifier): Array<SpriteInfo> = variants.getOrPut(id) {
		val textures = resolveTextures(id)
		net.astrorbits.AstrorbitsParticle.logger.debug(
			"[effect] sprite {} has {} variant(s): {}",
			id, textures.size, textures.joinToString(","),
		)
		Array(textures.size) { loadSprite(id, textures[it]) }
	}

	private fun loadSprite(id: Identifier, resolved: Identifier): SpriteInfo =
		runCatching {
			val sprite = Minecraft.getInstance().atlasManager.get(SpriteId(PARTICLE_ATLAS, resolved))
			val contents = sprite.contents()
			if (contents.isAnimated) {
				net.astrorbits.AstrorbitsParticle.logger.info(
					"[effect] sprite {} -> {} is animated, {} frames played by the atlas",
					id, resolved, contents.uniqueFrames.size,
				)
			} else {
				net.astrorbits.AstrorbitsParticle.logger.debug(
					"[effect] sprite {} -> static uv=({}, {})-({}, {})",
					id, resolved, sprite.u0, sprite.v0, sprite.u1, sprite.v1,
				)
			}
			SpriteInfo(
				layer = SingleQuadParticle.Layer.bySprite(sprite),
				u0 = sprite.u0,
				u1 = sprite.u1,
				v0 = sprite.v0,
				v1 = sprite.v1,
				atlasSprite = if (contents.isAnimated) sprite else null,
			)
		}.getOrElse {
			net.astrorbits.AstrorbitsParticle.logger.warn(
				"[effect] sprite lookup failed for {}: {}", resolved, it.message,
			)
			SpriteInfo(SingleQuadParticle.Layer.TRANSLUCENT, 0f, 0f, 0f, 0f, null)
		}

	private data class SpriteInfo(
		val layer: SingleQuadParticle.Layer,
		val u0: Float,
		val u1: Float,
		val v0: Float,
		val v1: Float,
		/** 动画 sprite 才交给 Sodium 标记；静态 sprite 不需要。 */
		val atlasSprite: TextureAtlasSprite?,
	)
}
