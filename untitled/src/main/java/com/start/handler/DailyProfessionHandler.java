package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.vision.ImageRenderer;
import com.start.vision.ProfessionCardTemplate;
import com.start.vision.ProfessionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽职业（图片渲染版 - 位阶系统）
 */
public class DailyProfessionHandler implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(DailyProfessionHandler.class);

    private static final Set<String> TRIGGERS = Set.of(
            "今日职业", "抽职业", "我的职业", "今日命格", "抽命格", "抽取"
    );

    // 职业体系：每个职业有多个位阶，位阶越高越稀有
    private static final List<ProfessionEntry> PROFESSIONS = Arrays.asList(
            // ===== 剑修一脉 =====
            new ProfessionEntry("见习剑客", 1, "普通", "初握剑柄，剑道漫漫，还需勤学苦练", 100, 300),
            new ProfessionEntry("御剑游侠", 2, "普通", "能御剑飞行，行走江湖，行侠仗义", 300, 800),
            new ProfessionEntry("剑心通明", 3, "稀有", "剑心澄澈，万物皆可为剑，一剑破万法", 800, 2000),
            new ProfessionEntry("剑道宗师", 4, "史诗", "开宗立派，剑道巅峰，一剑可断山河", 2000, 5000),
            new ProfessionEntry("万剑归宗", 5, "传说", "万剑臣服，剑道至尊，一念之间剑开天门", 5000, 10000),

            // ===== 法神一脉 =====
            new ProfessionEntry("魔法学徒", 1, "普通", "初识元素，连火球术都未必能施展", 100, 300),
            new ProfessionEntry("元素使", 2, "普通", "掌握四大元素，可召唤风雨雷电", 300, 800),
            new ProfessionEntry("大魔导师", 3, "稀有", "魔力浩瀚，能施展禁咒级魔法", 800, 2000),
            new ProfessionEntry("法神", 4, "史诗", "元素之主，一念可改写天地法则", 2000, 5000),
            new ProfessionEntry("时空掌控者", 5, "传说", "超越时间空间，过去未来尽在掌控", 5000, 10000),

            // ===== 刺客一脉 =====
            new ProfessionEntry("潜行者", 1, "普通", "擅长隐匿，但还不够致命", 100, 300),
            new ProfessionEntry("暗影猎手", 2, "普通", "如影随形，一击必杀的冷酷杀手", 300, 800),
            new ProfessionEntry("夜刃大师", 3, "稀有", "黑夜即是主场，刀光过处无声无息", 800, 2000),
            new ProfessionEntry("影舞者", 4, "史诗", "在刀尖上起舞，以暗影编织死亡之舞", 2000, 5000),
            new ProfessionEntry("死亡领主", 5, "传说", "执掌生死，暗影中的至高主宰", 5000, 10000),

            // ===== 丹道一脉 =====
            new ProfessionEntry("采药童子", 1, "普通", "背着竹篓，在山间辨认各种灵草", 100, 300),
            new ProfessionEntry("炼丹师", 2, "普通", "能炼制基础丹药，救死扶伤", 300, 800),
            new ProfessionEntry("丹道大师", 3, "稀有", "丹火纯青，可炼九转金丹", 800, 2000),
            new ProfessionEntry("丹圣", 4, "史诗", "一粒丹成，可起死回生，造化无穷", 2000, 5000),
            new ProfessionEntry("造化丹尊", 5, "传说", "以天地为炉，炼万灵为丹，造化苍生", 5000, 10000),

            // ===== 御兽一脉 =====
            new ProfessionEntry("驯兽学徒", 1, "普通", "只能驯服鸡鸭鹅等普通禽兽", 100, 300),
            new ProfessionEntry("御兽师", 2, "普通", "能与灵兽沟通，驾驭猛兽作战", 300, 800),
            new ProfessionEntry("万兽之主", 3, "稀有", "万兽臣服，一声兽吼震天动地", 800, 2000),
            new ProfessionEntry("龙骑统帅", 4, "史诗", "驾驭上古巨龙，俯瞰天下", 2000, 5000),
            new ProfessionEntry("太古兽神", 5, "传说", "血脉返祖，化身太古凶兽，吞噬天地", 5000, 10000),

            // ===== 佛修一脉 =====
            new ProfessionEntry("沙弥", 1, "普通", "青灯古佛，诵读经文，感悟佛法", 100, 300),
            new ProfessionEntry("苦行僧", 2, "普通", "以苦为乐，金身不灭，物理免疫", 300, 800),
            new ProfessionEntry("罗汉", 3, "稀有", "十八罗汉转世，降妖伏魔", 800, 2000),
            new ProfessionEntry("菩萨", 4, "史诗", "慈悲为怀，普度众生，佛法无边", 2000, 5000),
            new ProfessionEntry("佛祖", 5, "传说", "如来神掌，五指山下定乾坤", 5000, 10000),

            // ===== 符箓一脉 =====
            new ProfessionEntry("画符小童", 1, "普通", "握着毛笔，照着师傅的样子画符", 100, 300),
            new ProfessionEntry("符箓师", 2, "普通", "能绘制基础符箓，驱邪镇鬼", 300, 800),
            new ProfessionEntry("天符大师", 3, "稀有", "笔落惊风雨，符成泣鬼神", 800, 2000),
            new ProfessionEntry("符道圣手", 4, "史诗", "虚空画符，天地共鸣，万法不侵", 2000, 5000),
            new ProfessionEntry("道祖", 5, "传说", "太上忘情，道法自然，一言可定天地规则", 5000, 10000),

            // ===== 搞笑一脉 =====
            new ProfessionEntry("摸鱼学徒", 1, "普通", "上班时间偷偷刷手机，技术有待提高", 100, 300),
            new ProfessionEntry("躺平真人", 2, "普通", "只要我躺得够快，压力就追不上我", 300, 800),
            new ProfessionEntry("社畜剑圣", 3, "稀有", "白天敲代码，晚上练剑法，时间管理大师", 800, 2000),
            new ProfessionEntry("996符咒师", 4, "史诗", "用加班怨气驱动符箓，威力无穷", 2000, 5000),
            new ProfessionEntry("带薪修仙者", 5, "传说", "上班时间偷偷修炼，下班直接飞升", 5000, 10000)
    );

    private final Map<String, ProfessionRecord> userProfessionCache = new ConcurrentHashMap<>();
    private final ImageRenderer renderer = ImageRenderer.getInstance();

    @Override
    public boolean match(JsonNode message) {
        if (!"group".equals(message.path("message_type").asText())) return false;
        String rawMsg = message.path("raw_message").asText().trim();
        return TRIGGERS.contains(rawMsg);
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        String groupId = message.get("group_id").asText();
        long userId = message.get("user_id").asLong();
        String cacheKey = groupId + ":" + userId;
        String today = LocalDate.now().toString();

        ProfessionRecord record = userProfessionCache.get(cacheKey);
        ProfessionEntry selected;
        int combatPower;

        if (record != null && today.equals(record.date)) {
            // 今天已经抽过，返回旧职业
            selected = record.professionEntry;
            combatPower = record.combatPower;
        } else {
            // 首次抽取：根据稀有度权重抽取
            selected = drawByWeightedRandom();
            combatPower = calculateCombatPower(selected);
            userProfessionCache.put(cacheKey, new ProfessionRecord(today, selected, combatPower));
        }

        // 准备数据并渲染
        ProfessionData data = new ProfessionData(
                String.valueOf(userId),
                selected.name,
                selected.tier,
                getTierName(selected.tier),
                selected.description,
                selected.rarity,
                combatPower
        );
        
        String base64 = renderer.renderToBase64(new ProfessionCardTemplate(), data);

        if (base64 != null) {
            bot.sendGroupReply(Long.parseLong(groupId), "[CQ:image,file=base64://" + base64 + "]");
        } else {
            // 降级处理
            bot.sendGroupReply(Long.parseLong(groupId), 
                    "✨ 你的天命职业是：【" + selected.rarity + "】" + 
                    selected.name + "（" + getTierName(selected.tier) + "）战力值：" + combatPower);
        }

        logger.info("👤 群 {} 用户 {} 抽到职业: {} {}阶 [{}] 战力:{}", 
                groupId, userId, selected.name, selected.tier, selected.rarity, combatPower);
    }

    /**
     * 计算战力值（在同阶范围内随机）
     */
    private int calculateCombatPower(ProfessionEntry entry) {
        Random rand = new Random();
        return entry.minPower + rand.nextInt(entry.maxPower - entry.minPower + 1);
    }

    /**
     * 根据稀有度权重随机抽取（高阶更难抽）
     */
    private ProfessionEntry drawByWeightedRandom() {
        Random rand = new Random();
        double roll = rand.nextDouble() * 100;

        // 权重分配：传说 3%, 史诗 10%, 稀有 27%, 普通 60%
        double cumulative = 0;
        cumulative += 3;  if (roll < cumulative) return drawByTier(5); // 传说
        cumulative += 10; if (roll < cumulative) return drawByTier(4); // 史诗
        cumulative += 27; if (roll < cumulative) return drawByTier(3); // 稀有
        cumulative += 60; return drawByTier(rand.nextInt(2) + 1);     // 普通（1-2阶）
    }

    /**
     * 从指定位阶中随机抽取
     */
    private ProfessionEntry drawByTier(int targetTier) {
        List<ProfessionEntry> tierProfessions = PROFESSIONS.stream()
                .filter(p -> p.tier == targetTier)
                .toList();
        if (tierProfessions.isEmpty()) {
            return PROFESSIONS.get(0); // fallback
        }
        return tierProfessions.get(new Random().nextInt(tierProfessions.size()));
    }

    /**
     * 位阶名称转换
     */
    private String getTierName(int tier) {
        return switch (tier) {
            case 1 -> "一阶·初窥门径";
            case 2 -> "二阶·登堂入室";
            case 3 -> "三阶·融会贯通";
            case 4 -> "四阶·炉火纯青";
            case 5 -> "五阶·登峰造极";
            default -> "未知位阶";
        };
    }

    private static class ProfessionRecord {
        final String date;
        final ProfessionEntry professionEntry;
        final int combatPower;

        ProfessionRecord(String date, ProfessionEntry professionEntry, int combatPower) {
            this.date = date;
            this.professionEntry = professionEntry;
            this.combatPower = combatPower;
        }
    }

    /**
     * 职业条目内部类
     */
    private static class ProfessionEntry {
        final String name;
        final int tier;
        final String rarity;
        final String description;
        final int minPower;  // 最低战力
        final int maxPower;  // 最高战力

        ProfessionEntry(String name, int tier, String rarity, String description, int minPower, int maxPower) {
            this.name = name;
            this.tier = tier;
            this.rarity = rarity;
            this.description = description;
            this.minPower = minPower;
            this.maxPower = maxPower;
        }
    }
}