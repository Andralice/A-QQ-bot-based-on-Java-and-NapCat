package com.start.util;

import java.time.LocalDate;
import java.util.Random;

/**
 * 幸运值工具类
 * <p>
 * 提供基于用户ID和日期的确定性随机幸运值生成服务。
 * 同一用户在同一日期内获取的幸运值是固定的，不同日期或不同用户则不同。
 * </p>
 *
 * @author Lingma
 * @version 1.0
 */
public class LuckUtil {

    /**
     * 根据用户ID和日期生成固定的幸运值（0~100）
     */
    public static int getDailyLuck(long userId) {
        String seedStr = userId + "-" + LocalDate.now();
        long seed = seedStr.hashCode(); // 确保每天不同，但同一天相同
        Random random = new Random(seed);
        return random.nextInt(101); // 0 ~ 100
    }
}
