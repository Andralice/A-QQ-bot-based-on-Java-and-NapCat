# 最简单的用法，会自动下载所有必要文件
from ChatTTS import Chat

chat = Chat()
chat.load_models()  # 这行代码会自动从 Hugging Face 下载模型