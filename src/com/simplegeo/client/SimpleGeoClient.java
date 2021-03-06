/**
 * Copyright (c) 2009-2010, SimpleGeo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without 
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, 
 * this list of conditions and the following disclaimer. Redistributions 
 * in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or 
 * other materials provided with the distribution.
 * 
 * Neither the name of the SimpleGeo nor the names of its contributors may
 * be used to endorse or promote products derived from this software 
 * without specific prior written permission.
 *  
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE 
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE 
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS 
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR 
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE 
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER 
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, 
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.simplegeo.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.logging.Logger;

import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.simplegeo.client.concurrent.RequestThreadPoolExecutor;
import com.simplegeo.client.encoder.GeoJSONEncoder;
import com.simplegeo.client.geojson.GeoJSONObject;
import com.simplegeo.client.http.GeoJSONHandler;
import com.simplegeo.client.http.JSONHandler;
import com.simplegeo.client.http.OAuthHttpClient;
import com.simplegeo.client.http.RecordHandler;
import com.simplegeo.client.http.SimpleGeoHandler;
import com.simplegeo.client.http.exceptions.APIException;
import com.simplegeo.client.http.exceptions.UnsupportedHandlerException;
import com.simplegeo.client.model.DefaultRecord;
import com.simplegeo.client.model.Envelope;
import com.simplegeo.client.model.GeoJSONRecord;
import com.simplegeo.client.model.IRecord;
import com.simplegeo.client.query.HistoryQuery;
import com.simplegeo.client.query.NearbyQuery;

/**
 * Interfaces with the SimpleGeo API. Requests are created through 
 * {@link com.simplegeo.client.http.OAuthHttpClient}, which is created
 * with a thread-safe connection manager.
 * 
 * Requests can be created and set on the same thread or they can be
 * used in a non-blocking fashion. The default behavior is to send the
 * request on the same thread the call was made. By setting 
 * {@link com.simplegeo.client.SimpleGeoClient#futureTask} to true,
 * requests will be built and sent on a thread chosen by
 * {@link com.simplegeo.client.concurrent.RequestThreadPoolExcecutor} and
 * a {@link java.util.concurrent.FutureTask} will be returned instead of
 * the return object that is build from the ResponseHandler.
 * 
 * In order to properly authenticate requests, an OAuth token is required.
 * This property is set by calling 
 * {@link com.simplegeo.client.http.OAuthHttpClient#setToken(String, String)}.
 * 
 * @author Derek Smith
 */
public class SimpleGeoClient {
	
	private static Logger logger = Logger.getLogger(SimpleGeoClient.class.getName());
	
	private static final String mainURL = "http://api.simplegeo.com/0.1";
	
	private static SimpleGeoClient sharedLocationService = null;
	
	private OAuthHttpClient httpClient = null;
	private RequestThreadPoolExecutor threadExecutor = null;
	
	private RecordHandler recordHandler = null;
	private GeoJSONHandler geoJSONHandler = null;
	private JSONHandler jsonHandler = null;
	private SimpleGeoHandler simpleGeoHandler = null;
	
	/**
	 * Tells the service whether to make the Http call on the same thread.
	 */
	public boolean futureTask = false; 
	
	/**
	 * Enums that are used to differentiate between different handlers.
	 */
	static public enum Handler { JSON, GEOJSON, RECORD, SIMPLEGEO }
	
	/**
	 * @return the shared instance of this class
	 */
	static public SimpleGeoClient getInstance() {
		
		if(sharedLocationService == null)
			sharedLocationService = new SimpleGeoClient();
		
		return sharedLocationService;
	}
	
