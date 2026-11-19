package com.zzx.zzxaicode.quality;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import com.zzx.zzxaicode.config.ScreenshotProperties;
import com.zzx.zzxaicode.utils.ScreenshotDriverFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实打包页面与 Chrome、受控 HTTP/SSE 后端；不连接实际应用或数据库。 */
@EnabledIfSystemProperty(named="quality.live-frontend",matches="true")
class QualityFrontendTest {
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicInteger requests=new AtomicInteger();
    private final AtomicReference<Map<String,Object>> latest=new AtomicReference<>(task("FAILED"));
    /** 失败报告保留旧 iframe；下一任务只有成功后改变版本，错误连接不会自动重发。 */
    @Test void renderedChatRetainsFailureAndRefreshesOnlyAfterSuccess() throws Exception {
        Path dist=Path.of("zzx-ai-code-frontend/dist").toAbsolutePath();
        Assumptions.assumeTrue(Files.isRegularFile(dist.resolve("index.html")),"请先执行前端 Vite 构建");
        var assets=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newVirtualThreadPerTaskExecutor();
        String origin="http://localhost:"+assets.getAddress().getPort();
        // 生产包使用相对 /api，所有模拟请求只到临时端口，不占用实际后端端口。
        assets.createContext("/api/",exchange->api(exchange,origin));
        assets.createContext("/",exchange->{
            try(exchange) {
                Path file=dist.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
                if(!file.startsWith(dist) || !Files.isRegularFile(file)) file=dist.resolve("index.html");
                String name=file.toString();exchange.getResponseHeaders().set("Content-Type",name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":name.endsWith(".png")?"image/png":"text/html; charset=UTF-8");
                byte[] content=Files.readAllBytes(file);exchange.sendResponseHeaders(200,content.length);exchange.getResponseBody().write(content);
            }
        });assets.setExecutor(executor);assets.start();
        WebDriver driver=null;
        try {
            var props=new ScreenshotProperties();props.setAutoDownload(false);props.setNoSandbox(false);
            driver=new ScreenshotDriverFactory(props).createDriver();
            var wait=new WebDriverWait(driver,Duration.ofSeconds(15));
            driver.get(origin+"/app/chat/1");
            wait.until(d->!d.findElements(By.cssSelector(".preview-iframe")).isEmpty());
            String old=driver.findElement(By.cssSelector(".preview-iframe")).getAttribute("src");
            submit(driver,"修复首屏");
            wait.until(d->requests.get()==1 && d.findElement(By.cssSelector(".quality-panel")).getText().contains("未通过"));
            assertEquals(old,driver.findElement(By.cssSelector(".preview-iframe")).getAttribute("src"));
            assertTrue(driver.findElement(By.cssSelector(".quality-panel")).getText().contains("预览保留上次成功结果"));
            Path evidence=Path.of("target/quality-ui");Files.createDirectories(evidence);
            Files.write(evidence.resolve("failed.png"),((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES));
            wait.until(d->d.findElement(By.cssSelector("textarea")).isEnabled());
            submit(driver,"再次修复");
            wait.until(d->d.findElement(By.cssSelector(".preview-iframe")).getAttribute("src").contains("?v="));
            assertTrue(driver.findElement(By.cssSelector(".quality-panel")).getText().contains("已通过"));
            assertEquals(2,requests.get());
            Files.write(evidence.resolve("success.png"),((TakesScreenshot)driver).getScreenshotAs(OutputType.BYTES));
        } finally { if(driver!=null) driver.quit();assets.stop(0);executor.shutdownNow(); }
    }
    /** @param driver 受控页面 @param text 本轮需求；只操作测试后端 */
    private void submit(WebDriver driver,String text) {
        driver.findElement(By.cssSelector("textarea")).sendKeys(text);
        driver.findElement(By.cssSelector("button[aria-label='发送消息']")).click();
    }
    /** @param exchange 前端请求 @param origin 允许的测试页面源；固定 JSON 和 SSE 不调用业务外部服务 */
    private void api(HttpExchange exchange,String origin) throws IOException {
        try(exchange) {
            var headers=exchange.getResponseHeaders();headers.set("Access-Control-Allow-Origin",origin);headers.set("Access-Control-Allow-Credentials","true");headers.set("Access-Control-Allow-Headers","Content-Type");
            String path=exchange.getRequestURI().getPath();String body;
            if(path.endsWith("/chat/gen/code")) {
                int n=requests.incrementAndGet();var done=task(n==1?"FAILED":"SUCCEEDED");latest.set(done);
                headers.set("Content-Type","text/event-stream; charset=UTF-8");
                body="data: {\"d\":\"正在处理需求\"}\n\nevent: quality\ndata: "+json.writeValueAsString(done)+"\n\n"+(n==1?"":"event: done\ndata: {}\n\n");
            } else if(path.contains("/static/")) { headers.set("Content-Type","text/html; charset=UTF-8");body="<h1>上次成功页面</h1>"; }
            else {
                Object data=path.endsWith("/user/get/login")?Map.of("id","1","userName","测试用户","userRole","user"):
                        path.endsWith("/app/get/vo")?Map.of("id","1","userId","1","appName","质量检查验收","codeGenType","vue_project"):
                        path.contains("/chatHistory/app/")?Map.of("records",List.of(Map.of("messageType","ai","message","上次生成完成"),Map.of("messageType","user","message","创建计数器"))):latest.get();
                headers.set("Content-Type","application/json; charset=UTF-8");body=json.writeValueAsString(Map.of("code",0,"data",data));
            }
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }
    }
    /** @param status 受控终态 @return 与生产质量协议一致的最小测试快照 */
    private Map<String,Object> task(String status) {
        return Map.of("taskId",UUID.randomUUID().toString(),"appId","1","status",status,"stage",status,"repairAttempts",1,"maxRepairAttempts",2,
                "message",status.equals("SUCCEEDED")?"质量检查通过":"修复后语法错误仍存在","reports",List.of(),"repairs",List.of());
    }
}
