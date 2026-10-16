package app.switter;

import org.json.JSONObject;
import java.time.ZoneId;
import java.util.UUID;
import okhttp3.HttpUrl;

public final class Blog {
    public String id = UUID.randomUUID().toString(), name = "", url = "", admin = "", login = "", password = "";
    public String proxyHost = "", zone = ZoneId.systemDefault().getId();
    public int proxyPort = 1080;
    public boolean proxy = false;
    public Blog() {}
    public Blog(JSONObject o) {
        id=o.optString("id",id); name=o.optString("name"); url=o.optString("url");
        admin=o.optString("admin"); login=o.optString("login"); password=o.optString("password");
        proxyHost=o.optString("proxyHost"); proxyPort=o.optInt("proxyPort",1080);
        proxy=o.optBoolean("proxy"); zone=o.optString("zone",zone);
    }
    public JSONObject json() {
        try { return new JSONObject().put("id",id).put("name",name).put("url",url).put("admin",admin)
            .put("login",login).put("password",password).put("proxyHost",proxyHost)
            .put("proxyPort",proxyPort).put("proxy",proxy).put("zone",zone); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public HttpUrl base() { return requireUrl(url).resolve("."); }
    public HttpUrl adminUrl() {
        if(admin.isBlank()) throw new IllegalArgumentException("Укажите путь к административному скрипту");
        HttpUrl u=base().resolve(admin);
        if(u==null || !u.username().isEmpty() || !u.password().isEmpty()) throw new IllegalArgumentException("Некорректный адрес администрирования");
        return u;
    }
    public void validate(boolean owner) {
        HttpUrl normalized=requireUrl(url);
        String path=normalized.encodedPath();
        if(!path.endsWith("/") && !path.substring(path.lastIndexOf('/')+1).contains("."))
            normalized=normalized.newBuilder().encodedPath(path+"/").build();
        url=normalized.toString();
        if(name.isBlank()) name=requireUrl(url).host();
        ZoneId.of(zone);
        if(owner) adminUrl();
        if(proxy && (proxyHost.isBlank() || proxyPort<1 || proxyPort>65535))
            throw new IllegalArgumentException("Укажите хост SOCKS5 и порт от 1 до 65535");
    }
    public static HttpUrl requireUrl(String value) {
        HttpUrl u=HttpUrl.parse(value);
        if(u==null || !u.username().isEmpty() || !u.password().isEmpty())
            throw new IllegalArgumentException("Введите полный http:// или https:// URL без логина и пароля");
        return u;
    }
}
