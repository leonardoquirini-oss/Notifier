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
 * Fetches trailer data for each plate and builds report_data structure
 * @param {Object} apiData - Raw data from API
 * @returns {Object} Processed data with report_data array
 */
async function processData(apiData) {
  // Validation
  if (!apiData) {
    throw new Error('API data is null or undefined');
  }

  if (!apiData.resultList || !Array.isArray(apiData.resultList)) {
    throw new Error('API data does not contain resultList array');
  }

  logger.info('Processing asset damage data', {
    totalRecords: apiData.resultList.length
  });

  const reportData = [];

  // Process each record sequentially
  for (const record of apiData.resultList) {
    const plate = record.assetIdentifier;
    const reportNotes = record.reportNotes;

    logger.debug('###DEBUG### plate : ' , {plate : record.assetIdentifier});

    // Skip if no plate or reportNotes
    if (!plate || !reportNotes) {
      logger.debug('Skipping record - missing plate or reportNotes', {
        id: record.id,
        plate,
        hasReportNotes: !!reportNotes
      });
      continue;
    }
    
    logger.debug('###DEBUG### plate : ' , plate);
    
    if ( plate.toLowerCase().startsWith("gbtu") ) {
      // No Container, trailers only
      continue;
    }

    // Fetch trailer data from trailer API
    const trailerData = await apiClient.fetchTrailerByPlate(plate);

    // Skip if trailer API call failed or returned null
    if (!trailerData) {
      logger.debug('Skipping record - trailer data not found', {
        id: record.id,
        plate
      });
      continue;
    }

    logger.debug('###DEBUG### TRAILER_DATA : ' , {data : trailerData});

    // Add to report data
    reportData.push({
      plate: plate,
      report_notes: reportNotes
    });

    logger.debug('Record processed successfully', {
      id: record.id,
      plate
    });
  }

  logger.info('Data processing completed', {
    totalProcessed: apiData.resultList.length,
    reportDataCount: reportData.length
  });


  logger.debug('Valkey Event : ', reportData );

  return {
    report_data: reportData
  };
}

/**
 * Executes the main job workflow
 * Always writes to Redis, even on failure
 * @param {Object} dataSource - Optional data source (if provided, skips API fetch)
 */
async function executeJob(dataSource = null) {
  const jobId = uuidv4();
  const startTime = Date.now();

  logger.info('Job started', { jobId, mode: dataSource ? 'file' : 'api' });

  const result = {
    jobId,
    timestamp: new Date().toISOString(),
    status: 'success',
    data: null,
    error: null,
    executionTime: 0,
  };

  try {
    // Step 1: Fetch data from API or use provided data
    let apiData;

    if (dataSource) {
      logger.info('Using provided data source', { jobId });
      apiData = dataSource;
    } else {
      logger.info('Fetching data from API', { jobId });
      apiData = await apiClient.fetchData();
    }

    // Step 2: Process the data
    logger.info('Processing data', { jobId });
    const processedData = await processData(apiData);

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
