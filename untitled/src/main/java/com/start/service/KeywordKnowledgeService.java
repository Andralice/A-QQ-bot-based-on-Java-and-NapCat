package com.start.service;

// 导入 HanLP 自然语言处理库的相关类
import com.hankcs.hanlp.HanLP;
import com.hankcs.hanlp.seg.common.Term;

// 导入 HikariCP 数据库连接池
import com.zaxxer.hikari.HikariDataSource;
import lombok.Getter;
import lombok.Setter;

// 标准 Java SQL 和集合工具类
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 关键词匹配知识库管理器
 *
 * 功能：从数据库加载问答知识，通过关键词提取与匹配，为用户问题返回最相关的答案。
 * 特点：支持缓存、停用词过滤、优先级加权、命中日志记录等。
 */
public class KeywordKnowledgeService { // ✅ 修正1: 类名应为 KeywordKnowledgeService

    // 数据库连接池（由外部传入）
    private final HikariDataSource dataSource;

    // 缓存系统：
    // keywordCache: 每个关键词 -> 包含该关键词的所有知识条目（用于快速检索）
    private final Map<String, List<KnowledgeItem>> keywordCache;
    // fullCache: 所有活跃知识条目的完整列表（用于兜底或高优先级补充）
    private final List<KnowledgeItem> fullCache;
    // 停用词集合（中英文常见无意义词汇）
    private final Set<String> stopWords;

    // Getter 和 Setter：允许动态调整配置
    // 配置参数
    @Setter
    @Getter
    private double similarityThreshold = 0.6; // 相似度阈值：低于此值不返回结果
    @Setter
    @Getter
    private int maxResults = 3;               // 最大返回结果数（当前未完全使用）
    @Setter
    @Getter
    private boolean enableCache = true;       // 是否启用关键词缓存加速匹配

    /**
     * 构造函数：初始化管理器并加载知识库
     */
    public KeywordKnowledgeService(HikariDataSource dataSource) { // ✅ 修正2: 构造函数名必须与类名一致
        this.dataSource = dataSource;
        this.keywordCache = new ConcurrentHashMap<>(); // 线程安全的缓存
        this.fullCache = new ArrayList<>();
        this.stopWords = initStopWords(); // 初始化停用词表

        // 启动时从数据库加载所有活跃知识条目
        reloadKnowledgeBase();

        System.out.println("关键词知识管理器初始化完成，共加载 " + fullCache.size() + " 条知识");
    }

    /**
     * 内部类：表示一条知识条目
     * ✅ 修正3: 改为非静态内部类，以便访问外部类的 stopWords 字段
     */
    private class KnowledgeItem {
        long id;                     // 数据库主键ID
        List<String> patterns;       // 问题模式列表（多个问题模板，用 | 分隔）
        String answer;               // 对应的答案模板
        int priority;                // 优先级（数值越大越优先）
        Set<String> keywords;        // 从 pattern 和 answer 中提取的关键词集合
        String category;             // 所属分类（如“客服”、“技术”等）

        /**
         * 构造函数：解析数据库字段并构建知识条目
         */
        KnowledgeItem(long id, String patternStr, String answer, int priority, String category) {
            this.id = id;
            this.answer = answer;
            this.priority = priority;
            this.category = category;

            // 解析问题模式字符串（按 | 分割）
            this.patterns = new ArrayList<>();
            if (patternStr != null && !patternStr.trim().isEmpty()) {
                String[] patternArray = patternStr.split("\\|");
                for (String pattern : patternArray) {
                    this.patterns.add(pattern.trim());
                }
            }

            // 从 pattern + answer 中提取关键词
            this.keywords = extractKeywords(patternStr + " " + answer);
        }

        /**
         * 计算当前知识条目与用户问题的匹配分数（0～2分）
         * @param question 用户输入的问题
         * @param questionKeywords 用户问题中提取的关键词集合
         * @return 匹配分数（越高越相关）
         */
        float calculateMatchScore(String question, Set<String> questionKeywords) {
            float score = 0;

            // 1. 完全匹配：如果用户问题包含任意一个 pattern，直接 +1 分（最高权重）
            String lowerQuestion = question.toLowerCase();
            for (String pattern : patterns) {
                if (lowerQuestion.contains(pattern.toLowerCase())) {
                    score += 1.0f;
                }
            }

            // 2. 关键词匹配：计算关键词重合比例，最多贡献 0.8 分
            int matchedKeywords = 0;
            for (String keyword : keywords) {
                if (questionKeywords.contains(keyword)) {
                    matchedKeywords++;
                }
            }

            if (!keywords.isEmpty()) {
                float keywordScore = (float) matchedKeywords / keywords.size();
                score += keywordScore * 0.8f;
            }

            // 3. 优先级加成：每级 +0.05 分（例如 priority=10 → +0.5 分）
            score += priority * 0.05f;

            // 4. 长度惩罚：极短问题匹配长答案可能不合理，打 5 折
            if (question.length() < 3 && answer.length() > 100) {
                score *= 0.5f;
            }

            // 限制总分不超过 2.0（防止过拟合）
            return Math.min(score, 2.0f);
        }

