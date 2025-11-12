/**
 * API Client Module
 * Handles authentication and API calls to TFP service with retry logic
 */

const axios = require('axios');
const config = require('./config');
const logger = require('./logger');

class ApiClient {
  constructor() {
    this.token = null;
    this.axiosInstance = axios.create({
      baseURL: config.api.url,
      timeout: config.api.timeout,
      headers: {
        'Content-Type': 'application/json',
      },
    });
  }

  /**
   * Performs login and retrieves Bearer token
   * @returns {Promise<string>} Authentication token
   */
  async login() {
    try {
      logger.info('Attempting API login', { url: `${config.api.url}core/auth/` });

      const response = await this.axiosInstance.post('core/auth/', {
        username: config.api.username,
        password: config.api.password,
      });

      if (!response.data || !response.data.token) {
        throw new Error('Login response does not contain token');
      }

      this.token = response.data.token;
      logger.info('API login successful');
      logger.debug('Token : ', response.data.token);

      return this.token;
    } catch (error) {
      logger.error('API login failed', {
        error: error.message,
        status: error.response?.status,
        data: error.response?.data,
      });
      throw error;
    }
  }

  /**
   * Fetches asset damage data from TFP API with automatic pagination
   * @param {string} filterType - Asset Type filter (e.g., 'UNIT', 'VEHICLE')
   * @param {string} filterStatus - Status filter (e.g., 'OPEN', 'UNDER_REPAIR')
   * @returns {Promise<Object>} Asset damage data with all results
   */
  async fetchAssetDamage(filterType, filterStatus) {
    // Ensure we have a valid token
    if (!this.token) {
      await this.login();
    }

    const limit = 200;
    let offset = 0;
    let allResults = [];
    let totalCount = 0;
    let fetchedCount = 0;

    try {
      logger.info('Fetching asset damage data [type - status] : ', filterType, filterStatus);

      // First request to get initial data and totalCount
      const firstRequestBody = {
        offset: 0,
        limit: limit,
        filter: {
          enabled: 1,
          assetType: filterType,
          status: filterStatus,
        },
        sortingList: [
          {
            column: 'reportTime',
            direction: 'DESC',
          },
        ],
      };

      const firstResponse = await this.makeAuthenticatedRequest(
        'units-tracking/assetdamage/browse',
        firstRequestBody
      );

      // Collect first batch of results
      if (firstResponse.data?.resultList && Array.isArray(firstResponse.data.resultList)) {
        allResults = [...firstResponse.data.resultList];
        fetchedCount = allResults.length;
      }

      totalCount = firstResponse.data?.totalCount || 0;

      logger.info('First batch fetched', {
        fetchedCount,
        totalCount,
        status: filterStatus
      });

      // If there are more results to fetch, continue with pagination
      if (totalCount > limit) {
        offset = limit;

        while (fetchedCount < totalCount) {
          logger.info('Fetching next batch', {
            offset,
            fetchedCount,
            totalCount,
            status: filterStatus
          });

          const requestBody = {
            offset: offset,
            limit: limit,
            filter: {
              enabled: 1,
              assetType: filterType,
              status: filterStatus,
            },
            sortingList: [
              {
                column: 'reportTime',
                direction: 'DESC',
              },
            ],
          };

          const response = await this.makeAuthenticatedRequest(
            'units-tracking/assetdamage/browse',
            requestBody
          );

          if (response.data?.resultList && Array.isArray(response.data.resultList)) {
            allResults = allResults.concat(response.data.resultList);
            fetchedCount = allResults.length;
          }

          offset += limit;
        }
      }

      logger.info('Asset damage data fetched successfully', {
        totalCount,
        fetchedCount,
        status: filterStatus
      });

      // Return in the same format as original API response
      return {
        resultList: allResults,
        totalCount: totalCount
      };
    } catch (error) {
      logger.error('Failed to fetch asset damage data', {
        error: error.message,
        status: error.response?.status,
        data: error.response?.data,
      });
      throw error;
    }
  }

  /**
   * Makes an authenticated API request with automatic retry on 401
   * @param {string} endpoint - API endpoint
   * @param {Object} requestBody - Request payload
   * @returns {Promise<Object>} API response
   */
  async makeAuthenticatedRequest(endpoint, requestBody) {
    try {
      const response = await this.axiosInstance.post(
        endpoint,
        requestBody,
        {
          headers: {
            Authorization: `Bearer ${this.token}`,
          },
        }
      );

      return response;
    } catch (error) {
      // If unauthorized, try to re-login once and retry
      if (error.response?.status === 401) {
        logger.warn('Token expired, attempting re-login');
        await this.login();

        // Retry the request with new token
        const retryResponse = await this.axiosInstance.post(
          endpoint,
          requestBody,
          {
            headers: {
              Authorization: `Bearer ${this.token}`,
            },
          }
        );

        return retryResponse;
      }

      throw error;
    }
  }

  /**
   * Executes API call with exponential backoff retry
   * @param {Function} apiCall - The API call function to execute
   * @returns {Promise<any>} API response
   */
  async executeWithRetry(apiCall) {
    let lastError;

    for (let attempt = 1; attempt <= config.api.retryAttempts; attempt++) {
      try {
        return await apiCall();
      } catch (error) {
        lastError = error;
        const isLastAttempt = attempt === config.api.retryAttempts;

        if (isLastAttempt) {
          logger.error('All retry attempts exhausted', {
            attempts: attempt,
            error: error.message,
          });
          throw error;
        }

        // Exponential backoff: 2^attempt * 1000ms (1s, 2s, 4s, etc.)
        const backoffMs = Math.pow(2, attempt) * 1000;

        logger.warn('API call failed, retrying', {
          attempt,
          maxAttempts: config.api.retryAttempts,
          nextRetryIn: `${backoffMs}ms`,
          error: error.message,
        });

        await this.sleep(backoffMs);
      }
    }

    throw lastError;
  }

  /**
   * Sleep helper for backoff
   * @param {number} ms - Milliseconds to sleep
   */
  sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  /**
   * Fetches trailer information by plate from Trailer API
   * @param {string} plate - Vehicle plate number (will be trimmed)
   * @returns {Promise<Object|null>} Trailer data or null if not found/error
   */
  async fetchTrailerByPlate(plate) {
    try {
      // Trim all spaces from the plate
      const trimmedPlate = plate.replace(/\s/g, '');

      logger.debug('Fetching trailer by plate', {
        originalPlate: plate,
        trimmedPlate
      });

      const trailerAxios = axios.create({
        baseURL: config.trailerApi.url,
        timeout: config.trailerApi.timeout,
        headers: {
          'X-API-Key': config.trailerApi.apiKey,
          'Content-Type': 'application/json',
        },
      });

      const response = await trailerAxios.get(`/api/trailers/by-plate/${trimmedPlate}`);

      logger.debug('Trailer data fetched successfully', {
        plate: trimmedPlate,
        hasReportNotes: !!response.data?.reportNotes
      });

      return response.data;
    } catch (error) {
      logger.warn('Failed to fetch trailer data for plate', {
        plate,
        error: error.message,
        status: error.response?.status,
      });
      // Return null to skip this record as per requirements
      return null;
    }
  }

  /**
   * Main entry point to fetch data with retry logic
   * @returns {Promise<Object>} Asset damage data
   */
  async fetchAssetDamageData(filterType, filterStatus) {
    return this.executeWithRetry(() => this.fetchAssetDamage(filterType, filterStatus));
  }  
}

module.exports = new ApiClient();