	private SimpleGeoClient() {
		
		// We want to make sure the client is threadsafe
		HttpParams params = new BasicHttpParams();
		HttpProtocolParams.setUseExpectContinue(params, false);
		SchemeRegistry schemeRegistry = new SchemeRegistry();
		schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
		ThreadSafeClientConnManager connManager = new ThreadSafeClientConnManager(params, schemeRegistry);
		
		this.httpClient = new OAuthHttpClient(connManager, params);
		this.threadExecutor = new RequestThreadPoolExecutor("SimpleGeoClient");
		
		setHandler(Handler.JSON, new JSONHandler());
		setHandler(Handler.RECORD, new RecordHandler());
		setHandler(Handler.GEOJSON, new GeoJSONHandler());
		
		this.simpleGeoHandler = new SimpleGeoHandler();
	}

	/**
	 * Set the handler per type.
	 * 
	 * This is useful if subclasses or implementations of the model
	 * objects change and the response object needs to be built 
	 * differently.
	 * 
	 * @param type the Handler type to override
	 * @param handler the handler must be a subclass of SimpleGeoHandler to
	 * ensure that a valid response was recieved
	 */
	public void setHandler(Handler type, SimpleGeoHandler handler) {
		
		switch(type) {
			case GEOJSON:
				geoJSONHandler = (GeoJSONHandler)handler;
				break;
			case RECORD:
				recordHandler = (RecordHandler)handler;
				break;
			case JSON:
				jsonHandler = (JSONHandler)handler;
				break;
			default:
				break;
		}
	}
	
	/**
	 * @param type the type of handler to retrieved defined by 
	 * {@link com.simplegeo.client.Handler}
	 * @return the instance of {@link com.simplegeo.client.http.SimpleGeoHandler}
	 * that is associated with the type
	 */
	public SimpleGeoHandler getHandler(Handler type) {
		
		SimpleGeoHandler handler = null;
		switch(type) {
			
			case GEOJSON:
				handler = geoJSONHandler;
				break;
			case RECORD:
				handler = recordHandler;
				break;
			case JSON:
				handler = jsonHandler;
				break;
			default:
				break;
				
		}
		
		return handler;
	}
		
