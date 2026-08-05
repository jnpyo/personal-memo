FROM node:24.6-alpine
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run lint
RUN npm run test
RUN npm run build
EXPOSE 5173
CMD ["npm","run","dev"]
