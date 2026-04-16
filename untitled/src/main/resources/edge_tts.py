#!/opt/qq-bot/tts/venv/bin/python3
# -*- coding: utf-8 -*-

import sys
import asyncio
import edge_tts

async def main():
    if len(sys.argv) != 3:
        print("Usage: edge_tts.py <text> <output.mp3>", file=sys.stderr)
        sys.exit(1)

    text = sys.argv[1]
    output_file = sys.argv[2]

    # 使用糖果熊专属音色 + 语速
    communicate = edge_tts.Communicate(
        text=text,
        voice="zh-CN-XiaoyiNeural",  # 17岁甜美少女
        rate="+10%",                 # 更活泼
        pitch="+0Hz"
    )
    await communicate.save(output_file)
    print(f"Success: {output_file}", file=sys.stderr)

if __name__ == "__main__":
    asyncio.run(main())