/*******************************************************************************
 * Copyright 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.google.cloud.dataflow.examples.opinionanalysis;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.services.AbstractGoogleClientRequest;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.pubsub.Pubsub;

import org.apache.beam.runners.dataflow.DataflowRunner;
import org.apache.beam.runners.direct.DirectRunner;
import org.apache.beam.sdk.options.BigQueryOptions;
import org.apache.beam.sdk.util.Transport;

import sirocco.util.IdConverterUtils;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import javax.servlet.http.HttpServletResponse;

/**
 *
 */
public class IndexerPipelineUtils {
	
	public static final String WEBRESOURCE_TABLE = "webresource";
	public static final String WRSOCIALCOUNT_TABLE = "wrsocialcount";
	public static final String DOCUMENT_TABLE = "document";
	public static final String SENTIMENT_TABLE = "sentiment";
	public static final String STATTOPIC_TABLE = "stattopic";
	
	
	public static final DateTimeFormatter dateTimeFormatYMD_HMS =
			DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withZoneUTC();

	public static final DateTimeFormatter dateTimeFormatYMD_HMS_MSTZ =
			DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSS z");

	public static final DateTimeFormatter dateTimeFormatYMD =
	        DateTimeFormat.forPattern("yyyy-MM-dd").withZoneUTC();

	//TODO: Move the date parsing functions to DateUtils
	public static DateTime parseDateString(DateTimeFormatter formatter, String s){
		DateTime result = null;
		try {
			result = formatter.parseDateTime(s);
		} catch (Exception e) {
			result = null;	
		}
		return result;
	}
	
	public static Long parseDateToLong(DateTimeFormatter formatter, String s){
		Long result = null;
		DateTime date = parseDateString(formatter,s);
		if (date != null)
			result = date.getMillis();
		return result;
	}
	
	public static Long parseDateToLong(String s) {
		Long pt = parseDateToLong(dateTimeFormatYMD_HMS,s);
		if (pt==null) pt = parseDateToLong(dateTimeFormatYMD,s);
		return pt;
	}
	
	// same as in sirocco.utils.IdConverterUtils
	public static int getDateIdFromTimestamp(long millis)
	{
    	int result;
    	DateTime dt = new DateTime(millis,DateTimeZone.UTC);
    	int year = dt.getYear();
    	int month = dt.getMonthOfYear();
    	int day = dt.getDayOfMonth();
    	result = day + (100 * month) + (10000 * year);
    	return result;
	}
	
