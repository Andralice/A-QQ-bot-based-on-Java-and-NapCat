package com.start.handler;

import static com.start.handler.DailyProfessionHandler.drawForUser;
import static com.start.handler.DailyProfessionHandler.getCombatPower;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.DatabaseConfig;
import com.start.repository.GroupMessageStatsRepository;
import com.start.repository.UserAliasRepository;
import com.start.util.LuckUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class RankHandler implements MessageHandler {
    private static final Logger logger = LoggerFactory.getLogger(RankHandler.class);
    private static final UserAliasRepository aliasRepo = new UserAliasRepository();
    private static final int TOP_N = 15;

    private static final Set<String> TRIGGERS = Set.of(
            "发言排行", "发言榜", "水群排行", "水群榜",
            "今日发言", "今日排行", "今天发言",
            "本周发言", "本周排行", "这周发言",
            "幸运排行", "幸运榜", "运势排行", "运势榜",
            "好感排行", "好感榜", "好感度排行", "好感度榜",
            "群cp", "CP排行", "谁最配", "谁和谁最配", "社交关系",
            "职业排行", "职业榜", "战力排行", "战力榜",
            "群排行", "排行榜", "有什么榜", "榜单", "排名"
    );

    @Override
    public boolean match(JsonNode msg) {
        if (!"group".equals(msg.path("message_type").asText())) return false;
        String text = msg.path("raw_message").asText().trim();
        for (String t : TRIGGERS) if (text.contains(t)) return true;
        return false;
    }

    @Override
    public void handle(JsonNode msg, Main bot) {
        String raw = msg.path("raw_message").asText().trim();
        long groupId = msg.path("group_id").asLong();
        String gid = String.valueOf(groupId);

        String feature = "群排行";
        if (raw.contains("发言") || raw.contains("水群")) {
            String period = "total";
            if (raw.contains("今日") || raw.contains("今天")) { period = "today"; feature = "今日发言"; }
            else if (raw.contains("本周") || raw.contains("这周")) { period = "week"; feature = "本周发言"; }
            else feature = "发言排行";
            bot.sendGroupReply(groupId, buildMessageRank(gid, period));
        } else if (raw.contains("幸运") || raw.contains("运势")) {
            feature = "幸运排行";
            bot.sendGroupReply(groupId, buildLuckRank(gid));
        } else if (raw.contains("好感")) {
            feature = "好感排行";
            bot.sendGroupReply(groupId, buildAffinityRank(gid));
        } else if (raw.contains("职业") || raw.contains("战力")) {
            feature = "职业排行";
            bot.sendGroupReply(groupId, buildProfessionRank(gid));
        } else if (raw.contains("cp") || raw.contains("CP") || raw.contains("配") || raw.contains("社交")) {
            feature = "群CP";
            bot.sendGroupReply(groupId, buildCPRank(gid));
        } else if (raw.contains("榜") || raw.contains("排名")) {
            feature = "排行榜帮助";
            bot.sendGroupReply(groupId, buildHelp());
        } else {
            bot.sendGroupReply(groupId, buildMessageRank(gid, "total"));
        }
        // 记录到 AI 上下文
        bot.getBaiLianService().recordBotAction(gid, String.valueOf(msg.path("user_id").asLong()),
                msg.path("sender").path("nickname").asText(""), feature, "");
    }

    // ==== 静态方法供 RankTool 调用 ====

    public static String buildMessageRankStatic(String groupId) { return buildMessageRank(groupId, "total"); }
    public static String buildLuckRankStatic(String groupId) { return buildLuckRank(groupId); }
    public static String buildAffinityRankStatic(String groupId) { return buildAffinityRank(groupId); }

    // ==== 内部实现 ====

    private String buildProfessionRank(String groupId) {
        Map<String, ProfessionScore> map = new LinkedHashMap<>();
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT user_id FROM group_message_stats WHERE group_id=?")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String uid = rs.getString("user_id");
                    try {
                        long id = Long.parseLong(uid);
                        var p = drawForUser(id);
                        int power = getCombatPower(id);
                        map.put(uid, new ProfessionScore(p.name, p.rarity, p.tier, power));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) { logger.error("职业排行查询失败", e); }
        if (map.isEmpty()) return "暂无本群活跃数据~";

        int showN = Math.min(TOP_N, map.size());
        StringBuilder sb = new StringBuilder("⚔️ 今日职业战力排行 TOP").append(showN).append("：\n");
        int[] idx = {0};
        String[] medals = {"🥇","🥈","🥉","4","5","6","7","8","9","10","11","12","13","14","15"};
        map.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().power, a.getValue().power))
                .limit(showN)
                .forEach(e -> {
                    var ps = e.getValue();
                    sb.append(idx[0] <= 2 ? medals[idx[0]] : medals[idx[0]]+" ")
                      .append(displayName(e.getKey(), groupId))
                      .append(": 【").append(ps.rarity).append("】").append(ps.name)
                      .append("(").append(ps.tier).append("阶) 战力:").append(ps.power).append("\n");
                    idx[0]++;
                });
        return sb.toString();
    }

    private record ProfessionScore(String name, String rarity, int tier, int power) {}

    private String buildHelp() {
        return "📊 可用排行榜：\n" +
               "💬 发言榜 — 说\"发言排行\"/\"今日发言\"/\"本周发言\"\n" +
               "🍀 幸运榜 — 说\"幸运排行\"\n" +
               "💕 好感榜 — 说\"好感排行\"\n" +
               "💑 群CP — 说\"群CP\"查看谁和谁最配\n" +
               "⚔️ 职业排行 — 说\"职业排行\"查看今日职业战力榜";
    }

    private String buildCPRank(String groupId) {
        var pairs = CPTracker.getTopPairs(groupId, 10);
        if (pairs.isEmpty()) return "💑 暂无CP数据，多@互动几次就有了~";
        StringBuilder sb = new StringBuilder("💑 群CP热度 TOP10：\n");
        int i = 1;
        for (var p : pairs) {
            String nameA = displayName(p.userA(), groupId);
            String nameB = displayName(p.userB(), groupId);
            sb.append(i).append(". ").append(nameA).append(" ❤️ ").append(nameB)
              .append("（互动").append(p.count()).append("次）\n");
            i++;
        }
        return sb.toString();
    }

    private static Map<String, String> memberNickCache = Collections.emptyMap();
    private static long memberNickCacheTime = 0;

    private static String displayName(String userId, String groupId) {
        var alias = aliasRepo.getBestAlias(userId, groupId);
        if (alias.isPresent()) return alias.get();
        // 群成员昵称缓存
        if (memberNickCache.containsKey(userId)) return memberNickCache.get(userId);
        // 数据库昵称
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT nickname FROM users WHERE user_id=? LIMIT 1")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String nick = rs.getString("nickname");
                    if (nick != null && !nick.isEmpty() && !"未知用户".equals(nick)) return nick;
                }
            }
        } catch (Exception ignored) {}
        return userId;
    }

    /** 刷新群成员昵称缓存并更新数据库 */
    public static void refreshMemberNicks(Main bot, long groupId) {
        if (System.currentTimeMillis() - memberNickCacheTime < 300_000) return;
        try {
            var map = bot.getOneBotWsService().getGroupMemberDisplayNames(groupId);
            if (map != null && !map.isEmpty()) {
                memberNickCache = map;
                memberNickCacheTime = System.currentTimeMillis();
                // 同步写到数据库，解决昵称缺失问题
                try (Connection c = DatabaseConfig.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                             "INSERT INTO users (user_id, nickname) VALUES (?, ?) ON DUPLICATE KEY UPDATE nickname = VALUES(nickname)")) {
                    for (var e : map.entrySet()) {
                        if (e.getValue() == null || e.getValue().isEmpty() || e.getValue().equals(e.getKey())) continue;
                        ps.setString(1, e.getKey());
                        ps.setString(2, e.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) { logger.debug("刷新群成员昵称失败: {}", e.getMessage()); }
    }

    private static String buildMessageRank(String groupId, String period) {
        String label = GroupMessageStatsRepository.getPeriodLabel(period);
        var list = GroupMessageStatsRepository.getMessageRank(groupId, period);
        if (list.isEmpty()) return "💬 " + label + "暂无发言数据~";
        int showN = Math.min(TOP_N, list.size());
        StringBuilder sb = new StringBuilder("💬 ").append(label).append("发言排行 TOP").append(showN).append("：\n");
        int i = 1;
        String[] medals = {"🥇","🥈","🥉","4","5","6","7","8","9","10","11","12","13","14","15"};
        for (var e : list) {
            if (i > showN) break;
            sb.append(i <= 3 ? medals[i-1] : medals[i-1]+" ").append(displayName(e.getKey(), groupId))
              .append(": ").append(e.getValue()).append("条\n");
            i++;
        }
        return sb.toString();
    }

    private static String buildLuckRank(String groupId) {
        Map<String, Integer> luckMap = new LinkedHashMap<>();
        List<String> userIds = new ArrayList<>();
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT DISTINCT user_id FROM group_message_stats WHERE group_id=?")) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) userIds.add(rs.getString("user_id"));
            }
        } catch (Exception e) { logger.error("幸运排行查询失败", e); }

        for (String uid : userIds) {
            try { luckMap.put(uid, LuckUtil.getDailyLuck(Long.parseLong(uid))); }
            catch (NumberFormatException ignored) {}
        }
        if (luckMap.isEmpty()) return "🍀 暂无本群活跃数据~";

        int showN = Math.min(TOP_N, luckMap.size());
        StringBuilder sb = new StringBuilder("🍀 今日幸运排行 TOP").append(showN).append("：\n");
        int[] idx = {0};
        String[] medals = {"🥇","🥈","🥉","4","5","6","7","8","9","10","11","12","13","14","15"};
        luckMap.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(showN)
                .forEach(e -> {
                    sb.append(idx[0] <= 2 ? medals[idx[0]] : medals[idx[0]]+" ")
                      .append(displayName(e.getKey(), groupId))
                      .append(": ").append(e.getValue()).append("分\n");
                    idx[0]++;
                });
        return sb.toString();
    }

    private static String buildAffinityRank(String groupId) {
        List<String[]> rows = new ArrayList<>();
        try (Connection c = DatabaseConfig.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT user_id, affinity_score FROM user_affinity WHERE group_id=? ORDER BY affinity_score DESC LIMIT " + TOP_N)) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{rs.getString("user_id"), String.valueOf(rs.getInt("affinity_score"))});
                }
            }
        } catch (Exception e) { logger.error("好感度排行查询失败", e); return "好感度排行查询失败~"; }
        if (rows.isEmpty()) return "暂无好感度数据~";
        int showN = rows.size();
        StringBuilder sb = new StringBuilder("💕 好感度排行 TOP").append(showN).append("：\n");
        int i = 1;
        String[] medals = {"🥇","🥈","🥉","4","5","6","7","8","9","10","11","12","13","14","15"};
        for (String[] row : rows) {
            if (i > showN) break;
            sb.append(i <= 3 ? medals[i-1] : medals[i-1]+" ").append(displayName(row[0], groupId))
              .append(": ").append(row[1]).append("分\n");
            i++;
        }
        return sb.toString();
    }
}
