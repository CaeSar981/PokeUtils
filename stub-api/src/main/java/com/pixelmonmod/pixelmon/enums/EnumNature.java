package com.pixelmonmod.pixelmon.enums;

/**
 * 编译桩：性格枚举（Hardy / Adamant / Timid … 共 25 种）。
 *
 * <p><b>签名来源</b>：真实类位于 {@code com.pixelmonmod.pixelmon.enums.EnumNature}，
 * {@code javap} 显示它是 {@code public final class EnumNature extends java.lang.Enum<EnumNature>}
 * —— 所以这里声明成 {@code enum} 是等价的（javac 会生成同样的 final class + Enum 父类）。</p>
 *
 * <p><b>刻意不声明任何常量</b>：桩里写死常量反而会在「真实枚举多一个/少一个」时
 * 编译得过、运行期错位。插件对性格只做两件事 —— 读取父本的 {@code getNature()}，
 * 以及用 {@code EnumNature.valueOf(String)} 还原名字 —— 两者都不依赖具体常量表。
 * 显示名走 {@code PokemonAttr#natureName(String)} 的中文映射（找不到就回落到枚举名）。</p>
 */
public enum EnumNature {
}
