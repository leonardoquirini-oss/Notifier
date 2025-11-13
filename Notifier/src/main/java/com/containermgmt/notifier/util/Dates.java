package com.containermgmt.notifier.util;


import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.TimeZone;

import org.apache.commons.lang3.time.DateUtils;

/**
 * The Class Dates.
 */
public class Dates
{

    /**
     * Instantiates a new dates.
     */
    public Dates()
    {
        // constructor
    }
    public static final String ISO_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
    
    public static final String AMAZON_REQUEST_FORMAT= "yyyyMMdd'T'HHmmss'Z'";
    /** The Constant XML_FILENAME_DATETIME. */
    public static final String VTS_FILE_METRIC_DATETIME = "yyyyMMddHHmmss";
    
    /** The Constant XML_FILENAME_DATETIME_MILLIS. */
    public static final String XML_FILENAME_DATETIME_MILLIS =  "yyyyMMddHHmmssSSS";

    public static final String XSD_DATETIME_MILLIS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
    
    public static final String XSD_DATETIME_MICROS_TZ = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'";
    
    public static final String XSD_DATETIME_MICROS_NO_TZ = "yyyy-MM-dd'T'HH:mm:ss.SSSSSS";
        
    public static final String XSD_DATETIME_NO_MILLIS = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    
    public static final String CPOA_DATETIME_NO_MILLIS = "yyyy/MM/dd'T'HH:mm:ss'Z'";

    /** The Constant XSD_DATETIME_NO_MILLIS_NO_TZ. */
    public static final String XSD_DATETIME_NO_MILLIS_NO_TZ = "yyyy-MM-dd'T'HH:mm:ss";

    
    public static final String JAVA_DATE_YYYYMMDD = "yyyyMMdd";
    
    /** The Constant JAVA_DATETIME. */
    public static final String JAVA_DATETIME = "yyyy-MM-dd HH:mm:ss";
    
    public static final String JAVA_DATE = "yyyy-MM-dd";
    
    public static final String JAVA_ITALIAN_DATE = "dd/MM/yyyy";
    
    public static final String JAVA_ITALIAN_TIMESTAMP = "dd/MM/yyyy HH:mm:ss";
    
    public static final String HHMM_FORMAT = "HHmm";

    /** The Constant UTC. */
    public static final TimeZone UTC = TimeZone.getTimeZone("UTC");

	public static final int TIMESTAMP_IGNORED = 2018;

    
     public static Date parseDate(String dateString, String datePattern) {	
    	Date date=null;
		try {
			date = DateUtils.parseDate(dateString, new String[] { datePattern });
		} catch (ParseException e) {
			e.printStackTrace();
		}
    	return date;
    }

    public static Date parse(String dateString) {	
    	Date date=null;
		
    	if ( dateString != null ) {
	    	try {
				date = DateUtils.parseDate(dateString, new String[] { ISO_DATE_FORMAT,
																	  XSD_DATETIME_MICROS_TZ,
																	  XSD_DATETIME_MICROS_NO_TZ,
																	  XSD_DATETIME_MILLIS,
																	  XSD_DATETIME_NO_MILLIS,
																	  XSD_DATETIME_NO_MILLIS_NO_TZ, 
																	  
																	  XML_FILENAME_DATETIME_MILLIS,
																	  
																	  JAVA_DATETIME, 
																	  JAVA_ITALIAN_TIMESTAMP, 
																	  JAVA_DATE, 
																	  JAVA_ITALIAN_DATE });
			} catch (ParseException e) {
//				e.printStackTrace();
			}
    	}
		
    	return date;
    }

    /** Format a date in the given <code>format</code>; TimeZone will be the same as the JVM*/
    public static String format(LocalDateTime d, String format)
    {
        if ( d == null ) // to avoid null pointer exception
            return null;

    	DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
        String formattedDateTime = d.format(formatter);
        return formattedDateTime;
    }

    
}
