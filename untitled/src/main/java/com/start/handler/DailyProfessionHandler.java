package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
 // 请根据实际包路径调整
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 抽职业
 */
public class DailyProfessionHandler implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(DailyProfessionHandler.class);

    private static final Set<String> TRIGGERS = Set.of(
            "今日职业", "抽职业", "我的职业", "今日命格", "抽命格", "抽取"
    );

    private static final List<String> PROFESSIONS = Arrays.asList(
            // 西幻/奇幻
            "龙裔骑士", "暗影刺客", "元素法师", "圣光牧师", "亡灵术士",
            "矮人铁匠", "精灵游侠", "兽人狂战士", "半身人盗贼", "吸血鬼伯爵",
            "龙语学者", "时空旅者", "混沌术士", "符文工匠", "梦境编织者",

            // 仙侠/东方玄幻
            "剑仙", "丹修", "符箓师", "御兽宗师", "阵法师",
            "魔道巨擘", "佛门罗汉", "散修真人", "天机阁主", "九幽冥使",
            "雷法天师", "蛊毒圣手", "琴心剑魄", "阴阳判官", "星宿使者",

            // 搞笑/混搭
            "摸鱼宗掌门", "躺平真人", "社畜剑圣", "996符咒师", "咸鱼龙骑士",
            "外卖御剑使", "PPT炼器师", "会议遁甲士"
    );

    // 缓存结构：key = "groupId:userId", value = ProfessionRecord{date, profession}
    private final Map<String, ProfessionRecord> userProfessionCache = new ConcurrentHashMap<>();

    @Override
    public boolean match(JsonNode message) {
        if (!"group".equals(message.path("message_type").asText())) {
            return false;
        }
        String rawMsg = message.path("raw_message").asText().trim();

        // 精确匹配：消息必须完全等于某个 trigger（忽略首尾空格）
        return TRIGGERS.contains(rawMsg);
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        String groupId = message.get("group_id").asText();
        long userId = message.get("user_id").asLong();
        String cacheKey = groupId + ":" + userId;
        String today = LocalDate.now().toString();

        ProfessionRecord record = userProfessionCache.get(cacheKey);

        String profession;
        if (record != null && today.equals(record.date)) {
            // 今天已经抽过，返回旧职业
            profession = record.profession;
        } else {
            // 首次抽取：随机选一个
            profession = PROFESSIONS.get(new Random().nextInt(PROFESSIONS.size()));
            // 更新缓存
            userProfessionCache.put(cacheKey, new ProfessionRecord(today, profession));
        }

        // 构造回复
        String reply = String.format(
                "✨ %s，你今天的天命职业是：\n「%s」\n%s",
                getAt(userId),
                profession,
                getRandomSuffix()
        );
        bot.sendGroupReply(Long.parseLong(groupId), reply);

        logger.info("👤 群 {} 用户 {} 查看今日职业: {}", groupId, userId, profession);
    }

    private String getAt(long userId) {
        return "[CQ:at,qq=" + userId + "]";
    }

    private String getRandomSuffix() {
        String[] suffixes = {
                "此乃天机，不可泄露！",
                "命运齿轮已转动！",
                "切记：莫要逆天而行！",
                "今日宜装逼，忌加班。",
                "小心隔壁的魔道巨擘盯上你！",
                "你的命格，非同凡响！"
        };
        return suffixes[new Random().nextInt(suffixes.length)];
    }

    // 内部记录类
    private static class ProfessionRecord {
        final String date;        // 格式：2025-04-05
        final String profession;

        ProfessionRecord(String date, String profession) {
            this.date = date;
            this.profession = profession;
        }
    }
}