package app.switter;

import org.junit.Test;
import static org.junit.Assert.*;
import okhttp3.mockwebserver.*;
import java.util.*;

public class ArchiveSearchTest {
    private Blog blog(String url){Blog b=new Blog();b.url=url;b.zone="UTC";return b;}
    private String archive(String title,String body){return "<article class='post'><h2>"+title+"</h2><img src='uploads/photo.png'><p>"+body+"</p></article>";}
    @Test public void archiveUsesItsOwnImageDirectoryAndPreservesSameTitles(){
        Blog b=blog("https://example.org/sub/");
        List<Post> posts=Content.archive(b,archive("Same","**one**")+archive("Same","<b>two</b>"),"https://example.org/sub/archive/2026.html");
        assertEquals(2,Content.timeline(Collections.singletonList(posts)).size());
        assertEquals("https://example.org/sub/archive/uploads/photo.png",posts.get(0).image);
        assertTrue(posts.get(1).content.contains("<b>two</b>"));
        assertEquals(0,posts.get(0).timestamp);assertEquals("",posts.get(0).id);
        assertTrue(posts.get(0).repostText("Source").contains("](https://example.org/sub/archive/2026.html)"));
        assertFalse(posts.get(0).repostText("Source").contains("#switter-post"));
    }
    @Test public void linksAreDeduplicatedAndStayInsideArchiveDirectory(){
        Blog b=blog("https://example.org/sub/index.php");
        List<String> urls=Content.archiveLinks(b,"<a href='2026.html'>new</a><a href='2025.html'>old</a>"
            +"<a href='2026.html?q=x#fragment'>duplicate</a><a href='../private.html'>outside</a>"
            +"<a href='https://evil.test/sub/archive/a.html'>external</a><a href='uploads/a.html'>nested</a>"
            +"<a href='javascript:alert(1)'>bad</a><a href='a.php'>php</a>","https://example.org/sub/archive/");
        assertEquals(Arrays.asList("https://example.org/sub/archive/2026.html","https://example.org/sub/archive/2025.html"),urls);
    }
    @Test public void searchMatchesRenderedTextAndUnicodeWithoutSearchingMarkup(){
        Blog b=blog("https://example.org/");
        List<Post> posts=Content.archive(b,archive("ПрИвЕт","<b>Мир &amp; Café</b><a href='https://hidden.test'>link</a>"),"https://example.org/archive/a.html");
        assertEquals(1,Content.search(Collections.singletonList(posts)," привет ").size());
        assertEquals(1,Content.search(Collections.singletonList(posts),"МИР & CAFÉ").size());
        assertTrue(Content.search(Collections.singletonList(posts),"hidden.test").isEmpty());
    }
    @Test public void nativeAndArchivedResultsAreCombinedWithoutLosingSameTitles()throws Exception{
        Blog b=blog("https://example.org/");
        List<Post> current=Content.json(b,"[{\"id\":\"x\",\"title\":\"same\",\"content\":\"needle\"}]");
        List<Post> old=Content.archive(b,archive("same","needle"),"https://example.org/archive/one.html");
        List<Post> older=Content.archive(b,archive("same","needle"),"https://example.org/archive/two.html");
        assertEquals(1,Content.search(Collections.singletonList(current),"needle").size());
        assertEquals(3,Content.search(Arrays.asList(current,old,older),"needle").size());
    }
    @Test public void transportReadsIndexAndFullArchiveWithRelativePaths()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.enqueue(new MockResponse().setBody("<a href='one.html'>One</a>"));
            server.enqueue(new MockResponse().setBody(archive("Archived","Full text, not an excerpt")));
            Blog b=blog(server.url("/sub/index.php").toString());Network network=new Network();
            List<String> links=network.archives(b);List<Post> posts=network.archive(b,links.get(0));
            assertEquals("/sub/archive/",server.takeRequest().getPath());
            assertEquals("/sub/archive/one.html",server.takeRequest().getPath());
            assertTrue(posts.get(0).content.contains("not an excerpt"));network.clear();
        }
    }
    @Test public void emptyArchivesAreValid(){
        Blog b=blog("https://example.org/");
        assertTrue(Content.archiveLinks(b,"<h1>Архив</h1><p>Нет доступных HTML файлов.</p>",b.base().resolve("archive/").toString()).isEmpty());
        assertTrue(Content.archive(b,"<h1>Archive</h1>",b.base().resolve("archive/empty.html").toString()).isEmpty());
    }
}
