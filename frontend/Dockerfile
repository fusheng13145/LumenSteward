# ===== 构建阶段（Node 20，与 CI 固定的 node-version 一致） =====
FROM node:20-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# ===== 运行阶段（Nginx 静态托管 + API 反向代理，NFR-PO-01） =====
FROM nginx:1.27-alpine AS runtime
RUN ln -snf /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && echo Asia/Shanghai > /etc/timezone
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
