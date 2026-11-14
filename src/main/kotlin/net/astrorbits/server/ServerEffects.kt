package net.astrorbits.server

import net.astrorbits.AstrorbitsParticle
import net.astrorbits.model.EffectDefinition
import net.astrorbits.model.Orientation
import net.astrorbits.model.SubEffectTrigger
import net.astrorbits.net.EffectClearPayload
import net.astrorbits.net.EffectImpactPayload
import net.astrorbits.net.EffectStartPayload
import net.astrorbits.net.EffectStopPayload
import net.astrorbits.net.ModPayloads
import net.astrorbits.style.BoundEffect
import net.astrorbits.style.EffectBinder
import net.astrorbits.style.ParticleStyles
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.asin
import kotlin.math.atan2

data class Scene(
	val id: String,
	val dimension: ResourceKey<Level>,
	val min: Vec3,
	val max: Vec3,
	val viewDistance: Float,
	val scanInterval: Int,
) {
	fun contains(pos: Vec3): Boolean =
		pos.x in min.x..max.x && pos.y in min.y..max.y && pos.z in min.z..max.z
}

class ServerEffect(
	val id: Long,
	val definition: EffectDefinition,
	val seed: Long,
	val dimension: ResourceKey<Level>,
	val origin: Vec3,
	val scale: Float,
	val startTick: Long,
	val endTick: Long,
	val scene: Scene?,
	/** `orientation=view` 且触发时给了方向才有值。 */
	val viewDirection: Vec3? = null,
	val subscribers: MutableSet<UUID> = HashSet(),
	val definitionHash: Int = definition.source.hashCode(),
	val durationTicks: Long = (definition.duration * 20f).toLong().coerceAtLeast(1),
	val depth: Int = 0,
	val boundsRadius: Float = 16f,
) {
	var impactSpawns: Int = 0
}

object ServerEffects {
	private const val SCAN_INTERVAL = 10
	private const val DEFINITION_CACHE_LIMIT = 128
	private const val MAX_IMPACT_SUB_EFFECTS = 8
	private const val MAX_SUB_DEPTH = 3

	private data class SubSpawn(
		val definition: EffectDefinition,
		val level: ServerLevel,
		val origin: Vec3,
		val depth: Int,
		val viewDirection: Vec3?,
	)

	private var server: MinecraftServer? = null
	private val effects = LinkedHashMap<Long, ServerEffect>()
	private val definitions = LinkedHashMap<Identifier, EffectDefinition>()
	private val boundCache = LinkedHashMap<Int, BoundEffect>()
	private val sentDefinitions = HashMap<UUID, MutableSet<Int>>()
	private var scenes: List<Scene> = emptyList()
	private var nextId = 1L
	private var lastScan = -SCAN_INTERVAL.toLong()

	fun init(server: MinecraftServer) {
		this.server = server
		effects.clear()
		sentDefinitions.clear()
		nextId = 1L
		lastScan = -SCAN_INTERVAL.toLong()
		loadConfig()
	}

	fun tick(server: MinecraftServer) {
		val now = server.overworld().gameTime
		expire(server, now)
		if (now - lastScan < SCAN_INTERVAL) return
		lastScan = now
		scan(server, now)
	}

