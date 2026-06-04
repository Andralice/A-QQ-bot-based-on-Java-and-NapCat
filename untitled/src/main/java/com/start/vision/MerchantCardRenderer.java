package com.start.vision;

import com.start.service.MerchantApiService.MerchantData;
import com.start.service.MerchantApiService.MerchantProduct;
import com.start.service.MerchantApiService.MerchantRoundInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * 远行商人卡片渲染器 — 童真风格，柔和色彩。
 */
public class MerchantCardRenderer {

    private static final Logger logger = LoggerFactory.getLogger(MerchantCardRenderer.class);

    private static final int W = 640;
    private static final int PAD = 22;
    private static final int ICON_SZ = 50;
    private static final int ROW_H = 72;
    private static final int GAP = 8;

    // 柔和童真配色
    private static final Color BG        = new Color(0xFFF8F0); // 暖奶油白
    private static final Color ROW_BG    = new Color(0xFFF0E8); // 浅桃色
    private static final Color DIVIDER   = new Color(0xF5E0D0);
    private static final Color TEXT      = new Color(0x5C4033); // 暖棕
    private static final Color SUBTEXT   = new Color(0x9B8E82);
    private static final Color C_LEMON   = new Color(0xF0A500); // 活泼黄
    private static final Color C_LEMON_BG = new Color(0xFFF3D0);
    private static final Color C_MINT    = new Color(0x3CB371); // 薄荷绿
    private static final Color C_MINT_BG = new Color(0xE0F5EC);
    private static final Color C_PINK    = new Color(0xF08080); // 浅珊瑚
    private static final Color C_PINK_BG = new Color(0xFFE8E0);
    private static final Color C_LAVENDER = new Color(0x9B7EC4); // 淡紫
    private static final Color C_LAVENDER_BG = new Color(0xF0E8F8);
    private static final Color C_SKY     = new Color(0x5BA0D0); // 天空蓝
    private static final Color C_SKY_BG  = new Color(0xE0EFF8);
    private static final Color C_ICON_BG = new Color(0xFFE8D8);

    private final Font fTitle, fBody, fSmall, fBadge;

    public MerchantCardRenderer() {
        fTitle  = load("HarmonyOS_SansSC_Bold.ttf",   20f);
        fBody  = load("HarmonyOS_SansSC_Medium.ttf",  15f);
        fSmall = load("HarmonyOS_SansSC_Regular.ttf", 12f);
        fBadge = load("HarmonyOS_SansSC_Bold.ttf",    11f);
    }