	/**
	 * Retrieves information from SimpleGeo about the 
	 * {@link com.simplegeo.client.model.IRecord}. The record
	 * must already exist in SimpleGeo in order for the request to be
	 * successful. 
	 * 
	 * @param defaultRecord
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	public Object retrieve(IRecord defaultRecord) throws ClientProtocolException, IOException {
		
		List<IRecord> list = new ArrayList<IRecord>();
		list.add(defaultRecord);
		
		Object object = retrieve(list);
		
		return futureTask ? object : getIRecord(retrieve(list));
	}	
					
	/**
	 * Retrieves information from SimpleGeo about all of the 
	 * {@link com.simplegeo.client.model.IRecord}s within the list. Atleast
	 * one record must already exist in SimpleGeo in order for the request to
	 * be successful.
	 * 
	 * @param records
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object retrieve(List<IRecord> records) throws ClientProtocolException, IOException {
		
		if(!records.isEmpty()) {
			
			IRecord firstRecord = records.get(0);
			return retrieve(getLayer(records), getRecordIds(records), getHandler(firstRecord));
		}
		
		return null;
	}
	
	/**
	 * Retrieve a information from SimpleGeo about all the record ids within
	 * the list that are contained within a layer.
	 * 
	 * @param layer the name of the layer to find the records in
	 * @param recordIds the ids of records to retrieve
	 * @param handler the handler responsible for creating the return object
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	private Object retrieve(String layer, String recordIds, SimpleGeoHandler handler) throws ClientProtocolException, IOException {
		
		String uri = mainURL + String.format("/records/%s/%s.json", layer, recordIds);
		
		logger.info( String.format("retrieving %s from %s", layer, recordIds));
		
		return execute(new HttpGet(uri), handler);
	}
		
	/**
	 * Updates the {@link com.simplegeo.client.model.IRecord} in SimpleGeo, creating the
	 * record if necessary.
	 * 
	 * @param record the record to update
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object update(IRecord record) throws ClientProtocolException, IOException {		
		return update(getGeoJSONObject(record));
	}
	
	/**
	 * Updates the list of {@link com.simplegeo.client.model.IRecord} in SimpleGeo, creating
	 * them if ncessary.
	 * 
	 * @param records the records to update
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object update(List<IRecord> records) throws ClientProtocolException, IOException {		
		return update(getGeoJSONObject(records));
	}

	/**
	 * Uses the {@link com.simplegeo.client.geojson.GeoJSONObject} to update records 
	 * in SimpleGeo, creating the record if necessary.
	 * 
	 * @param geoJSONObject the geojson object to send in the request
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object update(GeoJSONObject geoJSONObject) throws ClientProtocolException, IOException {
		
		GeoJSONObject record = null;
		if(geoJSONObject.isFeature()) {
			
			record = new GeoJSONRecord("FeatureCollection");
			
			try {
				
				JSONArray features = new JSONArray();
				features.put(geoJSONObject);
				record.setFeatures(features);
				
			} catch (JSONException e) {
				e.printStackTrace();
			}
			
		} else {
			
			record = geoJSONObject;
			
		}
		
		return update(getLayer(record), getGeoJSONObject(record), getHandler(record));
		
	}
	
	/**
	 * Uses the {@link com.simplegeo.client.geojson.GeoJSONObject} and layer name
	 * to update records in SimpleGeo, creating the record if necessary.
	 * 
	 * @param layer the layer to add the records to
	 * @param geoJSONObject the GeoJSON object that contains a FeatureCollection
	 * @param handler the handler responsible for creating the return object
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object update(String layer, GeoJSONObject geoJSONObject, SimpleGeoHandler handler) 
	throws ClientProtocolException, IOException {
	
		String uri = mainURL + String.format("/records/%s.json", layer);
		HttpPost post = new HttpPost(uri);
		
		String jsonString = geoJSONObject.toString();
		post.setEntity(new ByteArrayEntity(jsonString.getBytes()));
		
		logger.info(String.format("updating %s", jsonString));
		
		return execute(post, handler);
	}
	
	/**
	 * Deletes the {@link com.simplegeo.client.model.IRecord} from SimpleGeo.
	 * The record must already exist in SimpleGeo in order for the request to be successful. 
	 * 
	 * @param record the record to delete
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object delete(IRecord record) throws ClientProtocolException, IOException {
		
		if(record != null)
			return delete(record.getLayer(), record.getRecordId(), getHandler(record));
		
		return null;
	}
	
	/**
	 * Deletes the record form SimpleGeo. The record must already exist in SimpleGeo in
	 * order for the request to be successful.
	 * 
	 * @param layer the name of the layer that holds the record.
	 * @param recordId the id of the record.
	 * @param handler the handler responsible for creating the return object
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object delete(String layer, String recordId, SimpleGeoHandler handler) 
		throws ClientProtocolException, IOException {
		
		String uri = mainURL + String.format("/records/%s/%s.json", layer, recordId);
		logger.info(String.format("deleting %s at %s", layer, recordId));
		return execute(new HttpDelete(uri), handler);
	}
	
	/**
	 * Return a reverse chronological list of where a record has been over time.
	 * Currently only returns the last 10 places a record has been. The response
	 * is a GeoJSON GeometryCollection containing a list of Point objects,
	 * each with a created field containing the timestamp. 
	 * 
	 * This query supports pagination which means "next_cursor" will be set as a top-level key/value
	 * pair if there are more data points to offer after the initial query limit has been reached.
	 * You can use the value at "next_cursor" to get the remaining results of the query.
	 * @see com.simpelgeo.com.client.service.query.IQuery#setCursor(String).
	 * 
	 * @param query the history query object
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	public Object history(HistoryQuery query) throws ClientProtocolException, IOException{
		return history(query, Handler.GEOJSON);
	}

	/**
	 * Return a reverse chronological list of where a record has been over time.
	 * Currently only returns the last 10 places a record has been. The response
	 * is a GeoJSON GeometryCollection containing a list of Point objects,
	 * each with a created field containing the timestamp. 
	 * 
	 * This query supports pagination which means "next_cursor" will be set as a top-level key/value
	 * pair if there are more data points to offer after the initial query limit has been reached.
	 * You can use the value at "next_cursor" to get the remaining results of the query.
	 * @see com.simpelgeo.com.client.service.query.IQuery#setCursor(String).
	 * 
	 * @param query the history query object
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 */
	public Object history(HistoryQuery query, Handler type) 
		throws ClientProtocolException, IOException {
		
		if(type != Handler.GEOJSON)
			throw new UnsupportedHandlerException(400, "The contains endpoint can only return GeoJSON objects.");
		
		String uri = mainURL + query.getUri();
		HttpGet request = new HttpGet(buildUrl(uri, query.getParams()));
		
		return execute(request, getHandler(type));
	}
	
