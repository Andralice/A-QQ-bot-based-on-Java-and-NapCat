package com.start.util;

import java.time.LocalDate;
import java.util.Random;

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
