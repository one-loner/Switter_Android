package app.switter;

import org.junit.*;
import static org.junit.Assert.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class ClientTest {
    private Blog blog(String url){Blog b=new Blog();b.url=url;b.zone="UTC";b.name="Блог";return b;}
    private final String row="{\"id\":\"a\",\"title\":\"Пост\",\"content\":\"**текст**\",\"image\":null,\"created_at\":\"2026-10-16 10:00:00\"}";
    @Test public void jsonAndChronology()throws Exception{
        Blog a=blog("https://a.test/blog/");Blog b=blog("https://b.test/");
        Post first=Content.json(a,"["+row+"]").get(0);
        Post second=Content.json(b,"["+row.replace("10:00:00","11:00:00")+"]").get(0);
        assertEquals(second,Content.timeline(Arrays.asList(Arrays.asList(first),Arrays.asList(second))).get(0));
        assertEquals("**текст**",first.content);assertFalse(first.source.contains("#"));assertTrue(first.source.contains("?q="));
    }
    @Test public void timezoneIsApplied(){assertEquals(10800000L,Content.time("2026-10-16 10:00:00","UTC")-Content.time("2026-10-16 10:00:00","Europe/Moscow"));}
    @Test public void emptyBlogIsValid()throws Exception{assertTrue(Content.json(blog("https://a.test/"),"[]").isEmpty());}
    @Test public void badDataFallsOut()throws Exception{
        for(String bad:new String[]{"{}","[{\"foo\":1}]","["+row+","+row+"]","["+row.replace("null","\"../secrets.png\"")+"]"}){
            try{Content.json(blog("https://a.test/"),bad);fail(bad);}catch(Exception expected){}
        }
    }
    @Test public void imageUsesBlogSubdirectory()throws Exception{
        Post p=Content.json(blog("https://a.test/sub/index.php"),"["+row.replace("null","\"abcdef.png\"")+"]").get(0);
        assertEquals("https://a.test/sub/uploads/abcdef.png",p.image);
    }
    @Test public void nativeHtmlParsing(){
        Post p=Content.html(blog("https://a.test/sub/"),"<article class=p><h2>Заголовок</h2><p>Опубликовано: 13:12 16.10.2026</p><img src='uploads/a.png'><p class=pg><b>Текст</b><br>ещё</p></article>").get(0);
        assertEquals("https://a.test/sub/uploads/a.png",p.image);assertTrue(p.html);assertTrue(p.timestamp>0);
        assertTrue(p.repostText().contains("**Текст**"));assertTrue(p.repostText().contains("Первоисточник"));
    }
    @Test(expected=IllegalArgumentException.class)public void unknownHtmlTriggersWebFallback(){Content.html(blog("https://a.test/"),"<html>Custom page</html>");}
    @Test public void formattingEscapesAndSanitizes(){
        String h=Content.render("**bold** *italic* __under__ ~~strike~~ [link](https://a.test/)\n<script>x</script>");
        assertTrue(h.contains("<b>bold</b>"));assertTrue(h.contains("<s>strike</s>"));assertFalse(h.contains("<script>"));
        assertFalse(Content.safe("<a href='javascript:alert(1)'>x</a><img src=x onerror=x><script>x</script>").contains("javascript:"));
    }
    @Test public void arbitraryAdminPath(){Blog b=blog("https://a.test/blog/");b.admin="private/door.php";assertEquals("https://a.test/blog/private/door.php",b.adminUrl().toString());b.admin="https://admin.test/custom";assertEquals("admin.test",b.adminUrl().host());}
    @Test public void invalidProxyIsRejected(){Blog b=blog("https://a.test/");b.proxy=true;b.proxyPort=0;try{b.validate(false);fail();}catch(IllegalArgumentException expected){}}
    @Test public void fullSessionCsrfAndMultipart()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            java.util.List<RecordedRequest> posts=new CopyOnWriteArrayList<>();
            s.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public MockResponse dispatch(RecordedRequest r){
                if(r.getMethod().equals("GET")){
                    if(!"PHPSESSID=ok".equals(r.getHeader("Cookie")))return new MockResponse().setBody("<form><input name=action value=login></form>");
                    return new MockResponse().setBody("<form><input name=csrf_token value=fresh-token><input name=action value=add></form>");
                }
                posts.add(r);String body=r.getBody().clone().readUtf8();
                if(body.contains("action=login"))return new MockResponse().setResponseCode(302).addHeader("Location","/hidden/door.php").addHeader("Set-Cookie","PHPSESSID=ok; Path=/; HttpOnly");
                return new MockResponse().setResponseCode(302).addHeader("Location","/hidden/door.php");
            }});
            Blog b=blog(s.url("/").toString());b.admin="hidden/door.php";b.login="admin";b.password="secret";
            Network n=new Network();n.publish(b,"Название","**Текст**",new byte[]{1,2,3},"image/png");
            assertEquals(2,posts.size());assertEquals("/hidden/door.php",posts.get(0).getPath());
            String multipart=posts.get(1).getBody().clone().readUtf8();assertTrue(multipart.contains("fresh-token"));assertTrue(multipart.contains("filename=\"image.png\""));assertTrue(multipart.contains("**Текст**"));
            n.action(b,"archive","");assertTrue(posts.get(2).getBody().clone().readUtf8().contains("action=archive"));
            n.action(b,"delete","a");assertTrue(posts.get(3).getBody().clone().readUtf8().contains("id=a"));n.clear();
        }
    }
    @Test public void failedLoginIsNotSuccess()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.enqueue(new MockResponse().setBody("<form><input name=action value=login></form>"));s.enqueue(new MockResponse().setBody("Неверный пароль"));
            Blog b=blog(s.url("/").toString());b.admin="renamed.php";
            try{new Network().publish(b,"a","b",null,"");fail();}catch(IOException e){assertTrue(e.getMessage().contains("Ошибка входа"));}
        }
    }
    @Test public void validationErrorIsNotSuccess()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.enqueue(new MockResponse().setBody("<form><input name=csrf_token value=x><input name=action value=add></form>"));
            s.enqueue(new MockResponse().setBody("<div class=page><p>Ошибка загрузки изображения</p></div>"));
            Blog b=blog(s.url("/").toString());b.admin="custom.php";
            try{new Network().publish(b,"a","b",null,"");fail();}catch(IOException e){assertTrue(e.getMessage().contains("Ошибка загрузки"));}
        }
    }
    @Test public void refusesCredentialRedirect()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.enqueue(new MockResponse().setBody("<form><input name=action value=login></form>"));s.enqueue(new MockResponse().setResponseCode(302).addHeader("Location","http://evil.invalid/steal"));
            Blog b=blog(s.url("/").toString());b.admin="custom.php";
            try{new Network().publish(b,"a","b",null,"");fail();}catch(IOException expected){}
            assertEquals(2,s.getRequestCount());
        }
    }
    @Test public void fallsBackFromJsonToHtml()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.enqueue(new MockResponse().setResponseCode(403));s.enqueue(new MockResponse().setBody("<article class=p><h2>HTML</h2><p class=pg>body</p></article>"));
            assertEquals("HTML",new Network().posts(blog(s.url("/").toString())).get(0).title);
        }
    }
    @Test public void proxyFailureNeverConnectsDirect()throws Exception{
        try(MockWebServer s=new MockWebServer();ServerSocket closed=new ServerSocket(0)){
            int port=closed.getLocalPort();closed.close();Blog b=blog(s.url("/").toString());b.proxy=true;b.proxyHost="127.0.0.1";b.proxyPort=port;
            try{new Network().get(b,b.url,1000);fail();}catch(IOException expected){}
            assertEquals(0,s.getRequestCount());
        }
    }
    @Test public void socksResolvesDestinationRemotely()throws Exception{
        try(ServerSocket proxy=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))){
            ExecutorService e=Executors.newSingleThreadExecutor();
            Future<String> host=e.submit(()->{try(Socket socket=proxy.accept()){
                socket.setSoTimeout(5000);DataInputStream in=new DataInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();
                assertEquals(5,in.readUnsignedByte());int n=in.readUnsignedByte();in.skipBytes(n);out.write(new byte[]{5,0});out.flush();
                assertEquals(5,in.readUnsignedByte());assertEquals(1,in.readUnsignedByte());in.readUnsignedByte();assertEquals(3,in.readUnsignedByte());
                byte[] domain=new byte[in.readUnsignedByte()];in.readFully(domain);in.readUnsignedShort();
                out.write(new byte[]{5,0,0,1,127,0,0,1,0,80});out.flush();
                BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]".getBytes(StandardCharsets.UTF_8));out.flush();
                return new String(domain,StandardCharsets.UTF_8);
            }});
            try{Blog b=blog("http://no-dns.invalid/");b.proxy=true;b.proxyHost="127.0.0.1";b.proxyPort=proxy.getLocalPort();
                assertEquals("[]",new Network().get(b,b.url,100).text());assertEquals("no-dns.invalid",host.get(5,TimeUnit.SECONDS));
            }finally{e.shutdownNow();}
        }
    }
    @Test public void boundedReadsAndMime()throws Exception{
        try{Network.read(new ByteArrayInputStream(new byte[12]),10);fail();}catch(IOException expected){}
        assertEquals("jpg",Network.extension("image/jpeg; charset=UTF-8"));
        try{Network.extension("image/svg+xml");fail();}catch(IOException expected){}
    }
}