	fun play(
		definition: EffectDefinition,
		level: ServerLevel,
		origin: Vec3,
		scale: Float = 1f,
		seed: Long? = null,
		depth: Int = 0,
		direction: Vec3? = null,
	): ServerEffect? {
		val hash = definition.source.hashCode()
		val bound = boundCache.getOrPut(hash) {
			EffectBinder.bind(definition).getOrElse {
				AstrorbitsParticle.logger.warn(
					"[effect] rejected definition {}: {}", definition.style, it.message,
				)
				return null
			}
		}
		while (boundCache.size > 64) {
			boundCache.remove(boundCache.keys.first())
		}
		val id = nextId++
		val scene = findScene(level, origin)
		val durationTicks = (definition.duration * 20f).toLong().coerceAtLeast(1)
		val viewDirection = if (definition.orientation == Orientation.VIEW) {
			direction?.takeIf { it.lengthSqr() > 1e-9 }?.normalize()
		} else {
			null
		}
		val effect = ServerEffect(
			id = id,
			definition = definition,
			seed = seed ?: level.random.nextLong(),
			dimension = level.dimension(),
			origin = origin,
			scale = scale,
			startTick = level.gameTime,
			endTick = if (definition.loop) Long.MAX_VALUE else level.gameTime + durationTicks,
			scene = scene,
			viewDirection = viewDirection,
			durationTicks = durationTicks,
			depth = depth,
			boundsRadius = bound.radius * scale,
		)
		effects[id] = effect
		// 频谱扫描每 10 tick 一次，新建特效要立刻下发，否则会跳过开头
		for (player in level.server.playerList.players) {
			if (!isEligible(player, effect, false)) continue
			sendStart(player, effect, 0L)
			effect.subscribers += player.uuid
		}
		AstrorbitsParticle.logger.info(
			"[effect] spawned {} id={} depth={} scene={} origin={}",
			definition.style, id, depth, scene?.id ?: "-", origin,
		)
		return effect
	}

	fun stop(id: Long): Boolean {
		val effect = effects.remove(id) ?: return false
		val level = server?.getLevel(effect.dimension) ?: return true
		val payload = EffectStopPayload(longArrayOf(id))
		for (uuid in effect.subscribers) {
			level.server.playerList.players.firstOrNull { it.uuid == uuid }?.let {
				ServerPlayNetworking.send(it, payload)
			}
		}
		return true
	}

	fun stopAll() {
		val ids = effects.keys.toLongArray()
		if (ids.isEmpty()) return
		effects.clear()
		val payload = EffectStopPayload(ids)
		server?.playerList?.players?.forEach { ServerPlayNetworking.send(it, payload) }
	}

	/** 在客户端报告的碰撞点生成一次子特效；总次数按特效封顶。 */
	fun impact(player: ServerPlayer, payload: EffectImpactPayload) {
		val effect = effects[payload.effectId] ?: return
		val definition = effect.definition
		if (definition.subEffectTrigger != SubEffectTrigger.IMPACT) return
		val target = definition.subEffect?.let(definitions::get) ?: return
		if (target.subEffect != null) return
		if (player.uuid !in effect.subscribers) return
		if (player.level().dimension() != effect.dimension) return
		val level = server?.getLevel(effect.dimension) ?: return
		val distance = definition.visibility.viewDistance + definition.visibility.boundsPadding
		val maxDistanceSqr = (distance * distance).toDouble()
		var spawned = 0
		var i = 0
		while (i + 2 < payload.positions.size && effect.impactSpawns < MAX_IMPACT_SUB_EFFECTS) {
			val x = payload.positions[i].toDouble()
			val y = payload.positions[i + 1].toDouble()
			val z = payload.positions[i + 2].toDouble()
			i += 3
			if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
			val position = Vec3(x, y, z)
			if (position.distanceToSqr(effect.origin) > maxDistanceSqr) continue
			if (!level.isLoaded(BlockPos.containing(position))) continue
			if (effect.depth >= MAX_SUB_DEPTH) break
			if (play(target, level, position, 1f, null, effect.depth + 1, effect.viewDirection) == null) continue
			effect.impactSpawns++
			spawned++
		}
		if (spawned > 0) {
			AstrorbitsParticle.logger.info(
				"[effect] impact id={} spawned {} sub-effect(s) of {}",
				effect.id, spawned, target.style,
			)
		}
	}

	fun list(): List<ServerEffect> = effects.values.toList()

	fun definitions(): Map<Identifier, EffectDefinition> = definitions

	fun scenes(): List<Scene> = scenes