	public static Integer parseDateToInteger(String s) {
		Integer result = null;
		Long l = parseDateToLong(s);
		if (l!=null){
			result = getDateIdFromTimestamp(l.longValue());
		}
		return result;
	}

	
	public static String buildJdbcSourceImportQuery(IndexerPipelineOptions options){
		String timeWindow = null; 
		if (!(options.getJdbcSourceFromDate() == null || options.getJdbcSourceFromDate().isEmpty()))
			timeWindow = "	AND pages.pub_at >= '"+options.getJdbcSourceFromDate()+"'";
		if (!(options.getJdbcSourceToDate() == null || options.getJdbcSourceToDate().isEmpty())) {
			if (timeWindow!=null)
				timeWindow+=" AND ";
			else
				timeWindow="";
			timeWindow += "pages.pub_at <= '"+options.getJdbcSourceToDate() +"'";
		}
		if (timeWindow==null && options.getJdbcSourceTimeWindowSec() != null)
			timeWindow="	AND pages.pub_at >= DATE_SUB(NOW(), INTERVAL "+ options.getJdbcSourceTimeWindowSec() +" SECOND)";
		
		String result = 
			"SELECT\n"+ 
			"	pages.url AS url,\n"+
			"	unix_timestamp(pages.pub_at) AS pub_time,\n"+
			"	pages.title AS title,\n"+
			"	pages.author AS author,\n"+
			"	'en' AS language,\n"+
			"	page_texts.text AS page_text,\n"+ 
		    "	'01' AS doc_col_id,\n"+ 
			"	IFNULL(pages.duplicate_page_id, pages.id) AS col_item_id,\n"+
		    "	IF(pages.duplicate_page_id IS NOT NULL,1,0) AS skip_indexing\n"+
			"FROM pages\n"+ 
			"	LEFT JOIN page_texts on pages.id = page_texts.page_id\n"+
			"WHERE\n"+ 
			"	pages.language_id = 1\n"+
			"	AND page_texts.text IS NOT NULL\n"+ 
			timeWindow; 	
		
		return result;

	}
	
	
	public static String buildJdbcSourceImportQueryForSocialStats(IndexerPipelineOptions options){
		String timeWindow = null; 
		if (!(options.getJdbcSourceFromDate() == null || options.getJdbcSourceFromDate().isEmpty()))
			timeWindow = "	AND s.created_at >= '"+options.getJdbcSourceFromDate()+"'";
		if (!(options.getJdbcSourceToDate() == null || options.getJdbcSourceToDate().isEmpty())) {
			if (timeWindow!=null)
				timeWindow+=" AND ";
			else
				timeWindow="";
			timeWindow += "s.created_at <= '"+options.getJdbcSourceToDate() +"'";
		}
		if (timeWindow==null && options.getJdbcSourceTimeWindowSec() != null)
			timeWindow="	AND s.created_at >= DATE_SUB(NOW(), INTERVAL "+ options.getJdbcSourceTimeWindowSec() +" SECOND)";
		
		String result = 

			"SELECT\n"+ 
			"	page_pub_time,\n"+
			"	url,\n"+
			"	doc_col_id,\n"+
			"	col_item_id,\n"+
			"	MAX(count_time) AS count_time,\n"+
			"	count_tw,\n"+
			"	count_fb\n"+
			"FROM (\n"+
			"	SELECT\n"+ 
			"		unix_timestamp(p.pub_at) AS page_pub_time,\n"+
			"		p.url,\n"+
			"		'01' AS doc_col_id,\n"+
			"		page_id AS col_item_id,\n"+
			"		unix_timestamp(s.created_at) AS count_time,\n"+
			"		SUM( CASE s.provider_id WHEN 1 THEN s.count ELSE 0 END ) AS count_tw,\n"+
			"		SUM( CASE s.provider_id WHEN 2 THEN s.count ELSE 0 END ) AS count_fb\n"+   
			"	FROM social_stats s\n"+
			"		INNER JOIN pages p ON p.id = s.page_id\n"+
			"	WHERE\n"+
			"		s.count > 0\n"
			+ timeWindow +         
			"	GROUP BY 1,2,3,4,5 ) AS a1\n"+
			"GROUP BY 1,2,3,4,6,7\n"+
			"ORDER BY 1,2,3,4,6,7\n";
				
		return result;

	}	

	public static String buildBigQueryProcessedUrlsQuery(IndexerPipelineOptions options){
		String timeWindow = null; 
		
		if (options.getProcessedUrlHistorySec() != null) {
			if (options.getProcessedUrlHistorySec() != Integer.MAX_VALUE) {
				Instant fromTime = Instant.now();
				fromTime = fromTime.minus(options.getProcessedUrlHistorySec()*1000L);
				Integer fromDateId = IdConverterUtils.getDateIdFromTimestamp(fromTime.getMillis());
				timeWindow = "PublicationDateId >= " + fromDateId;
			}
		}
		
		if (timeWindow!=null)
			timeWindow="WHERE " + timeWindow;
		
		String result = 
			"SELECT Url, MAX(ProcessingTime) AS ProcessingTime\n"+ 
			"FROM " + options.getBigQueryDataset() + "." + WEBRESOURCE_TABLE + "\n" 
			+ timeWindow + "\n" +
			"GROUP BY Url";
		
		return result;
	}
	
	
	
