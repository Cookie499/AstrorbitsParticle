package com.example.astrorbitsparticle.style

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object StyleDocGenerator {
	@JvmStatic
	fun main(args: Array<String>) {
		require(args.isNotEmpty()) { "usage: StyleDocGenerator <output.md>" }
		BuiltinStyles.registerAll()
		val output = Paths.get(args[0])
		output.parent?.let { Files.createDirectories(it) }
		Files.writeString(output, StyleDocs.renderAll())
		println("[docs] wrote ${Path.of(args[0]).toAbsolutePath()}")
	}
}