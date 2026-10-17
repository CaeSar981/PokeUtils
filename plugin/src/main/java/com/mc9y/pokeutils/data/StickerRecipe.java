package com.mc9y.pokeutils.data;

/**
 * 一条「粘剂合成」配方（来自 config.yml 的 {@code Compound.sticker-craft.recipes}）。
 *
 * <p>含义：消耗 {@code from} 粘剂 {@link #getAmount()} 个 + 金币 {@link #getMoney()}，
 * 得到 {@code to} 粘剂 {@link #getResult()} 个。</p>
 *
 * <p>存在的意义是给粘剂自己一条产出路径 —— 在此之前，低阶粘剂只会越积越多
 * （失败补偿、分母奖励都在发它），而高阶粘剂除管理员手动发放之外没有任何来源。</p>
 */
public class StickerRecipe {

    private final String from;
    private final String to;
    private final int amount;
    private final int result;
    private final int money;

    public StickerRecipe(String from, String to, int amount, int result, int money) {
        this.from = from;
        this.to = to;
        this.amount = amount;
        this.result = result;
        this.money = money;
    }

    /** 消耗的粘剂键名。 */
    public String getFrom() {
        return this.from;
    }

    /** 产出的粘剂键名。 */
    public String getTo() {
        return this.to;
    }

    /** 需要消耗多少个 {@code from}。 */
    public int getAmount() {
        return this.amount;
    }

    /** 能产出多少个 {@code to}。 */
    public int getResult() {
        return this.result;
    }

    /** 额外消耗的金币。 */
    public int getMoney() {
        return this.money;
    }

    /** 该配方是否可用（键名缺失或数量非法时视为不可用，避免出现「零成本凭空造粘剂」的配方）。 */
    public boolean isValid() {
        return this.from != null && !this.from.isEmpty()
                && this.to != null && !this.to.isEmpty()
                && this.amount > 0 && this.result > 0 && this.money >= 0;
    }
}
