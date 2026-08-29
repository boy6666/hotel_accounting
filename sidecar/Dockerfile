# Python 旁车：Excel 解析 / 归类 / 预测 / LLM 解读
FROM python:3.12-slim
WORKDIR /app

# 先装依赖，利用层缓存
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ENV TZ=Asia/Shanghai
# 容器内监听 0.0.0.0；端口不对外发布，仅 compose 网络内被主后端调用
EXPOSE 8001

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8001"]
