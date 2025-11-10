# Multi-stage Dockerfile for TFP Asset Damage Collector
# Uses Node.js 18 Alpine for minimal image size

# Stage 1: Dependencies
FROM node:20-alpine AS deps

WORKDIR /app

# Copy package files
COPY package*.json ./

# Installa dipendenze
RUN npm install --legacy-peer-deps

# Stage 2: Production image
FROM node:20-alpine AS runner

# Install dumb-init for proper signal handling
RUN apk add --no-cache dumb-init

WORKDIR /app

# Create non-root user
RUN addgroup -g 1001 -S nodejs && \
    adduser -S nodejs -u 1001

# Copy dependencies from deps stage
COPY --from=deps --chown=nodejs:nodejs /app/node_modules ./node_modules

# Copy application code
COPY --chown=nodejs:nodejs package*.json ./
COPY --chown=nodejs:nodejs src ./src

# Switch to non-root user
USER nodejs

# Set environment
ENV NODE_ENV=production

# Expose health check port (optional - for future HTTP health endpoint)
EXPOSE 3000 9229

# Use dumb-init to handle signals properly
ENTRYPOINT ["dumb-init", "--"]

# Start the application
CMD ["node", "--inspect=0.0.0.0:9229", "src/index.js"]
