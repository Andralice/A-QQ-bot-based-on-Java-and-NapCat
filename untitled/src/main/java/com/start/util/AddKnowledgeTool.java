package com.start.util;

import com.start.config.DatabaseConfig;
import com.start.service.KeywordKnowledgeService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.Scanner;

/**
 * 独立运行的知识添加工具（命令行交互式）
 * 启动后提示用户输入 pattern / answer / category / priority，
 * 并通过 KeywordKnowledgeService 添加到数据库并刷新缓存。
 */
public class AddKnowledgeTool {

    // ⚠️ 请根据你的实际数据库配置修改以下参数


    public static void main(String[] args) {
        System.out.println("=== 知识库添加工具 ===");
        System.out.println("请输入知识条目信息（支持多问法，用 | 分隔）\n");

        // 创建数据源和服务
        HikariDataSource dataSource = DatabaseConfig.getDataSource();
        KeywordKnowledgeService service = new KeywordKnowledgeService(dataSource);

        Scanner scanner = new Scanner(System.in);

        try {
            while (true) {
                System.out.print("❓ 问题模板（多个用 | 分隔，如：怎么登录|无法登录）: ");
                String pattern = scanner.nextLine().trim();
                if (pattern.isEmpty()) {
                    System.out.println("问题模板不能为空，请重新输入。\n");
                    continue;
                }

                System.out.print("💬 答案: ");
                String answer = scanner.nextLine().trim();
                if (answer.isEmpty()) {
                    System.out.println("答案不能为空，请重新输入。\n");
                    continue;
                }

                System.out.print("📂 分类（如：客服、技术、账户）: ");
                String category = scanner.nextLine().trim();
                if (category.isEmpty()) category = "通用";

                System.out.print("🔢 优先级（默认 5，越高越优先）: ");
                int priority = 5;
                String prioStr = scanner.nextLine().trim();
                if (!prioStr.isEmpty()) {
                    try {
                        priority = Integer.parseInt(prioStr);
                    } catch (NumberFormatException e) {
                        System.out.println("⚠️ 优先级格式错误，使用默认值 5");
                        priority = 5;
                    }
                }

                // 调用 KeywordKnowledgeService 添加知识
                boolean success = service.addKnowledge(pattern, answer, category, priority);

                if (success) {
                    System.out.println("\n✅ 知识添加成功！\n");
                } else {
                    System.out.println("\n❌ 添加失败！请检查数据库连接或表结构。\n");
                }

                System.out.print("是否继续添加？(y/n): ");
                String again = scanner.nextLine().trim().toLowerCase();
                if (!"y".equals(again) && !"yes".equals(again)) {
                    break;
                }
                System.out.println(); // 空行分隔
            }

            System.out.println("👋 工具已退出。");
        } finally {
            scanner.close();
            dataSource.close(); // 关闭连接池
        }
    }
}