package com.cfop.lab.model

import kotlin.random.Random

enum class CfopStage(val label: String) {
    CROSS("Cross"),
    F2L("F2L"),
    OLL("OLL"),
    PLL("PLL")
}

data class FormulaItem(
    val id: String,
    val stage: CfopStage,
    val category: String,
    val code: String,
    val name: String,
    val alg: String,
    val is2Look: Boolean,
    val tips: String
)

object FormulaRepository {

    val allFormulas: List<FormulaItem> = listOf(
        // Cross (3)
        FormulaItem("cross-1", CfopStage.CROSS, "基础", "Cross 01", "棱块色相翻转归位", "F' U' R U", true, "白蓝棱在正面中层色相错误时，F' 提顶再用 U' R U 顺势入底。"),
        FormulaItem("cross-2", CfopStage.CROSS, "基础", "Cross 02", "底层错位双棱联动", "R2 U' F2 D'", true, "两颗底棱位置互换时，R2 提一颗避开，F2 归位另一颗，最后 D' 整体对齐。"),
        FormulaItem("cross-3", CfopStage.CROSS, "基础", "Cross 03", "顶层异位借道插入", "U R' F R", true, "无需整体转体，U 调整后 R' 接住再 F 归位，R 还原原底棱。"),

        // F2L (16)
        FormulaItem("f2l-1", CfopStage.F2L, "标准标态", "F2L 01", "异色分离（右入）", "U R U' R'", true, "【核心标态】角块白朝右，U 藏角、R 提槽、U' 配对、R' 入槽。"),
        FormulaItem("f2l-2", CfopStage.F2L, "标准标态", "F2L 02", "异色分离（前入）", "U' F' U F", true, "【镜像标态】角块白朝前，直接前槽配对入槽。"),
        FormulaItem("f2l-3", CfopStage.F2L, "标准标态", "F2L 03", "同色相连（前入）", "F' U' F", true, "角棱已在顶层贴紧且同色，白朝前直接开门送入关门。"),
        FormulaItem("f2l-4", CfopStage.F2L, "标准标态", "F2L 04", "同色相连（右入）", "R U R'", true, "最顺手的三步标态公式：R U R' 直接入槽。"),
        FormulaItem("f2l-5", CfopStage.F2L, "角块在顶", "F2L 05", "顶层同色分离（白朝右）", "U' R U R' U2 R U' R'", false, "先 U' 藏角，将棱块转到对面配成标态后再入槽。"),
        FormulaItem("f2l-6", CfopStage.F2L, "角块在顶", "F2L 06", "顶层同色分离（白朝前）", "U F' U' F U2 F' U F", false, "F2L 05 的前侧镜像。"),
        FormulaItem("f2l-7", CfopStage.F2L, "角块在顶", "F2L 07", "顶层同色远距（白朝右）", "U' R U2 R' U2 R U' R'", false, "U' R U2 R' 藏角拉棱形成标态，再 U2 R U' R' 入槽。"),
        FormulaItem("f2l-8", CfopStage.F2L, "角块在顶", "F2L 08", "顶层白朝上（异色相连）", "R U2 R' U' R U R'", true, "【高频技巧】先用 R U2 R' 拆开翻白到侧面，立刻化为标态。"),
        FormulaItem("f2l-9", CfopStage.F2L, "角块在顶", "F2L 09", "顶层白朝上（同色远距）", "U R U2 R' U R U' R'", false, "棱块对齐侧心后用 R U2 R' 翻角成标态。"),
        FormulaItem("f2l-10", CfopStage.F2L, "角块在顶", "F2L 10", "顶层错色贴紧（白朝右）", "R U' R' U R U' R' U2 R U' R'", false, "先拆开错色贴紧对，再转成标准异色入槽。"),
        FormulaItem("f2l-11", CfopStage.F2L, "棱块在顶", "F2L 11", "角在底槽白朝下，棱在顶", "U R U' R' U' F' U F", true, "经典顶层送棱公式，先提角配对再从前侧入槽。"),
        FormulaItem("f2l-12", CfopStage.F2L, "棱块在顶", "F2L 12", "角在底槽白朝下（镜像送棱）", "U' F' U F U R U' R'", true, "F2L 11 镜像版本，棱块在 UL 位起手。"),
        FormulaItem("f2l-13", CfopStage.F2L, "棱块在顶", "F2L 13", "角在底槽白朝右，棱在顶", "R U' R' U R U' R'", false, "R U' R' 提出角块的同时与顶棱完成配对。"),
        FormulaItem("f2l-14", CfopStage.F2L, "棱块在顶", "F2L 14", "角在底槽白朝前，棱在顶", "R U R' U' R U R'", false, "Sexy Move 连贯提出并直接入槽。"),
        FormulaItem("f2l-15", CfopStage.F2L, "角棱在底", "F2L 15", "角棱都在底槽（棱块翻转）", "R U' R' d R' U2 R U2 R' U R", false, "提出整组拆开，通过 d 换向重新配对入槽。"),
        FormulaItem("f2l-16", CfopStage.F2L, "角棱在底", "F2L 16", "角棱都在底槽（角歪棱反）", "R U R' U' R U2 R' U' R U R'", false, "双双卡在底槽时提至顶层分离再标态入槽。"),

        // OLL (29)
        FormulaItem("oll-21", CfopStage.OLL, "十字型", "OLL 21", "双头车 / 十字双对灯 (H)", "R U2 R' U' R U R' U' R U' R'", true, "【2-Look 必背】前后各有一对黄色车灯，前后放置车灯起手。"),
        FormulaItem("oll-22", CfopStage.OLL, "十字型", "OLL 22", "单侧车灯 / 十字单对灯 (Pi)", "R U2 R2 U' R2 U' R2 U2 R", true, "【2-Look 必背】左侧有一对黄色车灯，车灯放左侧起手。"),
        FormulaItem("oll-23", CfopStage.OLL, "十字型", "OLL 23", "小车灯 / 十字同侧双黄", "R2 D R' U2 R D' R' U2 R'", true, "【2-Look 必背】前排两角黄朝正前方，车灯朝前起手。"),
        FormulaItem("oll-24", CfopStage.OLL, "十字型", "OLL 24", "变色龙 / 十字异侧双黄", "r U R' U' r' F R F'", true, "【2-Look 必背】左排全黄放左侧，右两角背对背。"),
        FormulaItem("oll-25", CfopStage.OLL, "十字型", "OLL 25", "领结 / 十字对角双黄", "F' r U R' U' r' F R", true, "【2-Look 必背】顶面左上与右下为黄，前左黄朝前。"),
        FormulaItem("oll-26", CfopStage.OLL, "十字型", "OLL 26", "小鱼2 / 逆小鱼 (Antisune)", "R U2 R' U' R U' R'", true, "【2-Look 必背】鱼头放右后方，顺时针翻三角。"),
        FormulaItem("oll-27", CfopStage.OLL, "十字型", "OLL 27", "小鱼1 / 顺小鱼 (Sune)", "R U R' U R U2 R'", true, "【2-Look 必背】经典小鱼公式！鱼头放左前方，逆时针翻三角。"),
        FormulaItem("oll-2", CfopStage.OLL, "点型", "OLL 2", "点型翻棱 (2-Look 一步翻棱)", "F R U R' U' F' f R U R' U' f'", true, "【2-Look 必背】先 F(RUR'U')F' 出折角，再双层 f 出十字。"),
        FormulaItem("oll-45", CfopStage.OLL, "T型", "OLL 45", "T字型 / 一字横线翻棱", "F R U R' U' F'", true, "【2-Look 必背】横线水平放置，前压 F + R U R' U' + F'。"),
        FormulaItem("oll-44", CfopStage.OLL, "P型", "OLL 44", "小折角 / 9点钟翻棱", "f R U R' U' f'", true, "【2-Look 必背】左上折角双层 f 起手一步出十字。"),
        FormulaItem("oll-1", CfopStage.OLL, "点型", "OLL 1", "点型双侧大灯", "R U2 R2 F R F' U2 R' F R F'", false, "左右两侧各有三个黄色连排。"),
        FormulaItem("oll-3", CfopStage.OLL, "点型", "OLL 3", "点型单角顺时针", "f R U R' U' f' U' F R U R' U' F'", false, "顶面仅右下角与中心块为黄。"),
        FormulaItem("oll-4", CfopStage.OLL, "点型", "OLL 4", "点型单角逆时针", "f R U R' U' f' U F R U R' U' F'", false, "OLL 3 镜像，顶面右上角与中心为黄。"),
        FormulaItem("oll-33", CfopStage.OLL, "T型", "OLL 33", "标准小T字", "R U R' U' R' F R F'", false, "超顺手钥匙公式，Sexy Move 接锤子手。"),
        FormulaItem("oll-34", CfopStage.OLL, "C型", "OLL 34", "C字双灯", "R U R2 U' R' F R U R U' F'", false, "开口朝下的 C 字，前排两角黄朝前。"),
        FormulaItem("oll-46", CfopStage.OLL, "C型", "OLL 46", "右开口C字", "R' U' R' F R F' U R", false, "极简 8 步侧卧 C 字。"),
        FormulaItem("oll-47", CfopStage.OLL, "L型", "OLL 47", "小L双侧灯（左）", "F' L' U' L U L' U' L U F", false, "折角放右下，左侧三黄连排。"),
        FormulaItem("oll-48", CfopStage.OLL, "L型", "OLL 48", "小L双侧灯（右）", "F R U R' U' R U R' U' F'", false, "折角放左下，右侧三黄连排，两遍 Sexy Move。"),
        FormulaItem("oll-49", CfopStage.OLL, "L型", "OLL 49", "小L单灯（右）", "r U' r2 U r2 U r2 U' r", false, "全双层 r 连招，节奏感极强。"),
        FormulaItem("oll-50", CfopStage.OLL, "L型", "OLL 50", "小L单灯（左）", "r' U r2 U' r2 U' r2 U r'", false, "OLL 49 镜像公式。"),
        FormulaItem("oll-36", CfopStage.OLL, "W型", "OLL 36", "W字阶梯（左）", "L' U' L U' L' U L U L F' L' F", false, "斜向阶梯 W 形状。"),
        FormulaItem("oll-38", CfopStage.OLL, "W型", "OLL 38", "W字阶梯（右）", "R U R' U R U' R' U' R' F R F'", false, "全右手连招衔接锤子手。"),
        FormulaItem("oll-31", CfopStage.OLL, "P型", "OLL 31", "正P字（右侧竖条）", "R' U' F U R U' R' F' R", false, "右侧 2x3 形成字母 P。"),
        FormulaItem("oll-32", CfopStage.OLL, "P型", "OLL 32", "反P字（左侧竖条）", "S R U R' U' R' F R f'", false, "中层 S 起手极其流畅。"),
        FormulaItem("oll-43", CfopStage.OLL, "P型", "OLL 43", "反P字（前侧双灯）", "R' U' F' U F R", false, "短短 6 步瞬间翻黄。"),
        FormulaItem("oll-5", CfopStage.OLL, "田字闪电", "OLL 5", "左后田字格", "r' U2 R U R' U r", false, "左上 2x2 田字格，7 步完成。"),
        FormulaItem("oll-6", CfopStage.OLL, "田字闪电", "OLL 6", "左前田字格", "r U2 R' U' R U' r'", false, "左下 2x2 田字格，OLL 5 镜像。"),
        FormulaItem("oll-7", CfopStage.OLL, "田字闪电", "OLL 7", "宽闪电（左起手）", "r U R' U R U2 r'", false, "双层 r 版顺小鱼。"),
        FormulaItem("oll-8", CfopStage.OLL, "田字闪电", "OLL 8", "宽闪电（右起手）", "r' U' R U' R' U2 r", false, "双层 r' 版逆小鱼。"),

        // PLL (21)
        FormulaItem("pll-ua", CfopStage.PLL, "纯棱块", "Ua-Perm", "逆时针三棱换", "R U' R U R U R U' R' U' R2", true, "【2-Look 必背】完成面放背面，前三棱逆时针轮换。"),
        FormulaItem("pll-ub", CfopStage.PLL, "纯棱块", "Ub-Perm", "顺时针三棱换", "R2 U R U R' U' R' U' R' U R'", true, "【2-Look 必背】完成面放背面，前三棱顺时针轮换。"),
        FormulaItem("pll-h", CfopStage.PLL, "纯棱块", "H-Perm", "十字对棱互换", "M2 U M2 U2 M2 U M2", true, "【2-Look 必背】四角归位，前后与左右对棱互换。"),
        FormulaItem("pll-z", CfopStage.PLL, "纯棱块", "Z-Perm", "斜向邻棱互换", "M' U M2 U M2 U M' U2 M2", true, "【2-Look 必背】前右与后左邻棱互换。"),
        FormulaItem("pll-aa", CfopStage.PLL, "纯角块", "Aa-Perm", "顺时针三角换", "x R' U R' D2 R U' R' D2 R2 x'", false, "2x2 大块放左后，其余三角顺时针轮换。"),
        FormulaItem("pll-ab", CfopStage.PLL, "纯角块", "Ab-Perm", "逆时针三角换", "x R2 D2 R U R' D2 R U' R x'", false, "2x2 大块放右后，其余三角逆时针轮换。"),
        FormulaItem("pll-e", CfopStage.PLL, "纯角块", "E-Perm", "两组对角前后互换", "x' R U' R' D R U R' D' R U R' D R U' R' D' x", false, "四棱归位，前后两组角块各自互换。"),
        FormulaItem("pll-t", CfopStage.PLL, "邻角换", "T-Perm", "标准T字换（邻角+对棱）", "R U R' U' R' F R2 U' R' U' R U R' F'", true, "【2-Look 必背】车灯放左侧，右两角与左右棱互换。"),
        FormulaItem("pll-jb", CfopStage.PLL, "邻角换", "Jb-Perm", "右手J字换", "R U R' F' R U R' U' R' F R2 U' R'", false, "完成条放左侧，右侧角棱相邻互换。"),
        FormulaItem("pll-ja", CfopStage.PLL, "邻角换", "Ja-Perm", "左手J字换", "R' U L' U2 R U' R' U2 R L", false, "完成条放左侧，与 Jb 互为镜像。"),
        FormulaItem("pll-ra", CfopStage.PLL, "邻角换", "Ra-Perm", "R字换A", "R U' R' U' R U R D R' U' R D' R' U2 R'", false, "左侧车灯 + 左后 1x2 连块。"),
        FormulaItem("pll-rb", CfopStage.PLL, "邻角换", "Rb-Perm", "R字换B", "R' U2 R U2 R' F R U R' U' R' F' R2", false, "前排车灯 + 左前 1x2 连块。"),
        FormulaItem("pll-f", CfopStage.PLL, "邻角换", "F-Perm", "F字换", "R' U' F' R U R' U' R' F R2 U' R' U' R U R' U R", false, "左侧完整一条，R' U' F' 接 T-Perm。"),
        FormulaItem("pll-y", CfopStage.PLL, "对角换", "Y-Perm", "标准Y字换（对角+邻棱）", "F R U' R' U' R U R' F' R U R' U' R' F R F'", true, "【2-Look 必背】无车灯，左上 2x2 块，对角互换。"),
        FormulaItem("pll-v", CfopStage.PLL, "对角换", "V-Perm", "V字换", "R' U R' U' y R' F' R2 U' R' U R' F R F", false, "左前 2x2 块，对角 + 右后邻棱互换。"),
        FormulaItem("pll-na", CfopStage.PLL, "对角换", "Na-Perm", "N字换A", "R U R' U R U R' F' R U R' U' R' F R2 U' R' U2 R U' R'", false, "右上与左下错位双连条。"),
        FormulaItem("pll-nb", CfopStage.PLL, "对角换", "Nb-Perm", "N字换B", "R' U R U' R' F' U' F R U R' F R' F' R U' R", false, "左上与右下错位双连条。"),
        FormulaItem("pll-ga", CfopStage.PLL, "G-Perms", "Ga-Perm", "G字换A", "R2 U R' U R' U' R U' R2 U' D R' U R D'", false, "左灯 + 右前连块。"),
        FormulaItem("pll-gb", CfopStage.PLL, "G-Perms", "Gb-Perm", "G字换B", "R' U' R U D' R2 U R' U R U' R U' R2 D", false, "左灯 + 右后连块。"),
        FormulaItem("pll-gc", CfopStage.PLL, "G-Perms", "Gc-Perm", "G字换C", "R2 U' R U' R U R' U R2 U D' R U' R' D", false, "左灯 + 后左连块。"),
        FormulaItem("pll-gd", CfopStage.PLL, "G-Perms", "Gd-Perm", "G字换D", "R U R' U' D R2 U' R U' R' U R' U R2 D'", false, "左灯 + 前左连块。")
    )

    fun generateWcaScramble(length: Int = 20): List<String> {
        val faces = arrayOf("R", "L", "U", "D", "F", "B")
        val mods = arrayOf("", "'", "2")
        val axis = intArrayOf(0, 0, 1, 1, 2, 2)
        val out = mutableListOf<String>()
        var last = -1
        var prev2 = -1
        while (out.size < length) {
            val f = Random.nextInt(6)
            if (f == last) continue
            if (prev2 != -1 && axis[f] == axis[last] && axis[f] == axis[prev2]) continue
            out.add(faces[f] + mods[Random.nextInt(3)])
            prev2 = last
            last = f
        }
        return out
    }
}
