package net.astrorbits.config

import net.astrorbits.AstrorbitsParticle
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * 渲染与定义上限。server 与 client 各自读取，缺文件时写入默认值。
 * 读取后不再热重载，改配置需要重启客户端或服务器。
 */
object ModConfig {
	var maxParticlesPerEffect = 8192
	var maxVisibleParticles = 20000
	var maxVisibleEffects = 64
	var frameBudgetMs = 4f

	private const val DEFAULT_JSON = """{
  "maxParticlesPerEffect": 8192,
  "maxVisibleParticles": 20000,
  "maxVisibleEffects": 64,
  "frameBudgetMs": 4.0
}
"""

	fun load() {
		val path = FabricLoader.getInstance().configDir.resolve("astrorbitsparticle/config.json")
		if (!Files.exists(path)) {
			Files.createDirectories(path.parent)
			Files.writeString(path, DEFAULT_JSON)
		} else {
			runCatching {
				val obj = JsonParser.parseString(Files.readString(path)).asJsonObject
				obj.get("maxParticlesPerEffect")?.let { maxParticlesPerEffect = it.asInt.coerceIn(1, 1 shl 20) }
				obj.get("maxVisibleParticles")?.let { maxVisibleParticles = it.asInt.coerceIn(1, 1 shl 20) }
				obj.get("maxVisibleEffects")?.let { maxVisibleEffects = it.asInt.coerceIn(1, 1024) }
				obj.get("frameBudgetMs")?.let { frameBudgetMs = it.asFloat.coerceIn(0.5f, 50f) }
			}.onFailure {
				AstrorbitsParticle.logger.warn("[config] failed to read config.json: {}", it.message)
			}
		}
		AstrorbitsParticle.logger.info(
			"[config] limits: maxParticlesPerEffect={}, maxVisibleParticles={}, maxVisibleEffects={}, frameBudgetMs={}",
			maxParticlesPerEffect, maxVisibleParticles, maxVisibleEffects, frameBudgetMs,
		)
	}
}