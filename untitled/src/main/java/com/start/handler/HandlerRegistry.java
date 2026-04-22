package com.start.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.start.Main;
import com.start.config.DatabaseConfig;
import com.start.repository.EggGroupDataCenter;
import com.start.repository.UserAffinityRepository;
import com.start.service.AgentService;
import com.start.service.BaiLianService;
import com.start.service.KeywordKnowledgeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;


/**
 * 消息处理注册中心
 */
public class HandlerRegistry {
    private static final UserAffinityRepository userAffinityRepo = new UserAffinityRepository();
    private static final List<MessageHandler> handlers = new ArrayList<>();
    private static final Logger logger = LoggerFactory.getLogger(HandlerRegistry.class);

    private static final BaiLianService baiLianService = new BaiLianService();
    private static final KeywordKnowledgeService knowledgeService = new KeywordKnowledgeService(DatabaseConfig.getDataSource());
    private static final AgentService agentService = new AgentService(baiLianService, knowledgeService,userAffinityRepo);

    private static final EggGroupDataCenter dataCenter = new EggGroupDataCenter();
    private static final TravelingMerchantHandler merchantHandler = new TravelingMerchantHandler();

    static {
        // 注册所有 Handler（顺序很重要！先匹配的先生效）
        
        // 打招呼/问候处理器：处理如“你好”、“嗨”等基础问候语
        handlers.add(new HelloHandler());
        
        // 运势/运气处理器：提供每日运势、抽签或随机运气相关功能
        handlers.add(new LuckHandler());
        
        // 笑话处理器：发送随机笑话或幽默内容
        handlers.add(new JokeHandler());
        
        // 提醒事项处理器：处理定时提醒、闹钟或待办事项设置
        handlers.add(new ReminderHandler());
        
        // 三角洲
        handlers.add(new SanjiaoHandler());
        
        // 每日职业/身份处理器：分配或查询每日随机职业、身份或角色
        handlers.add(new DailyProfessionHandler());
        
        // 每日CP/配对处理器：进行随机人物配对或CP生成
        handlers.add(new DailyCpHandler());
        
        // 洛克王国
        handlers.add(new EggGroupSearchHandler(dataCenter));
        
        // Agent智能代理处理器：集成百炼服务和知识库，处理复杂的AI代理任务
        handlers.add(new AgentHandler(agentService));
        logger.debug("未使用agent");
        
        // 行商/旅行商人处理器：处理与虚拟商人交易、互动或响应特定事件
        handlers.add(merchantHandler);
        
        // 通用AI对话处理器：提供基础的AI聊天回复功能
        handlers.add(new AIHandler());
        
        // 注意：merchantHandler 被重复添加，请确认是否为预期行为（通常只需添加一次）
        handlers.add(merchantHandler);
        
        // 后续新增功能，只需在这里 add(new XxxHandler())
    }

    /**
     * 处理来自目标群的响应消息（优先处理）
     * @param message 收到的消息
     * @param bot 机器人实例
     * @return 是否已处理
     */
    public static boolean handleMerchantResponse(JsonNode message, Main bot) {
        return merchantHandler.handleResponse(message);
    }

    public static void dispatch(JsonNode message, Main bot) {
        for (MessageHandler handler : handlers) {
            if (handler.match(message)) {
                handler.handle(message, bot);
                return;
            }


        }
        logger.debug("未找到匹配的handle");
        // 可选：默认回复
//         bot.sendReply(message, "😅 我还不会这个命令，输入「帮助」查看指令");
    }
}