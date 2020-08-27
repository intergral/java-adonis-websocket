package com.nerdvision.adonis;

import org.junit.Test;

public class AdonisWebSocketClientTest
{
    @Test
    public void name() throws InterruptedException
    {
        final AdonisWebSocketClient build = AdonisWebSocketClient.Builder
                .with( "wss://api.nerd.vision/stream-debugger-ws-service/ws?group=5e57b24848c6842f7f251be2&token=rY6spyDAT8mWUOFTG7mWa9F03Jm5xyUnjl0AC5AS2rJBsIU2swhV34xnotCytmmI" )
                .build();
        build.setOnChangeStateListener( new AdonisWebSocketClient.OnStateChangeListener()
        {
            @Override
            public void onChange( final AdonisWebSocketClient.State status )
            {
                System.out.println(status);
            }
        } );
        final AdonisWebSocketClient connect = build
                .connect();
        connect
                .join( "breakpoints" );
        connect.onEvent( "update", new AdonisWebSocketClient.OnEventListener()
        {
            @Override
            public void onMessage( final String event )
            {
                System.out.println( event );
            }
        } );

        Thread.sleep( 10000 );
    }
}
