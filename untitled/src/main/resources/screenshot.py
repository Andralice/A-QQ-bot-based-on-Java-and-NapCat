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
    },
    "stock-chart": {
        "url": "https://example.com/chart",
        "selector": ".chart-container"
    },
    "kkrb-overview-2": {
        "url": "https://www.kkrb.net/?viewpage=view%2Foverview",
        "selector": "#overview-bcic-container",
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

            # 等待 JS 渲染
            time.sleep(2)

            # ✅ 精准关闭 layui 弹窗
            try:
                # 等待弹窗出现（最多 10 秒）
                page.wait_for_selector(".layui-layer-dialog", timeout=10000)
                print("🔍 发现弹窗，准备关闭...", file=sys.stderr)

                # 点击“确定”按钮（a 标签）
                page.click(".layui-layer-btn0")
                print("✅ 已点击‘确定’按钮关闭弹窗", file=sys.stderr)

                # 短暂等待确保弹窗消失
                page.wait_for_timeout(500)
            except Exception as e:
                print(f"⚠️ 弹窗未找到或点击失败（可能已自动关闭）: {e}", file=sys.stderr)

            # 等待目标容器加载
            try:
                page.wait_for_selector(selector, timeout=30000)
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

            # 👇 第二步：额外向上滚动一点（避开底部固定层）
            try:
                # 向上滚动 150px（根据你的截图调整）
                page.evaluate("window.scrollBy(0, 150);")
                print("▲ 额外向上滚动 150px 以避开底部遮挡", file=sys.stderr)
                # 📸 新增：保存滚动后的调试截图
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
                # 全页截图
                page.screenshot(path=output_path, full_page=True)
                print(f"📸 使用全页截图: {output_path}", file=sys.stderr)

                # 👇 第三步（终极兜底）：自动裁剪底部（需 Pillow）
                if HAS_PIL:
                    try:
                        img = Image.open(output_path)
                        width, height = img.size
                        # 裁掉底部 100 像素（根据你的截图调整）
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