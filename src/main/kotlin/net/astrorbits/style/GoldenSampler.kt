package net.astrorbits.style

import net.astrorbits.server.TestPresets
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale

/**
 * 黄金采样：按固定种子与年龄采样 9 个测试预设，输出可与基线逐字节比较的文本。
 * 用法：GoldenSampler <基线目录> [--write]
 */
object GoldenSampler {
	private val AGES = floatArrayOf(0f, 0.5f, 1f, 2f)
	private const val SEED = 12345L

	@JvmStatic
	fun main(args: Array<String>) {
		require(args.isNotEmpty()) { "usage: GoldenSampler <dir> [--write]" }
		BuiltinStyles.registerAll()
		val dir = Paths.get(args[0])
		val write = args.contains("--write")
		Files.createDirectories(dir)
		var failures = 0
		for (name in TestPresets.names().sorted()) {
			val definition = TestPresets.resolve(name)
				?: error("unknown preset: $name")
			val bound = EffectBinder.bind(definition).getOrThrow()
			val text = render(bound)
			val file = dir.resolve("$name.txt")
			when {
				write -> {
					Files.writeString(file, text)
					println("[golden] wrote $file")
				}
				!Files.exists(file) -> {
					println("[golden] missing $file")
					failures++
				}
				Files.readString(file) != text -> {
					println("[golden] mismatch $name")
					failures++
				}
			}
		}
		check(failures == 0) { "golden mismatch: $failures style(s)" }
		println("[golden] ok: ${TestPresets.names().size} styles")
	}

	private fun render(bound: BoundEffect): String = buildString {
		val buffer = ParticleBuffer()
		for (age in AGES) {
			buffer.reset(bound.count)
			bound.style.sample(bound, Vec3.ZERO, SEED, age, buffer)
			appendLine("age=$age count=${buffer.count}")
			for (i in 0 until buffer.count) {
				appendLine(
					"${fmt(buffer.x[i])},${fmt(buffer.y[i])},${fmt(buffer.z[i])}," +
						"${fmt(buffer.size[i])},${Integer.toHexString(buffer.argb[i])}",
				)
			}
		}
	}

	private fun fmt(value: Float): String = String.format(Locale.ROOT, "%.5f", value)
}