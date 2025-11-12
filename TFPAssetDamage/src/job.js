/**
 * Job Module
 * Main job execution logic with error handling
 */

const { v4: uuidv4 } = require('uuid');
const logger = require('./logger');
const apiClient = require('./api-client');
const redisClient = require('./redis-client');

const sanitize = v => (v == null || v === 'null' ? '' : v);

/**
 * Formats ISO date string to DD/MM/YYYY HH:MM:SS format
 * @param {string} isoDateString - ISO date string (e.g., "2025-10-21T14:44:29Z")
 * @returns {string} Formatted date string (e.g., "21/10/2025 14:44:29")
 */
function formatDate(isoDateString) {
  if (!isoDateString) {
    return '';
  }

  try {
    const date = new Date(isoDateString);

    const day = String(date.getDate()).padStart(2, '0');
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const year = date.getFullYear();

    const hours = String(date.getHours()).padStart(2, '0');
    const minutes = String(date.getMinutes()).padStart(2, '0');
    const seconds = String(date.getSeconds()).padStart(2, '0');

    return `${day}/${month}/${year} ${hours}:${minutes}:${seconds}`;
  } catch (error) {
    logger.warn('Failed to format date', { isoDateString, error: error.message });
    return isoDateString;
  }
}

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

  logger.info('Processing asset damage data ', {
    totalRecords: apiData.resultList.length
  });

  // Step 1: Filter and sort records by plate
  const sortedRecords = apiData.resultList
    .filter(record => {
      const plate = record.assetIdentifier ?? '';
      const status = record.status;
      const reportNotes = record.reportNotes ?? '';

      // Skip if no plate or reportNotes
      if (!plate || ( status === 'OPEN' && !reportNotes) ) {
        logger.debug('Skipping record - missing plate or reportNotes (if its an OPEN issue)', {
          id: record.id,
          plate,
          status,
          hasReportNotes: !!reportNotes
        });
        return false;
      }

      // No Container, trailers only
      if (plate.toLowerCase().startsWith("gbtu")) {
        return false;
      }

      return true;
    })
    .sort((a, b) => a.assetIdentifier.localeCompare(b.assetIdentifier));

   logger.debug('###DEBUG### LISTA SEGNALAZIONI', { sortedRecords } );

  // Step 2: Group records by status and then by plate
  const groupedByStatusAndPlate = {
    open: {},
    under_repair: {}
  };

  for (const record of sortedRecords) {
    const plate = record.assetIdentifier;
    const status = record.status;

    // Map status to snake_case key
    const statusKey = status === 'OPEN' ? 'open' :
                      status === 'UNDER_REPAIR' ? 'under_repair' : null;

    // Skip if status is not recognized
    if (!statusKey) {
      logger.debug('Skipping record - unknown status', {
        id: record.id,
        status
      });
      continue;
    }

    if (!groupedByStatusAndPlate[statusKey][plate]) {
      groupedByStatusAndPlate[statusKey][plate] = [];
    }

    groupedByStatusAndPlate[statusKey][plate].push({
      priority: record.severity || 'UNKNOWN',
      report_time: formatDate(record.reportTime),
      report_time_raw: record.reportTime, // Keep raw for sorting
      report_notes: sanitize(record.reportNotes)
    });
  }

  // Step 3: Process each status group
  const priorityOrder = { 'HIGH': 1, 'MEDIUM': 2, 'LOW': 3, 'UNKNOWN': 4 };
  const reportData = {
    open: [],
    under_repair: []
  };

  // Process plates for each status
  for (const status of ['open', 'under_repair']) {
    const platesForStatus = groupedByStatusAndPlate[status];

    for (const plate of Object.keys(platesForStatus)) {
      logger.debug('###DEBUG### Processing plate : ', { status, plate });

      // Fetch trailer data from trailer API (once per plate)
      const trailerData = await apiClient.fetchTrailerByPlate(plate);

      // Skip if trailer API call failed or returned null
      if (!trailerData) {
        logger.debug('Skipping plate - trailer data not found', { status, plate });
        continue;
      }

      logger.debug('###DEBUG### TRAILER_DATA : ', { data: trailerData });

      // Sort reports by priority, then by date (newest first)
      const sortedReports = platesForStatus[plate].sort((a, b) => {
        const priorityA = priorityOrder[a.priority] || 999;
        const priorityB = priorityOrder[b.priority] || 999;

        // First, compare by priority
        if (priorityA !== priorityB) {
          return priorityA - priorityB;
        }

        // If same priority, sort by date (newest first)
        const dateA = new Date(a.report_time_raw || 0);
        const dateB = new Date(b.report_time_raw || 0);
        return dateB - dateA;
      });

      // Remove the raw date field before adding to final result
      const reportsWithoutRawDate = sortedReports.map(({ report_time_raw, ...report }) => report);

      // Add to report data with grouped structure
      reportData[status].push({
        plate: plate,
        report: reportsWithoutRawDate
      });

      logger.debug('Plate processed successfully', {
        status,
        plate,
        reportCount: sortedReports.length
      });
    }
  }

  const totalReports = reportData.open.reduce((sum, item) => sum + item.report.length, 0) +
                       reportData.under_repair.reduce((sum, item) => sum + item.report.length, 0);

  logger.info('Data processing completed', {
    totalProcessed: apiData.resultList.length,
    openPlates: reportData.open.length,
    underRepairPlates: reportData.under_repair.length,
    totalReports: totalReports
  });

  logger.debug('Valkey Event : ', reportData);

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
    eventType: 'trailer:damage_report',
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
      logger.info('Fetching Damage data from API', { jobId });

      // =======================================================================
      // UNIT - OPEN 
      let damageUnitApiData     = await apiClient.fetchAssetDamageData('UNIT','OPEN');
      apiData = damageUnitApiData;

      let num = damageUnitApiData?.resultList?.length;
      logger.info('--> Response for UNIT/OPEN status : ', { num });
      // =======================================================================
      // UNIT - UNDER REPAIR
      let repairinigUnitApiData = await apiClient.fetchAssetDamageData('UNIT','UNDER_REPAIR');
      apiData.resultList.push(...repairinigUnitApiData.resultList);

      num = repairinigUnitApiData?.resultList?.length;
      logger.info('--> Response for UNIT/UNDER_REPAIR status : ', { num });
      // =======================================================================
      // UNIT - OPEN 
      let damageVehicleApiData     = await apiClient.fetchAssetDamageData('VEHICLE','OPEN');
      apiData.resultList.push(...damageVehicleApiData.resultList);

      num = damageVehicleApiData?.resultList?.length;
      logger.info('--> Response for VEHICLE/OPEN status : ', { num });
      // =======================================================================
      // UNIT - UNDER REPAIR
      let repairinigVehicleApiData = await apiClient.fetchAssetDamageData('VEHICLE','UNDER_REPAIR');
      apiData.resultList.push(...repairinigVehicleApiData.resultList);

      num = repairinigVehicleApiData?.resultList?.length;
      logger.info('--> Response for VEHICLE/UNDER_REPAIR status : ', { num });
    }

    // Step 2: Process the data
    logger.info('Processing data, num record : ', { num : apiData.resultList.length });

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
    result.status = 'error';
    result.data = null;
    result.error = {
      message: error.message || 'Unknown error',
      code: error.code || 'UNKNOWN_ERROR',
      details: error.response?.data || null,
      stack: error.stack || null,
    };

    // Step 4: Handle errors
    logger.error('Job failed', {
      jobId,
      result : result
    });
    
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
