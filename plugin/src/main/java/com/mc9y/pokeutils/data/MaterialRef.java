package com.mc9y.pokeutils.data;

/**
 * 「这次合成用的材料从哪里取」的引用。
 *
 * <p><b>为什么需要它</b>：原实现用两个 {@code int} 表示材料（{@code slot1} / {@code slot2}），
 * 语义是<b>队伍下标</b>（0~5，-1 表示未选）。这与「材料只能从队伍里出」是同一件事的两种说法 ——
 * 一旦要支持从电脑盒子取料，下标就不够用了：队伍下标 3 和「箱子 2 的第 3 格」是两只不同的精灵。</p>
 *
 * <p>于是把「材料」抽成一个值对象，两种来源各有自己的坐标。这样消耗、解析、重复判定
 * 都能写在同一个地方（{@link MaterialAccess}），而不是在每个调用点各写一遍 if。</p>
 *
 * <p><b>不可变</b>：所有字段 final，没有 setter。会话里换材料是<b>换引用</b>，
 * 不是改引用内部的坐标 —— 后者会让「同一个引用被两个槽位共享」这类问题变得难以发现。</p>
 */
public final class MaterialRef {

    /** 材料来源。 */
    public enum Source {
        /** 玩家队伍（6 格）。 */
        PARTY,
        /** 电脑盒子（多箱 × 每箱若干格）。 */
        PC
    }

    private final Source source;
    /** 电脑盒子来源时的箱子下标（0 基）；队伍来源时无意义，固定 -1。 */
    private final int box;
    /** 队伍来源时是队伍下标（0~5）；电脑来源时是箱子内的槽位下标。 */
    private final int slot;
    /**
     * 选中这只材料时它自己的 UUID。
     *
     * <p>坐标只说明「在哪一格」，不说明「是哪一只」。玩家选完材料、还没点确认的这段时间里，
     * 那一格的内容可能被换掉（自己拖队伍、从盒子取精灵补空位、其他插件把打工的精灵还回队伍），
     * 而坐标还指在原处 —— 此时按坐标消耗，吃掉的就不是玩家选的那只。</p>
     *
     * <p>{@code null} 表示无从校验（旧会话、或由不关心身份的调用方构造），此时一律放行。</p>
     */
    private final String uuid;

    private MaterialRef(Source source, int box, int slot, String uuid) {
        this.source = source;
        this.box = box;
        this.slot = slot;
        this.uuid = uuid;
    }

    /** 队伍里的第 {@code slot} 格。 */
    public static MaterialRef ofParty(int slot) {
        return new MaterialRef(Source.PARTY, -1, slot, null);
    }

    /** 队伍里的第 {@code slot} 格，并记下选中时那只精灵的 UUID。 */
    public static MaterialRef ofParty(int slot, String uuid) {
        return new MaterialRef(Source.PARTY, -1, slot, uuid);
    }

    /** 电脑盒子里第 {@code box} 箱的第 {@code slot} 格。 */
    public static MaterialRef ofPc(int box, int slot) {
        return new MaterialRef(Source.PC, box, slot, null);
    }

    /** 电脑盒子里第 {@code box} 箱的第 {@code slot} 格，并记下选中时那只精灵的 UUID。 */
    public static MaterialRef ofPc(int box, int slot, String uuid) {
        return new MaterialRef(Source.PC, box, slot, uuid);
    }

    /** 选中时那只精灵的 UUID；{@code null} 表示未记录、不参与校验。 */
    public String getUuid() {
        return this.uuid;
    }

    public Source getSource() {
        return this.source;
    }

    public boolean isParty() {
        return this.source == Source.PARTY;
    }

    public boolean isPc() {
        return this.source == Source.PC;
    }

    /** 电脑盒子来源时的箱子下标。注意队伍来源返回 -1，别拿它当坐标用。 */
    public int getBox() {
        return this.box;
    }

    public int getSlot() {
        return this.slot;
    }

    /**
     * 队伍下标投影：队伍来源时返回队伍下标，其它情况返回 -1。
     *
     * <p>给「只关心队伍那一条路径」的既有消费方用 —— {@code /pu debug} 的展示口径、
     * 以及测试替身里按名字反射读会话的那几个断言。它们此前拿到的就是队伍下标，
     * 这里保持同一个口径，避免为了一个新功能去改一堆无关的断言。</p>
     */
    public int toPartyIndex() {
        return this.isParty() ? this.slot : -1;
    }

    /**
     * 人类可读描述。
     *
     * <p>队伍来源<b>刻意只写数字</b>（与原展示口径逐字一致，测试里断言的是 {@code "0/1"}）；
     * 电脑来源写「箱子N·槽M」，箱子与槽位都按 1 基显示 —— 玩家在界面上看到的是 1 基。</p>
     */
    public String describe() {
        if (this.isParty()) {
            return String.valueOf(this.slot);
        }
        return "箱子" + (this.box + 1) + "·槽" + (this.slot + 1);
    }

    /** 两个引用是否指向同一只（含都是 null 的情况）。 */
    public static boolean same(MaterialRef a, MaterialRef b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    /**
     * 相等性<b>只看坐标，不看 {@link #uuid}</b>，这是刻意的。
     *
     * <p>本方法被 {@link #same} 用于「该精灵已在其他槽位选择」的重复判定 ——
     * 判的是「同一格」。如果把 uuid 算进来，那么「同一格在两次操作之间换了精灵」
     * 就会被当成两个不同的材料引用，重复判定失效，玩家可以把同一格选两次。</p>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MaterialRef)) {
            return false;
        }
        MaterialRef other = (MaterialRef) o;
        return this.source == other.source && this.box == other.box && this.slot == other.slot;
    }

    @Override
    public int hashCode() {
        return (this.source.ordinal() * 31 + this.box) * 31 + this.slot;
    }

    @Override
    public String toString() {
        return this.source + "(" + this.describe() + ")";
    }
}
