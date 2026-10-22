package com.mc9y.pokeutils.util;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 文案读取入口。
 *
 * <h3>为什么需要它</h3>
 * <p>在此之前，界面与提示文案全部写死在 Java 里。改一个错别字、换一套配色、做一次
 * 节日活动，都得重新跑 {@code build.sh} 并重启服务端 —— 对运营来说是纯粹的阻塞。
 * 现在统一走 {@link Msg}，文案放在 {@code messages.yml}。</p>
 *
 * <h3>三级查找（顺序固定）</h3>
 * <ol>
 *   <li>数据目录的 {@code messages.yml} —— 服主可改的那一份；</li>
 *   <li>jar 内嵌的 {@code messages.yml} —— 出厂默认。升级插件后新增的 key 由它兜底，
 *       所以「换 jar 但沿用旧 messages.yml」不会因为缺键而显示成空白；</li>
 *   <li>都没有 → 返回 {@code §c<missing:key>}。<b>刻意显示出来而不是返回空串</b>：
 *       缺键导致的空白消息极难排查，一眼能看见的红字会立刻被人发现。</li>
 * </ol>
 *
 * <h3>占位符</h3>
 * <p>只支持具名形式 {@code {name}}，调用时按 {@code 名称, 值} 交替传入：
 * <pre>Msg.get("compound.confirm", "star", star1)</pre>
 * 替换在<b>上色之前</b>完成 —— 替换进去的颜色码（{@code &a}）也会生效。</p>
 */
public final class Msg {

    private static final String FILE_NAME = "messages.yml";
    private static final String MISSING_PREFIX = "§c<missing:";

    /** 数据目录里那一份（服主可改）。 */
    private static YamlConfiguration data;
    /** jar 里那一份（出厂默认；升级后新增 key 的兜底）。 */
    private static YamlConfiguration builtin;

    private Msg() {
    }

    /** 载入 / 重载文案。{@code /pu reload} 会再调一次。 */
    public static void load(JavaPlugin owner) {
        File file = new File(owner.getDataFolder(), FILE_NAME);
        if (!file.exists()) {
            // 首次运行释放一份可编辑的副本；之后不再覆盖，避免冲掉服主的修改。
            owner.saveResource(FILE_NAME, false);
        }
        data = YamlConfiguration.loadConfiguration(file);

        try (InputStream in = owner.getResource(FILE_NAME)) {
            if (in != null) {
                builtin = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // 拿不到内嵌副本只影响兜底，不影响已配置的 key。
            builtin = null;
        }
    }

    /** 取一条文案（已上色）。 */
    public static String get(String key) {
        return get(key, new String[0]);
    }

    /**
     * 取一条文案、替换具名占位符并上色。
     *
     * @param nameValuePairs {@code 名称, 值} 交替；长度为奇数时最后一个被忽略
     */
    public static String get(String key, Object... nameValuePairs) {
        String raw = raw(key);
        if (raw == null) {
            return MISSING_PREFIX + key + ">";
        }
        // 内置前缀先用 messages.yml 里的值展开，调用方不必每个消息都传一遍。
        // 只展开一次（不递归）：prefix 自己在文件里就是一段字面量，不该再套自己。
        raw = expandPrefixes(raw);
        for (int i = 0; i + 1 < nameValuePairs.length; i += 2) {
            Object name = nameValuePairs[i];
            if (name == null) {
                continue;
            }
            String value = nameValuePairs[i + 1] == null ? "" : String.valueOf(nameValuePairs[i + 1]);
            raw = raw.replace("{" + name + "}", value);
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** 取原文（未替换、未上色）；不存在返回 {@code null}。 */
    private static String raw(String key) {
        String value = data == null ? null : data.getString(key);
        if (value == null && builtin != null) {
            // 刻意不回写数据文件：写回会把服主删掉的 key 重新塞回去，
            // 之后他就分不清哪些是自己配的、哪些是插件补的。
            value = builtin.getString(key);
        }
        return value;
    }

    /**
     * 把 {@code {prefix}} / {@code {admin-prefix}} 展开成 messages.yml 里的值。
     *
     * <p>只做一层：{@code prefix} 本身在文件里就是一段字面量，再套一层只会把
     * 自己的值又拼一遍。顺手把这两个 key 从「调用方参数」里解放出来 ——
     * 否则每条消息都得写一遍 {@code "prefix", ...}，漏一条就会把占位符原样发给玩家。</p>
     */
    private static String expandPrefixes(String text) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        String main = raw("prefix");
        if (main != null) {
            text = text.replace("{prefix}", main);
        }
        String admin = raw("admin-prefix");
        if (admin != null) {
            text = text.replace("{admin-prefix}", admin);
        }
        return text;
    }

    /** 该 key 是否存在（含内嵌兜底）。供给自检 / 诊断用。 */
    public static boolean has(String key) {
        return raw(key) != null;
    }

    /** 内嵌清单快照（key → 原文），只读。 */
    public static Map<String, String> builtinKeys() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (builtin != null) {
            for (String key : builtin.getKeys(true)) {
                if (builtin.isString(key)) {
                    out.put(key, builtin.getString(key));
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 数据文件里缺失（靠内嵌兜底）的 key 数，供启动自检打印。 */
    public static int customMissingCount() {
        if (data == null) {
            return builtinKeys().size();
        }
        int missing = 0;
        for (String key : builtinKeys().keySet()) {
            if (data.getString(key) == null) {
                missing++;
            }
        }
        return missing;
    }

    /** 插件关闭 / 重载时清空引用，避免旧配置对象被继续持有。 */
    public static void invalidate() {
        data = null;
        builtin = null;
    }
}
