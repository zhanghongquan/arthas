package com.alibaba.arthas.tunnel.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.arthas.tunnel.common.MethodConstants;
import com.alibaba.arthas.tunnel.common.URIConstans;
import com.taobao.arthas.common.ArthasConstants;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.QueryStringEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * 
 * @author hengyunabc 2019-08-28
 *
 */
public class TunnelClient {
    private final static Logger logger = LoggerFactory.getLogger(TunnelClient.class);

    private String tunnelServerUrl;

    private int reconnectDelay = 5;

    // connect to proxy server
    // two thread because need to reconnect. #1284
    private EventLoopGroup eventLoopGroup = new NioEventLoopGroup(2, new DefaultThreadFactory("arthas-TunnelClient", true));

    // agent id, generated by tunnel server. if reconnect, reuse the id
    volatile private String id;

    public ChannelFuture start() throws IOException, InterruptedException, URISyntaxException {
        return connect(false);
    }

    public ChannelFuture connect(boolean reconnect) throws SSLException, URISyntaxException, InterruptedException {
        QueryStringEncoder queryEncoder = new QueryStringEncoder(this.tunnelServerUrl);
        queryEncoder.addParam(URIConstans.METHOD, MethodConstants.AGENT_REGISTER);
        if (id != null) {
            queryEncoder.addParam(URIConstans.ID, id);
        }
        // ws://127.0.0.1:7777/ws?method=agentRegister
        final URI agentRegisterURI = queryEncoder.toUri();

        logger.info("Try to register arthas agent, uri: {}", agentRegisterURI);

        String scheme = agentRegisterURI.getScheme() == null ? "ws" : agentRegisterURI.getScheme();
        final String host = agentRegisterURI.getHost() == null ? "127.0.0.1" : agentRegisterURI.getHost();
        final int port;
        if (agentRegisterURI.getPort() == -1) {
            if ("ws".equalsIgnoreCase(scheme)) {
                port = 80;
            } else if ("wss".equalsIgnoreCase(scheme)) {
                port = 443;
            } else {
                port = -1;
            }
        } else {
            port = agentRegisterURI.getPort();
        }

        if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Only WS(S) is supported. tunnelServerUrl: " + tunnelServerUrl);
        }

        final boolean ssl = "wss".equalsIgnoreCase(scheme);
        final SslContext sslCtx;
        if (ssl) {
            sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } else {
            sslCtx = null;
        }

        WebSocketClientHandshaker newHandshaker = WebSocketClientHandshakerFactory.newHandshaker(agentRegisterURI,
                WebSocketVersion.V13, null, true, new DefaultHttpHeaders());
        final WebSocketClientProtocolHandler websocketClientHandler = new WebSocketClientProtocolHandler(newHandshaker);
        final TunnelClientSocketClientHandler handler = new TunnelClientSocketClientHandler(TunnelClient.this);

        Bootstrap bs = new Bootstrap();

        bs.group(eventLoopGroup)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
        .option(ChannelOption.TCP_NODELAY, true)
        .channel(NioSocketChannel.class).remoteAddress(host, port)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (sslCtx != null) {
                            p.addLast(sslCtx.newHandler(ch.alloc(), host, port));
                        }

                        p.addLast(new HttpClientCodec(), new HttpObjectAggregator(ArthasConstants.MAX_HTTP_CONTENT_LENGTH), websocketClientHandler,
                                handler);
                    }
                });

        ChannelFuture connectFuture = bs.connect();
        if (reconnect) {
            connectFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (future.cause() != null) {
                        logger.error("connect to tunnel server error, uri: {}", tunnelServerUrl, future.cause());
                    }
                }
            });
        }
        connectFuture.sync();

        return handler.registerFuture();
    }

    public void stop() {
        eventLoopGroup.shutdownGracefully();
    }

    public String getTunnelServerUrl() {
        return tunnelServerUrl;
    }

    public void setTunnelServerUrl(String tunnelServerUrl) {
        this.tunnelServerUrl = tunnelServerUrl;
    }

    public int getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(int reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

}
