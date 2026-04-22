package com.start.vision;

public class ProfessionData {
    public String userId;
    public String professionName;    // 职业名称
    public int tier;                  // 位阶（1-5）
    public String tierName;           // 位阶名称
    public String description;        // 职业介绍
    public String rarity;             // 稀有度
    public int combatPower;           // 战力值

    public ProfessionData(String userId, String professionName, int tier,
                          String tierName, String description, String rarity, int combatPower) {
        this.userId = userId;
        this.professionName = professionName;
        this.tier = tier;
        this.tierName = tierName;
        this.description = description;
        this.rarity = rarity;
        this.combatPower = combatPower;
    }
}