        /**
         * 从文本中提取关键词（结合 HanLP 和简单分词）
         */
        private Set<String> extractKeywords(String text) {
            Set<String> keywords = new HashSet<>();

            // 使用 HanLP 提取最多 10 个关键词
            List<String> extracted = HanLP.extractKeyword(text, 10);
            keywords.addAll(extracted);

            // 补充：对每个 pattern 进行简单分词（按空格/标点分割），过滤停用词
            for (String pattern : patterns) {
                String[] words = pattern.split("[\\s\\pP]+"); // \pP 表示 Unicode 标点符号
                for (String word : words) {
                    word = word.trim().toLowerCase();
                    // 长度 >1 且不是停用词才保留
                    if (word.length() > 1 && !isStopWord(word)) {
                        keywords.add(word);
                    }
                }
            }

            return keywords;
        }

        /**
         * 判断是否为停用词 —— 现在可以正确访问外部类的 stopWords
         */
        private boolean isStopWord(String word) {
            return stopWords.contains(word); // ✅ 修正4: 正确引用外部类的 stopWords
        }
    }

    /**
     * 初始化中英文停用词表
     */
    private Set<String> initStopWords() {
        Set<String> stopWords = new HashSet<>();

        // 中文常见停用词
        String[] chineseStopWords = {
                "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个", "上", "也", "很",
                "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好", "自己", "这", "那", "里", "么",
                "之", "与", "及", "或", "日", "月", "年", "什么", "怎么", "吗", "呢", "啊", "吧", "哦", "嗯",
                "呀", "啦", "哇", "哈", "哼", "哎", "喂", "嘛", "呗", "喽", "噢", "呦", "呵", "嘻", "嘿"
        };

        // 英文常见停用词
        String[] englishStopWords = {
                "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by",
                "is", "am", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does",
                "did", "can", "could", "will", "would", "should", "may", "might", "must"
        };

        Collections.addAll(stopWords, chineseStopWords);
        Collections.addAll(stopWords, englishStopWords);

        return stopWords;
    }