	/**
	 * Sends a nearby request to SimpleGeo. 
	 * 
	 * This query supports pagination which means "next_cursor" will be set as a top-level key/value
	 * pair if there are more data points to offer after the initial query limit has been reached.
	 * You can use the value at "next_cursor" to get the remaining results of the query.
	 * @see com.simpelgeo.com.client.service.query.IQuery#setCursor(String).
	 * 
	 * @param query the nearby query
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object nearby(NearbyQuery query) throws ClientProtocolException, IOException {
		return nearby(query, Handler.GEOJSON);
	}
	
	/**
	 * Sends a nearby request to SimpleGeo.
	 * 
	 * This query supports pagination which means "next_cursor" will be set as a top-level key/value
	 * pair if there are more data points to offer after the initial query limit has been reached.
	 * You can use the value at "next_cursor" to get the remaining results of the query.
	 * @see com.simpelgeo.com.client.service.query.IQuery#setCursor(String).
	 * 
	 * @param type the handler responsible for creating the return object 
	 * @param query the nearby query
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object nearby(NearbyQuery query, Handler type) throws ClientProtocolException, IOException {
		
		String uri = mainURL + query.getUri();
		HttpGet request = new HttpGet(buildUrl(uri, query.getParams()));
		
		return execute(request, getHandler(type));
	}
		
	/**
	 * Reverse geocodes a lat/lon pair.
	 * 
	 * @param lat the latitude
	 * @param lon the longitude
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object reverseGeocode(double lat, double lon)
			throws ClientProtocolException, IOException {
		
		String uri = mainURL + String.format("/nearby/address/%f,%f.json", lat, lon);
		HttpGet request = new HttpGet(uri);
		
		return execute(request, getHandler(Handler.GEOJSON));
	}
	
	/**
	 * 
	 * @param day any day from {@link java.util.Calendar#DAY_OF_WEEK} in the DAY_OF_WEEK section
	 * @param hour an hour between 0 and 23, or something outside that range to query the whole day
	 * @param lat the latitude
	 * @param lon the longitude
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object density(int day, int hour, double lat, double lon)
	throws ClientProtocolException, IOException {
		return density(day, hour, lat, lon, Handler.GEOJSON);
	}
	
	/**
	 * 
	 * @param day any day from {@link java.util.Calendar#DAY_OF_WEEK} in the DAY_OF_WEEK section
	 * @param hour an hour between 0 and 23, or something outside that range to query the whole day
	 * @param lat the latitude
	 * @param lon the longitude
	 * @param type the handler responsible for creating the return object
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object density(int day, int hour, double lat, double lon, Handler type)
			throws ClientProtocolException, IOException {
		// /density/{dayname}/{hour}/{lat},{lon}.json
		String dayname = "";
		switch (day)
		{
		case Calendar.SUNDAY:
			dayname = "sun";
			break;
		case Calendar.MONDAY:
			dayname = "mon";
			break;
		case Calendar.TUESDAY:
			dayname = "tue";
			break;
		case Calendar.WEDNESDAY:
			dayname = "wed";
			break;
		case Calendar.THURSDAY:
			dayname = "thu";
			break;
		case Calendar.FRIDAY:
			dayname = "fri";
			break;
		case Calendar.SATURDAY:
			dayname = "sat";
			break;
		}
		
		String uri = mainURL;
		if (hour >=0 && hour <= 23)
		{
			uri += String.format("/density/%s/%d/%f,%f.json", dayname, hour, lat, lon);
		}
		else
		{
			uri += String.format("/density/%s/%f,%f.json", dayname, lat, lon);
		}
		HttpGet request = new HttpGet(uri);
		
		return execute(request, getHandler(Handler.GEOJSON));
	}
	
	/**
	 * Does a "pushpin" query through a series of polygon layers and identifies the "cone" of
	 * administrative and other boundaries in which the point lies.
	 *  
	 * Returns a list of elements where each element contains these key fields:
	 * <ul>
	 * <li>id: A string that uniquely identifies the feature in the SimpleGeo gazetteer. This ID can 
	 * be used to query the exact shape of the polygon itself via the `boundary` API call.</li>
	 * <li>name: A well-known name English-language name for the entity. This may change in the future.</li>
	 * <li>abbr: If present, an official abbreviation for the entity, e.g. an ISO code, postal 
	 * code, or similar.</li>
	 * <li>bounds: A list containing the (west, south, east, north) bounds of the polygon, in that order.</li>
	 * <li>type: A string describing the type of feature identified. May or may not be one of:
	 *   <ul>
	 *   <li>Country: A national boundary.</li>
     *   <li>Province: A state or province.</li>
	 *   <li>County: A county, parish, or similar sub-provincial administrative entity. US only at present.</li>
	 *   <li>City: An incorporated city, town, village, hamlet, etc. US only at present.</li>
	 *   <li>Urban Area: An approximate metropolitan region, from the 1:10M NaturalEarth cultural dataset. 
	 *   May be useful for places outside the US where no incorporated place boundary is (yet) available.</li>
	 *   <li>Neighborhood: A usually approximate boundary for a (usually informal) sub-division of a city. 
	 *   US only at present.</li>
	 *   <li>Postal Code: A postal delivery region. In the US, a ZIP code. US only at present.</li>
	 *   <li>Legislative District: In the US, a congressional district for the currently sitting US Congress.</li>
	 *   <li>Census Tract: As defined by the US Census Bureau in the most recently published census (2000).</li> 
	 *   <li>Other values are possible, so this list will be updated from time to time.</li>
	 *   </ul>
	 * </li>
	 * </ul>
	 * @param lat the latitude
	 * @param lon the longitude
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object contains(double lat, double lon) throws ClientProtocolException, IOException {
		return contains(lat, lon, Handler.JSON);
	}
	
	/**
	 * Does a "pushpin" query through a series of polygon layers and identifies the "cone" of
	 * administrative and other boundaries in which the point lies.
	 *  
	 * Returns a list of elements where each element contains these key fields:
	 * <ul>
	 * <li>id: A string that uniquely identifies the feature in the SimpleGeo gazetteer. This ID can 
	 * be used to query the exact shape of the polygon itself via the `boundary` API call.</li>
	 * <li>name: A well-known name English-language name for the entity. This may change in the future.</li>
	 * <li>abbr: If present, an official abbreviation for the entity, e.g. an ISO code, postal 
	 * code, or similar.</li>
	 * <li>bounds: A list containing the (west, south, east, north) bounds of the polygon, in that order.</li>
	 * <li>type: A string describing the type of feature identified. May or may not be one of:
	 *   <ul>
	 *   <li>Country: A national boundary.</li>
     *   <li>Province: A state or province.</li>
	 *   <li>County: A county, parish, or similar sub-provincial administrative entity. US only at present.</li>
	 *   <li>City: An incorporated city, town, village, hamlet, etc. US only at present.</li>
	 *   <li>Urban Area: An approximate metropolitan region, from the 1:10M NaturalEarth cultural dataset. 
	 *   May be useful for places outside the US where no incorporated place boundary is (yet) available.</li>
	 *   <li>Neighborhood: A usually approximate boundary for a (usually informal) sub-division of a city. 
	 *   US only at present.</li>
	 *   <li>Postal Code: A postal delivery region. In the US, a ZIP code. US only at present.</li>
	 *   <li>Legislative District: In the US, a congressional district for the currently sitting US Congress.</li>
	 *   <li>Census Tract: As defined by the US Census Bureau in the most recently published census (2000).</li> 
	 *   <li>Other values are possible, so this list will be updated from time to time.</li>
	 *   </ul>
	 * </li>
	 * </ul>
	 * @param lat the latitude
	 * @param lon the longitude
	 * @param type the handler responsible for creating the return object
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object contains(double lat, double lon, Handler type)
	throws ClientProtocolException, IOException {
		if(type != Handler.JSON)
			throw new UnsupportedHandlerException(400, "The contains endpoint can only return JSON objects.");

		String uri = mainURL + String.format("/contains/%f,%f.json", lat, lon);
		HttpGet request = new HttpGet(uri);
		
		return execute(request, getHandler(type));
	}
	
	/**
	 * Returns a feature object from the SimpleGeo gazetteer, {@link com.simplegeo.client.SimpleGeoClient#contains}, 
	 * along with the geometry of the feature in GeoJSON format in the geometry field.
	 * 
	 * @param featureId A string that uniquely identifies the feature in the SimpleGeo gazetteer.
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object boundaries(String featureId)
	throws ClientProtocolException, IOException {
		
		return boundaries(featureId, Handler.GEOJSON);
		
	}
	
	/**
	 * Returns a feature object from the SimpleGeo gazetteer, {@link com.simplegeo.client.SimpleGeoClient#contains}, 
	 * along with the geometry of the feature in GeoJSON format in the geometry field.
	 * 
	 * @param featureId A string that uniquely identifies the feature in the SimpleGeo gazetteer.
	 * @param type the handler responsible for creating the return object
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object boundaries(String featureId, Handler type)
	throws ClientProtocolException, IOException {
		
		String uri = mainURL + String.format("/boundary/%s.json", featureId);
		HttpGet request = new HttpGet(uri);
		
		return execute(request, getHandler(type));
	}
	
	/**
	 * Queries a series of polygon layers and identifies the "cone" of administrative and
	 * other boundaries that overlap with the given bounding box.
	 * 
	 * Specifying a limit is not a strict count of how many features will be returned, but 
	 * rather the maximum amount that will be returned. The maximum 
	 * number of features returned is 1000.  The results are not paginated, so if you need more than
	 * 1000 results, consider breaking down your bounding box into multiple queries.
	 * 
	 * @param envelope the bounding box area
	 * @param limit the amount of features to return. 
	 * @param featureType the feature type to filter by
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object overlaps(Envelope envelope, int limit, String featureType) 
	throws ClientProtocolException, IOException {
		
		return overlaps(envelope, limit, featureType, Handler.JSON);
	}
	
	/**
	 * Queries a series of polygon layers and identifies the "cone" of administrative and
	 * other boundaries that overlap with the given bounding box.
	 * 
	 * Specifying a limit is not a strict count of how many features will be returned, but 
	 * rather the maximum amount that will be returned. The maximum 
	 * number of features returned is 1000.  The results are not paginated, so if you need more than
	 * 1000 results, consider breaking down your bounding box into multiple queries.
	 * 
	 * @param envelope the bounding box area
	 * @param limit the amount of features to return. 
	 * @param featureType the feature type to filter by
	 * @param type the handler responsible for creating the return object
	 * @return if {@link com.simplegeo.client.SimpleGeoClient#futureTask} is false
	 * then the return value will be the result of the response based on the handler used. Otherwise,
	 * the return value will be a {@link java.util.concurrent.FutureTask}.
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @see <a href="http://help.simplegeo.com/faqs/api-documentation/endpoints"</a>
	 */
	public Object overlaps(Envelope envelope, int limit, String featureType, Handler type)
	throws ClientProtocolException, IOException {
		
		if(type != Handler.JSON)
			throw new UnsupportedHandlerException(400, "The contains endpoint can only return JSON objects.");
		
		String uri = mainURL + String.format("/overlaps/%s.json", envelope.toString());
		
		Map<String, String> params = new HashMap<String, String>();
		
		if (limit > 0)
			params.put("limit", Integer.toString(limit));
		
		if (featureType != null) 			
			params.put("type", featureType);
		
		HttpGet request = new HttpGet(buildUrl(uri, params));
		return execute(request, getHandler(type));
	}
	
