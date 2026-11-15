package net.astrorbits.style

enum class ParamKind(val label: String) {
	NUMBER("数值"),
	INTEGER("整数"),
	BOOLEAN("布尔"),
	VECTOR("向量"),
	NUMBER_LIST("数值区间"),
	STRING_LIST("字符串列表"),
	COLOR("颜色"),
	TEXT("文本"),
}

sealed interface ParamDefault {
	@JvmInline value class Num(val value: Float) : ParamDefault
	@JvmInline value class Whole(val value: Int) : ParamDefault
	@JvmInline value class Flag(val value: Boolean) : ParamDefault
	data class Vec(val x: Float, val y: Float, val z: Float) : ParamDefault
	data class Numbers(val values: FloatArray) : ParamDefault
	data class Texts(val values: List<String>) : ParamDefault
	@JvmInline value class Text(val value: String) : ParamDefault
}

/**
 * 一个样式参数的声明：类型、默认值、范围与说明。绑定、校验和文档都以此为准。
 */
data class ParamSpec(
	val name: String,
	val kind: ParamKind,
	val default: ParamDefault,
	val doc: String,
	val range: String = "",
	val min: Float? = null,
	val max: Float? = null,
)

class StyleSchema(
	val style: String,
	val title: String,
	val common: Boolean = false,
	val params: List<ParamSpec>,
) {
	fun spec(name: String): ParamSpec? = params.firstOrNull { it.name == name }
}

fun numSpec(
	name: String,
	default: Float,
	doc: String,
	range: String = "",
	min: Float? = null,
	max: Float? = null,
) = ParamSpec(name, ParamKind.NUMBER, ParamDefault.Num(default), doc, range, min, max)

fun intSpec(name: String, default: Int, doc: String, range: String = "", min: Float? = null, max: Float? = null) =
	ParamSpec(name, ParamKind.INTEGER, ParamDefault.Whole(default), doc, range, min, max)

fun boolSpec(name: String, default: Boolean, doc: String) =
	ParamSpec(name, ParamKind.BOOLEAN, ParamDefault.Flag(default), doc)

fun vecSpec(name: String, x: Float, y: Float, z: Float, doc: String) =
	ParamSpec(name, ParamKind.VECTOR, ParamDefault.Vec(x, y, z), doc)

fun numbersSpec(name: String, values: FloatArray, doc: String) =
	ParamSpec(name, ParamKind.NUMBER_LIST, ParamDefault.Numbers(values), doc)

fun textsSpec(name: String, values: List<String>, doc: String) =
	ParamSpec(name, ParamKind.STRING_LIST, ParamDefault.Texts(values), doc)

fun colorSpec(name: String, doc: String) =
	ParamSpec(name, ParamKind.COLOR, ParamDefault.Num(0f), doc)

fun textSpec(name: String, default: String, doc: String) =
	ParamSpec(name, ParamKind.TEXT, ParamDefault.Text(default), doc)

