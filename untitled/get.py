import requests
import json
import time


def fetch_all_pets():
    base_url = "https://wiki.lcx.cab/lk/get_pokemon_data.php"
    all_pets = []
    page = 1

    # 这里的 total 字段在数据里出现了，我们可以利用它来预判总数，
    # 但为了简单起见，我们还是用“直到返回空列表”来判断结束。

    print(f"🚀 开始抓取数据...")

    while True:
        params = {
            "page": page,
            "exclude_details": 1
        }

        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Referer": "https://wiki.lcx.cab/lk/index.php"
        }

        try:
            response = requests.get(base_url, params=params, headers=headers, timeout=10)

            if response.status_code == 200:
                data = response.json()

                # 检查是否是列表
                if not isinstance(data, list):
                    print("❌ 返回的数据不是列表，请检查接口。")
                    break

                # 如果这一页没有数据了，结束
                if len(data) == 0:
                    print(f"✅ 第 {page} 页无数据，抓取结束。")
                    break

                # 解析当前页数据
                for item in data:
                    # 逻辑：优先使用 form_display_name (如 "霜翅领主")
                    # 如果没有 form_display_name，则使用 name (如 "岚鸟冬天的样子")
                    # 注意：JSON中是 unicode 编码，requests 会自动处理
                    name = item.get('form_display_name') or item.get('name')

                    if name:
                        all_pets.append(name)

                print(f"📄 已处理第 {page} 页，当前共获取 {len(all_pets)} 个宠物。")
                page += 1
                time.sleep(0.3)  # 稍微延时，保护服务器也防止被封

            else:
                print(f"❌ 请求失败，状态码: {response.status_code}")
                break

        except Exception as e:
            print(f"❌ 发生错误: {e}")
            break

    return all_pets


# 执行
if __name__ == "__main__":
    pets_list = fetch_all_pets()

    # 去重（防止不同页面有重复数据）
    pets_list = list(dict.fromkeys(pets_list))

    # 按照你的规格生成 JSON
    result_json = {
        "pets": pets_list
    }

    # 保存文件
    with open('pets.json', 'w', encoding='utf-8') as f:
        json.dump(result_json, f, ensure_ascii=False, indent=2)

    print(f"\n🎉 完成！共获取 {len(pets_list)} 个宠物名字，已保存至 pets.json")
    print("前5个示例:", pets_list[:5])