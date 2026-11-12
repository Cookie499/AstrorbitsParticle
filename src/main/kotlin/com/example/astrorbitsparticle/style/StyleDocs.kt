package com.example.astrorbitsparticle.style

/**
 * 从样式声明生成参数文档，命令与 Gradle 任务共用同一份渲染逻辑。
 */
object StyleDocs {
	fun renderAll(): String = buildString {
		appendLine("# 样式参数")
		appendLine()
		appendLine("> 本文由 `gradlew generateStyleDocs` 从代码里的样式声明生成，改样式时重新生成，不要手改。")
		appendLine()
		appendTable(COMMON_SCHEMA)
		for (style in ParticleStyles.all()) {
			appendLine()
			appendTable(style.schema)
		}
	}

	fun renderStyle(style: ParticleStyle): String = buildString {
		appendTable(style.schema)
	}

	fun renderStyleLines(style: ParticleStyle): List<String> = style.schema.params.map(::lineOf)

	fun renderCommonLines(): List<String> = COMMON_SCHEMA.params.map(::lineOf)

	private fun lineOf(spec: ParamSpec): String =
		"${spec.name} (${spec.kind.label}) 默认=${formatDefault(spec.default)}" +
			(if (spec.range.isNotEmpty()) " 范围=${spec.range}" else "") +
			"  ${spec.doc}"

	private fun StringBuilder.appendTable(schema: StyleSchema) {
		appendLine("## ${schema.style} ${schema.title}")
		appendLine()
		appendLine("| 参数 | 类型 | 默认 | 范围 | 说明 |")
		appendLine("| --- | --- | --- | --- | --- |")
		for (spec in schema.params) {
			appendLine(
				"| `${spec.name}` | ${spec.kind.label} | ${formatDefault(spec.default)} | " +
					"${spec.range.ifEmpty { "-" }} | ${spec.doc} |",
			)
		}
	}

	private fun formatDefault(default: ParamDefault): String = when (default) {
		is ParamDefault.Num -> trimNumber(default.value)
		is ParamDefault.Whole -> default.value.toString()
		is ParamDefault.Flag -> default.value.toString()
		is ParamDefault.Vec ->
			"[${trimNumber(default.x)}, ${trimNumber(default.y)}, ${trimNumber(default.z)}]"
		is ParamDefault.Numbers -> default.values.joinToString(", ", "[", "]") { trimNumber(it) }
		is ParamDefault.Texts -> default.values.joinToString(", ")
		is ParamDefault.Text -> default.value.ifEmpty { "-" }
	}

	private fun trimNumber(value: Float): String =
		if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
}