    private Font load(String name, float sz) {
        try {
            java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("assets/fonts/" + name);
            return is == null ? new Font(Font.SANS_SERIF, Font.PLAIN, (int) sz)
                              : Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(sz);
        } catch (Exception e) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, (int) sz);
        }
    }

    public String renderToBase64(MerchantData data) {
        try {
            int rows = Math.max(data.products.size(), 1);
            int H = PAD + 80 + GAP + rows * (ROW_H + GAP) + PAD;
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            configure(g);

            // 背景
            g.setColor(BG);
            g.fillRoundRect(0, 0, W, H, 20, 20);

            int y = PAD;
            y = header(g, data, y);
            y += GAP;
            productList(g, data, y);

            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            logger.error("渲染卡片失败", e);
            return null;
        }
    }

    private void configure(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    // === Header ===

    private int header(Graphics2D g, MerchantData data, int y0) {
        MerchantRoundInfo r = data.roundInfo;
        int x = PAD;
        int w = W - 2 * PAD;

        // 标题
        g.setFont(fTitle);
        g.setColor(TEXT);
        g.drawString("远行商人", x, y0 + 26);

        // 轮次 pill
        String round = "第 " + r.current + "/" + r.total + " 轮";
        g.setFont(fBadge);
        FontMetrics fm = g.getFontMetrics();
        int bw = fm.stringWidth(round) + 18;
        int bx = x + w - bw;
        g.setColor(C_LEMON_BG);
        g.fillRoundRect(bx, y0 + 6, bw, 26, 14, 14);
        g.setColor(C_LEMON);
        g.drawString(round, bx + 9, y0 + 22);

        // 信息行
        int iy = y0 + 52;
        g.setFont(fSmall);

        g.setColor(SUBTEXT);
        g.drawString("日期", x, iy + 2);
        g.setColor(TEXT);
        g.drawString(r.dateStr, x + 32, iy + 2);

        if (data.timeRangeLabel != null && !data.timeRangeLabel.isEmpty()) {
            g.setColor(SUBTEXT);
            g.drawString("时间", x + 170, iy + 2);
            g.setColor(TEXT);
            g.drawString(data.timeRangeLabel, x + 202, iy + 2);
        }

        // 倒计时
        g.setColor(C_MINT);
        g.drawString("剩余 " + r.countdown, x + 460, iy + 2);

        // 分隔线
        int ly = iy + 14;
        g.setColor(DIVIDER);
        g.drawLine(x, ly, x + w, ly);
        return ly;
    }

    // === Products ===

    private void productList(Graphics2D g, MerchantData data, int y0) {
        if (data.products.isEmpty()) {
            g.setFont(fBody);
            g.setColor(SUBTEXT);
            g.drawString("还没有商品哦~", PAD + 20, y0 + 32);
            return;
        }
        int y = y0 + 6;
        for (int i = 0; i < data.products.size(); i++) {
            boolean last = i == data.products.size() - 1;
            y = productRow(g, data.products.get(i), y, last);
        }
    }

    private int productRow(Graphics2D g, MerchantProduct p, int y, boolean last) {
        int x = PAD;
        int w = W - 2 * PAD;

        // 行背景
        g.setColor(ROW_BG);
        g.fillRoundRect(x, y, w, ROW_H, 14, 14);

        // 图标
        int ix = x + 12;
        int iy = y + (ROW_H - ICON_SZ) / 2;
        BufferedImage icon = loadIcon(p.iconUrl);
        if (icon != null) {
            Shape clip = g.getClip();
            g.setClip(new RoundRectangle2D.Float(ix, iy, ICON_SZ, ICON_SZ, 12, 12));
            g.drawImage(icon, ix, iy, ICON_SZ, ICON_SZ, null);
            g.setClip(clip);
        } else {
            g.setColor(C_ICON_BG);
            g.fillRoundRect(ix, iy, ICON_SZ, ICON_SZ, 12, 12);
            g.setColor(C_LEMON);
            g.setFont(fBody);
            String ch = p.name.isEmpty() ? "?" : p.name.substring(0, 1);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(ch, ix + (ICON_SZ - fm.stringWidth(ch)) / 2, iy + 30);
        }

        // 名称
        int tx = ix + ICON_SZ + 14;
        g.setFont(fBody);
        g.setColor(TEXT);
        g.drawString(p.name, tx, y + 22);

        // 时间
        g.setFont(fSmall);
        g.setColor(SUBTEXT);
        if (p.startTime != null && p.endTime != null) {
            DateTimeFormatter tf = DateTimeFormatter.ofPattern("MM-dd HH:mm");
            ZoneId z = ZoneId.systemDefault();
            LocalDateTime s = LocalDateTime.ofInstant(Instant.ofEpochSecond(p.startTime), z);
            LocalDateTime e = LocalDateTime.ofInstant(Instant.ofEpochSecond(p.endTime), z);
            g.drawString(s.format(tf) + " — " + e.format(tf), tx, y + 42);
        }

        // 右侧标签
        int rx = x + w - 12;
        int ry = y + 16;

        if (p.buyLimit > 0) {
            String t = "限购 " + p.buyLimit;
            rx -= pillW(g, t);
            pill(g, t, rx, ry, C_SKY_BG, C_SKY);
            rx -= 6;
        }
        if (p.price > 0) {
            String t = fmtPrice(p.price);
            rx -= pillW(g, t);
            pill(g, t, rx, ry, C_LEMON_BG, C_LEMON);
            rx -= 6;
        }
        if (p.typeLabel != null && !p.typeLabel.isEmpty()) {
            rx -= pillW(g, p.typeLabel);
            pill(g, p.typeLabel, rx, ry, C_PINK_BG, C_PINK);
        }

        return y + ROW_H + (last ? 0 : 5);
    }

    // === Helpers ===

    private int pillW(Graphics2D g, String text) {
        return g.getFontMetrics(fBadge).stringWidth(text) + 16;
    }

    private void pill(Graphics2D g, String text, int x, int y, Color bg, Color fg) {
        FontMetrics fm = g.getFontMetrics(fBadge);
        int w = fm.stringWidth(text) + 16;
        g.setColor(bg);
        g.fillRoundRect(x, y, w, 24, 13, 13);
        g.setFont(fBadge);
        g.setColor(fg);
        g.drawString(text, x + 8, y + 16);
    }

    private String fmtPrice(int n) {
        if (n >= 10000) return (n / 10000) + "万";
        if (n >= 1000) return (n / 1000) + "," + String.format("%03d", n % 1000);
        return String.valueOf(n);
    }

    private BufferedImage loadIcon(String url) {
        if (url == null || url.isEmpty()) return null;
        try { return ImageIO.read(new URL(url)); } catch (Exception e) { return null; }
    }
}
