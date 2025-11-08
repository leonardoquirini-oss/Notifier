/**
 * Redis Client Module
 * Handles connection to Redis and writing results to Redis Stream
 */

const Redis = require('ioredis');
const config = require('./config');
const logger = require('./logger');

class RedisClient {
  constructor() {
    this.client = null;
    this.isConnected = false;
  }

  /**
   * Establishes connection to Redis with retry logic
   */
  async connect() {
    if (this.isConnected) {
      return;
    }

    try {
      logger.info('Connecting to Redis', {
        host: config.redis.host,
        port: config.redis.port,
      });

      this.client = new Redis({
        host: config.redis.host,
        port: config.redis.port,
        retryStrategy: (times) => {
          const delay = Math.min(times * 50, 2000);
          logger.warn('Redis connection retry', { attempt: times, delay });
          return delay;
        },
        maxRetriesPerRequest: 3,
      });

      this.client.on('connect', () => {
        this.isConnected = true;
        logger.info('Redis connected successfully');
      });

      this.client.on('error', (error) => {
        logger.error('Redis connection error', { error: error.message });
        this.isConnected = false;
      });

      this.client.on('close', () => {
        logger.warn('Redis connection closed');
        this.isConnected = false;
      });

      // Wait for connection to be established
      await this.waitForConnection();
    } catch (error) {
      logger.error('Failed to connect to Redis', { error: error.message });
      throw error;
    }
  }

  /**
   * Waits for Redis connection to be established
   */
  async waitForConnection() {
    const maxWaitTime = 10000; // 10 seconds
    const startTime = Date.now();

    while (!this.isConnected && Date.now() - startTime < maxWaitTime) {
      await this.sleep(100);
    }

    if (!this.isConnected) {
      throw new Error('Redis connection timeout');
    }
  }

  /**
   * Writes job result to Redis Stream using XADD
   * @param {Object} result - Job result object
   */
  async writeResult(result) {
    try {
      if (!this.isConnected) {
        await this.connect();
      }

      const streamKey = config.redis.streamKey;

      // Convert result object to flat key-value pairs for Redis Stream
      const streamData = [
        'jobId', result.jobId,
        'eventType', result.eventType,
        'timestamp', result.timestamp,
        'status', result.status,
        'executionTime', result.executionTime.toString(),
        'data', JSON.stringify(result.data),
        'error', JSON.stringify(result.error),
      ];

      // Use XADD to append to stream (* means auto-generate ID)
      const messageId = await this.client.xadd(streamKey, '*', ...streamData);

      logger.info('Result written to Redis Stream', {
        streamKey,
        messageId,
        jobId: result.jobId,
        status: result.status,
      });

      return messageId;
    } catch (error) {
      logger.error('Failed to write to Redis Stream', {
        error: error.message,
        jobId: result.jobId,
      });
      throw error;
    }
  }

  /**
   * Closes Redis connection cleanly
   */
  async disconnect() {
    if (this.client) {
      try {
        logger.info('Closing Redis connection');
        await this.client.quit();
        this.isConnected = false;
        logger.info('Redis connection closed successfully');
      } catch (error) {
        logger.error('Error closing Redis connection', { error: error.message });
        // Force close if graceful quit fails
        this.client.disconnect();
      }
    }
  }

  /**
   * Sleep helper
   * @param {number} ms - Milliseconds to sleep
   */
  sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}

module.exports = new RedisClient();
