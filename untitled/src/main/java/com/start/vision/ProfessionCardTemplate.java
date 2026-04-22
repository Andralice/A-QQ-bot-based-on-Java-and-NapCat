package com.start.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.*;
import java.awt.image.BufferedImage;

public class ProfessionCardTemplate implements ImageTemplate<ProfessionData> {

    private static final Logger logger = LoggerFactory.getLogger(ProfessionCardTemplate.class);
    private final ImageRenderer renderer;

    // 核心配色方案
    private static final Color BRIGHT_WHITE = new Color(255, 255, 255);
    private static final Color LIGHT_GRAY = new Color(204, 204, 204);
    
    // 位阶专属配色（1-5阶，越高级越尊贵）
    private static final Color TIER_1_COLOR = new Color(150, 160, 180);      // 一阶 - 铁灰（朴素）
    private static final Color TIER_2_COLOR = new Color(100, 180, 255);      // 二阶 - 天蓝（清新）
    private static final Color TIER_3_COLOR = new Color(0, 191, 255);        // 三阶 - 霓虹蓝（稀有）
    private static final Color TIER_4_COLOR_START = new Color(138, 43, 226); // 四阶起始 - 蓝紫（史诗）
    private static final Color TIER_4_COLOR_END = new Color(180, 80, 255);   // 四阶结束 - 亮紫
    private static final Color TIER_5_COLOR = new Color(255, 80, 80);        // 五阶 - 赤焰红（传说）

    public ProfessionCardTemplate() {
        this.renderer = ImageRenderer.getInstance();
    }

    @Override
    public BufferedImage render(Object data) {
        if (!(data instanceof ProfessionData)) {
            throw new IllegalArgumentException("数据格式错误");
        }
        ProfessionData p = (ProfessionData) data;

        int width = 800;
        int height = 600;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        renderer.configureRenderingHints(g);

        // 1. 绘制背景（根据位阶变化）
        drawBackground(g, width, height, p.tier);

        // 2. 绘制双层边框
        drawDoubleBorder(g, width, height, p.tier);

        // 3. 绘制四角装饰
        drawCornerDecorations(g, width, height, p.tier);

        // 4. 绘制标题区
        drawHeader(g, width);

        // 5. 绘制职业核心信息
        drawProfessionCore(g, width, p);

        // 6. 绘制战力进度条
        drawPowerBar(g, width, p);

        // 7. 绘制职业描述
        drawDescription(g, width, p);

        // 8. 绘制底部信息
        drawFooter(g, width, height, p);

        g.dispose();
        return img;
    }

    private void drawBackground(Graphics2D g, int width, int height, int tier) {
        // 根据位阶调整背景色调（位阶越高越深邃尊贵）
        Color bgStart, bgEnd, topGlowColor;
        switch (tier) {
            case 5: // 传说 - 深红紫
                bgStart = new Color(20, 5, 30);
                bgEnd = new Color(35, 10, 40);
                topGlowColor = new Color(255, 80, 80, 25);
                break;
            case 4: // 史诗 - 深紫
                bgStart = new Color(15, 8, 35);
                bgEnd = new Color(28, 12, 48);
                topGlowColor = new Color(180, 80, 255, 25);
                break;
            case 3: // 稀有 - 深蓝
                bgStart = new Color(10, 12, 35);
                bgEnd = new Color(18, 20, 45);
                topGlowColor = new Color(0, 191, 255, 20);
                break;
            case 2: // 普通二阶 - 蓝灰
                bgStart = new Color(12, 15, 30);
                bgEnd = new Color(20, 25, 42);
                topGlowColor = new Color(100, 180, 255, 15);
                break;
            default: // 一阶 - 深灰蓝
                bgStart = new Color(12, 15, 25);
                bgEnd = new Color(18, 22, 35);
                topGlowColor = new Color(150, 160, 180, 15);
        }

        // 径向渐变背景
        RadialGradientPaint bg = new RadialGradientPaint(
                width / 2, height / 2, 500,
                new float[]{0f, 0.5f, 1f},
                new Color[]{bgEnd, bgStart, new Color(8, 10, 18)}
        );
        g.setPaint(bg);
        g.fillRect(0, 0, width, height);

        // 顶部光晕
        GradientPaint topGlow = new GradientPaint(0, 0, topGlowColor, 0, 200, new Color(0, 0, 0, 0));
        g.setPaint(topGlow);
        g.fillRect(0, 0, width, 200);

        // 粒子效果
        g.setColor(new Color(255, 255, 255, 8));
        for (int i = 0; i < 25; i++) {
            int x = (int) (Math.random() * width);
            int y = (int) (Math.random() * height);
            int size = (int) (Math.random() * 2) + 1;
            g.fillOval(x, y, size, size);
        }
    }

