/*
 * Copyright 2010 Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.magnifis.parking.utils;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlertDialog.Builder;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;


/**
 * Utility class supporting the Facebook Object.
 * 
 * @author ssoneff@facebook.com
 *
 */
public final class FbHttpUtils {

    /**
     * Generate the multi-part post body providing the parameters and boundary
     * string
     * 
     * @param parameters the parameters need to be posted
     * @param boundary the random string as boundary
     * @return a string of the post body
     */
	
    public static String encodePostBody(Bundle parameters, String boundary) {
        if (parameters == null) return "";
        StringBuilder sb = new StringBuilder();
        
        for (String key : parameters.keySet()) {
            if (parameters.getByteArray(key) != null) {
        	    continue;
            }
        	
            sb.append("Content-Disposition: form-data; name=\"" + key + 
            		"\"\r\n\r\n" + parameters.getString(key));
            sb.append("\r\n" + "--" + boundary + "\r\n");
        }
        
        return sb.toString();
    }

    public static String encodeUrl(Bundle parameters) {
        if (parameters == null) {
        	return "";
        }
        
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String key : parameters.keySet()) {
            if (first) first = false; else sb.append("&");
            sb.append(key + "=" + parameters.getString(key));
        }
        return sb.toString();
    }

    public static Bundle decodeUrl(String s) {
        Bundle params = new Bundle();
        if (s != null) {
            String array[] = s.split("&");
            for (String parameter : array) {
                String v[] = parameter.split("=");
                params.putString(v[0], v[1]);
            }
        }
        return params;
    }

    /**
     * Parse a URL query and fragment parameters into a key-value bundle.
     * 
     * @param url the URL to parse
     * @return a dictionary bundle of keys and values
     */
    public static Bundle parseUrl(String url) {
        // hack to prevent MalformedURLException
        url = url.replace("fbconnect", "http"); 
        try {
            URL u = new URL(url);
            Bundle b = decodeUrl(u.getQuery());
            b.putAll(decodeUrl(u.getRef()));
            return b;
        } catch (MalformedURLException e) {
            return new Bundle();
        }
    }

    
    /**
     * Connect to an HTTP URL and return the response as a string.
     * 
     * Note that the HTTP method override is used on non-GET requests. (i.e.
     * requests are made as "POST" with method specified in the body).
     * 
     * @param url - the resource to open: must be a welformed URL
     * @param method - the HTTP method to use ("GET", "POST", etc.)
     * @param params - the query parameter for the URL (e.g. access_token=foo)
     * @return the URL contents as a String
     * @throws MalformedURLException - if the URL format is invalid
     * @throws IOException - if a network problem occurs
     */
    public static String openUrl(String url, String method, Bundle params) 
          throws MalformedURLException, IOException {
    	// random string as boundary for multi-part http post
    	String strBoundary = "3i2ndDfv2rTHiSisAbouNdArYfORhtTPEefj3q2f";
    	String endLine = "\r\n";
   
    	OutputStream os;

        if (method.equals("GET")) {
            url = url + "?" + encodeUrl(params);
        }
        Log.d("Twitter-Util", method + " URL: " + url);
        HttpURLConnection conn = 
            (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", System.getProperties().
                getProperty("http.agent") + " TwitterAndroidSDK");
        if (!method.equals("GET")) {
            Bundle dataparams = new Bundle();
            for (String key : params.keySet()) {
                if (params.getByteArray(key) != null) {
                        dataparams.putByteArray(key, params.getByteArray(key));
                }
            }
        	
            // use method override
            if (!params.containsKey("method")) {
            	params.putString("method", method);           	
            }
            
            if (params.containsKey("access_token")) {
            	String decoded_token = URLDecoder.decode(params.getString("access_token"));
            	params.putString("access_token", decoded_token);
            }
                     
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data;boundary="+strBoundary);
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setRequestProperty("Connection", "Keep-Alive");
            conn.connect();
            os = new BufferedOutputStream(conn.getOutputStream());
            
            os.write(("--" + strBoundary +endLine).getBytes());
            os.write((encodePostBody(params, strBoundary)).getBytes());
            os.write((endLine + "--" + strBoundary + endLine).getBytes());
            
            if (!dataparams.isEmpty()) {
            	
                for (String key: dataparams.keySet()){
                    os.write(("Content-Disposition: form-data; filename=\"" + key + "\"" + endLine).getBytes());
                    os.write(("Content-Type: content/unknown" + endLine + endLine).getBytes());
                    os.write(dataparams.getByteArray(key));
                    os.write((endLine + "--" + strBoundary + endLine).getBytes());

                }          	
            }
            os.flush();
        }
        
        String response = "";
        try {
        	response = read(conn.getInputStream());
        } catch (FileNotFoundException e) {
            // Error Stream contains JSON that we can parse to a FB error
            response = read(conn.getErrorStream());
        }
        return response;
    }

    private static String read(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in), 1000);
        for (String line = r.readLine(); line != null; line = r.readLine()) {
            sb.append(line);
        }
        in.close();
        return sb.toString();
    }
/*
    public static void clearCookies(Context packageName) {
        // Edge case: an illegal state exception is thrown if an instance of 
        // CookieSyncManager has not be created.  CookieSyncManager is normally
        // created by a WebKit view, but this might happen if you start the 
        // app, restore saved state, and click logout before running a UI 
        // dialog in a WebView -- in which case the app crashes
        @SuppressWarnings("unused")
        CookieSyncManager cookieSyncMngr = 
            CookieSyncManager.createInstance(packageName);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookie();
    }
*/
}
