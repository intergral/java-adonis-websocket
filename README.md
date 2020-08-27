# java-adonis-websocket

Connect to an Adonis websocket server from a Java client. Inspired by https://github.com/utsavstha/adonis-websocket-client-android

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

## Maven

Add as a dependency with maven:
```xml
<dependency>
    <groupId>com.nerdvision</groupId>
    <artifactId>java-adonis-websocket</artifactId>
    <version>0.0.1</version>
</dependency>
```

# Building
This project used maven to build and supports Java 1.7+. Build and test with `mvn clean verify`.

# Contributing
To contribute ensure the build passes using the above command, and submit a Pull Request.