	public static String buildBigQueryProcessedSocialCountsQuery(IndexerPipelineOptions options){
		String timeWindow = null; 
		
		if (options.getWrSocialCountHistoryWindowSec() != null) {
			if (options.getWrSocialCountHistoryWindowSec() != Integer.MAX_VALUE) {
				Instant fromTime = Instant.now();
				fromTime = fromTime.minus(options.getWrSocialCountHistoryWindowSec()*1000L);
				Integer fromDateId = IdConverterUtils.getDateIdFromTimestamp(fromTime.getMillis());
				timeWindow = "WrPublicationDateId >= " + fromDateId;
			}
		}
		
		if (timeWindow!=null)
			timeWindow="WHERE " + timeWindow;
		
		String result = 
			"SELECT WebResourceHash, MAX(CountTime) AS LastCountTime\n"+ 
			"FROM " + options.getBigQueryDataset() + "." + WRSOCIALCOUNT_TABLE + "\n" 
			+ timeWindow + "\n" +
			"GROUP BY WebResourceHash";
				
		return result;

	}
	
	
	
	public static void validateIndexerPipelineOptions(IndexerPipelineOptions options) throws Exception {
		
		int numSourceTypes = 0;
		if ( options.isSourcePubsub()) numSourceTypes++;
		if ( options.isSourceJDBC()) numSourceTypes++;
		if ( options.isSourceRecordFile()) numSourceTypes++;
		
		if (numSourceTypes ==0)
			throw new IllegalArgumentException("At least one of the Source Type options needs to be specified.");

		if (numSourceTypes > 1)
			throw new IllegalArgumentException("At most one of the Source Type options can to be specified.");
		
		if ( options.isSourcePubsub()) {
			if (options.getPubsubTopic().isEmpty())
				throw new IllegalArgumentException("Pub/Sub topic needs to be specified when Pub/Sub is being used as a source type.");
		}

		if ( options.isSourceJDBC()) {
			if (options.getJdbcDriverClassName().isEmpty() || options.getJdbcSourceUrl().isEmpty() || options.getJdbcSourceUsername().isEmpty() || options.getJdbcSourcePassword().isEmpty())
				throw new IllegalArgumentException("JDBC driver class, Url, username, and password need to be specified when JDBC is being used as a source type.");
			
			if (options.getJdbcSourceTimeWindowSec() == null &&
				(options.getJdbcSourceFromDate() == null || options.getJdbcSourceFromDate().isEmpty()) && 
				(options.getJdbcSourceToDate() == null || options.getJdbcSourceToDate().isEmpty()))
				throw new IllegalArgumentException("If Source is JDBC, one of TimeWindowSec or FromDate or ToDate parameters needs to be set.");

				if (!(options.getJdbcSourceFromDate() == null || options.getJdbcSourceFromDate().isEmpty())){
					Long l = IndexerPipelineUtils.parseDateToLong(options.getJdbcSourceFromDate());
					if (l==null)
						throw new IllegalArgumentException("Invalid value of the FromDate parameter: " + options.getJdbcSourceFromDate());
				}

				if (!(options.getJdbcSourceToDate() == null || options.getJdbcSourceToDate().isEmpty())){
					Long l = IndexerPipelineUtils.parseDateToLong(options.getJdbcSourceToDate());
					if (l==null)
						throw new IllegalArgumentException("Invalid value of the ToDate parameter: " + options.getJdbcSourceToDate());
				}
		
		}
		
		if ( options.isSourceRecordFile()) {
			if (options.getInputFile().isEmpty())
				throw new IllegalArgumentException("Input file path pattern needs to be specified when GCS is being used as a source type.");
		}
		
		if ( options.getBigQueryDataset().isEmpty()) {
			throw new IllegalArgumentException("Sink BigQuery dataset needs to be specified.");
		}
		
		
		if (options.isSourcePubsub()) {
			options.setStreaming(true);
		}
		
		if (options.isSourcePubsub()) {
			setupPubsubTopic(options);
		}
		setupRunner(options);
		
	}
	