	/**
	 * @return the Http client used to execute all requests
	 */
	public OAuthHttpClient getHttpClient() {
		return this.httpClient;
	}
	
	private String buildUrl(String url, Map<String, ?> parameters) {
		
		if(parameters == null)
			return url;
		
		StringBuilder sb = new StringBuilder(url);
		boolean first = true;
		for (Entry<String, ?> entry : parameters.entrySet()) {
			if (entry.getValue() != null) {
				sb.append(first ? "?" : "&");
				try {
					String key = URLEncoder.encode(entry.getKey(), HTTP.DEFAULT_CONTENT_CHARSET);
					String value = URLEncoder.encode(entry.getValue().toString(), HTTP.DEFAULT_CONTENT_CHARSET);
					sb.append(key).append("=").append(value);
				} catch (UnsupportedEncodingException e) {
					throw new RuntimeException(e);
				}
				first = false;
			}
		}
		return sb.toString();
	}
		
	private Object execute(HttpUriRequest request, SimpleGeoHandler handler)
									throws ClientProtocolException, IOException {
		
		logger.info(String.format("sending %s", request.toString()));
		
		if(futureTask) {
			
			final HttpUriRequest finalRequest = request;
			final SimpleGeoHandler finalHandler = handler;
			
			FutureTask<Object> future = 
				new FutureTask<Object>(new Callable<Object>() {
					public Object call() throws ClientProtocolException, IOException {
						Object object = null;
						try {
							object = httpClient.executeOAuthRequest(finalRequest, finalHandler);
						} catch (OAuthMessageSignerException e) {
							dealWithAuthorizationException(e);
						} catch (OAuthExpectationFailedException e) {
							dealWithAuthorizationException(e);
						} catch (OAuthCommunicationException e) {
							dealWithAuthorizationException(e);
						}
						
						return object;
					}
				});
			
			threadExecutor.execute(future);
			return future;
			
		} else {
			
			Object object = null;
			try {
				object = httpClient.executeOAuthRequest(request, handler);
			} catch (OAuthMessageSignerException e) {
				dealWithAuthorizationException(e);
			} catch (OAuthExpectationFailedException e) {
				dealWithAuthorizationException(e);
			} catch (OAuthCommunicationException e) {
				dealWithAuthorizationException(e);
			};
			
			return object;
		}
		
	}
	
