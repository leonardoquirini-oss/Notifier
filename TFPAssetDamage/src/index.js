/**
 * Main Entry Point
 * Initializes scheduler and handles graceful shutdown
 */

const cron = require('node-cron');
const config = require('./config');
const logger = require('./logger');
const redisClient = require('./redis-client');
const { executeJob } = require('./job');

// Track if a job is currently running
let isJobRunning = false;

/**
 * Scheduler job wrapper with concurrent execution protection
 */
async function scheduledJobWrapper() {
  if (isJobRunning) {
    logger.warn('Previous job still running, skipping this execution');
    return;
  }

  isJobRunning = true;

  try {
    await executeJob();
  } catch (error) {
    logger.error('Unhandled error in scheduled job', {
      error: error.message,
      stack: error.stack,
    });
  } finally {
    isJobRunning = false;
  }
}

/**
 * Graceful shutdown handler
 */
async function gracefulShutdown(signal) {
  logger.info(`Received ${signal}, starting graceful shutdown`);

  // Stop accepting new jobs
  if (schedulerTask) {
    schedulerTask.stop();
    logger.info('Scheduler stopped');
  }

  // Wait for current job to complete (with timeout)
  const shutdownTimeout = 60000; // 60 seconds
  const startTime = Date.now();

  while (isJobRunning && Date.now() - startTime < shutdownTimeout) {
    logger.info('Waiting for current job to complete...');
    await sleep(1000);
  }

  if (isJobRunning) {
    logger.warn('Shutdown timeout reached, forcing exit');
  }

  // Close Redis connection
  await redisClient.disconnect();

  logger.info('Graceful shutdown completed');
  process.exit(0);
}

/**
 * Sleep helper
 * @param {number} ms - Milliseconds to sleep
 */
function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Main initialization
 */
async function main() {
  logger.info('Starting TFP Asset Damage Collector');
  logger.info('Configuration', {
    cronSchedule: config.cronSchedule,
    timezone: config.timezone,
    redisHost: config.redis.host,
    redisPort: config.redis.port,
    apiUrl: config.api.url,
  });

  // Validate cron expression
  if (!cron.validate(config.cronSchedule)) {
    logger.error('Invalid cron schedule', { schedule: config.cronSchedule });
    process.exit(1);
  }

  // Connect to Redis
  try {
    await redisClient.connect();
  } catch (error) {
    logger.error('Failed to connect to Redis, exiting', { error: error.message });
    process.exit(1);
  }

  // Setup scheduler
  const schedulerTask = cron.schedule(
    config.cronSchedule,
    scheduledJobWrapper,
    {
      scheduled: true,
      timezone: config.timezone,
    }
  );

  logger.info('Scheduler started successfully', {
    schedule: config.cronSchedule,
    timezone: config.timezone,
  });

  // Setup graceful shutdown handlers
  process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
  process.on('SIGINT', () => gracefulShutdown('SIGINT'));

  // Keep the process running
  logger.info('Service is running, waiting for scheduled jobs...');
}

// Start the application
main().catch((error) => {
  logger.error('Fatal error during startup', {
    error: error.message,
    stack: error.stack,
  });
  process.exit(1);
});
