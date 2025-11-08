/**
 * Configuration Module
 * Loads and validates environment variables
 */

const config = {
  // Scheduling
  cronSchedule: process.env.CRON_SCHEDULE || '0 18 * * *',
  timezone: process.env.TZ || 'Europe/Rome',

  // Redis
  redis: {
    host: process.env.REDIS_HOST || 'redis',
    port: parseInt(process.env.REDIS_PORT, 10) || 6379,
    streamKey: process.env.REDIS_STREAM_KEY || 'daily-job-results',
  },

  // API Externa
  api: {
    url: process.env.TPF_API_URL || 'https://api.example.com/data',
    timeout: parseInt(process.env.TPF_API_TIMEOUT, 10) || 30000,
    retryAttempts: parseInt(process.env.TPF_API_RETRY_ATTEMPTS, 10) || 3,
    username: process.env.TPF_API_USERNAME,
    password: process.env.TPF_API_PASSWORD,
  },

  // Trailer API
  trailerApi: {
    url: process.env.TRAILER_API_URL || 'http://localhost:8080',
    apiKey: process.env.TRAILER_API_KEY,
    timeout: parseInt(process.env.TRAILER_API_TIMEOUT, 10) || 30000,
  },

  // Logging
  logLevel: process.env.LOG_LEVEL || 'info',
};

/**
 * Validates required configuration
 * @throws {Error} if required config is missing
 */
function validateConfig() {
  const required = [
    'TPF_API_USERNAME',
    'TPF_API_PASSWORD',
    'TRAILER_API_KEY',
  ];

  const missing = required.filter(key => !process.env[key]);

  if (missing.length > 0) {
    throw new Error(`Missing required environment variables: ${missing.join(', ')}`);
  }
}

// Validate on module load
validateConfig();

module.exports = config;