	private GeoJSONObject getGeoJSONObject(Object object) {
		
		GeoJSONObject jsonObject = null;
		if(JSONObject.class.isInstance(object)) {
			try {
				jsonObject = (GeoJSONObject)object;
				jsonObject.flatten();
			} catch (JSONException e) {
				logger.info("unable to flatten GeoJSONObject");
			}
			
		} else {
			jsonObject = GeoJSONEncoder.getGeoJSONRecord((DefaultRecord)object);
		}
		
		return jsonObject;
	}
	
	private GeoJSONObject getGeoJSONObject(List<IRecord> records) {
		
		GeoJSONObject geoJSONObject = null;
		
		if(records != null && !records.isEmpty()) {
			
			try {
			
				geoJSONObject = new GeoJSONObject("FeatureCollection");
			
				JSONArray features = new JSONArray();
				for(IRecord record : records) {
				
					GeoJSONObject jsonObject = getGeoJSONObject(record); 
					if(jsonObject != null) {
						features.put(jsonObject);
					}
				}
			
				geoJSONObject.put("features", features);
				
			} catch (JSONException e) {
				
				logger.info(String.format("unable to create GeoJSONObject from %s", records));
				
			}
		}
		
		return geoJSONObject;
	}
	
	private String getLayer(List<? extends Object> records) {
		
		String layer = null;
		if(records != null && !records.isEmpty())
			layer = getLayer(records.get(0));
		
		return layer;
	}
	
