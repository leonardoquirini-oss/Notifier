/**
 * Job Module
 * Main job execution logic with error handling
 */

const { v4: uuidv4 } = require('uuid');
const logger = require('./logger');
const apiClient = require('./api-client');
const redisClient = require('./redis-client');

/**
 * Processes raw API data into desired format
 * This function can be easily extended for more complex transformations
 * @param {Object} apiData - Raw data from API
 * @returns {Object} Processed data
 */
function processData(apiData) {
  // Validation
  if (!apiData) {
    throw new Error('API data is null or undefined');
  }

  // Basic transformation - can be extended as needed
  const processedData = {
    recordCount: Array.isArray(apiData) ? apiData.length : 0,
    processedAt: new Date().toISOString(),
    records: apiData,
  };

  logger.debug('Data processed', { recordCount: processedData.recordCount });

  /*
    QUI FARE IL PARSING DEI VEICOLI, PER CIASCUNO INVOCARE IL METODO 

    http://localhost:8080/api/trailers/by-plate/AB123CD
    
    Passando la targa trimmata da tutti gli spazi

    e se rispetta i criteri (da vedere con Giuseppe/Francesco) creare un oggetto:

    {
      "report_data" : [
        {
          "plate" : "AA 111 BB",
          "report_notes" : "<contenuto dell'attributo reportNotes>""
        }
      ]
    }
  */

  return processedData;
}

/**
 * Executes the main job workflow
 * Always writes to Redis, even on failure
 */
async function executeJob() {
  const jobId = uuidv4();
  const startTime = Date.now();

  logger.info('Job started', { jobId });

  const result = {
    jobId,
    timestamp: new Date().toISOString(),
    status: 'success',
    data: null,
    error: null,
    executionTime: 0,
  };

  try {
    // Step 1: Fetch data from API
    logger.info('Fetching data from API', { jobId });
    const apiData = await apiClient.fetchData();

    // Step 2: Process the data
    logger.info('Processing data', { jobId });
    const processedData = processData(apiData);

    // Step 3: Prepare success result
    result.data = processedData;

    if ( result.data.report_data.length > 0 ) {
      result.status = 'success';
    } else {
      result.status = 'no_data';
    }

    logger.info('Job completed successfully', { jobId });
  } catch (error) {
    // Step 4: Handle errors
    logger.error('Job failed', {
      jobId,
      error: error.message,
      stack: error.stack,
    });

    result.status = 'error';
    result.data = null;
    result.error = {
      message: error.message,
      code: error.code || 'UNKNOWN_ERROR',
      details: error.response?.data || {},
      stack: error.stack,
    };
  } finally {
    // Step 5: ALWAYS write result to Redis
    result.executionTime = Date.now() - startTime;

    try {
      await redisClient.writeResult(result);
      logger.info('Job result written to Valkey', {
        jobId,
        status: result.status,
        executionTime: result.executionTime,
      });
    } catch (redisError) {
      // Log Redis write failure but don't throw - job has already completed
      logger.error('Failed to write job result to Valkey', {
        jobId,
        error: redisError.message,
        originalStatus: result.status,
      });
    }
  
  }

  return result;
}

module.exports = {
  executeJob,
  processData,
};
