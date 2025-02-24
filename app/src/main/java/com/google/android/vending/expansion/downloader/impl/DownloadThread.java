package com.google.android.vending.expansion.downloader.impl;
/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import com.google.android.vending.expansion.downloader.Constants;
import com.google.android.vending.expansion.downloader.Helpers;
import com.google.android.vending.expansion.downloader.IDownloaderClient;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRouteParams;

import android.content.Context;
import android.net.Proxy;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SyncFailedException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Runs an actual download
 */
public class DownloadThread {

    private Context mContext;
    private DownloadInfo mInfo;
    private DownloaderService mService;
    private final DownloadsDB mDB;
    private final DownloadNotification mNotification;

    public DownloadThread(DownloadInfo info, DownloaderService service, DownloadNotification notification) {
        mContext = service;
        mInfo = info;
        mService = service;
        mNotification = notification;
        mDB = DownloadsDB.getDB(service);
    }

    /**
     * Returns the default user agent
     */
    private String userAgent() {
    	return Constants.DEFAULT_USER_AGENT;
    }
    
    /**
     * State for the entire run() method.
     */
    private static class State {
        public String mFilename;
        public FileOutputStream mStream;
        public boolean mCountRetry = false;
        public int mRetryAfter = 0;
        public int mRedirectCount = 0;
        public String mNewUri;
        public boolean mGotData = false;
        public String mRequestUri;

        public State(DownloadInfo info, DownloaderService service) {
            mRedirectCount = info.mRedirectCount;
            mRequestUri = info.mUri;
            mFilename = service.generateTempSaveFileName(info.mFileName);
        }
    }

    /**
     * State within executeDownload()
     */
    private static class InnerState {
        public int mBytesSoFar = 0;
        public int mBytesThisSession = 0;
        public String mHeaderETag;
        public boolean mContinuingDownload = false;
        public String mHeaderContentLength;
        public String mHeaderContentDisposition;
        public String mHeaderContentLocation;
        public int mBytesNotified = 0;
        public long mTimeLastNotification = 0;
    }

    /**
     * Raised from methods called by run() to indicate that the current request should be stopped
     * immediately.
     *
     * Note the message passed to this exception will be logged and therefore must be guaranteed
     * not to contain any PII, meaning it generally can't include any information about the request
     * URI, headers, or destination filename.
     */
    private class StopRequest extends Throwable {
        /**
		 * 
		 */
		private static final long serialVersionUID = 6338592678988347973L;
		public int mFinalStatus;

        public StopRequest(int finalStatus, String message) {
            super(message);
            mFinalStatus = finalStatus;
        }

        public StopRequest(int finalStatus, String message, Throwable throwable) {
            super(message, throwable);
            mFinalStatus = finalStatus;
        }
    }

    /**
     * Raised from methods called by executeDownload() to indicate that the download should be
     * retried immediately.
     */
    private class RetryDownload extends Throwable {

		/**
		 * 
		 */
		private static final long serialVersionUID = 6196036036517540229L;}

    /**
     * Returns the preferred proxy to be used by clients. This is a wrapper
     * around {@link android.net.Proxy#getHost()}. Currently no proxy will
     * be returned for localhost or if the active network is Wi-Fi.
     *
     * @param packageName the packageName which will be passed to
     * {@link android.net.Proxy#getHost()}
     * @param url the target URL for the request
     * @note Calling this method requires permission
     * android.permission.ACCESS_NETWORK_STATE
     * @return The preferred proxy to be used by clients, or null if there
     * is no proxy.
     */
    public HttpHost getPreferredHttpHost(Context context,
            String url) {
        if (!isLocalHost(url) && !mService.isWiFi()) {
            final String proxyHost = Proxy.getHost(context);
            if (proxyHost != null) {
                return new HttpHost(proxyHost, Proxy.getPort(context), "http");
            }
        }

        return null;
    }

