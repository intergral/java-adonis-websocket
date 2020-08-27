# java-adonis-websocket

Remove personally identifiable information from text. Inspired by https://github.com/utsavstha/adonis-websocket-client-android

Requirements:
 - Java 1.7+
 
# Usage

``` java
        final AdonisWebSocketClient build = Builder.with( "wss://api.example.com/ws" ).build();
        build.connect();
        build.join( "topic-name" );
        build.onEventResponse( "topic-name", "event-name", new OnEventResponseListener()
        {
            @Override
            public void onMessage( final String topic, final String event, final String data )
            {
                // your code here
            }
        } );
```