	public static void validateSocialStatsPipelineOptions(IndexerPipelineOptions options) throws Exception {
		
		if (options.getJdbcDriverClassName().isEmpty() || options.getJdbcSourceUrl().isEmpty() || options.getJdbcSourceUsername().isEmpty() || options.getJdbcSourcePassword().isEmpty())
			throw new IllegalArgumentException("JDBC driver class, Url, username, and password need to be specified when JDBC is being used as a source type.");
		
		if (options.getJdbcSourceTimeWindowSec() == null &&
			(options.getJdbcSourceFromDate() == null || options.getJdbcSourceFromDate().isEmpty()) && 
			(options.getJdbcSourceToDate() == null || options.getJdbcSourceToDate().isEmpty()))
			throw new IllegalArgumentException("If Source is JDBC, one of TimeWindowSec or FromDate or ToDate parameters needs to be set.");

		if (!(options.getJdbcSourceFromDate() == null || options.getJdbcSourceFromDate().isEmpty())){
			Long l = IndexerPipelineUtils.parseDateToLong(options.getJdbcSourceFromDate());
			if (l==null)
				throw new IllegalArgumentException("Invalid value of the FromDate parameter: " + options.getJdbcSourceFromDate());
		}

		if (!(options.getJdbcSourceToDate() == null || options.getJdbcSourceToDate().isEmpty())){
			Long l = IndexerPipelineUtils.parseDateToLong(options.getJdbcSourceToDate());
			if (l==null)
				throw new IllegalArgumentException("Invalid value of the ToDate parameter: " + options.getJdbcSourceToDate());
		}
		
		if ( options.getBigQueryDataset().isEmpty()) {
			throw new IllegalArgumentException("Sink BigQuery dataset needs to be specified.");
		}
		
		setupBigQuery(options);
		
	}

	
	
	/**
	 * Sets up the Google Cloud Pub/Sub client and tests existence of the topic.
	 *
	 * @throws IOException
	 *             if there is a problem setting up the Pub/Sub topic
	 */
	private static void setupPubsubTopic(IndexerPipelineOptions options) throws IOException {
		
		Pubsub pubsubClient = Transport.newPubsubClient(options).build();
		String topic = options.getPubsubTopic();
		if (executeNullIfNotFound(pubsubClient.projects().topics().get(topic)) == null) {
			// pubsubClient.projects().topics().create(topic, new Topic().setName(topic)).execute();
		}
		
	}

	/**
	 * Sets up the BigQuery client.
	 *
	 * @throws IOException
	 *             if there is a problem setting up BigQuery client
	 */
	private static void setupBigQuery(IndexerPipelineOptions options) throws IOException {
		Bigquery bigQueryClient = Transport.newBigQueryClient(options.as(BigQueryOptions.class)).build();
		//return bigQueryClient;
	}


	/**
	 * Do some runner setup: check that the DirectPipelineRunner is not used in
	 * conjunction with streaming, and if streaming is specified, use the
	 * DataflowPipelineRunner. Return the streaming flag value.
	 */
	private static void setupRunner(IndexerPipelineOptions options) {
		if (options.isStreaming()) {
			if (options.getRunner() == DirectRunner.class) {
				throw new IllegalArgumentException(
						"Processing of unbounded input sources is not supported with the DirectRunner.");
			}
			// In order to cancel the pipelines automatically,
			// {@literal DataflowPipelineRunner} is forced to be used.
			options.setRunner(DataflowRunner.class);
		}
	}


	public static <T> T executeNullIfNotFound(AbstractGoogleClientRequest<T> request) throws IOException {
		try {
			return request.execute();
		} catch (GoogleJsonResponseException e) {
			if (e.getStatusCode() == HttpServletResponse.SC_NOT_FOUND) {
				return null;
			} else {
				throw e;
			}
		}
	}
}