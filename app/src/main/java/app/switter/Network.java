package app.switter;

import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** One transport and cookie jar per blog. SOCKS routes never fall back to direct. */
public final class Network {
    public static final int MAX_IMAGE=5*1024*1024, MAX_PAGE=12*1024*1024;
    public static final class Page {
        public final byte[] bytes; public final String url,mime; public final int status;
        Page(byte[] bytes,String url,String mime,int status) {this.bytes=bytes;this.url=url;this.mime=mime;this.status=status;}
        public String text(){return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
    }
    private final ConcurrentMap<String,OkHttpClient> clients=new ConcurrentHashMap<>();
    public void clear() { for(OkHttpClient c:clients.values()) {c.dispatcher().cancelAll();c.connectionPool().evictAll();} clients.clear(); }
    public OkHttpClient client(Blog b) {
        String key=b.json().toString();
        return clients.computeIfAbsent(key,k->{
            OkHttpClient.Builder x=new OkHttpClient.Builder().connectTimeout(520,TimeUnit.SECONDS)
                .readTimeout(525,TimeUnit.SECONDS).callTimeout(540,TimeUnit.SECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .cookieJar(new CookieJar(){
                    private final List<Cookie> jar=new ArrayList<>();
                    public synchronized void saveFromResponse(HttpUrl u,List<Cookie> cookies){
                        for(Cookie c:cookies){jar.removeIf(v->v.name().equals(c.name())&&v.domain().equals(c.domain())&&v.path().equals(c.path()));jar.add(c);}
                    }
                    public synchronized List<Cookie> loadForRequest(HttpUrl u){
                        jar.removeIf(c->c.expiresAt()<System.currentTimeMillis());
                        List<Cookie> r=new ArrayList<>();for(Cookie c:jar)if(c.matches(u))r.add(c);return r;
                    }
                });
            x.proxy(b.proxy?new Proxy(Proxy.Type.SOCKS,new InetSocketAddress(b.proxyHost,b.proxyPort)):Proxy.NO_PROXY);
            return x.build();
        });
    }
    public Page get(Blog b,String url,int limit) throws IOException {
        HttpUrl current=Blog.requireUrl(url);
        for(int redirects=0;redirects<6;redirects++) {
            try(Response r=client(b).newCall(new Request.Builder().url(current).header("User-Agent","switter-android/1.0").build()).execute()) {
                if(r.isRedirect()) {
                    HttpUrl next=current.resolve(r.header("Location",""));
                    if(next==null || (current.isHttps()&&!next.isHttps())) throw new IOException("Недопустимое перенаправление");
                    current=next;continue;
                }
                if(!r.isSuccessful()) throw new IOException("HTTP "+r.code());
                if(r.body()==null) throw new IOException("Пустой ответ сервера");
                String mime=r.body().contentType()==null?"application/octet-stream":r.body().contentType().toString();
                return new Page(read(r.body().byteStream(),limit),current.toString(),mime,r.code());
            }
        }
        throw new IOException("Слишком много перенаправлений");
    }
    public List<Post> posts(Blog b) throws IOException {
        Exception first;
        try {return Content.json(b,get(b,b.base().resolve("posts.json").toString(),MAX_PAGE).text());}
        catch(Exception e){first=e;}
        try {return Content.html(b,get(b,b.url,MAX_PAGE).text());}
        catch(Exception e){throw new IOException("Не удалось прочитать записи. Доступен веб-режим. "+first.getMessage(),e);}
    }
    private String csrf(Blog b) throws IOException {
        HttpUrl admin=b.adminUrl();
        Document page=Jsoup.parse(get(b,admin.toString(),MAX_PAGE).text());
        if(page.selectFirst("input[name=action][value=login]")!=null) {
            FormBody.Builder login=new FormBody.Builder().add("action","login").add("login",b.login).add("password",b.password);
            Element token=page.selectFirst("input[name=csrf_token]");if(token!=null)login.add("csrf_token",token.val());
            submit(b,login.build(),true);
            page=Jsoup.parse(get(b,admin.toString(),MAX_PAGE).text());
        }
        Element token=page.selectFirst("form input[name=csrf_token]");
        if(token==null || page.selectFirst("input[name=action][value=add]")==null)
            throw new IOException("Вход не выполнен: проверьте адрес скрипта, логин и пароль");
        return token.val();
    }
    private void submit(Blog b,RequestBody body,boolean login) throws IOException {
        HttpUrl u=b.adminUrl();
        try(Response r=client(b).newCall(new Request.Builder().url(u).post(body).header("Referer",u.toString()).build()).execute()) {
            HttpUrl location=u.resolve(r.header("Location",""));
            if((r.code()==302 || r.code()==303) && location!=null && sameOrigin(u,location)
                && location.encodedPath().equals(u.encodedPath())) return;
            String error="";
            if(r.body()!=null) {
                Document d=Jsoup.parse(new String(read(r.body().byteStream(),MAX_PAGE),java.nio.charset.StandardCharsets.UTF_8));
                Element p=d.selectFirst(".page > p:not(:has(a)), .error, [role=alert]");
                if(p!=null) error=p.text();
            }
            throw new IOException((login?"Ошибка входа":"Сервер не подтвердил операцию")+" (HTTP "+r.code()+")"+(error.isEmpty()?"":": "+error));
        }
    }
    static boolean sameOrigin(HttpUrl a,HttpUrl b){return a.scheme().equals(b.scheme())&&a.host().equals(b.host())&&a.port()==b.port();}
    public void publish(Blog b,String title,String content,byte[] image,String mime) throws IOException {
        if(title.isBlank()||content.isBlank())throw new IOException("Заполните название и текст");
        MultipartBody.Builder f=new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("action","add")
            .addFormDataPart("csrf_token",csrf(b)).addFormDataPart("title",title.trim()).addFormDataPart("content",content.trim());
        if(image!=null) {
            if(image.length>MAX_IMAGE)throw new IOException("Изображение больше 5 МБ");
            String ext=extension(mime);
            f.addFormDataPart("image","image."+ext,RequestBody.create(image,MediaType.get("image/"+(ext.equals("jpg")?"jpeg":ext))));
        }
        submit(b,f.build(),false);
    }
    public void action(Blog b,String action,String id) throws IOException {
        if(!action.equals("delete")&&!action.equals("archive"))throw new IllegalArgumentException("action");
        submit(b,new FormBody.Builder().add("csrf_token",csrf(b)).add("action",action).add("id",id).build(),false);
    }
    public static String extension(String mime) throws IOException {
        String m=mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
        switch(m){case "image/jpeg":return "jpg";case "image/png":return "png";case "image/gif":return "gif";case "image/webp":return "webp";default:throw new IOException("Разрешены JPG, PNG, GIF и WEBP");}
    }
    public static byte[] read(InputStream in,int max) throws IOException {
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192];int n,total=0;
            while((n=stream.read(buf))!=-1){total+=n;if(total>max)throw new IOException("Превышен размер файла: "+(max/1024/1024)+" МБ");out.write(buf,0,n);}return out.toByteArray();
        }
    }
}
