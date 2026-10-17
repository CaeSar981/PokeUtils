package com.mc9y.pokeutils.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一种「每日委托」类型的定义（来自 config.yml 的 {@code Compound.quest.types}）。
 *
 * <p>五种类型的含义：</p>
 * <ul>
 *   <li>{@code SUCCESS} —— 成功合成 N 次。最直观的正反馈。</li>
 *   <li>{@code ATTEMPT} —— 进行 N 次合成（不论成败）。刻意与 SUCCESS 并存：
 *       高星档的成功率只有 20%，若只认成功，一条「成功 5 次」的委托可能几天都做不完。</li>
 *   <li>{@code SALVAGE} —— 分解回收 N 只精灵。给「分解」这个新功能一个每日出口。</li>
 *   <li>{@code STICKER} —— 合成 N 次粘剂。同理，让粘剂合成链每天都有人用。</li>
 *   <li>{@code SUBMIT} —— 交付一只 N 星及以上的精灵。这是唯一「主动交东西」的类型，
 *       也是唯一有星级门槛的：它把玩家手里多余的中间产物变成了日常目标。</li>
 * </ul>
 *
 * <p>{@code weight} 为 0 或负数即代表「不生成这类委托」，管理员因此可以只留自己喜欢的玩法。</p>
 */
public class QuestType {

    public static final String SUCCESS = "SUCCESS";
    public static final String ATTEMPT = "ATTEMPT";
    public static final String SALVAGE = "SALVAGE";
    public static final String STICKER = "STICKER";
    public static final String SUBMIT = "SUBMIT";

    /** 全部类型名，顺序固定（命令行补全与自检都按这个顺序）。 */
    public static final List<String> ALL = Arrays.asList(SUCCESS, ATTEMPT, SALVAGE, STICKER, SUBMIT);

    private final String key;
    private final int weight;
    private final List<Integer> targets;
    private final int star;
    private final String display;

    public QuestType(String key, int weight, List<Integer> targets, int star, String display) {
        this.key = key;
        this.weight = weight;
        this.targets = targets == null ? new ArrayList<Integer>() : targets;
        this.star = star;
        this.display = display;
    }

    /** 类型名（{@code SUCCESS} / {@code SUBMIT} …）。 */
    public String getKey() {
        return this.key;
    }

    /** 生成权重。<= 0 表示该类不参与生成。 */
    public int getWeight() {
        return this.weight;
    }

    /** 可选的目标次数档次（随机取一个）。为空时回落到 {@code {1}}。 */
    public List<Integer> getTargets() {
        return this.targets;
    }

    /** SUBMIT 类要求的星级下限（其它类型恒为 0）。 */
    public int getStar() {
        return this.star;
    }

    /** 委托文案模板，占位符 {@code {target}} 与 {@code {star}}。 */
    public String getDisplay() {
        return this.display;
    }

    /** 展开占位符后的完整文案。 */
    public String format(int target) {
        return (this.display == null ? this.key : this.display)
                .replace("{target}", String.valueOf(target))
                .replace("{star}", String.valueOf(this.star));
    }

    /** 该类型是否要求玩家实际交付一只精灵（需要额外弹一次精灵选择界面）。 */
    public boolean isSubmit() {
        return SUBMIT.equals(this.key);
    }
}
