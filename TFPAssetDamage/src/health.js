/**
 * Health endpoints — BERLink HEALTH_CONTRACT.md
 *
 * - GET /api/health/live  : public liveness, always 200, no dependency checks.
 * - GET /api/health/ready : readiness with Redis check. Requires X-API-Key. 503 if any check DOWN.
 * - GET /api/health       : deprecated alias of /ready (back-compat).
 */

const express = require('express');
const config = require('./config');
const logger = require('./logger');
const redisClient = require('./redis-client');

function buildLiveBody() {
  return {
    status: 'UP',
    service: config.health.serviceName,
    version: config.health.serviceVersion,
    timestamp: new Date().toISOString(),
  };
}

async function buildReadyBody() {
  const checks = {
    valkey: (await redisClient.ping()) ? 'UP' : 'DOWN',
  };
  const allUp = Object.values(checks).every((v) => v === 'UP');
  return {
    body: {
      status: allUp ? 'UP' : 'DOWN',
      service: config.health.serviceName,
      version: config.health.serviceVersion,
      timestamp: new Date().toISOString(),
      checks,
    },
    httpStatus: allUp ? 200 : 503,
  };
}

function createApp() {
  const app = express();
  app.disable('x-powered-by');

  app.get('/api/health/live', (_req, res) => {
    res.status(200).json(buildLiveBody());
  });

  const readyHandler = async (req, res) => {
    const configured = config.health.apiKey;
    if (!configured) {
      logger.error('HEALTH_API_KEY not configured: /api/health/ready unusable');
      return res.status(503).end();
    }
    if (req.header('X-API-Key') !== configured) {
      return res.status(401).end();
    }
    const { body, httpStatus } = await buildReadyBody();
    res.status(httpStatus).json(body);
  };

  app.get('/api/health/ready', readyHandler);
  app.get('/api/health', readyHandler);

  return app;
}

function startHealthServer() {
  return new Promise((resolve, reject) => {
    try {
      const app = createApp();
      const server = app.listen(config.health.port, () => {
        logger.info('Health server listening', { port: config.health.port });
        resolve(server);
      });
      server.on('error', reject);
    } catch (error) {
      reject(error);
    }
  });
}

module.exports = { createApp, startHealthServer };