    /**
     * 从数据库重新加载所有活跃知识条目，并重建缓存
     */
    public void reloadKnowledgeBase() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, question_pattern, answer_template, priority, category " +
                             "FROM knowledge_base WHERE is_active = TRUE ORDER BY priority DESC")) {

            // 清空旧缓存
            keywordCache.clear();
            fullCache.clear();

            while (rs.next()) {
                // 创建知识条目对象
                KnowledgeItem item = new KnowledgeItem(
                        rs.getLong("id"),
                        rs.getString("question_pattern"),
                        rs.getString("answer_template"),
                        rs.getInt("priority"),
                        rs.getString("category")
                );

                // 加入全量缓存
                fullCache.add(item);

                // 为每个关键词建立反向索引（关键词 → 条目列表）
                for (String keyword : item.keywords) {
                    keywordCache.computeIfAbsent(keyword, k -> new ArrayList<>())
                            .add(item);
                }
            }

            System.out.println("知识库重新加载完成，共 " + fullCache.size() + " 条知识，索引关键词 " + keywordCache.size() + " 个");

        } catch (SQLException e) {
            System.err.println("重新加载知识库失败: " + e.getMessage());
        }
    }

    /**
     * 查询接口（简化版）：仅传入问题
     */
    public KnowledgeResult query(String question) {
        return query(question, null, null);
    }

    /**
     * 主查询接口：根据用户问题匹配最佳答案
     * @param question 用户问题
     * @param userId 用户ID（可选，用于日志）
     * @param groupId 群组ID（可选，用于日志）
     * @return 匹配结果对象，若无匹配则返回 null
     */
    public KnowledgeResult query(String question, String userId, String groupId) {
        if (question == null || question.trim().isEmpty()) {
            return null;
        }

        String cleanQuestion = question.trim();

        // 步骤1：提取用户问题的关键词
        Set<String> questionKeywords = extractQuestionKeywords(cleanQuestion);

        // 步骤2：基于关键词快速筛选候选知识条目
        List<KnowledgeItem> candidateItems = quickKeywordMatch(cleanQuestion, questionKeywords);

        // 步骤3：计算每个候选条目的匹配分数
        List<MatchResult> matchResults = calculateMatchScores(candidateItems, cleanQuestion, questionKeywords);

        // 步骤4：选择最佳匹配（满足阈值且分数最高）
        KnowledgeResult result = selectBestMatch(matchResults, cleanQuestion);

        // 步骤5：若匹配成功，记录命中日志并更新命中次数
        if (result != null && result.matchedItem != null) {
            logHit(result.matchedItem.id, userId, groupId, cleanQuestion,
                    result.matchedKeywords, result.similarityScore);
            updateHitCount(result.matchedItem.id);
        }

        return result;
    }

    /**
     * 提取用户问题中的关键词（HanLP + 简单分词）
     */
    private Set<String> extractQuestionKeywords(String question) {
        Set<String> keywords = new HashSet<>();

        // 方法1：HanLP 提取最多5个关键词
        List<String> extracted = HanLP.extractKeyword(question, 5);
        keywords.addAll(extracted);

        // 方法2：按空格/标点简单分词，过滤停用词
        String[] words = question.split("[\\s\\pP]+");
        for (String word : words) {
            word = word.trim().toLowerCase();
            if (word.length() > 1 && !stopWords.contains(word)) {
                keywords.add(word);
            }
        }

        return keywords;
    }

    /**
     * 快速关键词匹配：利用 keywordCache 缩小搜索范围
     */
    private List<KnowledgeItem> quickKeywordMatch(String question, Set<String> questionKeywords) {
        List<KnowledgeItem> candidates = new ArrayList<>();

        // 若缓存禁用或为空，则返回全部知识（兜底）
        if (!enableCache || keywordCache.isEmpty()) {
            return new ArrayList<>(fullCache);
        }

        // 通过每个关键词查找关联的知识条目（去重）
        Set<KnowledgeItem> candidateSet = new HashSet<>();
        for (String keyword : questionKeywords) {
            List<KnowledgeItem> items = keywordCache.get(keyword);
            if (items != null) {
                candidateSet.addAll(items);
            }
        }
        candidates.addAll(candidateSet);

        // 若候选太少（<5），补充高优先级（≥8）条目以防漏检
        if (candidates.size() < 5 && !fullCache.isEmpty()) {
            for (KnowledgeItem item : fullCache) {
                if (item.priority >= 8 && !candidates.contains(item)) {
                    candidates.add(item);
                }
            }
        }

        return candidates;
    }

    /**
     * 为所有候选条目计算匹配分数，并过滤低分项
     */
    private List<MatchResult> calculateMatchScores(List<KnowledgeItem> candidates,
                                                   String question, Set<String> questionKeywords) {
        List<MatchResult> results = new ArrayList<>();

        for (KnowledgeItem item : candidates) {
            float score = item.calculateMatchScore(question, questionKeywords);
            // 只保留分数 > 0.3 的结果（避免噪声）
            if (score > 0.3) {
                // 记录具体匹配了哪些关键词
                Set<String> matchedKeywords = new HashSet<>();
                for (String keyword : item.keywords) {
                    if (questionKeywords.contains(keyword)) {
                        matchedKeywords.add(keyword);
                    }
                }
                results.add(new MatchResult(item, score, matchedKeywords));
            }
        }

        return results;
    }

    /**
     * 从匹配结果中选择最佳答案（满足阈值、分数最高、优先级次之）
     */
    private KnowledgeResult selectBestMatch(List<MatchResult> matchResults, String question) {
        if (matchResults.isEmpty()) {
            return null;
        }

        // 排序：先按分数降序，再按优先级降序
        matchResults.sort((a, b) -> {
            int scoreCompare = Float.compare(b.score, a.score);
            if (scoreCompare != 0) return scoreCompare;
            return Integer.compare(b.item.priority, a.item.priority);
        });

        MatchResult best = matchResults.get(0);

        // 若最高分仍低于阈值，视为无匹配
        if (best.score < similarityThreshold) {
            return null;
        }

        // 构建返回结果对象
        KnowledgeResult result = new KnowledgeResult();
        result.matchedItem = best.item;
        result.answer = best.item.answer;
        result.similarityScore = best.score;
        result.matchedKeywords = new ArrayList<>(best.matchedKeywords);
        result.category = best.item.category;

        return result;
    }

    /**
     * 异步记录命中日志（不影响主流程性能）
     */
    private void logHit(long knowledgeId, String userId, String groupId,
                        String question, List<String> matchedKeywords, double similarityScore) {
        new Thread(() -> {
            try (Connection conn = dataSource.getConnection()) {
                String sql = "INSERT INTO knowledge_hit_logs " +
                        "(knowledge_id, user_id, group_id, question, " +
                        "matched_keywords, similarity_score) " +
                        "VALUES (?, ?, ?, ?, ?, ?)";

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setLong(1, knowledgeId);
                    stmt.setString(2, userId);
                    stmt.setString(3, groupId);
                    // 限制 question 长度防超限
                    stmt.setString(4, question.length() > 500 ? question.substring(0, 500) : question);

                    String keywordsStr = matchedKeywords != null ? String.join(",", matchedKeywords) : "";
                    stmt.setString(5, keywordsStr.length() > 500 ? keywordsStr.substring(0, 500) : keywordsStr);

                    stmt.setDouble(6, similarityScore);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                System.err.println("记录命中日志失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 更新知识条目的命中次数（用于统计热门问题）
     */
    private void updateHitCount(long knowledgeId) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "UPDATE knowledge_base SET hit_count = hit_count + 1, " +
                    "updated_at = NOW() WHERE id = ?";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, knowledgeId);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("更新命中次数失败: " + e.getMessage());
        }
    }

    /**
     * 添加新知识到数据库，并触发缓存重载
     */
    public boolean addKnowledge(String pattern, String answer, String category, int priority) {
        try (Connection conn = dataSource.getConnection()) {
            // 提取关键词用于存储（便于后续分析）
            String keywords = extractKeywordsForStorage(pattern, answer);

            String sql = "INSERT INTO knowledge_base " +
                    "(question_pattern, answer_template, category, priority, keywords) " +
                    "VALUES (?, ?, ?, ?, ?)";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, pattern);
                stmt.setString(2, answer);
                stmt.setString(3, category);
                stmt.setInt(4, priority);
                stmt.setString(5, keywords);

                int rows = stmt.executeUpdate();

                if (rows > 0) {
                    reloadKnowledgeBase(); // 重新加载以包含新知识
                    return true;
                }
            }
        } catch (SQLException e) {
            System.err.println("添加知识失败: " + e.getMessage());
        }

        return false;
    }

    /**
     * 为存储提取关键词（限制数量，避免字段过长）
     */
    private String extractKeywordsForStorage(String pattern, String answer) {
        Set<String> keywords = new HashSet<>();

        // 从 pattern 提取
        if (pattern != null) {
            String[] patterns = pattern.split("\\|");
            for (String p : patterns) {
                // HanLP 提取
                List<String> extracted = HanLP.extractKeyword(p, 5);
                keywords.addAll(extracted);
                // 简单分词补充
                String[] words = p.split("[\\s\\pP]+");
                for (String word : words) {
                    word = word.trim().toLowerCase();
                    if (word.length() > 1 && !stopWords.contains(word)) {
                        keywords.add(word);
                    }
                }
            }
        }

        // 从 answer 提取（较少，只取3个）
        if (answer != null) {
            List<String> extracted = HanLP.extractKeyword(answer, 3);
            keywords.addAll(extracted);
        }

        // 限制最多10个关键词
        List<String> keywordList = new ArrayList<>(keywords);
        if (keywordList.size() > 10) {
            keywordList = keywordList.subList(0, 10);
        }

        return String.join(",", keywordList);
    }

    /**
     * 获取热门知识（按 hit_count 排序）
     */
    public List<KnowledgeItem> getPopularKnowledge(int limit) {
        List<KnowledgeItem> popular = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, question_pattern, answer_template, priority, category " +
                             "FROM knowledge_base WHERE is_active = TRUE " +
                             "ORDER BY hit_count DESC, priority DESC LIMIT " + limit)) {

            while (rs.next()) {
                KnowledgeItem item = new KnowledgeItem(
                        rs.getLong("id"),
                        rs.getString("question_pattern"),
                        rs.getString("answer_template"),
                        rs.getInt("priority"),
                        rs.getString("category")
                );
                popular.add(item);
            }

        } catch (SQLException e) {
            System.err.println("获取热门知识失败: " + e.getMessage());
        }

        return popular;
    }

    /**
     * 内部类：表示一次匹配的中间结果
     */
    private static class MatchResult {
        KnowledgeItem item;          // 匹配的知识条目
        float score;                 // 匹配分数
        Set<String> matchedKeywords; // 具体匹配的关键词

        MatchResult(KnowledgeItem item, float score, Set<String> matchedKeywords) {
            this.item = item;
            this.score = score;
            this.matchedKeywords = matchedKeywords;
        }
    }

    /**
     * 公共结果类：供外部调用者使用
     */
    public static class KnowledgeResult {
        public KnowledgeItem matchedItem;   // 匹配的知识对象
        public String answer;               // 答案内容
        public double similarityScore;      // 相似度分数
        public List<String> matchedKeywords;// 匹配的关键词列表
        public String category;             // 分类

        @Override
        public String toString() {
            return "KnowledgeResult{" +
                    "answer='" + (answer != null && answer.length() > 50 ?
                    answer.substring(0, 50) + "..." : answer) + '\'' +
                    ", similarityScore=" + similarityScore +
                    ", category='" + category + '\'' +
                    ", matchedKeywords=" + matchedKeywords +
                    '}';
        }
    }

}