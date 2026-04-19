package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.repository.EggGroupDataCenter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 宠物配对查询处理器
 * 职责：解析用户消息，调用数据中心查询，回复结果
 */
public class EggGroupSearchHandler implements MessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(EggGroupSearchHandler.class);

    private final EggGroupDataCenter dataCenter;
    private static final String CMD_PREFIX = "#查蛋";
    private static final String CMD_EGG_GROUP = "#查蛋组";
    private static final String CMD_CAN_BREED = "#能否生蛋";
    private static final String CMD_EVOLUTION = "#查进化";
    private static final String CMD_EGG_GROUP_DETAIL = "#查蛋组详情";
    // 通过构造函数注入数据中心
    public EggGroupSearchHandler(EggGroupDataCenter dataCenter) {
        this.dataCenter = dataCenter;
    }

    @Override
    public boolean match(JsonNode message) {
        String text = extractText(message);
        if (text == null) return false;
        
        // 支持五个命令前缀
        return text.startsWith(CMD_PREFIX) || 
               text.startsWith(CMD_EGG_GROUP) || 
               text.startsWith(CMD_CAN_BREED) ||
               text.startsWith(CMD_EVOLUTION);
    }

    @Override
    public void handle(JsonNode message, Main bot) {
        String text = extractText(message);
        if (text == null) return;

        // 路由到不同的处理逻辑
        if (text.startsWith(CMD_EVOLUTION)) {
            handleEvolutionQuery(text, bot, message);
        } else if (text.startsWith(CMD_CAN_BREED)) {
            handleCanBreed(text, bot, message);
        } else if (text.startsWith(CMD_EGG_GROUP)) {
            // 判断是查询宠物蛋组还是蛋组详情
            String content = text.replace(CMD_EGG_GROUP, "").trim();
            if (isEggGroupName(content)) {
                handleEggGroupDetail(content, bot, message);
            } else {
                handleEggGroupQuery(text, bot, message);
            }
        } else if (text.startsWith(CMD_PREFIX)) {
            handleMateQuery(text, bot, message);
        }
    }

    /**
     * 处理配对查询：#查蛋 宠物名
     */
    private void handleMateQuery(String text, Main bot, JsonNode message) {
        // 1. 解析宠物名
        String petName = text.replace(CMD_PREFIX, "").trim();
        if (petName.isEmpty()) {
            reply(bot, message, "❌ 格式错误，请使用：#查蛋 宠物名");
            return;
        }

        // 2. 查询数据
        String groupName = dataCenter.getGroupName(petName);

        if (groupName == null) {
            reply(bot, message, "❌ 找不到宠物 [" + petName + "] 的信息。");
            return;
        }

        List<String> mates = dataCenter.getMates(petName);

        // 3. 构建回复（返回全部数据，不截断）
        StringBuilder sb = new StringBuilder();
        sb.append("✨ ").append(petName).append(" 属于 【").append(groupName).append("】\n");
        sb.append("🥚 可配对宠物（共").append(mates.size()).append("只）：\n");

        for (int i = 0; i < mates.size(); i++) {
            sb.append(mates.get(i));
            if (i < mates.size() - 1) sb.append("、");
            
            // 每10个换行，避免消息过长
            if ((i + 1) % 10 == 0 && i < mates.size() - 1) {
                sb.append("\n");
            }
        }

        reply(bot, message, sb.toString());
    }

    /**
     * 处理蛋组查询：#查蛋组 宠物名
     */
    private void handleEggGroupQuery(String text, Main bot, JsonNode message) {
        // 1. 解析宠物名
        String petName = text.replace(CMD_EGG_GROUP, "").trim();
        if (petName.isEmpty()) {
            reply(bot, message, "❌ 格式错误，请使用：#查蛋组 宠物名");
            return;
        }

        // 2. 查询蛋组
        String groups = dataCenter.getPetEggGroups(petName);

        if (groups == null) {
            reply(bot, message, "❌ 找不到宠物 [" + petName + "] 的信息。");
            return;
        }

        // 3. 构建回复（支持多蛋组显示）
        String[] groupArray = groups.split(",");
        
        StringBuilder sb = new StringBuilder();
        sb.append("🏷️ ").append(petName).append(" 的蛋组：\n");
        
        for (int i = 0; i < groupArray.length; i++) {
            sb.append((i + 1)).append(". ").append(groupArray[i].trim());
            if (i < groupArray.length - 1) sb.append("\n");
        }
        
        if (groupArray.length > 1) {
            sb.append("\n💡 提示：该宠物属于多个蛋组，可以与以上任一组中的宠物配对");
        }

        reply(bot, message, sb.toString());
    }

    /**
     * 处理蛋组详情查询：#查蛋组 天空组
     */
    private void handleEggGroupDetail(String groupName, Main bot, JsonNode message) {
        // 1. 查询该蛋组的所有宠物
        List<String> pets = dataCenter.getPetsInGroup(groupName);
        
        if (pets == null || pets.isEmpty()) {
            reply(bot, message, "❌ 找不到蛋组 【" + groupName + "】 的信息。");
            return;
        }
        
        // 2. 构建回复
        StringBuilder sb = new StringBuilder();
        sb.append("📋 【").append(groupName).append("】共有 ").append(pets.size()).append(" 只宠物：\n\n");
        
        // 按进化链分组显示
        Map<String, List<String>> evolutionGroups = dataCenter.groupByEvolution(pets);
        
        int index = 0;
        for (Map.Entry<String, List<String>> entry : evolutionGroups.entrySet()) {
            String chain = entry.getKey();
            List<String> members = entry.getValue();
            
            index++;
            
            if (chain != null && !chain.equals("无进化")) {
                // 有进化链的宠物
                sb.append(index).append(". ").append(chain).append("\n");
            } else {
                // 无进化的宠物，逐个显示
                for (String pet : members) {
                    sb.append(index).append(". ").append(pet).append("\n");
                    index++;
                }
                continue;
            }
        }
        
        reply(bot, message, sb.toString());
    }

    /**
     * 处理生蛋判断：#能否生蛋 宠物1 宠物2
     */
    private void handleCanBreed(String text, Main bot, JsonNode message) {
        // 1. 解析两只宠物名
        String content = text.replace(CMD_CAN_BREED, "").trim();
        
        // 分割宠物名（支持空格、逗号、和分隔）
        String[] parts = content.split("\\s+|,|和");
        
        if (parts.length < 2) {
            reply(bot, message, "❌ 格式错误，请使用：#能否生蛋 宠物1 宠物2");
            return;
        }
        
        String pet1 = parts[0].trim();
        String pet2 = parts[1].trim();
        
        if (pet1.isEmpty() || pet2.isEmpty()) {
            reply(bot, message, "❌ 请输入有效的宠物名称");
            return;
        }

        // 2. 判断是否可以生蛋
        boolean canBreed = dataCenter.canBreed(pet1, pet2);
        
        // 3. 获取详细信息
        String groups1 = dataCenter.getPetEggGroups(pet1);
        String groups2 = dataCenter.getPetEggGroups(pet2);
        
        StringBuilder sb = new StringBuilder();
        
        if (canBreed) {
            sb.append("✅ ").append(pet1).append(" 和 ").append(pet2).append(" 可以生蛋！\n");
            
            // 显示共同的蛋组
            String[] g1Array = groups1.split(",");
            String[] g2Array = groups2.split(",");
            
            sb.append("🔗 共同蛋组：");
            for (String g1 : g1Array) {
                for (String g2 : g2Array) {
                    if (g1.trim().equals(g2.trim())) {
                        sb.append("【").append(g1.trim()).append("】 ");
                    }
                }
            }
        } else {
            sb.append("❌ ").append(pet1).append(" 和 ").append(pet2).append(" 无法生蛋\n");
            
            // 显示各自的蛋组
            if (groups1 != null && groups2 != null) {
                sb.append("📋 ").append(pet1).append(" 属于：").append(groups1).append("\n");
                sb.append("📋 ").append(pet2).append(" 属于：").append(groups2);
            }
        }

        reply(bot, message, sb.toString());
    }

    /**
     * 处理进化路径查询：#查进化 宠物名
     */
    private void handleEvolutionQuery(String text, Main bot, JsonNode message) {
        // 1. 解析宠物名
        String petName = text.replace(CMD_EVOLUTION, "").trim();
        if (petName.isEmpty()) {
            reply(bot, message, "❌ 格式错误，请使用：#查进化 宠物名");
            return;
        }

        // 2. 查询进化路径
        String chain = dataCenter.getEvolutionChain(petName);
        
        // 3. 构建回复
        if (chain == null || chain.isEmpty()) {
            reply(bot, message, "❌ 找不到宠物 [" + petName + "] 的进化信息，或该宠物无法进化。");
            return;
        }
        
        // 解析进化链，标记当前宠物位置
        String[] forms = chain.split("\\s*→\\s*|\\s+");
        StringBuilder sb = new StringBuilder();
        
        sb.append("🧬 ").append(petName).append(" 的进化路径：\n");
        
        for (int i = 0; i < forms.length; i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            
            // 高亮显示当前查询的宠物
            if (forms[i].trim().equals(petName)) {
                sb.append("【").append(forms[i].trim()).append("】");
            } else {
                sb.append(forms[i].trim());
            }
        }
        
        // 添加阶段说明
        if (forms.length > 1) {
            sb.append("\n\n📊 进化阶段：");
            for (int i = 0; i < forms.length; i++) {
                if (forms[i].trim().equals(petName)) {
                    sb.append("\n   ✨ ").append(forms[i].trim()).append(" ← 当前形态");
                } else {
                    sb.append("\n   • ").append(forms[i].trim());
                }
            }
            
            // 判断是否为最终形态
            if (forms[forms.length - 1].trim().equals(petName)) {
                sb.append("\n\n💡 提示：这是最终形态，无法继续进化");
            } else if (forms[0].trim().equals(petName)) {
                sb.append("\n\n💡 提示：这是初始形态，还可以继续进化");
            } else {
                sb.append("\n\n💡 提示：这是中间形态");
            }
        }

        reply(bot, message, sb.toString());
    }

    /**
     * 判断是否为蛋组名称
     */
    private boolean isEggGroupName(String text) {
        // 常见的蛋组名称关键词
        return text.contains("组") || 
               text.contains("类") || 
               text.equals("机械") || 
               text.equals("冰冰凉");
    }

    // 辅助方法：提取文本
    private String extractText(JsonNode message) {
        try {
            if (message.has("message")) {
                JsonNode messageArray = message.get("message");
                if (messageArray.isArray()) {
                    for (JsonNode node : messageArray) {
                        String type = node.path("type").asText();
                        if ("text".equals(type)) {
                            // 新格式：{"type":"text","data":{"text":"..."}}
                            if (node.has("data") && node.get("data").has("text")) {
                                return node.get("data").get("text").asText();
                            }
                            // 旧格式：{"type":"text","text":"..."}
                            else if (node.has("text")) {
                                return node.get("text").asText();
                            }
                        }
                        // 兼容旧格式 "Plain"
                        else if ("Plain".equals(type)) {
                            return node.get("text").asText();
                        }
                    }
                }
            }
            // 兼容 messageChain 格式
            if (message.has("messageChain")) {
                for (JsonNode node : message.get("messageChain")) {
                    if ("Plain".equals(node.get("type").asText())) {
                        return node.get("text").asText();
                    }
                }
            }
        } catch (Exception e) {
            logger.error("❌ 提取消息文本失败", e);
        }
        return null;
    }

    // 辅助方法：发送消息
    private void reply(Main bot, JsonNode source, String text) {
        bot.sendReply(source, text);
    }
}
