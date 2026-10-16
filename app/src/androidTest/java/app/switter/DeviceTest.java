package app.switter;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DeviceTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();
    private Context isolated(){return new ContextWrapper(context){@Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return context.getSharedPreferences("device-test-"+name,mode);}};}
    @Before public void reset(){context.getSharedPreferences("device-test-vault",0).edit().clear().commit();}
    @Test public void keystoreRoundtripAndPin()throws Exception{
        Vault v=new Vault(isolated());Blog b=new Blog();b.name="Секретный блог";b.url="https://a.test/";b.password="secret-password";v.blogs.add(b);v.ownId=b.id;v.setPin("1593");
        String ciphertext=isolated().getSharedPreferences("vault",0).getString("data","");assertFalse(ciphertext.contains("secret-password"));assertFalse(ciphertext.contains("1593"));
        Vault reopened=new Vault(isolated());assertEquals("secret-password",reopened.own().password);assertTrue(reopened.unlock("1593"));assertFalse(reopened.unlock("1594"));
        reopened.removePin();assertFalse(new Vault(isolated()).protectedByPin());
    }
    @Test public void pinAttemptDelayPersists()throws Exception{
        Vault v=new Vault(isolated());v.setPin("1593");for(int i=0;i<5;i++)assertFalse(v.unlock("0000"));
        assertTrue(v.remainingSeconds()>0);Vault reopened=new Vault(isolated());assertTrue(reopened.remainingSeconds()>0);assertFalse(reopened.unlock("1593"));
    }
    @Test public void appLocksAndUnlocks()throws Exception{
        android.content.SharedPreferences real=context.getSharedPreferences("vault",0);String backup=real.getString("data",null);Activity activity=null;
        try{
            real.edit().clear().commit();Vault v=new Vault(context);v.setPin("2468");
            Intent intent=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);activity=instrumentation.startActivitySync(intent);instrumentation.waitForIdleSync();
            final Activity a=activity;assertTrue((a.getWindow().getAttributes().flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
            assertNotNull(find(a.getWindow().getDecorView(),"Разблокировать"));
            instrumentation.runOnMainSync(()->{EditText f=findEdit(a.getWindow().getDecorView());assertNotNull(f);f.setText("2468");find(a.getWindow().getDecorView(),"Разблокировать").performClick();});
            long end=System.currentTimeMillis()+7000;while(System.currentTimeMillis()<end){instrumentation.waitForIdleSync();if(find(a.getWindow().getDecorView(),"Разблокировать")==null)break;Thread.sleep(100);}
            assertNull(find(a.getWindow().getDecorView(),"Разблокировать"));assertNotNull(find(a.getWindow().getDecorView(),"Подключить мой блог"));
            instrumentation.runOnMainSync(()->{instrumentation.callActivityOnPause(a);instrumentation.callActivityOnResume(a);});instrumentation.waitForIdleSync();
            assertNotNull(find(a.getWindow().getDecorView(),"Разблокировать"));
        }finally{if(activity!=null){Activity a=activity;instrumentation.runOnMainSync(a::finish);}if(backup==null)real.edit().clear().commit();else real.edit().putString("data",backup).commit();}
    }
    @Test public void webResourcesUseSocks()throws Exception{
        java.net.ServerSocket proxy=new java.net.ServerSocket(0,10,java.net.InetAddress.getByName("127.0.0.1"));
        java.util.List<String> seen=new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.ExecutorService server=java.util.concurrent.Executors.newCachedThreadPool();
        server.submit(()->{while(!proxy.isClosed()){try{java.net.Socket socket=proxy.accept();server.submit(()->{try(java.net.Socket c=socket){
            c.setSoTimeout(8000);java.io.DataInputStream in=new java.io.DataInputStream(c.getInputStream());java.io.OutputStream out=c.getOutputStream();
            in.readUnsignedByte();int n=in.readUnsignedByte();in.skipBytes(n);out.write(new byte[]{5,0});out.flush();
            in.readUnsignedByte();in.readUnsignedByte();in.readUnsignedByte();int kind=in.readUnsignedByte();
            if(kind!=3)throw new java.io.IOException("DNS should be remote");byte[] host=new byte[in.readUnsignedByte()];in.readFully(host);in.readUnsignedShort();
            String name=new String(host,java.nio.charset.StandardCharsets.UTF_8);seen.add(name);
            out.write(new byte[]{5,0,0,1,127,0,0,1,0,80});out.flush();
            java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(in));String first=reader.readLine(),line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
            String body=name.equals("blog.invalid")?"<html><head><link rel='stylesheet' href='http://style.invalid/a.css'></head><body>Fallback<img src='http://image.invalid/a.png'></body></html>":"body { color: green; }";
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: "+(name.equals("blog.invalid")?"text/html":"text/css")+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes());out.write(bytes);out.flush();
        }catch(Exception ignored){} });}catch(Exception ignored){break;}}});
        Activity activity=null;
        try{
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Blog b=new Blog();b.url="http://blog.invalid/";b.name="Web test";b.proxy=true;b.proxyHost="127.0.0.1";b.proxyPort=proxy.getLocalPort();
            final Activity a=activity;instrumentation.runOnMainSync(()->{try{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("browser",Blog.class,String.class);method.setAccessible(true);method.invoke(a,b,b.url);}catch(Exception e){throw new RuntimeException(e);}});
            long end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end&&!(seen.contains("style.invalid")&&seen.contains("image.invalid"))){Thread.sleep(100);}
            assertTrue("Main page via SOCKS: "+seen,seen.contains("blog.invalid"));assertTrue("CSS via SOCKS: "+seen,seen.contains("style.invalid"));assertTrue("Image via SOCKS: "+seen,seen.contains("image.invalid"));
        }finally{if(activity!=null){Activity a=activity;instrumentation.runOnMainSync(a::finish);}proxy.close();server.shutdownNow();}
    }
    @Test public void renderThemes()throws Exception{
        android.content.SharedPreferences real=context.getSharedPreferences("vault",0);String backup=real.getString("data",null);
        try(okhttp3.mockwebserver.MockWebServer server=new okhttp3.mockwebserver.MockWebServer()){
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest r){
                return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","application/json").setBody("[{\"id\":\"sample\",\"title\":\"Маленькие радости\",\"content\":\"Сегодня замедлился и вышел на прогулку. Осень умеет удивлять.\\n\\n**Записывайте то, что хочется сохранить.**\",\"created_at\":\"2026-10-16 14:30:00\",\"image\":null}]");
            }});
            for(String theme:new String[]{"light","dark"}){
                real.edit().clear().commit();Vault v=new Vault(context);v.theme=theme;
                Blog own=new Blog();own.url=server.url("/own/").toString();own.name="Мой дневник";own.admin="my-panel.php";v.blogs.add(own);v.ownId=own.id;
                Blog follow=new Blog();follow.url=server.url("/follow/").toString();follow.name="За пределами города";v.blogs.add(follow);v.save();
                Activity a=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                try{
                    long end=System.currentTimeMillis()+7000;while(System.currentTimeMillis()<end&&find(a.getWindow().getDecorView(),"Маленькие радости")==null)Thread.sleep(100);
                    assertNotNull(find(a.getWindow().getDecorView(),"Маленькие радости"));instrumentation.waitForIdleSync();
                    instrumentation.runOnMainSync(()->{try{
                        View view=a.getWindow().getDecorView();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));
                        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"switter-"+theme+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
                    }catch(Exception e){throw new RuntimeException(e);}});
                }finally{instrumentation.runOnMainSync(a::finish);instrumentation.waitForIdleSync();}
            }
        }finally{if(backup==null)real.edit().clear().commit();else real.edit().putString("data",backup).commit();}
    }
    @Test public void repostUiCopiesImageAndSource()throws Exception{
        android.content.SharedPreferences real=context.getSharedPreferences("vault",0);String backup=real.getString("data",null);Activity activity=null;
        java.util.concurrent.BlockingQueue<String> published=new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.List<String> paths=new java.util.concurrent.CopyOnWriteArrayList<>();
        java.io.ByteArrayOutputStream imageBytes=new java.io.ByteArrayOutputStream();
        android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG,100,imageBytes);
        byte[] png=imageBytes.toByteArray();
        try(okhttp3.mockwebserver.MockWebServer server=new okhttp3.mockwebserver.MockWebServer()){
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher(){public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest r){
                String path=r.getPath();paths.add(path);
                if(path.equals("/follow/posts.json"))return new okhttp3.mockwebserver.MockResponse().setBody("[{\"id\":\"original\",\"title\":\"Первоначальный пост\",\"content\":\"**Оригинальный текст**\",\"created_at\":\"2026-10-16 10:00:00\",\"image\":\"image.png\"}]");
                if(path.equals("/follow/uploads/image.png"))return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","image/png").setBody(new okio.Buffer().write(png));
                if(path.equals("/own/posts.json"))return new okhttp3.mockwebserver.MockResponse().setBody("[]");
                if(path.equals("/own/private.php")){
                    if(r.getMethod().equals("GET"))return new okhttp3.mockwebserver.MockResponse().setBody("<form><input name=csrf_token value=token><input name=action value=add></form>");
                    published.add(r.getBody().readUtf8());return new okhttp3.mockwebserver.MockResponse().setResponseCode(302).addHeader("Location","/own/private.php");
                }
                return new okhttp3.mockwebserver.MockResponse().setResponseCode(404);
            }});
            real.edit().clear().commit();Vault v=new Vault(context);
            Blog own=new Blog();own.url=server.url("/own/").toString();own.admin="private.php";own.name="Мой блог";v.blogs.add(own);v.ownId=own.id;
            Blog follow=new Blog();follow.url=server.url("/follow/").toString();follow.name="Подписка";v.blogs.add(follow);v.save();
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final Activity a=activity;
            long end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end&&find(a.getWindow().getDecorView(),"↗ Репост")==null)Thread.sleep(100);
            instrumentation.runOnMainSync(()->find(a.getWindow().getDecorView(),"↗ Репост").performClick());
            end=System.currentTimeMillis()+8000;while(System.currentTimeMillis()<end&&find(a.getWindow().getDecorView(),"Изображение из первоисточника")==null)Thread.sleep(100);
            assertNotNull(find(a.getWindow().getDecorView(),"Изображение из первоисточника"));
            instrumentation.runOnMainSync(()->find(a.getWindow().getDecorView(),"Опубликовать").performClick());
            String body=published.poll(8,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(body);
            assertTrue(body.contains("**Оригинальный текст**"));assertTrue(body.contains("Первоисточник"));assertTrue(body.contains("/follow/?q="));assertTrue(body.contains("filename=\"image.png\""));assertTrue(paths.contains("/follow/uploads/image.png"));
            Thread.sleep(300);instrumentation.waitForIdleSync();
        }finally{if(activity!=null){Activity a=activity;instrumentation.runOnMainSync(a::finish);}if(backup==null)real.edit().clear().commit();else real.edit().putString("data",backup).commit();}
    }
    private View find(View v,String text){if(v instanceof TextView&&((TextView)v).getText().toString().equals(text))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View r=find(g.getChildAt(i),text);if(r!=null)return r;}}return null;}
    private EditText findEdit(View v){if(v instanceof EditText)return (EditText)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){EditText r=findEdit(g.getChildAt(i));if(r!=null)return r;}}return null;}
}