	fun findDefinition(id: Identifier): EffectDefinition? = definitions[id]

	fun reload() {
		net.astrorbits.config.ModConfig.load()
		loadConfig()
		stopAll()
		server?.playerList?.players?.forEach { ServerPlayNetworking.send(it, EffectClearPayload()) }
	}

	private fun expire(server: MinecraftServer, now: Long) {
		val pending = ArrayList<SubSpawn>()
		val iterator = effects.values.iterator()
		while (iterator.hasNext()) {
			val effect = iterator.next()
			if (effect.endTick == Long.MAX_VALUE || now < effect.endTick) continue
			iterator.remove()
			val payload = EffectStopPayload(longArrayOf(effect.id))
			for (uuid in effect.subscribers) {
				server.playerList.players.firstOrNull { it.uuid == uuid }?.let {
					ServerPlayNetworking.send(it, payload)
				}
			}
			val subId = effect.definition.subEffect ?: continue
			if (effect.definition.subEffectTrigger != SubEffectTrigger.END) continue
			if (effect.depth >= MAX_SUB_DEPTH) continue
			val target = definitions[subId] ?: continue
			val level = server.getLevel(effect.dimension) ?: continue
			pending += SubSpawn(target, level, effect.origin, effect.depth + 1, effect.viewDirection)
		}
		for (spawn in pending) {
			play(spawn.definition, spawn.level, spawn.origin, 1f, null, spawn.depth, spawn.viewDirection)
		}
	}

	private fun scan(server: MinecraftServer, now: Long) {
		if (effects.isEmpty()) return
		for (effect in effects.values) {
			val level = server.getLevel(effect.dimension) ?: continue
			val elapsed = (now - effect.startTick).coerceAtLeast(0L)
			for (player in server.playerList.players) {
				val subscribed = player.uuid in effect.subscribers
				val eligible = isEligible(player, effect, subscribed)
				if (eligible && !subscribed) {
					sendStart(player, effect, elapsed)
					effect.subscribers += player.uuid
				} else if (!eligible && subscribed) {
					effect.subscribers -= player.uuid
					ServerPlayNetworking.send(player, EffectStopPayload(longArrayOf(effect.id)))
				}
			}
		}
	}

	private fun isEligible(player: ServerPlayer, effect: ServerEffect, subscribed: Boolean): Boolean {
		if (player.level().dimension() != effect.dimension) return false
		if (!ServerPlayNetworking.canSend(player, EffectStartPayload.TYPE)) return false
		val visibility = effect.definition.visibility
		val scene = effect.scene
		if (visibility.requireScene && (scene == null || !scene.contains(player.position()))) return false
		val limit = (scene?.viewDistance ?: visibility.viewDistance)
			.coerceAtMost(visibility.viewDistance) + effect.boundsRadius + visibility.boundsPadding
		// 已在订阅中的玩家用更大的退出半径，避免在边界反复收发。
		val radius = if (subscribed) limit * 1.15f else limit
		return player.position().distanceToSqr(effect.origin) <= (radius * radius).toDouble()
	}

	private fun sendStart(player: ServerPlayer, effect: ServerEffect, elapsed: Long) {
		val cache = sentDefinitions.getOrPut(player.uuid) { HashSet() }
		val known = effect.definitionHash in cache
		val elapsedTicks = if (effect.definition.loop) {
			(elapsed % effect.durationTicks).toInt()
		} else {
			elapsed.coerceAtMost(effect.durationTicks).toInt()
		}
		val direction = effect.viewDirection
		val payload = EffectStartPayload(
			effectId = effect.id,
			seed = effect.seed,
			elapsedTicks = elapsedTicks,
			x = effect.origin.x.toFloat(),
			y = effect.origin.y.toFloat(),
			z = effect.origin.z.toFloat(),
			scale = effect.scale,
			viewYaw = direction?.let { Math.toDegrees(atan2(-it.x, it.z)).toFloat() } ?: 0f,
			viewPitch = direction?.let { Math.toDegrees(-asin(it.y.coerceIn(-1.0, 1.0))).toFloat() } ?: 0f,
			definitionHash = effect.definitionHash,
			definition = if (known) null else effect.definition.source,
		)
		ServerPlayNetworking.send(player, payload)
		if (!known) {
			if (cache.size >= DEFINITION_CACHE_LIMIT) cache.clear()
			cache += effect.definitionHash
		}
	}