    static final private boolean isLocalHost(String url) {
        if (url == null) {
            return false;
        }

        try {
            final URI uri = URI.create(url);
            final String host = uri.getHost();
            if (host != null) {
                // TODO: InetAddress.isLoopbackAddress should be used to check
                // for localhost. However no public factory methods exist which
                // can be used without triggering DNS lookup if host is not localhost.
                if (host.equalsIgnoreCase("localhost") ||
                        host.equals("127.0.0.1") ||
                        host.equals("[::1]")) {
                    return true;
                }
            }
        } catch (IllegalArgumentException iex) {
            // Ignore (URI.create)
        }

        return false;
    }
    
    /**
     * Executes the download in a separate thread
     */
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        State state = new State(mInfo, mService);
        AndroidHttpClient client = null;
        PowerManager.WakeLock wakeLock = null;
        int finalStatus = DownloaderService.STATUS_UNKNOWN_ERROR;

        try {
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, Constants.TAG);
            wakeLock.acquire();


            if (Constants.LOGV) {
                Log.v(Constants.TAG, "initiating download for " + mInfo.mFileName);
                Log.v(Constants.TAG, "  at " + mInfo.mUri);
            }

            client = AndroidHttpClient.newInstance(userAgent(), mContext);

            boolean finished = false;
            while(!finished) {
                if (Constants.LOGV) {
                    Log.v(Constants.TAG, "initiating download for " + mInfo.mFileName);
                    Log.v(Constants.TAG, "  at " + mInfo.mUri);
                }
                // Set or unset proxy, which may have changed since last GET request.
                // setDefaultProxy() supports null as proxy parameter.
                ConnRouteParams.setDefaultProxy(client.getParams(),
                        getPreferredHttpHost(mContext, state.mRequestUri));
                HttpGet request = new HttpGet(state.mRequestUri);
                try {
                    executeDownload(state, client, request);
                    finished = true;
                } catch (RetryDownload exc) {
                    // fall through
                } finally {
                    request.abort();
                    request = null;
                }
            }

