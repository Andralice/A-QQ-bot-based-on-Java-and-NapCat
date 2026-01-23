package com.start.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

public class WeatherTool implements Tool {

    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    // 中文城市名到英文的映射（Open-Meteo 地理编码更认英文）
    private static final Map<String, String> CHINESE_TO_ENGLISH_CITY = Map.ofEntries(
            Map.entry("北京", "Beijing"),
            Map.entry("上海", "Shanghai"),
            Map.entry("广州", "Guangzhou"),
            Map.entry("深圳", "Shenzhen"),
            Map.entry("杭州", "Hangzhou"),
            Map.entry("成都", "Chengdu"),
            Map.entry("南京", "Nanjing"),
            Map.entry("西安", "Xi'an"),
            Map.entry("武汉", "Wuhan"),
            Map.entry("重庆", "Chongqing"),
            Map.entry("天津", "Tianjin"),
            Map.entry("苏州", "Suzhou"),
            Map.entry("郑州", "Zhengzhou"),
            Map.entry("长沙", "Changsha"),
            Map.entry("青岛", "Qingdao"),
            Map.entry("大连", "Dalian"),
            Map.entry("厦门", "Xiamen"),
            Map.entry("哈尔滨", "Harbin"),
            Map.entry("长春", "Changchun"),
            Map.entry("沈阳", "Shenyang"),
            Map.entry("济南", "Jinan"),
            Map.entry("福州", "Fuzhou"),
            Map.entry("昆明", "Kunming"),
            Map.entry("南宁", "Nanning"),
            Map.entry("贵阳", "Guiyang"),
            Map.entry("太原", "Taiyuan"),
            Map.entry("兰州", "Lanzhou"),
            Map.entry("西宁", "Xining"),
            Map.entry("银川", "Yinchuan"),
            Map.entry("呼和浩特", "Hohhot"),
            Map.entry("乌鲁木齐", "Urumqi"),
            Map.entry("拉萨", "Lhasa"),
            Map.entry("海口", "Haikou"),
            Map.entry("三亚", "Sanya"),
            Map.entry("珠海", "Zhuhai"),
            Map.entry("东莞", "Dongguan"),
            Map.entry("佛山", "Foshan"),
            Map.entry("宁波", "Ningbo"),
            Map.entry("温州", "Wenzhou"),
            Map.entry("合肥", "Hefei"),
            Map.entry("南昌", "Nanchang"),
            Map.entry("石家庄", "Shijiazhuang"),
            Map.entry("保定", "Baoding"),
            Map.entry("唐山", "Tangshan"),
            Map.entry("大同", "Datong"),
            Map.entry("包头", "Baotou"),
            Map.entry("赤峰", "Chifeng")
            // 可根据需要继续扩展
    );

    @Override
    public String getName() {
        return "get_weather";
    }

    @Override
    public String getDescription() {
        return "根据城市名称获取当前天气信息（温度、天气状况等）";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of(
                                "type", "string",
                                "description", "城市名称，例如：北京、上海、New York"
                        )
                ),
                "required", Arrays.asList("city")
        );
    }

    @Override
    public String execute(Map<String, Object> args) {
        String city = (String) args.get("city");
        if (city == null || city.trim().isEmpty()) {
            return "错误：缺少城市名称参数";
        }

        String originalCity = city.trim(); // 用于最终回复
        // 尝试映射为英文；若无匹配，保留原值（支持用户直接输入英文）
        String queryCity = CHINESE_TO_ENGLISH_CITY.getOrDefault(originalCity, originalCity);

        try {
            String encodedCity = URLEncoder.encode(queryCity, StandardCharsets.UTF_8);
            // 限定国家为中国（对中文城市更友好），但不影响国际城市
            String geocodingUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encodedCity + "&count=1&country=CN";

            HttpRequest geoRequest = HttpRequest.newBuilder()
                    .uri(URI.create(geocodingUrl))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> geoResponse = httpClient.send(geoRequest, HttpResponse.BodyHandlers.ofString());

            if (geoResponse.statusCode() != 200) {
                return "无法连接地理位置服务，请稍后再试。";
            }

            JsonNode geoJson = mapper.readTree(geoResponse.body());

            // 检查是否有有效结果
            if (!geoJson.has("results") || geoJson.get("results").size() == 0) {
                // 如果加了 CN 限制没找到，尝试不限国家（兼容国际城市）
                geocodingUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" + encodedCity + "&count=1";
                geoRequest = HttpRequest.newBuilder()
                        .uri(URI.create(geocodingUrl))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .build();
                geoResponse = httpClient.send(geoRequest, HttpResponse.BodyHandlers.ofString());
                geoJson = mapper.readTree(geoResponse.body());

                if (!geoJson.has("results") || geoJson.get("results").size() == 0) {
                    return "未找到城市 [" + originalCity + "]，请确认名称是否正确。";
                }
            }

            JsonNode firstResult = geoJson.get("results").get(0);
            double latitude = firstResult.get("latitude").asDouble();
            double longitude = firstResult.get("longitude").asDouble();

            // 获取天气
            String weatherUrl = String.format(
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,weather_code&timezone=auto",
                    latitude, longitude
            );

            HttpRequest weatherRequest = HttpRequest.newBuilder()
                    .uri(URI.create(weatherUrl))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> weatherResponse = httpClient.send(weatherRequest, HttpResponse.BodyHandlers.ofString());

            if (weatherResponse.statusCode() != 200) {
                return "天气服务暂时不可用。";
            }

            JsonNode weatherJson = mapper.readTree(weatherResponse.body());
            JsonNode current = weatherJson.path("current");

            if (current.isMissingNode()) {
                return "未能获取当前天气数据。";
            }

            double temperature = current.path("temperature_2m").asDouble();
            int weatherCode = current.path("weather_code").asInt();
            String weatherDesc = wmoToDescription(weatherCode);

            return String.format("📍%s 当前天气：%s，气温 %.1f°C", originalCity, weatherDesc, temperature);

        } catch (IOException | InterruptedException e) {
            return "网络请求失败，请稍后再试。";
        } catch (Exception e) {
            // 开发阶段可打印堆栈
            // e.printStackTrace();
            return "解析天气数据时出错。";
        }
    }

    private String wmoToDescription(int code) {
        return switch (code) {
            case 0 -> "晴朗";
            case 1, 2, 3 -> "多云";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61, 63, 65 -> "小到中雨";
            case 66, 67 -> "冻雨";
            case 71, 73, 75 -> "小到中雪";
            case 77 -> "雪粒";
            case 80, 81, 82 -> "阵雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "未知天气";
        };
    }
}