	private fun findScene(level: ServerLevel, origin: Vec3): Scene? =
		scenes.firstOrNull { it.dimension == level.dimension() && it.contains(origin) }

	private fun loadConfig() {
		val configDir = FabricLoader.getInstance().configDir.resolve(AstrorbitsParticle.MOD_ID)
		Files.createDirectories(configDir)
		loadScenes(configDir)
		loadDefinitions(configDir.resolve("effects"))
	}

	private fun loadScenes(configDir: Path) {
		val file = configDir.resolve("scenes.json")
		if (!Files.exists(file)) {
			Files.writeString(file, DEFAULT_SCENES)
		}
		scenes = runCatching {
			val root = JsonParser.parseString(Files.readString(file)).asJsonObject
			val array = root.getAsJsonArray("scenes") ?: JsonArray()
			array.mapNotNull { element ->
				val obj = element.asJsonObject
				val id = obj.get("id")?.asString ?: return@mapNotNull null
				val dimensionId = Identifier.tryParse(obj.get("dimension")?.asString ?: "minecraft:overworld")
					?: return@mapNotNull null
				val key = ResourceKey.create(Registries.DIMENSION, dimensionId)
				Scene(
					id = id,
					dimension = key,
					min = obj.vec3("min") ?: Vec3(-48.0, 56.0, -48.0),
					max = obj.vec3("max") ?: Vec3(48.0, 144.0, 48.0),
					viewDistance = obj.get("viewDistance")?.asFloat ?: 128f,
					scanInterval = obj.get("scanInterval")?.asInt ?: SCAN_INTERVAL,
				)
			}
		}.getOrElse {
			AstrorbitsParticle.logger.warn("[config] failed to load scenes: {}", it.message)
			emptyList()
		}
		AstrorbitsParticle.logger.info("[config] loaded {} scenes", scenes.size)
	}

	private fun loadDefinitions(dir: Path) {
		Files.createDirectories(dir)
		if (Files.list(dir).use { it.findAny().isEmpty }) {
			writeExampleDefinitions(dir)
		}
		definitions.clear()
		loadDatapackDefinitions()
		Files.list(dir).use { stream ->
			stream.filter { it.fileName.toString().endsWith(".json") }.forEach { path ->
				val name = path.fileName.toString().removeSuffix(".json")
				runCatching {
					val definition = EffectDefinition.parse(Files.readString(path)).getOrThrow()
					val id = Identifier.fromNamespaceAndPath(AstrorbitsParticle.MOD_ID, name)
					definitions[id] = definition
					AstrorbitsParticle.logger.info(
						"[config] loaded effect {} ({})", id, ParticleStyles.get(definition.style)?.id ?: "unknown",
					)
				}.onFailure {
					AstrorbitsParticle.logger.warn("[config] rejected effect {}: {}", name, it.message)
				}
			}
		}
	}

