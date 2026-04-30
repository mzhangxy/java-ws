import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import io.github.cdimascio.dotenv.Dotenv;

public class App {
    
    private static String UUID;
    private static String DOMAIN;
    private static String SUB_PATH;
    private static String NAME;
    private static String WSPATH;
    private static int PORT;
    private static boolean AUTO_ACCESS;
    private static boolean DEBUG;
    
    private static String PROTOCOL_UUID;
    private static byte[] UUID_BYTES;
    
    private static String currentDomain;
    private static int currentPort = 443;
    private static String tls = "tls";
    private static String isp = "Unknown";
    
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Map<String, String> dnsCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> dnsCacheTime = new ConcurrentHashMap<>();
    private static final long DNS_CACHE_TTL = 300000;
    
    private static boolean SILENT_MODE = true; 
    
    private static void log(String level, String msg) {
        if (SILENT_MODE && !level.equals("INFO")) return;  
        System.out.println(new Date() + " - " + level + " - " + msg);
    }
    
    private static void info(String msg) { log("INFO", msg); }
    private static void error(String msg) { log("ERROR", msg); }
    private static void error(String msg, Throwable t) { 
        log("ERROR", msg);
        if (DEBUG) t.printStackTrace();
    }
    private static void debug(String msg) { if (DEBUG) log("DEBUG", msg); }
    
    private static void loadConfig() {
        Map<String, String> envFromFile = new HashMap<>();
        try {
            Path envPath = Paths.get(".env");
            if (Files.exists(envPath)) {
                Dotenv dotenv = Dotenv.configure()
                        .directory(".")
                        .filename(".env")
                        .ignoreIfMissing()
                        .load();
                dotenv.entries().forEach(entry -> envFromFile.put(entry.getKey(), entry.getValue()));
            }
        } catch (Exception e) {
            debug("Failed to load .env file: " + e.getMessage());
        }
        
        UUID = getEnvValue(envFromFile, "UUID", "3732162e-4179-416f-b5e2-ccbf8e4479ae");
        DOMAIN = getEnvValue(envFromFile, "DOMAIN", "");
        SUB_PATH = getEnvValue(envFromFile, "SUB_PATH", "sub");
        NAME = getEnvValue(envFromFile, "NAME", "Karlo");
        
        String wspathFromEnv = getEnvValue(envFromFile, "WSPATH", null);
        if (wspathFromEnv != null) {
            WSPATH = wspathFromEnv;
        } else {
            WSPATH = UUID.substring(0, 8);
        }
        
        String portStr = getEnvValue(envFromFile, "SERVER_PORT", null);
        if (portStr == null) {
            portStr = getEnvValue(envFromFile, "PORT", "3000");
        }
        PORT = Integer.parseInt(portStr);
        
        AUTO_ACCESS = Boolean.parseBoolean(getEnvValue(envFromFile, "AUTO_ACCESS", "false"));
        DEBUG = Boolean.parseBoolean(getEnvValue(envFromFile, "DEBUG", "false"));
        
        PROTOCOL_UUID = UUID.replace("-", "");
        UUID_BYTES = hexStringToByteArray(PROTOCOL_UUID);
        currentDomain = DOMAIN;
        SILENT_MODE = !DEBUG;
    }
    
    private static String getEnvValue(Map<String, String> envFromFile, String key, String defaultValue) {
        if (envFromFile.containsKey(key)) return envFromFile.get(key);
        String sysEnv = System.getenv(key);
        if (sysEnv != null && !sysEnv.isEmpty()) return sysEnv;
        return defaultValue;
    }
    
