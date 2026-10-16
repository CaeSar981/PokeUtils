package com.mc9y.pokeutils.data;

import com.pixelmonmod.pixelmon.Pixelmon;
import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.api.storage.PCStorage;
import com.pixelmonmod.pixelmon.storage.PlayerPartyStorage;
import org.bukkit.entity.Player;

/**
 * 材料引用的解析层：把 {@link MaterialRef} 翻译成「哪只精灵」，以及「把它摘掉」。
 *
 * <p><b>存在的意义是「只有一个地方知道队伍和电脑盒子的区别」。</b>
 * 在此之前，队伍下标直接喂给 {@code PlayerPartyStorage#get/set}，调用点分散在
 * 界面渲染、成功率计算、消耗、（六星失败的）保留一只等四五处 —— 每加一种材料来源，
 * 这些地方都得各改一遍，漏一处就是「界面显示对了但消耗没发生」这类难查的问题。
 * 现在它们统一走 {@link #resolve} 与 {@link #consume}。</p>
 *
 * <p>⚠️ 本类的每个方法都返回「拿不到就是 null / false」而不抛异常：Pixelmon 的存储
 * 在玩家刚进服、或服务端刚启动时可能还没就绪，而这几个方法都会被界面渲染路径调到 ——
 * 那里抛出去就是一整条栈，玩家只看到界面打不开。</p>
 */
public final class MaterialAccess {

    private MaterialAccess() {
    }

    /** 电脑盒子存储；Pixelmon 未就绪或该玩家没有 PC 数据时返回 null。 */
    public static PCStorage pcStorage(Player p) {
        return Pixelmon.storageManager.getPCForPlayer(p.getUniqueId());
    }

    /**
     * 解析引用当前指向的那只精灵。
     *
     * <p>返回 null 的四种情况：引用为空（还没选）、存储没就绪、坐标越界、
     * 以及<b>那只精灵已经被移走</b>（界面开着时队伍/盒子被其他插件或指令改动）。
     * 最后一种是原插件抛空指针的地方，这里统一收敛成一个 null。</p>
     */
    public static Pokemon resolve(Player p, MaterialRef ref) {
        if (ref == null) {
            return null;
        }
        if (ref.isParty()) {
            PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
            return pps == null ? null : pps.get(ref.getSlot());
        }
        PCStorage pc = pcStorage(p);
        if (pc == null || ref.getBox() < 0 || ref.getBox() >= pc.getBoxCount()) {
            return null;
        }
        return pc.get(ref.getBox(), ref.getSlot());
    }

    /**
     * 把引用指向的精灵从它所在的位置摘掉（合成消耗、以及六星失败「保留一只」时丢掉另一只）。
     *
     * <p>注意语义是<b>摘除</b>而不是「置空一个槽位」：队伍走 {@code set(slot, null)}，
     * 电脑盒子走 {@code set(box, slot, null)} —— 电脑盒子必须带上箱子坐标，
     * 否则会摘到别的箱子里的同一格去。</p>
     *
     * @return 是否真的执行了摘除；存储没就绪或坐标越界时为 false
     */
    public static boolean consume(Player p, MaterialRef ref) {
        if (ref == null) {
            return false;
        }
        if (ref.isParty()) {
            PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
            if (pps == null) {
                return false;
            }
            pps.set(ref.getSlot(), null);
            return true;
        }
        PCStorage pc = pcStorage(p);
        if (pc == null || ref.getBox() < 0 || ref.getBox() >= pc.getBoxCount()) {
            return false;
        }
        pc.set(ref.getBox(), ref.getSlot(), null);
        return true;
    }

    /**
     * 队伍里非蛋精灵的数量 —— 「合成后队伍至少还要剩一只」那条判定用的口径。
     *
     * <p>与 {@link #partySize} 刻意不同：蛋不算数（它会孵出来，但此刻不能用），
     * 而原实现的这一处判定就是不数蛋的。</p>
     */
    public static int partyCount(Player p) {
        PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
        if (pps == null) {
            return 0;
        }
        int amount = 0;
        for (int i = 0; i < 6; i++) {
            Pokemon pokemon = pps.get(i);
            if (pokemon != null && !pokemon.isEgg()) {
                amount += 1;
            }
        }
        return amount;
    }

    /**
     * 队伍里<b>占着格子</b>的精灵数量（含蛋）—— 即原选料界面里的 {@code getTeam().size()}。
     *
     * <p>它与 {@link #partyCount} 是两个不同口径，别合并：选料界面那道「至少剩一只」用的是
     * 这个（不排蛋），而点确认时那道用的是 {@link #partyCount}（排蛋）。两者都是原实现行为，
     * 这里只做搬运，不做统一 —— 统一会悄悄改变已有的拒绝/放行边界。</p>
     */
    public static int partySize(Player p) {
        PlayerPartyStorage pps = Pixelmon.storageManager.getParty(p.getUniqueId());
        if (pps == null) {
            return 0;
        }
        return pps.getTeam().size();
    }

    /** 电脑盒子的箱子数量；Pixelmon 未就绪时返回 0。 */
    public static int boxCount(Player p) {
        PCStorage pc = pcStorage(p);
        return pc == null ? 0 : pc.getBoxCount();
    }

    /**
     * 每个箱子有多少格。
     *
     * <p><b>刻意在运行期推算</b>（{@code getAll().length / boxCount()}）而不是引用
     * {@code PCBox.POKEMON_PER_BOX} —— 那是个 {@code public static final int}，
     * javac 会把它的<b>值</b>内联进这里，而编译期看到的值来自我们的空壳类。
     * 只要空壳与真机不一致，就会静默按错误的格子数渲染。
     * 推不出来时回落到 30（Pixelmon 各版本的箱子容量都是 30）。</p>
     */
    public static int boxCapacity(Player p) {
        PCStorage pc = pcStorage(p);
        if (pc == null) {
            return 0;
        }
        int boxes = pc.getBoxCount();
        if (boxes <= 0) {
            return 0;
        }
        Pokemon[] all = pc.getAll();
        if (all == null || all.length == 0) {
            return 30;
        }
        int perBox = all.length / boxes;
        return perBox > 0 ? perBox : 30;
    }
}
