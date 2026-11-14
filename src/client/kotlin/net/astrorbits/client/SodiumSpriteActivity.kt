package net.astrorbits.client

import net.astrorbits.AstrorbitsParticle
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import java.lang.reflect.Method

/**
 * Sodium 的“只动画可见纹理”按 sprite 级别跟踪可见性：渲染时调用
 * `SpriteUtil.markSpriteActive`，下一 tick 才允许把动画帧写回图集。
 * 原版 `SingleQuadParticle` 走这条路径，本模组直接写 `QuadParticleRenderState`，
 * 因此需要在产出四边形时补上标记。
 *
 * Sodium 未安装时整个桥接为 null；反射查找或调用失败只记一次 debug 后停用。
 */
object SodiumSpriteActivity {
	private var bridge: Bridge? = load()

	fun mark(sprite: TextureAtlasSprite) {
		val current = bridge ?: return
		try {
			current.mark(sprite)
		} catch (t: Throwable) {
			AstrorbitsParticle.logger.debug("[render] sodium sprite activity failed: {}", t.toString())
			bridge = null
		}
	}

	private fun load(): Bridge? {
		if (!FabricLoader.getInstance().isModLoaded("sodium")) return null
		return runCatching {
			val type = Class.forName("net.caffeinemc.mods.sodium.api.texture.SpriteUtil")
			val instance = type.getField("INSTANCE").get(null)
			val method = type.getMethod("markSpriteActive", TextureAtlasSprite::class.java)
			Bridge(instance, method)
		}.getOrElse {
			AstrorbitsParticle.logger.debug("[render] sodium sprite activity unavailable: {}", it.toString())
			null
		}
	}

	private class Bridge(private val instance: Any, private val method: Method) {
		fun mark(sprite: TextureAtlasSprite) {
			method.invoke(instance, sprite)
		}
	}
}
