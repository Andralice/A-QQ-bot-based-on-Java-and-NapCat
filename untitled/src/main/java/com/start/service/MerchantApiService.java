package com.start.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.start.config.BotConfig;
import com.start.repository.MerchantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 远行商人 API 服务，支持缓存、匿名Token和API Key认证。
 */
public class MerchantApiService {

    private static final Logger logger = LoggerFactory.getLogger(MerchantApiService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    private static final long ANON_REFRESH_BUFFER_MS = 30_000;

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;
    private final String fingerprint;
    private final MerchantRepository repo;

    private String anonymousToken;
    private long anonymousTokenExpiresAt;

    public MerchantApiService() {
        this(BotConfig.getMerchantApiBaseUrl(), BotConfig.getMerchantApiKey(), null);
    }

    public MerchantApiService(MerchantRepository repo) {
        this(BotConfig.getMerchantApiBaseUrl(), BotConfig.getMerchantApiKey(), repo);
    }

    public MerchantApiService(String baseUrl, String apiKey, MerchantRepository repo) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.repo = repo;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.fingerprint = "qq-bot-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        logger.info("📡 MerchantApiService: baseUrl={}, hasApiKey={}, cacheEnabled={}",
                this.baseUrl, apiKey != null && !apiKey.isBlank(), repo != null);
    }

    // === data classes ===

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MerchantProduct {
        public String name;
        public String iconUrl;
        public Long startTime;
        public Long endTime;
        public int price;
        public int buyLimit;
        public String typeLabel;

        public MerchantProduct() {}
        public MerchantProduct(String name, String iconUrl) { this.name = name; this.iconUrl = iconUrl; }

        public boolean isActive() {
            long now = System.currentTimeMillis() / 1000;
            boolean afterStart = startTime == null || now >= startTime;
            boolean beforeEnd = endTime == null || now < endTime;
            return afterStart && beforeEnd;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MerchantRoundInfo {
        public int current;
        public int total = 4;
        public String countdown;
        public boolean isOpen;
        public String roundId;
        public String dateStr;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MerchantData {
        public List<MerchantProduct> products = new ArrayList<>();
        public MerchantRoundInfo roundInfo;
        public String activityName;
        public String timeRangeLabel;
    }

    // === auth ===

    private synchronized String getAuthHeader() { return (apiKey != null && !apiKey.isBlank()) ? "X-API-Key" : "X-Anonymous-Token"; }

    private synchronized String getAuthValue() throws Exception {
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        long now = System.currentTimeMillis();
        if (anonymousToken != null && anonymousTokenExpiresAt > now + ANON_REFRESH_BUFFER_MS) return anonymousToken;
        return refreshAnonymousToken();
    }

    private synchronized String refreshAnonymousToken() throws Exception {
        String url = baseUrl + "/api/v1/auth/anonymous-token";
        String body = MAPPER.writeValueAsString(MAPPER.createObjectNode().put("fingerprint", fingerprint));
        var request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());
        if (root.path("code").asInt() != 0) throw new RuntimeException("获取匿名Token失败: " + root.path("message").asText());
        JsonNode data = root.path("data");
        anonymousToken = data.path("token").asText();
        try { anonymousTokenExpiresAt = Instant.parse(data.path("expires_at").asText()).toEpochMilli(); }
        catch (Exception e) { anonymousTokenExpiresAt = System.currentTimeMillis() + 55 * 60 * 1000L; }
        return anonymousToken;
    }

    // === API with caching ===

    public MerchantData fetchMerchantInfo(boolean forceRefresh) {
        MerchantRoundInfo round = computeRoundInfo();
        if (!round.isOpen) {
            MerchantData empty = new MerchantData();
            empty.roundInfo = round;
            empty.activityName = "远行商人";
            return empty;
        }
        if (!forceRefresh && repo != null && !repo.isCacheStale(round.roundId, 4 * 3600 * 1000L)) {
            MerchantData cached = repo.getCachedData(round.roundId);
            if (cached != null) { cached.roundInfo = round; return cached; }
        }
        return fetchAndCache(round);
    }

    private MerchantData fetchAndCache(MerchantRoundInfo round) {
        try {
            MerchantData data = fetchFromApi();
            data.roundInfo = round;
            data.activityName = data.activityName != null ? data.activityName : "远行商人";
            if (repo != null) {
                List<String> names = data.products.stream().map(p -> p.name).toList();
                repo.saveCache(round.roundId, round.current, data.activityName,
                        data.products.size(), String.join(",", names), data);
            }
            return data;
        } catch (Exception e) {
            logger.error("获取远行商人数据失败: {}", e.getMessage());
            MerchantData empty = new MerchantData();
            empty.roundInfo = round;
            empty.activityName = "远行商人";
            return empty;
        }
    }

    private MerchantData fetchFromApi() throws Exception {
        String url = baseUrl + "/api/v1/games/rocom/merchant/info?refresh=true";
        var request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header(getAuthHeader(), getAuthValue()).GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String body = response.body();
            throw new RuntimeException("HTTP " + response.statusCode() + (body != null ? ": " + body.substring(0, Math.min(200, body.length())) : ""));
        }
        JsonNode root = MAPPER.readTree(response.body());
        int code = root.path("code").asInt();
        if (code != 0) throw new RuntimeException(root.path("message").asText("code=" + code));
        return parseResponse(root.path("data"));
    }

    private MerchantData parseResponse(JsonNode data) {
        MerchantData result = new MerchantData();
        JsonNode activities = data.path("merchantActivities");
        if (activities.isMissingNode() || !activities.isArray()) activities = data.path("merchant_activities");
        if (!activities.isArray() || activities.size() == 0) return result;

        JsonNode activity = activities.get(0);
        result.activityName = activity.path("name").asText("远行商人");

        // 时间范围
        long actStart = activity.path("start_time").asLong();
        long actEnd = activity.path("end_time").asLong();
        result.timeRangeLabel = formatTimeRange(actStart, actEnd);

        // 用 LinkedHashMap 按名去重（random_goods 优先，因为含价格/限购信息）
        Map<String, MerchantProduct> productMap = new LinkedHashMap<>();

        // 先解析 random_goods（含价格、限购、类型）
        parseRandomGoods(data.path("random_goods"), productMap);

        // 再解析 get_props / get_pets （补充 random_goods 中没有的）
        parsePropsOrPets(activity.path("get_props"), "道具", productMap);
        parsePropsOrPets(activity.path("get_extra_props"), "道具", productMap);
        parsePropsOrPets(activity.path("get_pets"), "宠物", productMap);

        for (MerchantProduct p : productMap.values()) {
            if (p.isActive()) result.products.add(p);
        }
        return result;
    }

    private void parseRandomGoods(JsonNode arr, Map<String, MerchantProduct> map) {
        if (!arr.isArray()) return;
        for (JsonNode g : arr) {
            String name = g.path("goods_name").asText(g.path("name").asText(""));
            if (name.isEmpty()) continue;
            MerchantProduct mp = new MerchantProduct();
            mp.name = name;
            mp.iconUrl = g.path("icon_url").asText("");
            mp.price = g.path("price").asInt(0);
            mp.buyLimit = g.path("buy_limit_num").asInt(0);
            int type = g.path("Type").asInt(0);
            mp.typeLabel = type == 1 ? "道具" : type == 2 ? "宠物" : "";
            // random_goods 没有时间字段，补 null
            mp.startTime = normalizeTimestamp(g.path("start_time"));
            mp.endTime = normalizeTimestamp(g.path("end_time"));
            map.putIfAbsent(name, mp);
        }
    }

    private void parsePropsOrPets(JsonNode arr, String typeLabel, Map<String, MerchantProduct> map) {
        if (!arr.isArray()) return;
        for (JsonNode p : arr) {
            String name = p.path("name").asText("");
            if (name.isEmpty()) continue;
            // 如果 random_goods 里已经有了，只补充时间和图标
            MerchantProduct existing = map.get(name);
            if (existing != null) {
                if (existing.startTime == null) existing.startTime = normalizeTimestamp(p.path("start_time"));
                if (existing.endTime == null) existing.endTime = normalizeTimestamp(p.path("end_time"));
                if (existing.iconUrl.isEmpty()) existing.iconUrl = p.path("icon_url").asText("");
                if (existing.typeLabel.isEmpty()) existing.typeLabel = typeLabel;
            } else {
                MerchantProduct mp = new MerchantProduct();
                mp.name = name;
                mp.iconUrl = p.path("icon_url").asText("");
                mp.startTime = normalizeTimestamp(p.path("start_time"));
                mp.endTime = normalizeTimestamp(p.path("end_time"));
                mp.typeLabel = typeLabel;
                map.put(name, mp);
            }
        }
    }

    private Long normalizeTimestamp(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) return null;
        long val = node.asLong();
        if (val == 0) return null;
        return val < 100000000000L ? val : val / 1000;
    }

    // === 轮次计算 ===

    public static MerchantRoundInfo computeRoundInfo() {
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        int[] roundHours = {8, 12, 16, 20};

        MerchantRoundInfo info = new MerchantRoundInfo();
        info.current = 0;
        info.isOpen = hour >= 8 && hour < 24;
        info.dateStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        for (int i = 0; i < roundHours.length; i++) {
            if (hour >= roundHours[i] && (i == roundHours.length - 1 || hour < roundHours[i + 1])) {
                info.current = i + 1;
                break;
            }
        }

        int nextHour = (info.current > 0 && info.current < 4) ? roundHours[info.current] : roundHours[0];
        LocalDateTime nextTime = now.withHour(nextHour).withMinute(0).withSecond(0).withNano(0);
        if (nextHour <= hour) nextTime = nextTime.plusDays(1);

        long diffSeconds = Duration.between(now, nextTime).getSeconds();
        info.countdown = String.format("%02d:%02d:%02d", diffSeconds / 3600, (diffSeconds % 3600) / 60, diffSeconds % 60);
        info.roundId = info.dateStr + "-" + (info.current > 0 ? info.current : "closed");
        return info;
    }

    // === 格式化 ===

    private static String formatTimeRange(long start, long end) {
        if (start == 0 && end == 0) return "";
        DateTimeFormatter df = DateTimeFormatter.ofPattern("MM-dd");
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime s = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), zone);
        LocalDateTime e = LocalDateTime.ofInstant(Instant.ofEpochMilli(end), zone);
        return s.format(df) + " " + s.format(tf) + " — " + e.format(tf);
    }

    public static String formatTimeWindowShort(long startSec, long endSec) {
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("HH:mm");
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime s = LocalDateTime.ofInstant(Instant.ofEpochSecond(startSec), zone);
        LocalDateTime e = LocalDateTime.ofInstant(Instant.ofEpochSecond(endSec), zone);
        return s.format(tf) + " — " + e.format(tf);
    }

    public String formatForReply(MerchantData data) {
        MerchantRoundInfo r = data.roundInfo;
        StringBuilder sb = new StringBuilder();
        sb.append("🏪 远行商人 · 第").append(r.current).append("/").append(r.total).append("轮\n");
        sb.append("⏰ 剩余 ").append(r.countdown).append(" | ").append(r.dateStr).append("\n");
        sb.append("━━━━━━━━\n");
        if (data.products.isEmpty()) {
            sb.append("📦 暂无商品\n");
        } else {
            for (MerchantProduct p : data.products) {
                if (p.iconUrl != null && !p.iconUrl.isEmpty()) {
                    sb.append("[CQ:image,file=").append(p.iconUrl).append("]\n");
                }
                sb.append("✦ ").append(p.name);
                if (p.price > 0) sb.append("  💰").append(p.price);
                if (p.buyLimit > 0) sb.append("  ⚡限购").append(p.buyLimit);
                if (p.typeLabel != null && !p.typeLabel.isEmpty()) sb.append("  🏷").append(p.typeLabel);
                if (p.startTime != null && p.endTime != null)
                    sb.append("\n  ⏱ ").append(formatTimeWindowShort(p.startTime, p.endTime));
                sb.append("\n");
            }
        }
        sb.append("━━━━━━━━\n📦 共").append(data.products.size()).append("件");
        return sb.toString();
    }

    public List<String> findHighValueMatches(MerchantData data, Set<String> highValueItems) {
        List<String> matched = new ArrayList<>();
        for (MerchantProduct p : data.products) {
            for (String keyword : highValueItems) {
                if (p.name.contains(keyword) && !matched.contains(p.name)) {
                    matched.add(p.name);
                }
            }
        }
        return matched;
    }
}