	private String getLayer(Object record) {
	
		String layer = null;
		
		if(GeoJSONObject.class.isInstance(record))
		{
			try {
				
				GeoJSONObject geoJSONObject = (GeoJSONObject)record;
				if(geoJSONObject.isFeatureCollection()) {
						
					JSONArray features = geoJSONObject.getFeatures();
					if(features != null && features.length() > 0)
						layer = features.getJSONObject(0).getString("layer");
					
				} else if(geoJSONObject.isFeature()) {
					
					layer = geoJSONObject.getString("layer");
				}

			} catch (JSONException e) {
				
				logger.info(String.format("unable to locate layer for the %s", record));
			} 			
			
		} else if(IRecord.class.isInstance(record)) {
			
			IRecord r = (IRecord)record;
			layer = r.getLayer();
		}
			
		return layer;
	}
	
	private String getRecordIds(List<? extends Object> objects) {
	
		String recordIds = null;
		
		for(Object object : objects) {
			
			String recordId = getRecordId(object);
			
			if(recordIds == null)
				recordIds = recordId;
			else
				recordIds += "," + recordId;
			
		}
		
		return recordIds;
	}
	
	private String getRecordId(Object object) {
		
		String recordId = null;
		
		if(IRecord.class.isInstance(object))
			recordId = ((IRecord)object).getRecordId();
		else if(GeoJSONObject.class.isInstance(object)) {
			
			try {
				
				GeoJSONObject geoJSONObject = (GeoJSONObject)object;
				if(geoJSONObject.isFeature()) {
					
					recordId = geoJSONObject.getString("id");
					
				} else if(geoJSONObject.isFeatureCollection()) {
						
					JSONArray features = geoJSONObject.getFeatures();
					if(features != null && features.length() > 0) {
						
						int size = features.length();
						for(int index = 0; index < size; index++) {
							
							String id = features.getJSONObject(index).getString("id");
							if(recordId == null)
								recordId = id;
							else
								recordId += "," + recordId;
						}
						
					}
				}

			} catch (JSONException e) {
				
				logger.info(String.format("unable to locate layer for the %s", object));
			} 			
	
		}
		
		return recordId;
	}
	
	private IRecord getIRecord(Object object) {
		IRecord record = null;
		if(object != null) {
			
			if(List.class.isInstance(object)) {
				
				List<IRecord> list = (List<IRecord>)object;
				if(!list.isEmpty())
					record = list.get(0);
				
			} else if(IRecord.class.isInstance(object)) {
				record = (IRecord)object;
			}
		}
		
		return record;
	}

	private SimpleGeoHandler getHandler(Object record) {
		
		SimpleGeoHandler handler = geoJSONHandler;
		if(DefaultRecord.class.isInstance(record))
			handler = recordHandler; 
		
		return handler;
	}
	
	private void dealWithAuthorizationException(Exception e) throws APIException {
		
		e.printStackTrace();
		throw new APIException(SimpleGeoHandler.NOT_AUTHORIZED, e.getMessage());
		
	}
}
