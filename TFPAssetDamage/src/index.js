/**
 * Main Entry Point
 * Supports dual mode: cron scheduler (default) or manual execution
 */

// Load environment variables from .env file (for local development)
// Docker environment variables will override these
require('dotenv').config();

const fs = require('fs');
const { Command } = require('commander');
const cron = require('node-cron');
const config = require('./config');
const logger = require('./logger');
const redisClient = require('./redis-client');
const { executeJob } = require('./job');

// Track if a job is currently running
let isJobRunning = false;
let schedulerTask = null;

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
 * Runs job once in manual mode and exits
 * @param {string|null} filePath - Optional path to JSON file to use as data source
 */
async function runOnce(filePath = null) {
  logger.info('Running in manual mode', { filePath: filePath || 'none (using API)' });

  // Connect to Redis
  try {
    await redisClient.connect();
    logger.info('Connected to Redis');
  } catch (error) {
    logger.error('Failed to connect to Redis', { error: error.message });
    process.exit(1);
  }

  try {
    let dataSource = null;

    // If file path provided, read and parse JSON
    if (filePath) {
      logger.info('Loading data from file', { filePath });

      try {
        const fileContent = fs.readFileSync(filePath, 'utf8');
        dataSource = JSON.parse(fileContent);

        // Validate structure
        if (!dataSource.resultList || !Array.isArray(dataSource.resultList)) {
          throw new Error('Invalid JSON structure: missing resultList array');
        }

        logger.info('File loaded successfully', {
          recordCount: dataSource.resultList.length
        });
      } catch (error) {
        logger.error('Failed to read or parse file', {
          filePath,
          error: error.message
        });
        await redisClient.disconnect();
        process.exit(1);
      }
    }

    // Execute job with optional data source
    const result = await executeJob(dataSource);

    logger.info('Manual job completed', {
      status: result.status,
      executionTime: result.executionTime
    });

    // Cleanup
    await redisClient.disconnect();
    logger.info('Redis disconnected');

    // Exit with appropriate code
    process.exit(result.status === 'error' ? 1 : 0);
  } catch (error) {
    logger.error('Fatal error during manual execution', {
      error: error.message,
      stack: error.stack
    });

    await redisClient.disconnect();
    process.exit(1);
  }
}

/**
 * Graceful shutdown handler for cron mode
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
 * Runs cron scheduler mode (default)
 */
async function runScheduler() {
  logger.info('Starting TFP Asset Damage Collector in cron mode');
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
  schedulerTask = cron.schedule(
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

/**
 * Main entry point with CLI parsing
 */
async function main() {
  const program = new Command();

  program
    .name('tfp-asset-damage-collector')
    .description('TFP Asset Damage Collector - batch scheduler or manual execution')
    .version('1.0.0')
    .option('-m, --manual', 'Run once manually instead of cron scheduler')
    .option('-f, --file <path>', 'Load data from JSON file instead of API (requires --manual)')
    .parse(process.argv);

  const options = program.opts();

  // Validate options
  if (options.file && !options.manual) {
    logger.error('The --file option requires --manual mode');
    console.error('Error: The --file option requires --manual mode');
    process.exit(1);
  }

  // Execute based on mode
  if (options.manual) {
    await runOnce(options.file || null);
  } else {
    await runScheduler();
  }
}

// Start the application
main().catch((error) => {
  logger.error('Fatal error during startup', {
    error: error.message,
    stack: error.stack,
  });
  process.exit(1);
});
