#!/usr/bin/env python3
import sys
import time
from playwright.sync_api import sync_playwright

# 可选：用于自动裁剪底部（如果 JS 隐藏和滚动都失败）
try:
    from PIL import Image
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

TASKS = {
    "kkrb-overview": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-swat-product-container",
        "needs_profit_switch": True,
    },
    "stock-chart": {
        "url": "https://example.com/chart",
        "selector": ".chart-container"
    },
    "kkrb-overview-2": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-bcic-container",
    },
    "kkrb-overview-3": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-bonus-door-container",
    },
    "kkrb-overview-1-1": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#layui-table-box",
    },
    "kkrb-overview-1-2": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#layui-table-box",
    },
    "kkrb-overview-1-3": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-bonus-door-container",
    },
    "kkrb-overview-1-4": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-bonus-door-container",
    }
}

def take_screenshot(task_name, output_path):
    if task_name not in TASKS:
        raise ValueError(f"Unknown task: {task_name}")

    config = TASKS[task_name]
    selector = config["selector"]

    with sync_playwright() as p:
        browser = p.chromium.launch(
            headless=True,
            args=[
                "--no-sandbox",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--disable-web-security",
                "--disable-extensions",
                "--disable-plugins",
                "--disable-software-rasterizer",
                "--disable-setuid-sandbox",
                "--disable-features=site-per-process",
                "--disable-features=VizDisplayCompositor",
            ]
        )
        page = browser.new_page()

        # 设置真实 User-Agent
        page.set_extra_http_headers({
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        })

        try:
            print(f"🌐 访问: {config['url']}", file=sys.stderr)
            page.goto(config["url"], wait_until="domcontentloaded")

            print(f"🔍 当前页面标题: {page.title()}", file=sys.stderr)
            print(f"🔍 当前页面 URL: {page.url}", file=sys.stderr)

            # 调试截图（初始状态）
            page.screenshot(path="/tmp/debug-after-goto.png")
            print("📸 已保存初始状态截图: /tmp/debug-after-goto.png", file=sys.stderr)

            # 等待 JS 渲染（Layui 初始化需要时间）
            time.sleep(2)  # 延长至 2 秒

            # ✅ 精准关闭 layui 弹窗
            try:
                page.wait_for_selector(".layui-layer-dialog", timeout=10000)
                print("🔍 发现弹窗，准备关闭...", file=sys.stderr)
                page.click(".layui-layer-btn0")
                print("✅ 已点击‘确定’按钮关闭弹窗", file=sys.stderr)
                page.wait_for_timeout(500)  # 稍等确保弹窗完全消失
            except Exception as e:
                print(f"⚠️ 弹窗未找到或点击失败（可能已自动关闭）: {e}", file=sys.stderr)

            # ✅ 条件性触发 profitSwitch（增强版）
            if config.get("needs_profit_switch", False):
                try:
                    container_selector = "#profitSwitch + .layui-unselect"
                    print(f"🔍 等待利润开关容器出现: {container_selector}", file=sys.stderr)
                    page.wait_for_selector(container_selector, timeout=12000)

                    # 获取当前 class
                    current_class = page.locator(container_selector).get_attribute("class") or ""
                    is_on = "layui-form-onswitch" in current_class

                    print(f"🔧 当前开关 class: '{current_class}'", file=sys.stderr)
                    print(f"📊 当前开关状态: {'开启（小时利润）' if is_on else '关闭（总利润）'}", file=sys.stderr)

                    if not is_on:
                        print("🔄 正在点击开关容器以切换到‘小时利润’模式...", file=sys.stderr)
                        # 👉 关键：点击可视化 div，不是 input
                        page.click(container_selector)
                        page.wait_for_timeout(1200)  # 给 JS 足够时间加载新数据

                        # 验证是否成功
                        new_class = page.locator(container_selector).get_attribute("class") or ""
                        new_is_on = "layui-form-onswitch" in new_class
                        print(f"🔧 切换后 class: '{new_class}'", file=sys.stderr)
                        if new_is_on:
                            print("✅ 开关已成功切换为‘小时利润’模式", file=sys.stderr)
                        else:
                            print("❌ 开关点击后仍未开启！可能被阻止或 JS 未响应", file=sys.stderr)
                            page.screenshot(path="/tmp/debug-switch-fail.png")
                            print("📸 已保存开关操作失败截图: /tmp/debug-switch-fail.png", file=sys.stderr)
                    else:
                        print("ℹ️ 开关已处于‘小时利润’模式，无需操作", file=sys.stderr)

                except Exception as e:
                    print(f"💥 利润开关操作异常: {e}", file=sys.stderr)
                    page.screenshot(path="/tmp/debug-switch-error.png")
                    print("📸 已保存异常状态截图: /tmp/debug-switch-error.png", file=sys.stderr)

            # 等待目标容器加载
            try:
                page.wait_for_selector(selector, timeout=15000)
                print(f"✅ 目标元素 '{selector}' 已加载", file=sys.stderr)
            except:
                print(f"⚠️ 未找到目标元素 '{selector}'，尝试全页截图", file=sys.stderr)

            # 👇 滚动到目标区域
            try:
                locator = page.locator(selector)
                locator.scroll_into_view_if_needed(timeout=5000)
                print("✅ 已滚动目标区域到视口内", file=sys.stderr)
            except Exception as e:
                print(f"⚠️ 滚动失败（可能元素不可滚动）: {e}", file=sys.stderr)

            # 👇 额外向上滚动一点（避开底部固定层）
            try:
                page.evaluate("window.scrollBy(0, 150);")
                print("▲ 额外向上滚动 150px 以避开底部遮挡", file=sys.stderr)
                page.screenshot(path="/tmp/debug-after-scroll.png")
                print("📸 已保存滚动后状态: /tmp/debug-after-scroll.png", file=sys.stderr)
            except Exception as e:
                print(f"⚠️ 额外滚动失败: {e}", file=sys.stderr)

            # 👇 尝试局部截图
            success = False
            try:
                locator = page.locator(selector)
                box = locator.bounding_box()
                if box and box["width"] > 0 and box["height"] > 0:
                    locator.screenshot(path=output_path)
                    print(f"📸 成功保存局部截图: {output_path}", file=sys.stderr)
                    success = True
                else:
                    raise Exception("Element has no visible dimensions")
            except Exception as e:
                print(f"⚠️ 局部截图失败 ({e})，尝试全页截图", file=sys.stderr)

            if not success:
                page.screenshot(path=output_path, full_page=True)
                print(f"📸 使用全页截图: {output_path}", file=sys.stderr)

                if HAS_PIL:
                    try:
                        img = Image.open(output_path)
                        width, height = img.size
                        cropped = img.crop((0, 0, width, max(0, height - 100)))
                        cropped.save(output_path)
                        print("✂️ 已自动裁剪底部 100px", file=sys.stderr)
                    except Exception as e:
                        print(f"⚠️ 自动裁剪失败: {e}", file=sys.stderr)

        except Exception as e:
            page.screenshot(path="/tmp/debug-final.png")
            print(f"💥 截图流程失败！最终状态已保存到 /tmp/debug-final.png", file=sys.stderr)
            raise e
        finally:
            browser.close()

    print(f"Saved to {output_path}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python3 screenshot.py <task_name> <output_path>", file=sys.stderr)
        sys.exit(1)

    task_name = sys.argv[1]
    output_path = sys.argv[2]

    try:
        take_screenshot(task_name, output_path)
    except Exception as e:
        print(f"ERROR: {str(e)}", file=sys.stderr)
        sys.exit(1)