    private void drawDoubleBorder(Graphics2D g, int width, int height, int tier) {
        int margin = 15;
        Color accent = getTierColor(tier);

        // 外层：虚线边框
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 60));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, 
                new float[]{8, 6}, 0));
        g.drawRoundRect(margin, margin, width - 2 * margin, height - 2 * margin, 16, 16);

        // 外层发光
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 15));
        g.setStroke(new BasicStroke(8f));
        g.drawRoundRect(margin, margin, width - 2 * margin, height - 2 * margin, 16, 16);

        // 内层：细白线
        g.setColor(new Color(255, 255, 255, 50));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(margin + 10, margin + 10, width - 2 * margin - 20, height - 2 * margin - 20, 12, 12);
    }

    private void drawCornerDecorations(Graphics2D g, int width, int height, int tier) {
        Color accent = getTierColor(tier);
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 70));
        g.setStroke(new BasicStroke(2.5f));

        int len = 40;
        int margin = 28;

        // 左上角
        g.drawLine(margin, margin + len, margin, margin);
        g.drawLine(margin, margin, margin + len, margin);
        // 右上角
        g.drawLine(width - margin - len, margin, width - margin, margin);
        g.drawLine(width - margin, margin, width - margin, margin + len);
        // 左下角
        g.drawLine(margin, height - margin - len, margin, height - margin);
        g.drawLine(margin, height - margin, margin + len, height - margin);
        // 右下角
        g.drawLine(width - margin - len, height - margin, width - margin, height - margin);
        g.drawLine(width - margin, height - margin - len, width - margin, height - margin);
    }

    private void drawHeader(Graphics2D g, int width) {
        // 标题：42pt 亮白 + 外发光
        Font titleFont = renderer.loadFont("HarmonyOS_SansSC_Bold.ttf", 42f);
        g.setFont(titleFont);

        // 外发光
        g.setColor(new Color(0, 191, 255, 30));
        renderer.drawCenteredString(g, "天命职业鉴定", width + 1, 85);
        g.setColor(new Color(0, 191, 255, 18));
        renderer.drawCenteredString(g, "天命职业鉴定", width, 84);

        // 主标题
        g.setColor(BRIGHT_WHITE);
        renderer.drawCenteredString(g, "天命职业鉴定", width, 83);

        // 分隔线
        g.setStroke(new BasicStroke(1f));
        GradientPaint lineLeft = new GradientPaint(200, 110, new Color(255, 255, 255, 0), 400, 110, new Color(255, 255, 255, 35));
        g.setPaint(lineLeft);
        g.drawLine(200, 110, 400, 110);
        GradientPaint lineRight = new GradientPaint(400, 110, new Color(255, 255, 255, 35), 600, 110, new Color(255, 255, 255, 0));
        g.setPaint(lineRight);
        g.drawLine(400, 110, 600, 110);
    }

    private void drawProfessionCore(Graphics2D g, int width, ProfessionData p) {
        int coreY = 175;
        Color accent = getTierColor(p.tier);

        // 职业名称：68pt，使用位阶颜色
        Font nameFont = renderer.loadFont("HarmonyOS_SansSC_Black.ttf", 68f);
        g.setFont(nameFont);

        // 外发光（根据位阶变色）
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 30));
        renderer.drawCenteredString(g, p.professionName, width + 2, coreY + 65);
        
        // 主体文字（使用位阶颜色而非白色）
        g.setColor(accent);
        renderer.drawCenteredString(g, p.professionName, width, coreY + 63);

        // 位阶标签：22pt
        Font tierFont = renderer.loadFont("HarmonyOS_SansSC_Regular.ttf", 22f);
        g.setFont(tierFont);
        g.setColor(new Color(200, 200, 210));
        renderer.drawCenteredString(g, p.tierName, width, coreY + 120);

        // 稀有度徽章
        drawRarityBadge(g, width, coreY + 150, p.rarity, accent);
    }

    private void drawRarityBadge(Graphics2D g, int width, int y, String rarity, Color accent) {
        String text = rarity;
        Font badgeFont = renderer.loadFont("HarmonyOS_SansSC_Bold.ttf", 18f);
        g.setFont(badgeFont);
        int textW = g.getFontMetrics().stringWidth(text);
        int badgeW = textW + 45;
        int badgeH = 34;
        int badgeX = (width - badgeW) / 2;

        // 徽章背景渐变
        GradientPaint badgeBg = new GradientPaint(badgeX, y, 
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45),
                badgeX + badgeW, y, 
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 15));
        g.setPaint(badgeBg);
        g.fillRoundRect(badgeX, y, badgeW, badgeH, 17, 17);

        // 徽章边框
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 70));
        g.setStroke(new BasicStroke(1.5f));
        g.drawRoundRect(badgeX, y, badgeW, badgeH, 17, 17);

        // 徽章文字
        g.setColor(BRIGHT_WHITE);
        int textX = (width - textW) / 2;
        g.drawString(text, textX, y + 23);
    }

    private void drawPowerBar(Graphics2D g, int width, ProfessionData p) {
        int barY = 410;
        int barW = 520;
        int barH = 26;
        int barX = (width - barW) / 2;
        Color accent = getTierColor(p.tier);

        // 未填充部分
        g.setColor(new Color(20, 20, 30, 180));
        g.fillRoundRect(barX, barY, barW, barH, 13, 13);

        // 进度条边框
        g.setColor(new Color(255, 255, 255, 25));
        g.setStroke(new BasicStroke(1f));
        g.drawRoundRect(barX, barY, barW, barH, 13, 13);

        // 填充部分（根据位阶变色）
        double powerRatio = Math.min(1.0, (double) p.combatPower / 10000.0);
        int fillW = (int) (barW * powerRatio);
        if (fillW > 10) {
            Color fillColor = getTierColor(p.tier);
            GradientPaint barFill = new GradientPaint(
                    barX, barY, fillColor,
                    barX + fillW, barY, new Color(fillColor.getRed() / 2, fillColor.getGreen() / 2, fillColor.getBlue() / 2)
            );
            g.setPaint(barFill);
            g.fillRoundRect(barX + 2, barY + 2, fillW - 4, barH - 4, 11, 11);

            // 光泽效果
            GradientPaint gloss = new GradientPaint(
                    barX, barY, new Color(255, 255, 255, 50),
                    barX, barY + barH / 2, new Color(255, 255, 255, 0)
            );
            g.setPaint(gloss);
            g.fillRoundRect(barX + 2, barY + 2, fillW - 4, barH / 2 - 2, 11, 11);
        }

        // 战力数值：28pt
        Font powerFont = renderer.loadFont("HarmonyOS_SansSC_Bold.ttf", 28f);
        g.setFont(powerFont);
        
        // 发光效果
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 35));
        renderer.drawCenteredString(g, "战力值：" + p.combatPower, width, barY + 55);
        
        // 主体文字
        g.setColor(accent);
        renderer.drawCenteredString(g, "战力值：" + p.combatPower, width, barY + 53);
    }

    private void drawDescription(Graphics2D g, int width, ProfessionData p) {
        // 描述文字：18pt
        Font descFont = renderer.loadFont("HarmonyOS_SansSC_Regular.ttf", 18f);
        g.setFont(descFont);
        g.setColor(BRIGHT_WHITE);
        wrapAndDrawText(g, p.description, width - 160, width, 500);
    }

    private void drawFooter(Graphics2D g, int width, int height, ProfessionData p) {
        int y = height - 38;
        Font footerFont = renderer.loadFont("HarmonyOS_SansSC_Light.ttf", 15f);
        g.setFont(footerFont);
        g.setColor(new Color(130, 140, 160));

        // 底部装饰线
        g.setStroke(new BasicStroke(1f));
        GradientPaint footerLeft = new GradientPaint(120, y - 12, new Color(255, 255, 255, 0), 400, y - 12, new Color(255, 255, 255, 20));
        g.setPaint(footerLeft);
        g.drawLine(120, y - 12, 400, y - 12);
        GradientPaint footerRight = new GradientPaint(400, y - 12, new Color(255, 255, 255, 20), 680, y - 12, new Color(255, 255, 255, 0));
        g.setPaint(footerRight);
        g.drawLine(400, y - 12, 680, y - 12);

        g.drawString("查询者：" + p.userId, 40, y);
        g.drawString("命运已注定", width - 130, y);
    }

    private void wrapAndDrawText(Graphics2D g, String text, int maxWidth, int centerX, int startY) {
        FontMetrics fm = g.getFontMetrics();
        int y = startY;
        String[] sentences = text.split("。|，|、|；");
        StringBuilder line = new StringBuilder();
        
        for (String sentence : sentences) {
            if (sentence.isEmpty()) continue;
            String test = line + sentence + "。";
            if (fm.stringWidth(test) > maxWidth) {
                renderer.drawCenteredString(g, line.toString().trim(), centerX, y);
                y += 30;
                line = new StringBuilder(sentence + "。");
            } else {
                line.append(sentence).append("。");
            }
        }
        if (line.length() > 0) {
            renderer.drawCenteredString(g, line.toString().trim(), centerX, y);
        }
    }

    /**
     * 根据位阶获取颜色（1-5阶，越高级越尊贵）
     */
    private Color getTierColor(int tier) {
        return switch (tier) {
            case 5 -> TIER_5_COLOR;      // 五阶 - 赤焰红（传说）
            case 4 -> TIER_4_COLOR_END;  // 四阶 - 亮紫（史诗）
            case 3 -> TIER_3_COLOR;      // 三阶 - 霓虹蓝（稀有）
            case 2 -> TIER_2_COLOR;      // 二阶 - 天蓝（普通）
            default -> TIER_1_COLOR;     // 一阶 - 铁灰（入门）
        };
    }
}