	/** data/&lt;namespace&gt;/effects/&lt;name&gt;.json，config 目录的同 id 文件覆盖数据包版本。 */
	private fun loadDatapackDefinitions() {
		val manager = server?.resourceManager ?: return
		val resources = runCatching {
			manager.listResources("effects") { path -> path.path.endsWith(".json") }
		}.getOrElse {
			AstrorbitsParticle.logger.warn("[config] failed to list datapack effects: {}", it.message)
			return
		}
		for ((fileId, resource) in resources) {
			val name = fileId.path.removePrefix("effects/").removeSuffix(".json")
			val id = Identifier.fromNamespaceAndPath(fileId.namespace, name)
			runCatching {
				val json = resource.open().use { it.readBytes().decodeToString() }
				val definition = EffectDefinition.parse(json).getOrThrow()
				definitions[id] = definition
				AstrorbitsParticle.logger.info("[config] loaded datapack effect {}", id)
			}.onFailure {
				AstrorbitsParticle.logger.warn("[config] rejected datapack effect {}: {}", id, it.message)
			}
		}
	}

	private fun writeExampleDefinitions(dir: Path) {
		Files.writeString(dir.resolve("radial_burst.json"), EXAMPLE_RADIAL)
		Files.writeString(dir.resolve("spiral_helix.json"), EXAMPLE_SPIRAL)
		Files.writeString(dir.resolve("rain.json"), EXAMPLE_RAIN)
	}

	private fun JsonObject.vec3(key: String): Vec3? {
		val array = getAsJsonArray(key) ?: return null
		if (array.size() < 3) return null
		return Vec3(array[0].asDouble, array[1].asDouble, array[2].asDouble)
	}

	private const val DEFAULT_SCENES = """{
  "scenes": [
    {
      "id": "astrorbitsparticle:main_stage",
      "dimension": "minecraft:overworld",
      "min": [-48, 56, -48],
      "max": [48, 144, 48],
      "viewDistance": 128.0,
      "scanInterval": 10
    }
  ]
}
"""

	private const val EXAMPLE_RADIAL = """{
  "style": "astrorbitsparticle:radial_burst",
  "duration": 2.4,
  "emission": {"count": 600, "lifetime": 1.8, "lifetimeJitter": 0.15},
  "physics": {"gravity": 0.0},
  "appearance": {
    "sprite": "minecraft:glitter_0",
    "size": 0.28,
    "color": "#FFD36E",
    "alphaByAge": [[0, 1.0], [0.6, 0.9], [1, 0.0]],
    "stretch": 0.02
  },
  "visibility": {"viewDistance": 120.0},
  "params": {"speed": 9.0, "speedJitter": 0.15, "cone": 180.0}
}
"""

	private const val EXAMPLE_SPIRAL = """{
  "style": "astrorbitsparticle:spiral",
  "duration": 6.0,
  "emission": {"count": 1200, "lifetime": 5.0, "lifetimeJitter": 0.06},
  "physics": {"gravity": 0.0},
  "appearance": {
    "sprite": "minecraft:end_rod",
    "size": 0.16,
    "color": "#8AF0FF",
    "alphaByAge": [[0, 0.0], [0.08, 1.0], [0.75, 0.9], [1, 0.0]]
  },
  "params": {
    "axis": [0, 1, 0],
    "radius": 1.8,
    "angularSpeed": 170.0,
    "axialSpeed": 1.1,
    "strands": 2,
    "phase": 0.0,
    "phaseJitter": 2.0,
    "scatter": 0.15,
    "scatterGrowth": 1.5
  }
}
"""

	private const val EXAMPLE_RAIN = """{
  "style": "astrorbitsparticle:rain",
  "duration": 900.0,
  "loop": true,
  "emission": {"count": 1200, "lifetime": 900.0, "lifetimeJitter": 0.0},
  "physics": {"gravity": 0.0},
  "appearance": {
    "sprite": "minecraft:splash_0",
    "size": 0.14,
    "color": "#8FB8FF",
    "alphaByAge": [[0, 0.0], [0.05, 0.55], [0.95, 0.55], [1, 0.0]],
    "stretch": 0.7,
    "blend": "alpha"
  },
  "params": {
    "direction": [0.12, -1, 0.0],
    "speedRange": [14.0, 22.0],
    "area": [26, 18, 26],
    "streakLength": 0.7,
    "trailCount": 0
  }
}
"""
}