            if (Constants.LOGV) {
                Log.v(Constants.TAG, "download completed for " + mInfo.mFileName);
                Log.v(Constants.TAG, "  at " + mInfo.mUri);
            }
            finalizeDestinationFile(state);
            finalStatus = DownloaderService.STATUS_SUCCESS;
        } catch (StopRequest error) {
            // remove the cause before printing, in case it contains PII
            Log.w(Constants.TAG,
                    "Aborting request for download " + mInfo.mFileName + ": " + error.getMessage());
            		error.printStackTrace();
            finalStatus = error.mFinalStatus;
            // fall through to finally block
        } catch (Throwable ex) { //sometimes the socket code throws unchecked exceptions
            Log.w(Constants.TAG, "Exception for " + mInfo.mFileName + ": " + ex);
            finalStatus = DownloaderService.STATUS_UNKNOWN_ERROR;
            // falls through to the code that reports an error
        } finally {
            if (wakeLock != null) {
                wakeLock.release();
                wakeLock = null;
            }
            if (client != null) {
                client.close();
                client = null;
            }
            cleanupDestination(state, finalStatus);
            notifyDownloadCompleted(finalStatus, state.mCountRetry, state.mRetryAfter,
                                    state.mRedirectCount, state.mGotData, state.mFilename);
        }
    }

    /**
     * Fully execute a single download request - setup and send the request, handle the response,
     * and transfer the data to the destination file.
     */
    private void executeDownload(State state, AndroidHttpClient client, HttpGet request)
            throws StopRequest, RetryDownload {
        InnerState innerState = new InnerState();
        byte data[] = new byte[Constants.BUFFER_SIZE];

        checkPausedOrCanceled(state);
        
        setupDestinationFile(state, innerState);
        addRequestHeaders(innerState, request);

        // check just before sending the request to avoid using an invalid connection at all
        checkConnectivity(state);

        mNotification.onDownloadStateChanged(IDownloaderClient.STATE_CONNECTING);
        HttpResponse response = sendRequest(state, client, request);
        handleExceptionalStatus(state, innerState, response);

        if (Constants.LOGV) {
            Log.v(Constants.TAG, "received response for " + mInfo.mUri);
        }

        processResponseHeaders(state, innerState, response);
        InputStream entityStream = openResponseEntity(state, response);
        mNotification.onDownloadStateChanged(IDownloaderClient.STATE_DOWNLOADING);
        transferData(state, innerState, data, entityStream);        
    }

	/**
     * Check if current connectivity is valid for this request.
     */
    private void checkConnectivity(State state) throws StopRequest {
    	switch ( mService.getNetworkAvailabilityState(mDB) ) {
    	    case DownloaderService.NETWORK_OK:
    	        return;
    	    case DownloaderService.NETWORK_NO_CONNECTION:
    	        throw new StopRequest(DownloaderService.STATUS_WAITING_FOR_NETWORK, "waiting for network to return");
    	    case DownloaderService.NETWORK_TYPE_DISALLOWED_BY_REQUESTOR:
    	        throw new StopRequest(DownloaderService.STATUS_QUEUED_FOR_WIFI, "waiting for wifi or for download over cellular to be authorized");
    	    case DownloaderService.NETWORK_CANNOT_USE_ROAMING:
    	        throw new StopRequest(DownloaderService.STATUS_WAITING_FOR_NETWORK, "roaming is not allowed");
    	}
    }

    /**
     * Transfer as much data as possible from the HTTP response to the destination file.
     * @param data buffer to use to read data
     * @param entityStream stream for reading the HTTP response entity
     */
    private void transferData(State state, InnerState innerState, byte[] data,
                                 InputStream entityStream) throws StopRequest {
        for (;;) {
            int bytesRead = readFromResponse(state, innerState, data, entityStream);
            if (bytesRead == -1) { // success, end of stream already reached
                handleEndOfStream(state, innerState);
                return;
            }

            state.mGotData = true;
            writeDataToDestination(state, data, bytesRead);
            innerState.mBytesSoFar += bytesRead;
            innerState.mBytesThisSession += bytesRead;
            reportProgress(state, innerState);

            checkPausedOrCanceled(state);
        }
    }

    /**
     * Called after a successful completion to take any necessary action on the downloaded file.
     */
    private void finalizeDestinationFile(State state) throws StopRequest {
        syncDestination(state);
    	String tempFilename = state.mFilename;
    	String finalFilename = Helpers.generateSaveFileName(mService, mInfo.mFileName);
    	if ( !state.mFilename.equals(finalFilename) ) {
    		File startFile = new File(tempFilename);
    		File destFile = new File(finalFilename);
            if ( mInfo.mTotalBytes != -1 && mInfo.mCurrentBytes == mInfo.mTotalBytes ) {
        		if ( !startFile.renameTo(destFile) ) {
        			throw new StopRequest(DownloaderService.STATUS_FILE_ERROR, 
        					"unable to finalize destination file");
        		}
            } else {
                throw new StopRequest(DownloaderService.STATUS_FILE_DELIVERED_INCORRECTLY,
                        "file delivered with incorrect size. probably due to network not browser configured");
            }        		
    	}
    }

    /**
     * Called just before the thread finishes, regardless of status, to take any necessary action on
     * the downloaded file.
     */
    private void cleanupDestination(State state, int finalStatus) {
        closeDestination(state);
        if (state.mFilename != null && DownloaderService.isStatusError(finalStatus)) {
            new File(state.mFilename).delete();
            state.mFilename = null;
        }
    }

    /**
     * Sync the destination file to storage.
     */
    private void syncDestination(State state) {
        FileOutputStream downloadedFileStream = null;
        try {
            downloadedFileStream = new FileOutputStream(state.mFilename, true);
            downloadedFileStream.getFD().sync();
        } catch (FileNotFoundException ex) {
            Log.w(Constants.TAG, "file " + state.mFilename + " not found: " + ex);
        } catch (SyncFailedException ex) {
            Log.w(Constants.TAG, "file " + state.mFilename + " sync failed: " + ex);
        } catch (IOException ex) {
            Log.w(Constants.TAG, "IOException trying to sync " + state.mFilename + ": " + ex);
        } catch (RuntimeException ex) {
            Log.w(Constants.TAG, "exception while syncing file: ", ex);
        } finally {
            if(downloadedFileStream != null) {
                try {
                    downloadedFileStream.close();
                } catch (IOException ex) {
                    Log.w(Constants.TAG, "IOException while closing synced file: ", ex);
                } catch (RuntimeException ex) {
                    Log.w(Constants.TAG, "exception while closing file: ", ex);
                }
            }
        }
    }

    /**
     * Close the destination output stream.
     */
    private void closeDestination(State state) {
        try {
            // close the file
            if (state.mStream != null) {
                state.mStream.close();
                state.mStream = null;
            }
        } catch (IOException ex) {
            if (Constants.LOGV) {
                Log.v(Constants.TAG, "exception when closing the file after download : " + ex);
            }
            // nothing can really be done if the file can't be closed
        }
    }

    /**
     * Check if the download has been paused or canceled, stopping the request appropriately if it
     * has been.
     */
    private void checkPausedOrCanceled(State state) throws StopRequest {
        if (mService.getControl() == DownloaderService.CONTROL_PAUSED) {
            int status = mService.getStatus();
            switch (status) {
                case DownloaderService.STATUS_PAUSED_BY_APP:
                    throw new StopRequest(mService.getStatus(),
                            "download paused");
            }                    
        }
    }

    /**
     * Report download progress through the database if necessary.
     */
    private void reportProgress(State state, InnerState innerState) {
        long now = System.currentTimeMillis();
        if (innerState.mBytesSoFar - innerState.mBytesNotified
                        > Constants.MIN_PROGRESS_STEP
                && now - innerState.mTimeLastNotification
                        > Constants.MIN_PROGRESS_TIME) {
           	// we store progress updates to the database here
        	mInfo.mCurrentBytes = innerState.mBytesSoFar;
        	mDB.updateDownloadCurrentBytes(mInfo);
        	
        	innerState.mBytesNotified = innerState.mBytesSoFar;
            innerState.mTimeLastNotification = now;
            
            long totalBytesSoFar = innerState.mBytesThisSession + mService.mBytesSoFar;
            
            if (Constants.LOGVV) {
                Log.v(Constants.TAG, "downloaded " + mInfo.mCurrentBytes + " out of " + mInfo.mTotalBytes);
                Log.v(Constants.TAG, "     total " + totalBytesSoFar + " out of " + mService.mTotalLength);
            }
            
            mService.notifyUpdateBytes(totalBytesSoFar);
        }
    }

    /**
     * Write a data buffer to the destination file.
     * @param data buffer containing the data to write
     * @param bytesRead how many bytes to write from the buffer
     */
    private void writeDataToDestination(State state, byte[] data, int bytesRead)
            throws StopRequest {
        for (;;) {
            try {
                if (state.mStream == null) {
                    state.mStream = new FileOutputStream(state.mFilename, true);
                }
                state.mStream.write(data, 0, bytesRead);
                // we close after every write --- this may be too inefficient
                closeDestination(state);
                return;
            } catch (IOException ex) {
            	if (!Helpers.isExternalMediaMounted()) {
                    throw new StopRequest(DownloaderService.STATUS_DEVICE_NOT_FOUND_ERROR,
                            "external media not mounted while writing destination file");
                }

                long availableBytes =
                    Helpers.getAvailableBytes(Helpers.getFilesystemRoot(state.mFilename));
                if (availableBytes < bytesRead) {
                    throw new StopRequest(DownloaderService.STATUS_INSUFFICIENT_SPACE_ERROR,
                            "insufficient space while writing destination file", ex);
                }
                throw new StopRequest(DownloaderService.STATUS_FILE_ERROR,
                        "while writing destination file: " + ex.toString(), ex);
            }
        }
    }

    /**
     * Called when we've reached the end of the HTTP response stream, to update the database and
     * check for consistency.
     */
    private void handleEndOfStream(State state, InnerState innerState) throws StopRequest {
    	mInfo.mCurrentBytes = innerState.mBytesSoFar;
    	// this should always be set from the market
//    	if ( innerState.mHeaderContentLength == null ) {
//    		mInfo.mTotalBytes = innerState.mBytesSoFar;
//    	}
    	mDB.updateDownload(mInfo);
        
        boolean lengthMismatched = (innerState.mHeaderContentLength != null)
                && (innerState.mBytesSoFar != Integer.parseInt(innerState.mHeaderContentLength));
        if (lengthMismatched) {
            if (cannotResume(innerState)) {
                throw new StopRequest(DownloaderService.STATUS_CANNOT_RESUME,
                        "mismatched content length");
            } else {
                throw new StopRequest(getFinalStatusForHttpError(state),
                        "closed socket before end of file");
            }
        }
    }

    private boolean cannotResume(InnerState innerState) {
        return innerState.mBytesSoFar > 0 && innerState.mHeaderETag == null;
    }

    /**
     * Read some data from the HTTP response stream, handling I/O errors.
     * @param data buffer to use to read data
     * @param entityStream stream for reading the HTTP response entity
     * @return the number of bytes actually read or -1 if the end of the stream has been reached
     */
    private int readFromResponse(State state, InnerState innerState, byte[] data,
                                 InputStream entityStream) throws StopRequest {
        try {
            return entityStream.read(data);
        } catch (IOException ex) {
            logNetworkState();
        	mInfo.mCurrentBytes = innerState.mBytesSoFar;
        	mDB.updateDownload(mInfo);
            if (cannotResume(innerState)) {
                String message = "while reading response: " + ex.toString()
                + ", can't resume interrupted download with no ETag";
                throw new StopRequest(DownloaderService.STATUS_CANNOT_RESUME,
                        message, ex);
            } else {
                throw new StopRequest(getFinalStatusForHttpError(state),
                        "while reading response: " + ex.toString(), ex);
            }
        }
    }

    /**
     * Open a stream for the HTTP response entity, handling I/O errors.
     * @return an InputStream to read the response entity
     */
    private InputStream openResponseEntity(State state, HttpResponse response)
            throws StopRequest {
        try {
            return response.getEntity().getContent();
        } catch (IOException ex) {
            logNetworkState();
            throw new StopRequest(getFinalStatusForHttpError(state),
                    "while getting entity: " + ex.toString(), ex);
        }
    }

    private void logNetworkState() {
        if (Constants.LOGX) {
            Log.i(Constants.TAG,
                    "Net " + (mService.getNetworkAvailabilityState(mDB) == DownloaderService.NETWORK_OK ? "Up" : "Down"));
        }
    }

    /**
     * Read HTTP response headers and take appropriate action, including setting up the destination
     * file and updating the database.
     */
    private void processResponseHeaders(State state, InnerState innerState, HttpResponse response)
            throws StopRequest {
        if (innerState.mContinuingDownload) {
            // ignore response headers on resume requests
            return;
        }

        readResponseHeaders(state, innerState, response);

        try {
        	state.mFilename = mService.generateSaveFile(mInfo.mFileName, mInfo.mTotalBytes);
        } catch (DownloaderService.GenerateSaveFileError exc) {
            throw new StopRequest(exc.mStatus, exc.mMessage);
        }
        try {        	
            state.mStream = new FileOutputStream(state.mFilename);
        } catch (FileNotFoundException exc) {
        	// make sure the directory exists
    		File pathFile = new File(Helpers.getSaveFilePath(mService));
    		try {
    			if ( pathFile.mkdirs() ) {
    				state.mStream = new FileOutputStream(state.mFilename);
    			}
    		} catch (Exception ex) {
                throw new StopRequest(DownloaderService.STATUS_FILE_ERROR,
                        "while opening destination file: " + exc.toString(), exc);    			
    		}
        }
        if (Constants.LOGV) {
            Log.v(Constants.TAG, "writing " + mInfo.mUri + " to " + state.mFilename);
        }
 
        updateDatabaseFromHeaders(state, innerState);
        // check connectivity again now that we know the total size
        checkConnectivity(state);
    }

    /**
     * Update necessary database fields based on values of HTTP response headers that have been
     * read.
     */
    private void updateDatabaseFromHeaders(State state, InnerState innerState) {
    	mInfo.mETag = innerState.mHeaderETag;
    	mDB.updateDownload(mInfo);
    }

    /**
     * Read headers from the HTTP response and store them into local state.
     */
    private void readResponseHeaders(State state, InnerState innerState, HttpResponse response)
            throws StopRequest {
        Header header = response.getFirstHeader("Content-Disposition");
        if (header != null) {
            innerState.mHeaderContentDisposition = header.getValue();
        }
        header = response.getFirstHeader("Content-Location");
        if (header != null) {
            innerState.mHeaderContentLocation = header.getValue();
        }
        header = response.getFirstHeader("ETag");
        if (header != null) {
            innerState.mHeaderETag = header.getValue();
        }
        String headerTransferEncoding = null;
        header = response.getFirstHeader("Transfer-Encoding");
        if (header != null) {
            headerTransferEncoding = header.getValue();
        }
        String headerContentType = null;
        header = response.getFirstHeader("Content-Type");
        if (header != null) {
            headerContentType = header.getValue();
            if ( !headerContentType.equals("application/vnd.android.obb") ) {
                throw new StopRequest(DownloaderService.STATUS_FILE_DELIVERED_INCORRECTLY,
                        "file delivered with incorrect Mime type");                
            }
        }
        
        if (headerTransferEncoding == null) {
            header = response.getFirstHeader("Content-Length");
            if (header != null) {
                innerState.mHeaderContentLength = header.getValue();
                // this is always set from Market
                long contentLength = Long.parseLong(innerState.mHeaderContentLength);
                if (contentLength != -1 && contentLength != mInfo.mTotalBytes) {
                    // we're most likely on a bad wifi connection -- we should probably
                    // also look at the mime type --- but the size mismatch is enough
                    // to tell us that something is wrong here
                    Log.e(Constants.TAG, "Incorrect file size delivered.");
                }
            }
        } else {
            // Ignore content-length with transfer-encoding - 2616 4.4 3
            if (Constants.LOGVV) {
                Log.v(Constants.TAG,
                        "ignoring content-length because of xfer-encoding");
            }
        }
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Content-Disposition: " +
                    innerState.mHeaderContentDisposition);
            Log.v(Constants.TAG, "Content-Length: " + innerState.mHeaderContentLength);
            Log.v(Constants.TAG, "Content-Location: " + innerState.mHeaderContentLocation);
            Log.v(Constants.TAG, "ETag: " + innerState.mHeaderETag);
            Log.v(Constants.TAG, "Transfer-Encoding: " + headerTransferEncoding);
        }

        boolean noSizeInfo = innerState.mHeaderContentLength == null
                && (headerTransferEncoding == null
                    || !headerTransferEncoding.equalsIgnoreCase("chunked"));
        if (noSizeInfo) {
            throw new StopRequest(DownloaderService.STATUS_HTTP_DATA_ERROR,
                    "can't know size of download, giving up");
        }
    }

    /**
     * Check the HTTP response status and handle anything unusual (e.g. not 200/206).
     */
    private void handleExceptionalStatus(State state, InnerState innerState, HttpResponse response)
            throws StopRequest, RetryDownload {
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode == 503 && mInfo.mNumFailed < Constants.MAX_RETRIES) {
            handleServiceUnavailable(state, response);
        }
        if (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307) {
            handleRedirect(state, response, statusCode);
        }
        
        int expectedStatus = innerState.mContinuingDownload ? 206 : DownloaderService.STATUS_SUCCESS;
        if (statusCode != expectedStatus) {
            handleOtherStatus(state, innerState, statusCode);
        } else {
        	// no longer redirected
        	state.mRedirectCount = 0;
        }
    }

    /**
     * Handle a status that we don't know how to deal with properly.
     */
    private void handleOtherStatus(State state, InnerState innerState, int statusCode)
            throws StopRequest {
        int finalStatus;
        if (DownloaderService.isStatusError(statusCode)) {
            finalStatus = statusCode;
        } else if (statusCode >= 300 && statusCode < 400) {
            finalStatus = DownloaderService.STATUS_UNHANDLED_REDIRECT;
        } else if (innerState.mContinuingDownload && statusCode == DownloaderService.STATUS_SUCCESS) {
            finalStatus = DownloaderService.STATUS_CANNOT_RESUME;
        } else {
            finalStatus = DownloaderService.STATUS_UNHANDLED_HTTP_CODE;
        }
        throw new StopRequest(finalStatus, "http error " + statusCode);
    }

    /**
     * Handle a 3xx redirect status.
     */
    private void handleRedirect(State state, HttpResponse response, int statusCode)
            throws StopRequest, RetryDownload {
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "got HTTP redirect " + statusCode);
        }
        if (state.mRedirectCount >= Constants.MAX_REDIRECTS) {
            throw new StopRequest(DownloaderService.STATUS_TOO_MANY_REDIRECTS, "too many redirects");
        }
        Header header = response.getFirstHeader("Location");
        if (header == null) {
            return;
        }
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "Location :" + header.getValue());
        }

        String newUri;
        try {
            newUri = new URI(mInfo.mUri).resolve(new URI(header.getValue())).toString();
        } catch(URISyntaxException ex) {
            if (Constants.LOGV) {
                Log.d(Constants.TAG, "Couldn't resolve redirect URI " + header.getValue()
                        + " for " + mInfo.mUri);
            }
            throw new StopRequest(DownloaderService.STATUS_HTTP_DATA_ERROR,
                    "Couldn't resolve redirect URI");
        }
        ++state.mRedirectCount;
        state.mRequestUri = newUri;
        if (statusCode == 301 || statusCode == 303) {
            // use the new URI for all future requests (should a retry/resume be necessary)
            state.mNewUri = newUri;
        }
        throw new RetryDownload();
    }

    /**
     * Add headers for this download to the HTTP request to allow for resume.
     */
    private void addRequestHeaders(InnerState innerState, HttpGet request) {
        if (innerState.mContinuingDownload) {
            if (innerState.mHeaderETag != null) {
                request.addHeader("If-Match", innerState.mHeaderETag);
            }
            request.addHeader("Range", "bytes=" + innerState.mBytesSoFar + "-");
        }
    }
    
    /**
     * Handle a 503 Service Unavailable status by processing the Retry-After header.
     */
    private void handleServiceUnavailable(State state, HttpResponse response) throws StopRequest {
        if (Constants.LOGVV) {
            Log.v(Constants.TAG, "got HTTP response code 503");
        }
        state.mCountRetry = true;
        Header header = response.getFirstHeader("Retry-After");
        if (header != null) {
           try {
               if (Constants.LOGVV) {
                   Log.v(Constants.TAG, "Retry-After :" + header.getValue());
               }
               state.mRetryAfter = Integer.parseInt(header.getValue());
               if (state.mRetryAfter < 0) {
                   state.mRetryAfter = 0;
               } else {
                   if (state.mRetryAfter < Constants.MIN_RETRY_AFTER) {
                       state.mRetryAfter = Constants.MIN_RETRY_AFTER;
                   } else if (state.mRetryAfter > Constants.MAX_RETRY_AFTER) {
                       state.mRetryAfter = Constants.MAX_RETRY_AFTER;
                   }
                   state.mRetryAfter += Helpers.sRandom.nextInt(Constants.MIN_RETRY_AFTER + 1);
                   state.mRetryAfter *= 1000;
               }
           } catch (NumberFormatException ex) {
               // ignored - retryAfter stays 0 in this case.
           }
        }
        throw new StopRequest(DownloaderService.STATUS_WAITING_TO_RETRY,
                "got 503 Service Unavailable, will retry later");
    }

    /**
     * Send the request to the server, handling any I/O exceptions.
     */
    private HttpResponse sendRequest(State state, AndroidHttpClient client, HttpGet request)
            throws StopRequest {
        try {
            return client.execute(request);
        } catch (IllegalArgumentException ex) {
            throw new StopRequest(DownloaderService.STATUS_HTTP_DATA_ERROR,
                    "while trying to execute request: " + ex.toString(), ex);
        } catch (IOException ex) {
            logNetworkState();
            throw new StopRequest(getFinalStatusForHttpError(state),
                    "while trying to execute request: " + ex.toString(), ex);
        }
    }

    private int getFinalStatusForHttpError(State state) {
        if (mService.getNetworkAvailabilityState(mDB) != DownloaderService.NETWORK_OK) {
            return DownloaderService.STATUS_WAITING_FOR_NETWORK;
        } else if (mInfo.mNumFailed < Constants.MAX_RETRIES) {
            state.mCountRetry = true;
            return DownloaderService.STATUS_WAITING_TO_RETRY;
        } else {
            Log.w(Constants.TAG, "reached max retries for " + mInfo.mNumFailed);
            return DownloaderService.STATUS_HTTP_DATA_ERROR;
        }
    }

    /**
     * Prepare the destination file to receive data.  If the file already exists, we'll set up
     * appropriately for resumption.
     */
    private void setupDestinationFile(State state, InnerState innerState)
            throws StopRequest {
        if (state.mFilename != null) { // only true if we've already run a thread for this download
            if (!Helpers.isFilenameValid(state.mFilename)) {
                // this should never happen
                throw new StopRequest(DownloaderService.STATUS_FILE_ERROR,
                        "found invalid internal destination filename");
            }
            // We're resuming a download that got interrupted
            File f = new File(state.mFilename);
            if (f.exists()) {
                long fileLength = f.length();
                if (fileLength == 0) {
                    // The download hadn't actually started, we can restart from scratch
                    f.delete();
                    state.mFilename = null;
                } else if (mInfo.mETag == null) {
                    // This should've been caught upon failure
                    f.delete();
                    throw new StopRequest(DownloaderService.STATUS_CANNOT_RESUME,
                            "Trying to resume a download that can't be resumed");
                } else {
                    // All right, we'll be able to resume this download
                    try {
                        state.mStream = new FileOutputStream(state.mFilename, true);
                    } catch (FileNotFoundException exc) {
                        throw new StopRequest(DownloaderService.STATUS_FILE_ERROR,
                                "while opening destination for resuming: " + exc.toString(), exc);
                    }
                    innerState.mBytesSoFar = (int) fileLength;
                    if (mInfo.mTotalBytes != -1) {
                        innerState.mHeaderContentLength = Long.toString(mInfo.mTotalBytes);
                    }
                    innerState.mHeaderETag = mInfo.mETag;
                    innerState.mContinuingDownload = true;
                }
            }
        }

        if (state.mStream != null) {
            closeDestination(state);
        }
    }

    /**
     * Stores information about the completed download, and notifies the initiating application.
     */
    private void notifyDownloadCompleted(
            int status, boolean countRetry, int retryAfter, int redirectCount, boolean gotData,
            String filename) {
        updateDownloadDatabase(
                status, countRetry, retryAfter, redirectCount, gotData, filename);
        if (DownloaderService.isStatusCompleted(status)) {
        	// TBD: send status update?
        }
    }

    private void updateDownloadDatabase(
            int status, boolean countRetry, int retryAfter, int redirectCount, boolean gotData,
            String filename) {
    	mInfo.mStatus = status;
    	mInfo.mRetryAfter = retryAfter;
    	mInfo.mRedirectCount = redirectCount;
    	mInfo.mLastMod = System.currentTimeMillis();
        if (!countRetry) {
        	mInfo.mNumFailed = 0;
        } else if (gotData) {
        	mInfo.mNumFailed = 1;
        } else {
        	mInfo.mNumFailed++;
        }
        mDB.updateDownload(mInfo);
     }

}