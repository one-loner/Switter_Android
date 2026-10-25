package app.switter;

public final class Post {
    public final Blog blog;
    public final String id, title, content, image, source;
    public final long timestamp;
    public final boolean html;
    public Post(Blog b,String id,String title,String content,String image,String source,long timestamp,boolean html) {
        this.blog=b; this.id=id; this.title=title; this.content=content; this.image=image;
        this.source=source; this.timestamp=timestamp; this.html=html;
    }
    public String repostText() { return repostText("Первоисточник"); }
    public String repostText(String sourceLabel) {
        return (html ? Content.markdown(content) : content) + "\n\n[" + sourceLabel + ": "
            + blog.name.replace("[", "").replace("]", "") + "](" + (source.contains("#switter-post-")?source.substring(0,source.indexOf('#')):source) + ")";
    }
}
