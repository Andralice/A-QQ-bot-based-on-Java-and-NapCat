package com.start.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 游戏状态管理。代码层跟踪，注入提示词，AI 不用靠记忆。
 * 注意：SpyGame/NumberGame 的字段由 BaiLianService.generate() 通过 AI 工具调用间接修改，
 * 同一群内由 GroupSerialExecutor 保证串行，此处方法加 synchronized 作为防御。
 */
public class GameStateService {
    private static final Logger logger = LoggerFactory.getLogger(GameStateService.class);

    public enum GameType { GUESS_NUMBER, IDIOM_CHAIN, SPY }
    public enum SpyPhase { REGISTERING, STARTED, VOTING }

    private final Map<String, SpyGame> spyGames = new ConcurrentHashMap<>();
    private final Map<String, NumberGame> numberGames = new ConcurrentHashMap<>();

    // ========== 谁是卧底 ==========

    public SpyGame getOrCreateSpy(String groupId) {
        return spyGames.computeIfAbsent(groupId, k -> new SpyGame());
    }

    public synchronized void endSpy(String groupId) {
        spyGames.remove(groupId);
        logger.info("游戏结束: group={}", groupId);
    }

    public static class SpyGame {
        public SpyPhase phase = SpyPhase.REGISTERING;
        public final Set<String> players = new LinkedHashSet<>();
        public final Map<String, String> words = new HashMap<>();
        public final Set<String> sentWords = new HashSet<>();
        public String spyUserId;
        public String civilianWord;
        public String spyWord;
        public final Set<String> alive = new HashSet<>();
        public int round = 0;
        public long lastActivity = System.currentTimeMillis();

        public synchronized String getDescription() {
            if (players.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\n【游戏进行中】");
            sb.append("\n阶段：").append(phase == SpyPhase.REGISTERING ? "报名中" : phase == SpyPhase.STARTED ? "游戏中" : "投票中");
            sb.append(" | 玩家(").append(players.size()).append(")：");
            for (String p : players) {
                sb.append(displayName(p)).append(sentWords.contains(p) ? "✓" : "?");
                if (!alive.contains(p)) sb.append("(已出局)");
                sb.append(" ");
            }
            if (spyUserId != null) {
                sb.append("\n卧底:").append(displayName(spyUserId)).append(" 平民词:").append(civilianWord).append(" 卧底词:").append(spyWord);
            }
            if (!alive.isEmpty() && !alive.equals(players)) {
                sb.append("\n存活:").append(alive.stream().map(this::displayName).collect(java.util.stream.Collectors.joining(" ")));
            }
            return sb.toString();
        }

        private String displayName(String uid) {
            var alias = new com.start.repository.UserAliasRepository().getBestAlias(uid, "0");
            return alias.orElse(uid);
        }
    }

    // ========== 猜数字 ==========

    public NumberGame getOrCreateNumber(String groupId) {
        return numberGames.computeIfAbsent(groupId, k -> new NumberGame());
    }

    public synchronized void endNumber(String groupId) {
        numberGames.remove(groupId);
    }

    public static class NumberGame {
        public int target = -1;
        public int min = 1, max = 100;
        public long lastGuess = System.currentTimeMillis();

        public synchronized String getDescription() {
            if (target < 0) return "";
            return "\n【猜数字进行中】范围：" + min + "-" + max + "，答案：" + target;
        }
    }
}
