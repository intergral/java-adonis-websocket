/*
 Copyright [2020] [Intergral GmbH]

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package com.nerdvision.adonis;

import java.net.ProtocolException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.internal.ws.RealWebSocket;
import okhttp3.logging.HttpLoggingInterceptor;
import okio.ByteString;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Websocket class based on OkHttp3 with {event->data} message format to make your life easier.
 *
 * @author Ali Yusuf
 * @since 3/13/17
 */

@SuppressWarnings({ "unused", "UnusedReturnValue" })
public class AdonisWebSocketClient
{
    private static final Logger LOGGER = LoggerFactory.getLogger( AdonisWebSocketClient.class );

    private static final String CLOSE_REASON = "End of session";
    private static final int MAX_COLLISION = 7;

    public static final String EVENT_OPEN = "open";
    public static final String EVENT_RECONNECT_ATTEMPT = "reconnecting";
    public static final String EVENT_CLOSED = "closed";



    /**
     * Main socket states
     */
    public enum State
    {
        CLOSED, CLOSING, CONNECT_ERROR, RECONNECT_ATTEMPT, RECONNECTING, OPENING, OPEN
    }



    private static final boolean DEBUG = Boolean.parseBoolean( System.getProperty( "adonis.debug", "false" ) );

    private static final HttpLoggingInterceptor logging =
            new HttpLoggingInterceptor()
                    .setLevel( DEBUG ? HttpLoggingInterceptor.Level.HEADERS : HttpLoggingInterceptor.Level.NONE );

    private static final OkHttpClient.Builder httpClient =
            new OkHttpClient.Builder()
                    .addInterceptor( logging );

    private int pingAttempts = 0;
    private int pingRemainingAttempts = 0;
    private final Timer timer = new Timer();



    public static class Builder
    {

        private final Request.Builder request;


        private Builder( Request.Builder request )
        {
            this.request = request;
        }


        public static Builder with( String url )
        {
            // Silently replace web socket URLs with HTTP URLs.
            if( !url.regionMatches( true, 0, "ws:", 0, 3 ) && !url.regionMatches( true, 0, "wss:", 0, 4 ) )
            {
                throw new IllegalArgumentException( "web socket url must start with ws or wss, passed url is " + url );
            }

            return new Builder( new Request.Builder().url( url ) );
        }


        public Builder setPingInterval( long interval, TimeUnit unit )
        {
            httpClient.pingInterval( interval, unit );
            return this;
        }


        public Builder addHeader( String name, String value )
        {
            request.addHeader( name, value );
            return this;
        }


        public AdonisWebSocketClient build()
        {
            return new AdonisWebSocketClient( request.build() );
        }
    }



    /**
     * Websocket state
     */
    private State state;
    /**
     * Websocket main request
     */
    private final Request request;
    /**
     * Websocket connection
     */
    private RealWebSocket realWebSocket;
    /**
     * Reconnection post delayed handler
     */
    //    private static Handler delayedReconnection;
    /**
     * Websocket events listeners
     */
    private final Map<String, OnEventListener> eventListener = new HashMap<>();
    /**
     * Websocket events new message listeners
     */
    private final Map<String, OnEventResponseListener> eventResponseListener = new HashMap<>();
    /**
     * Message list tobe send onEvent open {@link State#OPEN} connection state
     */
    private final Map<String, String> onOpenMessageQueue = new HashMap<>();
    /**
     * Websocket state change listener
     */
    private OnStateChangeListener onChangeStateListener;
    /**
     * Websocket new message listener
     */
    private OnMessageListener messageListener;
    /**
     * Number of reconnection attempts
     */
    private int reconnectionAttempts;
    private boolean skipOnFailure;


    private AdonisWebSocketClient( Request request )
    {
        this.request = request;
        state = State.CLOSED;
        //        delayedReconnection = new Handler(Looper.getMainLooper());
        skipOnFailure = false;
    }


    /**
     * Start socket connection if i's not already started
     */
    public AdonisWebSocketClient connect()
    {
        if( httpClient == null )
        {
            throw new IllegalStateException( "Make sure to use Socket.Builder before using Socket#connect." );
        }
        if( realWebSocket == null )
        {
            realWebSocket = (RealWebSocket) httpClient.build().newWebSocket( request, webSocketListener );
            changeState( State.OPENING );
        }
        else if( state == State.CLOSED )
        {
            realWebSocket.connect( httpClient.build() );
            changeState( State.OPENING );
        }
        return this;
    }


