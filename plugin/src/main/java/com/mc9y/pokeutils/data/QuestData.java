package com.mc9y.pokeutils.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 一条每日委托的实例（持久化在 SQLite 的 {@code pu_quest} 表）。
 *
 * <p><b>为什么生成必须是确定性的</b>：委托是「当天固定」的。如果用随机数生成，
 * 那么每次打开界面都得重新掷一次骰子 —— 除非把结果存下来。这里用
 * {@code (UUID, 日期)} 组合出一个固定种子，于是「同一个人在同一天生成的委托」永远相同，
 * 既不需要在玩家进服的瞬间就写库，也不存在「点开界面三次刷新出三套不同委托」的问题。</p>
 */
public class QuestData {

    private final int slot;
    private final String type;
    private final int target;
    private final int star;
    private int progress;
    private boolean claimed;

    public QuestData(int slot, String type, int target, int star, int progress, boolean claimed) {
        this.slot = slot;
        this.type = type;
        this.target = target;
        this.star = star;
        this.progress = progress;
        this.claimed = claimed;
    }

    /** 该委托在当天的序号（0 起）。 */
    public int getSlot() {
        return this.slot;
    }

    /** 类型名，取值见 {@link QuestType}。 */
    public String getType() {
        return this.type;
    }

    /** 需要的次数（SUBMIT 类固定为 1）。 */
    public int getTarget() {
        return this.target;
    }

    /** SUBMIT 类要求的星级下限。 */
    public int getStar() {
        return this.star;
    }

    public int getProgress() {
        return this.progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public boolean isClaimed() {
        return this.claimed;
    }

    public void setClaimed(boolean claimed) {
        this.claimed = claimed;
    }

    /** 进度是否已达标（达标即可领取）。 */
    public boolean isComplete() {
        return this.progress >= this.target;
    }

    /** 是否已完成但尚未领取 —— 界面上会高亮显示。 */
    public boolean isClaimable() {
        return isComplete() && !this.claimed;
    }

    /**
     * 按类型配置与种子生成当天的委托列表。
     *
     * @param uuid   玩家 UUID（参与种子，保证每人不同）
     * @param day    日期标识（如 {@code 2026-10-13}）
     * @param types  已启用且 weight &gt; 0 的类型定义
     * @param amount 每天生成几条
     * @return 生成的委托（保持 slot 0..amount-1 的顺序）
     */
    public static List<QuestData> generate(UUID uuid, String day, List<QuestType> types, int amount) {
        List<QuestData> out = new ArrayList<QuestData>();
        if (types == null || types.isEmpty() || amount <= 0) {
            return out;
        }
        // 种子刻意只依赖 (uuid, day)：同一人同一天必定得到同一组委托，与调用时机无关。
        long seed = (uuid == null ? 0L : uuid.getMostSignificantBits() * 31L + uuid.getLeastSignificantBits())
                * 1000003L + (day == null ? 0 : day.hashCode());
        Random random = new Random(seed);

        int totalWeight = 0;
        for (QuestType type : types) {
            if (type.getWeight() > 0) {
                totalWeight += type.getWeight();
            }
        }
        if (totalWeight <= 0) {
            return out;
        }

        for (int slot = 0; slot < amount; slot++) {
            QuestType picked = pickWeighted(types, totalWeight, random);
            if (picked == null) {
                break;
            }
            List<Integer> targets = picked.getTargets();
            int target = 1;
            if (targets != null && !targets.isEmpty()) {
                target = targets.get(random.nextInt(targets.size()));
            }
            if (target < 1) {
                target = 1;
            }
            if (picked.isSubmit()) {
                // 交付类一定是「一次交付」，目标次数固定 1 —— 否则会被理解成「交 N 只」，
                // 与 SUBMIT 的语义（一只满足星级的精灵）不符。
                target = 1;
            }
            out.add(new QuestData(slot, picked.getKey(), target, picked.getStar(), 0, false));
        }
        return out;
    }

    private static QuestType pickWeighted(List<QuestType> types, int totalWeight, Random random) {
        int roll = random.nextInt(totalWeight);
        for (QuestType type : types) {
            if (type.getWeight() <= 0) {
                continue;
            }
            roll -= type.getWeight();
            if (roll < 0) {
                return type;
            }
        }
        // 理论上到不了这里；真到了就退化取第一个可用类型，不返回 null 造成空列表。
        for (QuestType type : types) {
            if (type.getWeight() > 0) {
                return type;
            }
        }
        return null;
    }
}
