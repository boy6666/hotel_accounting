# ===== 构建阶段：Node 构建 Vue3 + Vite =====
FROM node:20-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# .env.production 中 VITE_USE_MOCK=false，直连主后端
RUN npm run build

# ===== 运行阶段：nginx 托管静态资源并反代 /api =====
FROM nginx:alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