    /**
     * Set listener which fired every time message received with contained data.
     *
     * @param listener message on arrive listener
     */
    public AdonisWebSocketClient onEvent( String event, OnEventListener listener )
    {
        eventListener.put( event, listener );
        return this;
    }


    /**
     * Set listener which fired every time message received with contained data.
     *
     * @param listener message on arrive listener
     */
    public AdonisWebSocketClient onEventResponse( String topic, String event, OnEventResponseListener listener )
    {
        eventResponseListener.put( topic + event, listener );
        return this;
    }


    /**
     * Send message in {event->data} format
     *
     * @param event event name that you want sent message to
     * @param data  message data in JSON format
     *
     * @return true if the message send/on socket send quest; false otherwise
     */
    public boolean send( String event, String data )
    {
        try
        {
            JSONObject text = new JSONObject();
            JSONObject topic = new JSONObject();
            topic.put( "topic", event );
            topic.put( "event", "message" );
            topic.put( "data", new JSONObject( data ) );
            text.put( "t", 7 );
            text.put( "d", topic );
            LOGGER.info( "Try to send data {}", text );

            return realWebSocket.send( text.toString() );
        }
        catch( JSONException e )
        {
            LOGGER.error( "Try to send data with wrong JSON format", data );
        }
        return false;
    }


    public boolean join( String topic )
    {
        try
        {
            JSONObject text = new JSONObject();
            JSONObject topics = new JSONObject();
            topics.put( "topic", topic );
            text.put( "t", 1 );
            text.put( "d", topics );
            LOGGER.info( "Try to send data {}", text );

            return realWebSocket.send( text.toString() );
        }
        catch( JSONException e )
        {
            LOGGER.error( "Try to send data with wrong JSON format", e );
        }
        return false;
    }


    public void ping( long pingInterval )
    {
        timer.scheduleAtFixedRate( new TimerTask()
        {
            @Override
            public void run()
            {
                if( pingRemainingAttempts > 0 )
                {
                    try
                    {
                        JSONObject text = new JSONObject();
                        text.put( "t", 8 );
                        LOGGER.info( "Try to send data {}", text );
                        realWebSocket.send( text.toString() );
                        pingRemainingAttempts--;
                    }
                    catch( JSONException e )
                    {
                        LOGGER.error( "Try to send data with wrong JSON format", e );
                    }
                }
            }
        }, 0, pingInterval );
    }


    /**
     * Set state listener which fired every time {@link AdonisWebSocketClient#state} changed.
     *
     * @param listener state change listener
     */
    public AdonisWebSocketClient setOnChangeStateListener( OnStateChangeListener listener )
    {
        onChangeStateListener = listener;
        return this;
    }


    /**
     * Message listener will be called in any message received even if it's not
     * in a {event -> data} format.
     *
     * @param listener message listener
     */
    public AdonisWebSocketClient setMessageListener( OnMessageListener listener )
    {
        messageListener = listener;
        return this;
    }


    public void removeEventListener( String event )
    {
        eventListener.remove( event );
        onOpenMessageQueue.remove( event );
    }


    /**
     * Clear all socket listeners in one line
     */
    public void clearListeners()
    {
        eventListener.clear();
        messageListener = null;
        onChangeStateListener = null;
    }


    /**
     * Send normal close request to the host
     */
    public void close()
    {
        if( realWebSocket != null )
        {
            realWebSocket.close( 1000, CLOSE_REASON );
        }
    }


    /**
     * Send close request to the host
     */
    public void close( int code, String reason )
    {
        if( realWebSocket != null )
        {
            realWebSocket.close( code, reason );
        }
    }


    /**
     * Terminate the socket connection permanently
     */
    public void terminate()
    {
        skipOnFailure = true; // skip onFailure callback
        if( realWebSocket != null )
        {
            realWebSocket.cancel(); // close connection
            realWebSocket = null; // clear socket object
        }
    }


