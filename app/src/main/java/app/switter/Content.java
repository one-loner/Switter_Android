package app.switter;

import org.json.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.safety.Safelist;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.regex.*;
import okhttp3.HttpUrl;

public final class Content {
    private Content() {}
    public static long time(String s, String zone) {
        if(s.isBlank()) return 0;
        try { return Instant.parse(s).toEpochMilli(); } catch(Exception ignored) {}
        try { return OffsetDateTime.parse(s).toInstant().toEpochMilli(); } catch(Exception ignored) {}
        for(String f: new String[]{"yyyy-MM-dd HH:mm:ss","HH:mm dd.MM.yyyy"}) {
            try { return LocalDateTime.parse(s,DateTimeFormatter.ofPattern(f)).atZone(ZoneId.of(zone)).toInstant().toEpochMilli(); }
            catch(Exception ignored) {}
        }
        throw new IllegalArgumentException("Неизвестный формат даты: " + s);
    }
    public static List<Post> json(Blog b,String raw) throws JSONException {
        JSONArray rows=new JSONArray(raw); List<Post> posts=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject o=rows.getJSONObject(i);
            if(!o.has("title") || !o.has("content") || !(o.get("title") instanceof String)
                || !(o.get("content") instanceof String)) throw new JSONException("Неизвестный формат записи");
            String id=o.optString("id","");
            if(!id.isEmpty() && !ids.add(id)) throw new JSONException("Повтор ID");
            String title=o.getString("title");
            String img=o.isNull("image") ? "" : o.optString("image");
            // Upstream stores a filename, never an arbitrary URL.
            if(!img.isEmpty() && !img.matches("[A-Za-z0-9_-]+\\.(?i:jpg|jpeg|png|gif|webp)"))
                throw new JSONException("Неизвестный путь изображения");
            posts.add(new Post(b,id,title,o.getString("content"),img.isEmpty()?"":b.base().resolve("uploads/"+img).toString(),
                source(b,title),time(o.optString("created_at"),b.zone),false));
        }
        return posts;
    }
    public static String source(Blog b,String title) {
        // Switter has no per-post route or anchors. Search links point to the original post.
        return Blog.requireUrl(b.url).newBuilder().query(null).addQueryParameter("q",title).build().toString();
    }
    public static List<Post> html(Blog b,String raw) {
        Document d=Jsoup.parse(raw,b.url); List<Post> posts=new ArrayList<>();
        for(Element a:d.select("article.p, article.post")) {
            Element h=a.selectFirst("h2"), body=a.selectFirst("p.pg");
            if(h==null || body==null) continue;
            long date=0;
            Element t=a.selectFirst("time[datetime]");
            if(t!=null) date=time(t.attr("datetime"),b.zone);
            else {
                Matcher m=Pattern.compile("(\\d{2}:\\d{2} \\d{2}\\.\\d{2}\\.\\d{4})").matcher(a.text());
                if(m.find()) date=time(m.group(1),b.zone);
            }
            Element image=a.selectFirst("img[src]"); String img=image==null?"":image.absUrl("src");
            if(!img.isEmpty()) Blog.requireUrl(img);
            posts.add(new Post(b,"",h.text(),safe(body.html()),img,source(b,h.text()),date,true));
        }
        if(posts.isEmpty()) throw new IllegalArgumentException("Страница не распознана");
        return posts;
    }
    /** Archive URLs are restricted to HTML files immediately inside this blog's archive directory. */
    public static List<String> archiveLinks(Blog b,String raw,String pageUrl) {
        HttpUrl directory=b.base().resolve("archive/");
        LinkedHashSet<String> links=new LinkedHashSet<>();
        for(Element a:Jsoup.parse(raw,pageUrl).select("a[href]")) {
            HttpUrl u=HttpUrl.parse(a.absUrl("href"));
            if(u==null || !Network.sameOrigin(directory,u) || !u.username().isEmpty() || !u.password().isEmpty())continue;
            String path=u.encodedPath(),prefix=directory.encodedPath();
            if(!path.startsWith(prefix))continue;
            String file=path.substring(prefix.length());
            if(file.isEmpty() || file.contains("/") || !file.toLowerCase(Locale.ROOT).endsWith(".html"))continue;
            links.add(u.newBuilder().query(null).fragment(null).build().toString());
        }
        List<String> result=new ArrayList<>(links);result.sort(Comparator.reverseOrder());return result;
    }
    public static List<Post> archive(Blog b,String raw,String url) {
        Document d=Jsoup.parse(raw,url);List<Post> posts=new ArrayList<>();int index=0;
        for(Element a:d.select("article.post, article.p")) {
            Element h=a.selectFirst("h2");if(h==null)continue;
            Element image=a.selectFirst("img[src]");String img=image==null?"":image.absUrl("src");
            if(!img.isEmpty())Blog.requireUrl(img);
            Element body=a.selectFirst("p.pg");
            StringBuilder content=new StringBuilder();
            if(body!=null)content.append(body.html());
            else for(Element paragraph:a.select("p"))content.append(paragraph.outerHtml());
            String source=Blog.requireUrl(url).newBuilder().fragment("switter-post-"+(index++)).build().toString();
            // Upstream archives omit original post dates and IDs. Do not invent dates or allow deletion.
            posts.add(new Post(b,"",h.text(),safe(content.toString()),img,source,0,true));
        }
        if(posts.isEmpty()&&!d.select("article").isEmpty())throw new IllegalArgumentException("Страница не распознана");
        return posts;
    }
    public static List<Post> search(Collection<List<Post>> groups,String query) {
        String needle=java.text.Normalizer.normalize(query.trim(),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<Post> result=new ArrayList<>();
        for(Post post:timeline(groups)) {
            String text=post.title+"\n"+Jsoup.parse(post.html?post.content:render(post.content)).text();
            if(java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).contains(needle))result.add(post);
        }
        return result;
    }
    public static List<Post> timeline(Collection<List<Post>> groups) {
        List<Post> all=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(List<Post> group:groups) for(Post p:group) {
            String key=p.blog.id+"|"+(p.id.isEmpty()?p.source:p.id);
            if(seen.add(key)) all.add(p);
        }
        all.sort(Comparator.comparingLong((Post p)->p.timestamp).reversed().thenComparing(p->p.blog.id).thenComparing(p->p.id));
        return all;
    }
    public static String safe(String html) {
        return Jsoup.clean(html,Safelist.none().addTags("strong","b","em","i","u","s","del","p","br","a","blockquote","code")
            .addAttributes("a","href").addProtocols("a","href","https","http"));
    }
    public static String render(String raw) {
        String s=org.jsoup.nodes.Entities.escape(raw);
        Pattern p=Pattern.compile("\\*\\*(.+?)\\*\\*|~~(.+?)~~|\\*(.+?)\\*|__(.+?)__",Pattern.DOTALL);
        Matcher m=p.matcher(s); StringBuffer out=new StringBuffer();
        while(m.find()) {
            String r=m.group(1)!=null?"<b>"+m.group(1)+"</b>":m.group(2)!=null?"<s>"+m.group(2)+"</s>":
                m.group(3)!=null?"<i>"+m.group(3)+"</i>":"<u>"+m.group(4)+"</u>";
            m.appendReplacement(out,Matcher.quoteReplacement(r));
        }
        m.appendTail(out);
        s=out.toString().replaceAll("\\[([^\\]\\r\\n]+)\\]\\((https?://[^\\s)]+)\\)","<a href=\"$2\">$1</a>");
        return safe(s.replace("\n","<br>"));
    }
    public static String markdown(String html) { return nodes(Jsoup.parseBodyFragment(html).body()).trim(); }
    private static String nodes(Node n) {
        if(n instanceof TextNode) return ((TextNode)n).getWholeText();
        StringBuilder s=new StringBuilder(); for(Node c:n.childNodes()) s.append(nodes(c));
        String tag=n.nodeName(), v=s.toString();
        switch(tag) {
            case "strong": case "b": return "**"+v+"**";
            case "em": case "i": return "*"+v+"*";
            case "u": return "__"+v+"__";
            case "s": case "del": return "~~"+v+"~~";
            case "br": return "\n";
            case "p": return v+"\n\n";
            case "a": String u=((Element)n).attr("href"); return u.startsWith("http://")||u.startsWith("https://")?"["+v+"]("+u+")":v;
            default: return v;
        }
    }
}