/** 所有样式共用的顶层字段。 */
val COMMON_SCHEMA: StyleSchema = StyleSchema(
	style = "common",
	title = "通用字段",
	common = true,
	params = listOf(
		numSpec("duration", 4f, "特效总时长（秒），循环播放时为一个周期", "0.05..3600", 0.05f, 3600f),
		boolSpec("loop", false, "是否循环播放：每个周期重新发射，粒子按寿命自然结束"),
		textSpec("orientation", "world", "特效朝向：world 固定世界坐标；view 把局部 +Z 对齐触发时的视线方向"),
		boolSpec("reverse", false, "反向播放：按 duration - age 采样；与 loop 同时启用时先按 duration 取模"),
		textSpec("subEffect", "", "子特效定义 id，不写则不生成"),
		textSpec("subEffectTrigger", "end", "子特效触发方式：end 本体结束、impact 客户端粒子碰撞、particle_end 单个粒子消失"),
		textSpec("emission.mode", "burst", "发射方式：burst 同时出生、stream 在窗口内均匀出生、random 随机出生"),
		intSpec("emission.count", 200, "粒子总数", "1..8192", 1f, 8192f),
		numbersSpec("emission.window", floatArrayOf(0f, 0f), "发射窗口 [开始, 结束]（秒）"),
		numSpec("emission.jitter", 0f, "出生时间抖动（秒）", "0..10", 0f, 10f),
		numSpec("emission.lifetime", 4f, "单个粒子寿命（秒），缺省等于 duration；循环时决定回溯的周期数", "0.05..3600", 0.05f, 3600f),
		numSpec("emission.lifetimeJitter", 0.1f, "寿命随机抖动比例", "0..1", 0f, 1f),
		vecSpec("physics.gravity", 0f, -9.8f, 0f, "重力加速度（块/秒²），也可写成单个 Y 分量"),
		numSpec("physics.drag", 0f, "线性阻尼（1/秒）", "0..10", 0f, 10f),
		vecSpec("physics.wind", 0f, 0f, 0f, "风场速度（块/秒）"),
		textSpec("appearance.sprite", "minecraft:generic_0", "粒子贴图 id，也可以写原版粒子定义 id（如 end_rod）；原版定义有多张贴图时按下一项选择"),
		numSpec("appearance.spriteCycleTicks", 2f, "cycle 模式换图间隔（tick）", "1..200", 1f, 200f),
		textSpec("appearance.spriteVariation", "particle", "多贴图变体选择：particle 每颗粒子随机取一张，effect 整个特效按 seed 共用一张，cycle 每 2 tick 轮换"),
		numSpec("appearance.size", 0.2f, "面片边长（块）", "0..16", 0f, 16f),
		numSpec("appearance.sizeJitter", 0.15f, "尺寸随机抖动比例", "0..1", 0f, 1f),
		textSpec("appearance.sizeByAge", "[[0,1],[1,0]]", "尺寸随归一化年龄的曲线"),
		textSpec("appearance.color", "#FFFFFF", "基色，支持 #RRGGBB 或 [r,g,b]"),
		numSpec("appearance.colorJitter", 0f, "亮度随机抖动比例", "0..1", 0f, 1f),
		textSpec("appearance.colorByAge", "", "颜色随年龄的渐变，格式 [[u,\"#RRGGBB\"],...]"),
		textSpec("appearance.alphaByAge", "[[0,1],[0.7,1],[1,0]]", "不透明度随年龄的曲线"),
		textSpec("appearance.blend", "additive", "混合方式：additive 叠加，alpha 普通混合"),
		boolSpec("appearance.fullbright", true, "是否忽略世界光照"),
		numSpec("appearance.stretch", 0f, "沿速度方向拉伸的时长（秒），0 关闭；只对能给出速度的样式生效", "0..10"),
		numSpec("appearance.spin", 0f, "面片自转（度/秒）"),
		boolSpec("trail.enabled", true, "轨迹开关；写 trail 对象即启用，设为 false 可关闭"),
		textSpec("trail.mode", "time", "轨迹回溯方式：time 按固定时间间隔，distance 按每颗粒子沿路径走过的距离"),
		numSpec("trail.interval", 0.1f, "time 模式每段的回溯间隔（秒）", "0.01..10", 0.01f, 10f),
		numSpec("trail.distance", 0.5f, "distance 模式每段沿路径的距离（块）", "0.01..16", 0.01f, 16f),
		numSpec("trail.lag", 1f, "distance 模式最长回溯时间（秒），决定能追上多少段", "0.01..16", 0.01f, 16f),
		intSpec("trail.cap", 8, "最多几段（不含源粒子）；单特效轨迹总数另受渲染预算限制", "1..32", 1f, 32f),
		numSpec("trail.spread", 0f, "第 k 段相对源粒子的横向随机偏移半径（块），取 spread * k / cap", "0..8", 0f, 8f),
		numSpec("trail.sizeScale", 0.8f, "逐段尺寸倍率，第 k 段乘 sizeScale^k", "0..1", 0f, 1f),
		numSpec("trail.alphaScale", 0.7f, "逐段不透明度倍率，第 k 段乘 alphaScale^k", "0..1", 0f, 1f),
		numSpec("visibility.viewDistance", 96f, "客户端可见距离（块）", "8..512", 8f, 512f),
		boolSpec("visibility.requireScene", true, "是否必须位于演唱会场景内"),
		numSpec("visibility.boundsPadding", 1f, "包围球额外外扩（块），避免边缘被裁切", "0..64", 0f, 64f),
	)
)