    /**
     * Add message in a queue if the socket not open and send them
     * if the socket opened
     *
     * @param event event name that you want sent message to
     * @param data  message data in JSON format
     */
    public void sendOnOpen( String event, String data )
    {
        if( state != State.OPEN )
        {
            onOpenMessageQueue.put( event, data );
        }
        else
        {
            send( event, data );
        }
    }


    /**
     * Retrieve current socket connection state {@link State}
     */
    public State getState()
    {
        return state;
    }


    /**
     * Change current state and call listener method with new state
     * {@link OnStateChangeListener#onChange(AdonisWebSocketClient, State)}
     *
     * @param newState new state
     */
    private void changeState( State newState )
    {
        state = newState;
        if( onChangeStateListener != null )
        {
            onChangeStateListener.onChange( AdonisWebSocketClient.this, state );
        }
    }


    /**
     * Try to reconnect to the websocket after delay time using <i>Exponential backoff</i> method.
     *
     * @see <a href="https://en.wikipedia.org/wiki/Exponential_backoff"></a>
     */
    private void reconnect()
    {
        if( state != State.CONNECT_ERROR ) // connection not closed !!
        {
            return;
        }

        changeState( State.RECONNECT_ATTEMPT );

        if( realWebSocket != null )
        {
            // Cancel websocket connection
            realWebSocket.cancel();
            // Clear websocket object
            realWebSocket = null;
        }

        if( eventListener.get( EVENT_RECONNECT_ATTEMPT ) != null )
        {
            eventListener.get( EVENT_RECONNECT_ATTEMPT ).onMessage( AdonisWebSocketClient.this, EVENT_RECONNECT_ATTEMPT );
        }

        // Calculate delay time
        int collision = Math.min( reconnectionAttempts, MAX_COLLISION );
        long delayTime = Math.round( (Math.pow( 2, collision ) - 1) / 2 ) * 1000;

        timer.schedule( new TimerTask()
        {
            @Override
            public void run()
            {
                changeState( State.RECONNECTING );
                reconnectionAttempts++; // Increment connections attempts
                connect(); // Establish new connection
            }
        }, delayTime );
    }


    private WebSocketListener webSocketListener = new WebSocketListener()
    {
        @Override
        public void onOpen( WebSocket webSocket, Response response )
        {
            LOGGER.info( "Socket has been opened successfully." );
            // reset connections attempts counter
            reconnectionAttempts = 0;

            // fire open event listener
            if( eventListener.get( EVENT_OPEN ) != null )
            {
                eventListener.get( EVENT_OPEN ).onMessage( AdonisWebSocketClient.this, EVENT_OPEN );
            }

            // Send data in queue
            for( String event : onOpenMessageQueue.keySet() )
            {
                send( event, onOpenMessageQueue.get( event ) );
            }
            // clear queue
            onOpenMessageQueue.clear();

            changeState( State.OPEN );
        }


        /**
         * Accept only Json data with format:
         * <b> {"event":"event name","data":{some data ...}} </b>
         */
        @Override
        public void onMessage( WebSocket webSocket, String text )
        {
            // print received message in log
            LOGGER.info( "New Message received {}", text );
            JSONObject response = new JSONObject( text );
            JSONObject d = response.optJSONObject( "d" );
            int type = response.optInt( "t" );
            try
            {
                if( type == 0 )
                {
                    final long pingInterval = d.optLong( "clientInterval" );
                    pingAttempts = d.optInt( "clientAttempts" );
                    LOGGER.debug( "clientInterval {}", pingInterval );
                    ping( pingInterval );

                    pingRemainingAttempts = pingAttempts;
                }
                else if( type == 7 )
                {
                    String topic = d.getString( "topic" );
                    String event = d.getString( "event" );
                    JSONObject data = d.getJSONObject( "data" );

                    // call event listener with received data
                    if( eventResponseListener.get( topic + event ) != null )
                    {
                        eventResponseListener.get( topic + event ).onMessage( AdonisWebSocketClient.this, topic, event, data );
                    }
                    // call event listener
                    if( eventListener.get( event ) != null )
                    {
                        eventListener.get( event ).onMessage( AdonisWebSocketClient.this, event );
                    }
                }
                else if( type == 9 )
                {
                    pingRemainingAttempts = pingAttempts;
                }

            }
            catch( JSONException e )
            {
                e.printStackTrace();
            }


            // call message listener
            if( messageListener != null )
            {
                messageListener.onMessage( AdonisWebSocketClient.this, text );
            }
        }


        @Override
        public void onMessage( WebSocket webSocket, ByteString bytes )
        {
            // TODO: some action
        }


        @Override
        public void onClosing( WebSocket webSocket, int code, String reason )
        {
            LOGGER.info( "Close request from server with reason '{}'", reason );
            changeState( State.CLOSING );
            webSocket.close( 1000, reason );
        }


        @Override
        public void onClosed( WebSocket webSocket, int code, String reason )
        {
            LOGGER.info( "Socket connection closed with reason '{}'", reason );
            changeState( State.CLOSED );
            if( eventListener.get( EVENT_CLOSED ) != null )
            {
                eventListener.get( EVENT_CLOSED ).onMessage( AdonisWebSocketClient.this, EVENT_CLOSED );
            }
        }


        /**
         * This method call if:
         * - Fail to verify websocket GET request  => Throwable {@link ProtocolException}
         * - Can't establish websocket connection after upgrade GET request => response null, Throwable {@link Exception}
         * - First GET request had been failed => response null, Throwable {@link java.io.IOException}
         * - Fail to send Ping => response null, Throwable {@link java.io.IOException}
         * - Fail to send data frame => response null, Throwable {@link java.io.IOException}
         * - Fail to read data frame => response null, Throwable {@link java.io.IOException}
         */
        @Override
        public void onFailure( WebSocket webSocket, Throwable t, Response response )
        {
            if( !skipOnFailure )
            {
                skipOnFailure = false; // reset flag
                LOGGER.info( "Socket connection fail, try to reconnect. ({})", reconnectionAttempts );
                changeState( State.CONNECT_ERROR );
                reconnect();
            }
        }
    };



