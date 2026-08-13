FROM node:22-alpine AS build

WORKDIR /app
COPY code/ui/package.json code/ui/package-lock.json ./
RUN npm ci
COPY code/ui/ ./
RUN npm run build

FROM nginx:stable-alpine

COPY --from=build /app/build /usr/share/nginx/html
COPY docker/ui/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
