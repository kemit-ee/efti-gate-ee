FROM node:22-alpine AS build

WORKDIR /app
COPY code/ui/package.json code/ui/package-lock.json ./
RUN npm ci
COPY code/ui/ ./
ARG VITE_USE_PROD_TARA_URL
RUN npm run build

FROM nginx:stable-alpine

ARG VITE_USE_PROD_TARA_URL
COPY --from=build /app/build /usr/share/nginx/html
COPY docker/ui/nginx.conf /etc/nginx/conf.d/default.conf
RUN if [ "$VITE_USE_PROD_TARA_URL" = "true" ]; then \
      sed -i '/location \/tara\//,/^[[:space:]]*}/d' /etc/nginx/conf.d/default.conf; \
    fi

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