    private static boolean isPortAvailable(int port) {
        try (var socket = new java.net.ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    private static int findAvailablePort(int startPort) {
        for (int port = startPort; port < startPort + 100; port++) {
            if (isPortAvailable(port)) return port;
        }
        throw new RuntimeException("No available ports found");
    }
    
    private static String resolveHost(String host) {
        try {
            InetAddress.getByName(host);
            return host;
        } catch (Exception e) {
            String cached = dnsCache.get(host);
            Long time = dnsCacheTime.get(host);
            if (cached != null && time != null && System.currentTimeMillis() - time < DNS_CACHE_TTL) {
                return cached;
            }
            try {
                InetAddress address = InetAddress.getByName(host);
                String ip = address.getHostAddress();
                dnsCache.put(host, ip);
                dnsCacheTime.put(host, System.currentTimeMillis());
                return ip;
            } catch (Exception ex) {
                error("DNS resolution failed for: " + host);
                return host;
            }
        }
    }
    
    private static void getIp() {
        if (DOMAIN == null || DOMAIN.isEmpty() || DOMAIN.equals("your-domain.com")) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api-ipv4.ip.sb/ip"))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    currentDomain = response.body().trim();
                    tls = "none";
                    currentPort = PORT;
                    info("public IP: " + currentDomain);
                }
            } catch (Exception e) {
                error("Failed to get IP: " + e.getMessage());
                currentDomain = "change-your-domain.com";
                tls = "tls";
                currentPort = 443;
            }
        } else {
            currentDomain = DOMAIN;
            tls = "tls";
            currentPort = 443;
        }
    }
    
    private static void getIsp() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://ip-api.com/json"))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                String countryCode = extractJsonValue(body, "countryCode");
                String org = extractJsonValue(body, "org");
                isp = countryCode + "-" + org;
                isp = isp.replace(" ", "_");
                info("Got ISP info: " + isp);
            }
        } catch (Exception e) {
            debug("Failed to get ISP from ip-api: " + e.getMessage());
        }
    }
    
    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        var matcher = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }
    
    private static void addAccessTask() {
        if (!AUTO_ACCESS || DOMAIN.isEmpty()) return;
        String fullUrl = "https://" + DOMAIN + "/" + SUB_PATH;
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://oooo.serv00.net/add-url"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"" + fullUrl + "\"}"))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            info("Automatic Access Task added successfully");
        } catch (Exception e) {
            debug("Failed to add access task: " + e.getMessage());
        }
    }
    
    private static String generateSubscription() {
        String namePart = NAME.isEmpty() ? isp : NAME + "-" + isp;
        String vlessUrl = String.format(
                "vless://%s@%s:%d?encryption=none&security=%s&sni=%s&fp=chrome&type=ws&host=%s&path=%%2F%s#%s",
                UUID, currentDomain, currentPort, tls, currentDomain, currentDomain, WSPATH, namePart);
        
        return Base64.getEncoder().encodeToString((vlessUrl + "\n").getBytes(StandardCharsets.UTF_8));
    }
    
    static class HttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();
            
            if ("/".equals(uri)) {
                String content = getIndexHtml();
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(content, StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                ctx.writeAndFlush(response);
                
            } else if (("/" + SUB_PATH).equals(uri)) {
                if ("Unknown".equals(isp)) getIsp();
                String subscription = generateSubscription();
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.copiedBuffer(subscription + "\n", StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                ctx.writeAndFlush(response);
                
            } else {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                        Unpooled.copiedBuffer("Not Found\n", StandardCharsets.UTF_8));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        }
        
        private String getIndexHtml() {
            try {
                Path path = Paths.get("index.html");
                if (Files.exists(path)) return Files.readString(path);
            } catch (IOException e) {
                debug("Failed to read index.html: " + e.getMessage());
            }
            return "<!DOCTYPE html><html><head><title>Hello world!</title></head>" +
                   "<body><h4>Hello world!</h4></body></html>";
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    static class WebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
        private Channel outboundChannel;
        private boolean connected = false;
        private boolean protocolIdentified = false;
        
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (frame instanceof BinaryWebSocketFrame) {
                ByteBuf content = frame.content();
                byte[] data = new byte[content.readableBytes()];
                content.readBytes(data);
                
                if (!connected && !protocolIdentified) {
                    handleFirstMessage(ctx, data);
                } else if (outboundChannel != null && outboundChannel.isActive()) {
                    outboundChannel.writeAndFlush(Unpooled.wrappedBuffer(data));
                }
            } else if (frame instanceof CloseWebSocketFrame) {
                ctx.close();
            }
        }
        
        private void handleFirstMessage(ChannelHandlerContext ctx, byte[] data) {
            // 仅检查 VLESS (以0x00开头)
            if (data.length > 18 && data[0] == 0x00) {
                boolean uuidMatch = true;
                for (int i = 0; i < 16; i++) {
                    if (data[i + 1] != UUID_BYTES[i]) {
                        uuidMatch = false;
                        break;
                    }
                }
                if (uuidMatch) {
                    if (handleVless(ctx, data)) {
                        protocolIdentified = true;
                        return;
                    }
                }
            }
            ctx.close(); // 非 VLESS 或 UUID 不匹配直接关闭
        }
        
        private boolean handleVless(ChannelHandlerContext ctx, byte[] data) {
            try {
                int addonsLength = data[17] & 0xFF;
                int offset = 18 + addonsLength;
                
                if (offset + 1 > data.length) return false;
                byte command = data[offset];
                if (command != 0x01) return false;
                offset++;
                
                if (offset + 2 > data.length) return false;
                int port = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                offset += 2;
                
                if (offset >= data.length) return false;
                byte atyp = data[offset];
                offset++;
                
                String host;
                int addressLength;
                
                if (atyp == 0x01) { // IPv4
                    if (offset + 4 > data.length) return false;
                    host = String.format("%d.%d.%d.%d",
                            data[offset] & 0xFF, data[offset + 1] & 0xFF,
                            data[offset + 2] & 0xFF, data[offset + 3] & 0xFF);
                    addressLength = 4;
                } else if (atyp == 0x02) { // 域名
                    if (offset >= data.length) return false;
                    int hostLen = data[offset] & 0xFF;
                    offset++;
                    if (offset + hostLen > data.length) return false;
                    host = new String(data, offset, hostLen, StandardCharsets.UTF_8);
                    addressLength = hostLen;
                } else if (atyp == 0x03) { // IPv6
                    if (offset + 16 > data.length) return false;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02x%02x", data[offset + i], data[offset + i + 1]));
                    }
                    host = sb.toString();
                    addressLength = 16;
                } else {
                    return false;
                }
                
                offset += addressLength;
                
                ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(new byte[]{0x00, 0x00})));
                
                final byte[] remainingData;
                if (offset < data.length) {
                    remainingData = Arrays.copyOfRange(data, offset, data.length);
                } else {
                    remainingData = new byte[0];
                }
                
                connectToTarget(ctx, host, port, remainingData);
                return true;
                
            } catch (Exception e) {
                return false;
            }
        }
        
        private void connectToTarget(ChannelHandlerContext ctx, String host, int port, 
                                     byte[] remainingData) {
            String resolvedHost = resolveHost(host);
            final byte[] dataToSend = remainingData;
            
            Bootstrap b = new Bootstrap();
            b.group(ctx.channel().eventLoop())
                    .channel(ctx.channel().getClass())
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new TargetHandler(ctx.channel(), dataToSend));
                        }
                    });
            
            ChannelFuture f = b.connect(resolvedHost, port);
            outboundChannel = f.channel();
            
            f.addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    connected = true;
                } else {
                    ctx.close();
                }
            });
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (outboundChannel != null && outboundChannel.isActive()) outboundChannel.close();
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    static class TargetHandler extends ChannelInboundHandlerAdapter {
        private final Channel inboundChannel;
        private final byte[] remainingData;
        
        public TargetHandler(Channel inboundChannel, byte[] remainingData) {
            this.inboundChannel = inboundChannel;
            this.remainingData = remainingData;
        }
        
        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            if (remainingData != null && remainingData.length > 0) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(remainingData));
            }
            ctx.channel().config().setAutoRead(true);
            inboundChannel.config().setAutoRead(true);
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof ByteBuf) {
                ByteBuf buf = (ByteBuf) msg;
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                if (inboundChannel.isActive()) {
                    inboundChannel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data)));
                }
            }
        }
        
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (inboundChannel.isActive()) inboundChannel.close();
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
    
    private static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }
    
    public static void main(String[] args) {
        loadConfig();
        
        info("Starting VLESS Server...");
        info("Subscription Path: /" + SUB_PATH);
        
        getIp();
        addAccessTask();
        
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new IdleStateHandler(30, 0, 0));
                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(65536));
                            p.addLast(new WebSocketServerCompressionHandler());
                            p.addLast(new WebSocketServerProtocolHandler("/" + WSPATH, null, true));
                            p.addLast(new HttpHandler());
                            p.addLast(new WebSocketHandler());
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);
            
            int actualPort = findAvailablePort(PORT);
            Channel ch = b.bind(actualPort).sync().channel();
            
            info("✅ VLESS server is running on port " + actualPort);
            ch.closeFuture().sync();
            
        } catch (InterruptedException e) {
            error("Server interrupted", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            error("Server error", e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            info("Server stopped");
        }
    }
}