    public abstract static class OnMessageListener
    {
        public abstract void onMessage( String data );


        /**
         * Method called from socket to execute listener implemented in
         * {@link #onMessage(String)} on main thread
         *
         * @param adonisWebSocketClient Socket that receive the message
         * @param data                  Data string received
         */
        private void onMessage( AdonisWebSocketClient adonisWebSocketClient, final String data )
        {
            adonisWebSocketClient.timer.schedule( new TimerTask()
            {
                @Override
                public void run()
                {
                    onMessage( data );
                }
            }, 0 );
        }
    }



    public abstract static class OnEventListener
    {
        public abstract void onMessage( String event );


        private void onMessage( AdonisWebSocketClient adonisWebSocketClient, final String event )
        {
            adonisWebSocketClient.timer.schedule( new TimerTask()
            {
                @Override
                public void run()
                {
                    onMessage( event );
                }
            }, 0 );
        }
    }



    public abstract static class OnEventResponseListener extends OnEventListener
    {
        /**
         * Method need to override in listener usage
         */
        public abstract void onMessage( String topic, String event, String data );


        /**
         * Just override the inherited method
         */
        @Override
        public void onMessage( String event )
        {
        }


        /**
         * Method called from socket to execute listener implemented in
         * {@link #onMessage(String, String, String)} on main thread
         *
         * @param adonisWebSocketClient Socket that receive the message
         * @param event                 Message received event
         * @param data                  Data received in the message
         */
        private void onMessage( AdonisWebSocketClient adonisWebSocketClient,
                                final String topic,
                                final String event,
                                final JSONObject data )
        {
            adonisWebSocketClient.timer.schedule( new TimerTask()
            {
                @Override
                public void run()
                {
                    onMessage( topic, event, data.toString() );
                    onMessage( event );
                }
            }, 0 );
        }
    }



    public abstract static class OnStateChangeListener
    {
        /**
         * Method need to override in listener usage
         */
        public abstract void onChange( State status );


        /**
         * Method called from socket to execute listener implemented in
         * {@link #onChange(State)} on main thread
         *
         * @param adonisWebSocketClient Socket that receive the message
         * @param status                new status
         */
        private void onChange( AdonisWebSocketClient adonisWebSocketClient, final State status )
        {
            adonisWebSocketClient.timer.schedule( new TimerTask()
            {
                @Override
                public void run()
                {
                    onChange( status );
                }
            }, 0 );
        }
    }

}
