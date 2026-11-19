package com.zzx.zzxaicode.quality.check;

import com.sun.net.httpserver.*;
import com.zzx.zzxaicode.quality.workspace.QualityWorkspaceService;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 仅在 loopback 暂时展示本轮候选，同时充当拒绝其他地址的浏览器代理；不开放任意 URL 探测。 */
public final class CandidatePreviewServer implements AutoCloseable {
    private final Path root;
    private final HttpServer server;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final List<String> missing=new CopyOnWriteArrayList<>();
    private final String origin;
    /** @param root 经过构建校验的唯一候选根；所有资源仍逐次校验路径 */
    public CandidatePreviewServer(Path root) throws IOException {
        this.root=root.toAbsolutePath().normalize();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        origin="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",this::serve);server.setExecutor(executor);server.start();
    }
    /** @return 本轮固定入口与代理地址，不接受模型或请求方指定 */
    public String origin() { return origin; }
    /** @return 缺失的本地脚本、样式路径，图片失败不作为必选检查失败 */
    public List<String> missingCriticalResources() { return List.copyOf(missing); }
    /** @param exchange 浏览器请求；只接受当前 origin 的 GET/HEAD，代理不转发任何外部网络请求 */
    private void serve(HttpExchange exchange) throws IOException {
        try(exchange) {
            URI uri=exchange.getRequestURI();
            String host=exchange.getRequestHeaders().getFirst("Host");
            boolean local=host!=null && host.equals("127.0.0.1:"+server.getAddress().getPort())
                    && (!uri.isAbsolute() || (uri.getScheme().equals("http") && origin.equals(uri.getScheme()+"://"+uri.getRawAuthority())));
            if(!local || !Set.of("GET","HEAD").contains(exchange.getRequestMethod())) { exchange.sendResponseHeaders(403,-1);return; }
            String relative=uri.getPath();
            if(relative.equals("/")) relative="/index.html";
            Path path=root.resolve(relative.substring(1)).normalize();
            if(!path.startsWith(root)) { exchange.sendResponseHeaders(403,-1);return; }
            QualityWorkspaceService.requireNoLink(path);
            if(!Files.isRegularFile(path)) {
                if((relative.endsWith(".js") || relative.endsWith(".css")) && missing.size()<100) missing.add(relative.substring(1));
                exchange.sendResponseHeaders(404,-1);return;
            }
            var headers=exchange.getResponseHeaders(); headers.set("Cache-Control","no-store");headers.set("X-Content-Type-Options","nosniff");
            headers.set("Content-Type",contentType(path));
            // 首屏检查不允许外部 API、嵌套页面、表单提交或浏览器插件；远程图片失败只作为受限环境边界。
            headers.set("Content-Security-Policy","default-src 'self' data: blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'none'; frame-src 'none'; object-src 'none'; form-action 'none'; base-uri 'self'");
            if(exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(200,-1);return; }
            exchange.sendResponseHeaders(200,Files.size(path));
            Files.copy(path,exchange.getResponseBody());
        }
    }
    /** @param path 候选文件 @return 浏览器需要的 MIME，未知类型作为二进制处理 */
    private String contentType(Path path) throws IOException {
        String name=path.getFileName().toString();
        if(name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript; charset=UTF-8";
        if(name.endsWith(".css")) return "text/css; charset=UTF-8";
        if(name.endsWith(".html")) return "text/html; charset=UTF-8";
        String detected=Files.probeContentType(path); return detected==null?"application/octet-stream":detected;
    }
    /** 停止临时监听和请求线程；候选源码、产物和证据继续保留。 */
    @Override public void close() { server.stop(0);executor.shutdownNow(); }
}
